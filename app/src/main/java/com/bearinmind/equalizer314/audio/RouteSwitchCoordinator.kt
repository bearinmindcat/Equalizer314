package com.bearinmind.equalizer314.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Policy run when [AudioRoutingMonitor] reports a new active output:
 *  1. Look up the device→preset binding.
 *  2. If found, snapshot current live state into `lastManualState` (for MainActivity's Undo).
 *  3. Load the named custom preset, copy its bands into live EQ state (same SP key the activity reads).
 *  4. Push new bands to [DynamicsProcessingManager] if the EQ is running.
 *  5. Broadcast [ACTION_ROUTE_PRESET_APPLIED] so a foregrounded MainActivity reloads and shows Undo.
 *
 * No binding for the device → strict no-op (matches the §C edge-case 1 default). Invoked on the main
 * thread from the routing monitor's debounce callback; SP reads are cheap, DP updates serialized in
 * [DynamicsProcessingManager].
 */
class RouteSwitchCoordinator(
    private val context: Context,
    private val eqPrefs: EqPreferencesManager,
    private val dynamicsManager: DynamicsProcessingManager,
) {

    fun onRouteChange(change: AudioRoutingMonitor.RouteChange) {
        // Remember the device even without a binding, so it appears in the Audio Output screen's
        // "seen devices" list for manual binding later.
        eqPrefs.rememberSeenDevice(change.key, change.label)

        // Master gate: with "Device auto-switch" off, route changes still populate seen-devices
        // (above) but never overwrite the loaded preset.
        if (!eqPrefs.getDeviceAutoSwitchEnabled()) {
            Log.d(TAG, "Auto-switch disabled — keeping current preset on route change to '${change.label}'")
            return
        }

        // No binding ("(none)" deletes the entry, or never bound) → leave live DP as-is.
        // "(none)" means "don't touch what's loaded," not "disable the EQ."
        val binding = eqPrefs.getDeviceBinding(change.key) ?: return
        // "Disable EQ" binding: global-DP detach is owned by EqService.handleDeviceRouteLifecycle
        // (keeps isDpRunning / notification consistent). Bail before loadCustomPreset so we don't
        // log a bogus "missing preset" for the sentinel name.
        if (binding.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED) return
        val preset = loadCustomPreset(binding.presetName)
        if (preset == null) {
            Log.w(TAG, "Binding for '${binding.label}' references missing preset '${binding.presetName}'")
            return
        }

        val livePrefs = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
        // Snapshot the current live state so MainActivity's Undo can revert.
        eqPrefs.saveLastManualState(livePrefs.getString("bands", null))

        // Channel-Side-EQ presets carry independent leftBands / rightBands. Branch on the saved CSE
        // flag and apply per-channel when present (else a TWS preset's per-channel filters drop).
        val cseOn = preset.optBoolean("channelSideEqEnabled", false)
        val hasLeftRight = cseOn && preset.has("leftBands") && preset.has("rightBands")

        if (hasLeftRight) {
            val leftArr = preset.getJSONArray("leftBands")
            val rightArr = preset.getJSONArray("rightBands")
            val leftEq = buildEqualizerFromBands(leftArr)
            val rightEq = buildEqualizerFromBands(rightArr)
            // Persist to the same prefs keys EqStateManager reads on launch so the app shows
            // per-channel divergence. Live `bands` key mirrors the (active) left channel for
            // back-compat / non-CSE views.
            eqPrefs.saveChannelSideEqEnabled(true)
            eqPrefs.saveLeftBands(leftEq)
            eqPrefs.saveRightBands(rightEq)
            livePrefs.edit().putString("bands", leftArr.toString()).apply()
            // Shared "Both" layer + per-channel preamps ride the preset.
            val sharedArr = preset.optJSONArray("sharedBands")
            val sharedEq = if (sharedArr != null) buildEqualizerFromBands(sharedArr) else ParametricEqualizer()
            eqPrefs.saveSharedBands(sharedEq)
            com.bearinmind.equalizer314.dsp.ParametricToDpConverter.overlayEq =
                if (sharedEq.getBandCount() > 0) sharedEq else null
            eqPrefs.savePreampLeft(preset.optDouble("preampLeft", 0.0).toFloat())
            eqPrefs.savePreampRight(preset.optDouble("preampRight", 0.0).toFloat())
            if (dynamicsManager.isActive) {
                dynamicsManager.leftChannelGainDb = eqPrefs.getLeftChannelGainDb() + eqPrefs.getPreampLeft()
                dynamicsManager.rightChannelGainDb = eqPrefs.getRightChannelGainDb() + eqPrefs.getPreampRight()
            }
        } else {
            // Single / shared preset — mirror its `bands` and clear stale per-channel divergence + flag
            // so a later CSE-enable forks cleanly.
            val bandsJson = preset.optJSONArray("bands") ?: return
            livePrefs.edit().putString("bands", bandsJson.toString()).apply()
            eqPrefs.saveChannelSideEqEnabled(false)
            eqPrefs.clearLeftRightBands()
            com.bearinmind.equalizer314.dsp.ParametricToDpConverter.overlayEq = null
            eqPrefs.savePreampLeft(0f)
            eqPrefs.savePreampRight(0f)
            if (dynamicsManager.isActive) {
                dynamicsManager.leftChannelGainDb = eqPrefs.getLeftChannelGainDb()
                dynamicsManager.rightChannelGainDb = eqPrefs.getRightChannelGainDb()
            }
        }

        // Push saved preamp to the live DP, not just prefs — else the audio path keeps the previous
        // device's preamp and an AutoEQ preset's -6 dB headroom drops on every route-in.
        if (preset.has("preamp")) {
            val preamp = preset.getDouble("preamp").toFloat()
            eqPrefs.savePreampGain(preamp)
            if (dynamicsManager.isActive) {
                dynamicsManager.preampGainDb = preamp
            }
        }

        if (dynamicsManager.isActive) {
            if (hasLeftRight) {
                val leftEq = buildEqualizerFromBands(preset.getJSONArray("leftBands"))
                val rightEq = buildEqualizerFromBands(preset.getJSONArray("rightBands"))
                dynamicsManager.updateFromEqualizers(leftEq, rightEq)
            } else {
                val eq = buildEqualizerFromBands(preset.getJSONArray("bands"))
                dynamicsManager.updateFromEqualizer(eq)
            }
        }

        // Full-chain presets: apply MBC + limiter too.
        com.bearinmind.equalizer314.state.PresetChainIo.applyChain(context, preset, eqPrefs, dynamicsManager)

        // Persist active preset name so getPresetName() reflects what's driving audio. EqService's
        // notification reads it for "Preset: X" in the BigText body; MainActivity's dropdown stays in sync.
        eqPrefs.savePresetName(binding.presetName)

        Log.d(TAG, "Applied '${binding.presetName}' for device '${change.label}'")
        context.sendBroadcast(
            Intent(ACTION_ROUTE_PRESET_APPLIED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_DEVICE_LABEL, change.label)
                .putExtra(EXTRA_PRESET_NAME, binding.presetName)
        )
    }

    private fun loadCustomPreset(name: String): JSONObject? {
        val prefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val str = prefs.getString("preset_$name", null) ?: return null
        return runCatching { JSONObject(str) }.getOrNull()
    }

    private fun buildEqualizerFromBands(arr: JSONArray): ParametricEqualizer {
        val eq = ParametricEqualizer()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val type = runCatching {
                BiquadFilter.FilterType.valueOf(o.getString("filterType"))
            }.getOrDefault(BiquadFilter.FilterType.BELL)
            eq.addBand(
                o.getDouble("frequency").toFloat(),
                o.getDouble("gain").toFloat(),
                type,
                o.getDouble("q"),
            )
            if (o.has("enabled")) eq.setBandEnabled(i, o.getBoolean("enabled"))
        }
        eq.isEnabled = true
        return eq
    }

    companion object {
        private const val TAG = "RouteSwitchCoord"
        const val ACTION_ROUTE_PRESET_APPLIED =
            "com.bearinmind.equalizer314.ROUTE_PRESET_APPLIED"
        const val EXTRA_DEVICE_LABEL = "device_label"
        const val EXTRA_PRESET_NAME = "preset_name"
    }
}
