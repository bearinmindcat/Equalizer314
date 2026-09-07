package com.bearinmind.equalizer314.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.json.JSONArray
import org.json.JSONObject

/** Applies device→preset bindings on route change and app→preset bindings while a bound app plays (System-wide): snapshot the EQ being replaced, load the preset into live state + DP, broadcast, restore when the binding ends. */
class RouteSwitchCoordinator(
    private val context: Context,
    private val eqPrefs: EqPreferencesManager,
    private val dynamicsManager: DynamicsProcessingManager,
) {

    /** A playing app bound to "Disable EQ" (System-wide) — EqService bypasses the global DP while it lasts. */
    @Volatile
    var appDisableActive = false
        private set

    private var lastPlaying: Set<String> = emptySet()

    /** [force] (binding edit, mode switch) applies the binding even over a preset the user picked by hand. */
    fun onRouteChange(change: AudioRoutingMonitor.RouteChange, force: Boolean = false) {
        // Remember the device even without a binding — feeds the "seen devices" list.
        eqPrefs.rememberSeenDevice(change.key, change.label)

        // Auto-switch off: still populate seen-devices, never overwrite the loaded preset.
        if (!eqPrefs.getDeviceAutoSwitchEnabled()) {
            Log.d(TAG, "Auto-switch disabled — keeping current preset on route change to '${change.label}'")
            return
        }
        // App wins while it plays; the device binding is re-run when the app override ends.
        if (appPresetDriving()) {
            Log.d(TAG, "App preset active — device binding for '${change.label}' deferred")
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
        // A preset picked by hand on this same device sticks until the device changes.
        val applied = eqPrefs.getAppliedBindingPreset()
        if (!force && applied != null && eqPrefs.getAppliedBindingKey() == change.key && eqPrefs.getPresetName() != applied) {
            Log.d(TAG, "'${eqPrefs.getPresetName()}' picked by hand on '${change.label}' — '${binding.presetName}' not re-applied")
            return
        }

        // Snapshot only the user's own EQ, never a previous binding's preset, so an unbound device gets it back.
        if (!liveStateIsDeviceDriven()) eqPrefs.saveLastManualState(eqPrefs.captureLiveEqState()?.toString())
        applyPreset(preset, binding.presetName)
        eqPrefs.saveAppliedBinding(change.key, binding.presetName)
        Log.d(TAG, "Applied '${binding.presetName}' for device '${change.label}'")
        broadcastApplied(change.label, binding.presetName)
    }

    private fun appPresetDriving(): Boolean =
        eqPrefs.getAppliedAppPreset().let { it != null && it != EqPreferencesManager.DEVICE_PRESET_DISABLED }

    /** Playing-set update (System-wide): the first playing bound app drives the EQ, "Disable EQ" bypasses it. Returns true when anything changed. */
    fun onPlayingAppsChanged(playing: Set<String>, force: Boolean = false): Boolean {
        if (eqPrefs.getAudioRoutingMode() == 1) return endAppOverride()
        if (!force && playing == lastPlaying) return false
        lastPlaying = playing
        val prevPkg = eqPrefs.getAppliedAppPackage()
        val prevPreset = eqPrefs.getAppliedAppPreset()
        // Keep the current driver while it still plays; otherwise the first playing bound app.
        val pick = (listOfNotNull(prevPkg) + playing.sorted())
            .filter { it in playing }
            .firstNotNullOfOrNull { pkg -> eqPrefs.getAppBinding(pkg)?.let { pkg to it.presetName } }
        if (pick == null) return endAppOverride()
        val (pkg, presetName) = pick
        if (presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED) {
            if (prevPreset != null && prevPreset != EqPreferencesManager.DEVICE_PRESET_DISABLED) endAppOverride()
            val changed = !appDisableActive || prevPkg != pkg
            appDisableActive = true
            eqPrefs.saveAppliedAppBinding(pkg, presetName)
            if (changed) {
                Log.d(TAG, "EQ disabled while '$pkg' plays")
                broadcastApplied(null, eqPrefs.getPresetName())
            }
            return changed
        }
        appDisableActive = false
        if (prevPkg == pkg && prevPreset == presetName && eqPrefs.getPresetName() == presetName) return false
        val preset = loadCustomPreset(presetName)
        if (preset == null) {
            Log.w(TAG, "Binding for '$pkg' references missing preset '$presetName'")
            return false
        }
        // Snapshot what the first app override replaces; a second app in a row keeps that snapshot.
        if (prevPreset == null || prevPreset == EqPreferencesManager.DEVICE_PRESET_DISABLED) {
            eqPrefs.saveAppOverrideSnapshot(eqPrefs.captureLiveEqState()?.toString())
        }
        applyPreset(preset, presetName)
        eqPrefs.saveAppliedAppBinding(pkg, presetName)
        Log.d(TAG, "Applied '$presetName' while '$pkg' plays")
        broadcastApplied(null, presetName)
        return true
    }

    /** Bound app stopped (or mode left System-wide): put back the EQ it replaced unless the user edited it since. */
    fun endAppOverride(): Boolean {
        val applied = eqPrefs.getAppliedAppPreset() ?: return false
        appDisableActive = false
        eqPrefs.saveAppliedAppBinding(null, null)
        if (applied == EqPreferencesManager.DEVICE_PRESET_DISABLED) {
            Log.d(TAG, "App disable ended")
            broadcastApplied(null, eqPrefs.getPresetName())
            return true
        }
        val snapshot = eqPrefs.getAppOverrideSnapshot()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.has("bands") }
        eqPrefs.saveAppOverrideSnapshot(null)
        val stillLoaded = eqPrefs.getPresetName() == applied && !eqPrefs.isLiveStateEditedFrom(applied)
        if (!stillLoaded || snapshot == null) {
            Log.d(TAG, "App stopped — keeping the current EQ")
            broadcastApplied(null, eqPrefs.getPresetName())
            return true
        }
        val name = snapshot.optString("presetName", "Custom")
        applyPreset(snapshot, name)
        Log.d(TAG, "App stopped — restored '$name'")
        broadcastApplied(null, name)
        return true
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

    /** [label] null for app-driven applies — EqService reads that extra as the device label. */
    private fun broadcastApplied(label: String?, presetName: String) {
        val intent = Intent(ACTION_ROUTE_PRESET_APPLIED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PRESET_NAME, presetName)
        if (label != null) intent.putExtra(EXTRA_DEVICE_LABEL, label)
        context.sendBroadcast(intent)
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
