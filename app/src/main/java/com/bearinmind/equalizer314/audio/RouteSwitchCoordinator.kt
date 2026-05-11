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
 * Owns the policy that runs when [AudioRoutingMonitor] reports a new
 * active output:
 *  1. Look up the device→preset binding.
 *  2. If found, snapshot the current live state into `lastManualState`
 *     (so MainActivity's Undo snackbar can roll back).
 *  3. Load the named custom preset and copy its bands into the live
 *     EQ state (same SP key the activity reads on launch).
 *  4. Push the new bands to [DynamicsProcessingManager] if the EQ is
 *     currently running.
 *  5. Broadcast [ACTION_ROUTE_PRESET_APPLIED] so a foregrounded
 *     MainActivity reloads its UI and shows the Undo snackbar.
 *
 * If no binding exists for the device, **nothing changes** — the
 * coordinator is a strict no-op (matches the §C edge-case 1 default).
 *
 * Thread model: invoked on the main thread from the routing
 * monitor's debounce callback. SharedPreferences reads are cheap;
 * DP updates are fast and already serialized inside
 * [DynamicsProcessingManager].
 */
class RouteSwitchCoordinator(
    private val context: Context,
    private val eqPrefs: EqPreferencesManager,
    private val dynamicsManager: DynamicsProcessingManager,
) {

    fun onRouteChange(change: AudioRoutingMonitor.RouteChange) {
        // Always remember the device — even if we don't apply a binding,
        // it should appear in the Audio Output screen's "seen devices"
        // list for the user to bind to manually later.
        eqPrefs.rememberSeenDevice(change.key, change.label)

        val binding = eqPrefs.getDeviceBinding(change.key) ?: return
        val preset = loadCustomPreset(binding.presetName)
        if (preset == null) {
            Log.w(TAG, "Binding for '${binding.label}' references missing preset '${binding.presetName}'")
            return
        }

        val livePrefs = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
        // Snapshot the current live state so MainActivity's Undo can revert.
        eqPrefs.saveLastManualState(livePrefs.getString("bands", null))

        // Mirror the preset's `bands` array into the live `bands` key.
        val bandsJson = preset.optJSONArray("bands") ?: return
        livePrefs.edit().putString("bands", bandsJson.toString()).apply()
        if (preset.has("preamp")) {
            eqPrefs.savePreampGain(preset.getDouble("preamp").toFloat())
        }

        if (dynamicsManager.isActive) {
            val eq = buildEqualizerFromBands(bandsJson)
            dynamicsManager.updateFromEqualizer(eq)
        }

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
