package com.bearinmind.equalizer314.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.json.JSONArray
import org.json.JSONObject

/** Applies the device→preset binding on route change (snapshot the user's EQ, load preset into live state + DP, broadcast) and restores that snapshot when an unbound device routes in. */
class RouteSwitchCoordinator(
    private val context: Context,
    private val eqPrefs: EqPreferencesManager,
    private val dynamicsManager: DynamicsProcessingManager,
) {

    fun onRouteChange(change: AudioRoutingMonitor.RouteChange) {
        // Remember the device even without a binding — feeds the "seen devices" list.
        eqPrefs.rememberSeenDevice(change.key, change.label)

        // Auto-switch off: still populate seen-devices, never overwrite the loaded preset.
        if (!eqPrefs.getDeviceAutoSwitchEnabled()) {
            Log.d(TAG, "Auto-switch disabled — keeping current preset on route change to '${change.label}'")
            return
        }

        val binding = eqPrefs.getDeviceBindingSmart(change.key, change.label)
        if (binding == null) {
            restoreManualState(change.label)
            return
        }
        // "Disable EQ" detach is owned by EqService.handleDeviceRouteLifecycle — bail here.
        if (binding.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED) return
        val preset = loadCustomPreset(binding.presetName)
        if (preset == null) {
            Log.w(TAG, "Binding for '${binding.label}' references missing preset '${binding.presetName}'")
            return
        }
        // Same device with its preset still loaded (DP or service restart): keep the user's edits on top of it.
        if (eqPrefs.getAppliedBindingKey() == change.key &&
            eqPrefs.getAppliedBindingPreset() == binding.presetName &&
            eqPrefs.getPresetName() == binding.presetName
        ) {
            Log.d(TAG, "'${binding.presetName}' already loaded for '${change.label}' — not re-applied")
            return
        }

        // Snapshot only the user's own EQ, never a previous binding's preset, so an unbound device gets it back.
        if (!liveStateIsDeviceDriven()) eqPrefs.saveLastManualState(eqPrefs.captureLiveEqState()?.toString())
        applyPreset(preset, binding.presetName)
        eqPrefs.saveAppliedBinding(change.key, binding.presetName)
        Log.d(TAG, "Applied '${binding.presetName}' for device '${change.label}'")
        broadcastApplied(change.label, binding.presetName)
    }

    /** Live EQ is still the preset a binding loaded — no manual preset change or band edits since. */
    private fun liveStateIsDeviceDriven(): Boolean {
        val applied = eqPrefs.getAppliedBindingPreset() ?: return false
        return eqPrefs.getPresetName() == applied && !eqPrefs.isLiveStateEditedFrom(applied)
    }

    /** Unbound device routed in (or the binding was removed): put back the EQ the binding replaced, unless the user changed it since. */
    private fun restoreManualState(label: String) {
        if (eqPrefs.getAppliedBindingPreset() == null) return
        val driven = liveStateIsDeviceDriven()
        eqPrefs.saveAppliedBinding(null, null)
        if (!driven) {
            Log.d(TAG, "No binding for '$label' — keeping the user-edited EQ")
            return
        }
        val snapshot = eqPrefs.getLastManualState()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.has("bands") }
        if (snapshot == null) {
            Log.d(TAG, "No binding for '$label' — no manual-state snapshot to restore")
            return
        }
        val name = snapshot.optString("presetName", "Custom")
        applyPreset(snapshot, name)
        eqPrefs.saveLastManualState(null)
        Log.d(TAG, "No binding for '$label' — restored '$name'")
        broadcastApplied(label, name)
    }

    /** Preset-shaped JSON → live prefs + running DP; [name] becomes the loaded preset name. */
    private fun applyPreset(preset: JSONObject, name: String) {
        val livePrefs = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
        // CSE presets carry independent leftBands / rightBands — apply per-channel when present.
        val cseOn = preset.optBoolean("channelSideEqEnabled", false)
        val hasLeftRight = cseOn && preset.has("leftBands") && preset.has("rightBands")

        if (hasLeftRight) {
            val leftArr = preset.getJSONArray("leftBands")
            val rightArr = preset.getJSONArray("rightBands")
            val leftEq = buildEqualizerFromBands(leftArr)
            val rightEq = buildEqualizerFromBands(rightArr)
            // Same prefs keys EqStateManager reads on launch; `bands` mirrors L for back-compat.
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
            // Single preset — mirror `bands`, clear stale per-channel divergence.
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

        // Push the preamp to the live DP too — prefs alone keep the previous device's preamp.
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

        // Persist the preset name — notification "Preset:" line + dropdown read it.
        eqPrefs.savePresetName(name)
    }

    private fun broadcastApplied(label: String, presetName: String) {
        context.sendBroadcast(
            Intent(ACTION_ROUTE_PRESET_APPLIED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_DEVICE_LABEL, label)
                .putExtra(EXTRA_PRESET_NAME, presetName)
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
