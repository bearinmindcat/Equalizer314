package com.bearinmind.equalizer314.state

import android.content.Context
import android.net.Uri
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import org.json.JSONArray
import org.json.JSONObject

class EqPreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
    private val bindingsPrefs = context.getSharedPreferences("device_bindings", Context.MODE_PRIVATE)
    private val appBindingsPrefs = context.getSharedPreferences("app_bindings", Context.MODE_PRIVATE)
    // Single custom-preset pool shared by all four EQ modes + AutoEQ + bindings.
    private val customPresetsPrefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)

    init {
        migrateLegacySimplePresets()
    }

    /** Device → preset binding: stable device key, UI label, preset name (or [DEVICE_PRESET_DISABLED]). */
    data class Binding(val key: String, val label: String, val presetName: String)

    companion object {
        /** Reserved presetName: detach DP entirely while this device is routed (distinct from "(none)"). */
        const val DEVICE_PRESET_DISABLED = "__disable_eq__"

        // Mirrors SimpleEqController.FREQUENCIES/.Q — change together.
        private val SIMPLE_FREQS = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private const val SIMPLE_Q = 1.414
    }

    /** Per-app → preset binding for sessions that broadcast OPEN_AUDIO_EFFECT_CONTROL_SESSION. */
    data class AppBinding(val packageName: String, val presetName: String)

    fun saveState(eq: ParametricEqualizer, slots: List<Int>? = null) {
        val bandsJson = JSONArray()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            bandsJson.put(JSONObject().apply {
                put("frequency", band.frequency.toDouble())
                put("gain", band.gain.toDouble())
                put("filterType", band.filterType.name)
                put("q", band.q)
                put("enabled", band.enabled)
                if (slots != null && i < slots.size) {
                    put("slot", slots[i])
                }
            })
        }

        prefs.edit()
            .putString("bands", bandsJson.toString())
            .putBoolean("eqEnabled", eq.isEnabled)
            .apply()
    }

    fun restoreState(eq: ParametricEqualizer) {
        val bandsStr = prefs.getString("bands", null) ?: return
        val bandsJson = JSONArray(bandsStr)

        // Rebuild EQ to match saved band count
        eq.clearBands()
        for (i in 0 until bandsJson.length()) {
            val obj = bandsJson.getJSONObject(i)
            val filterType = try {
                BiquadFilter.FilterType.valueOf(obj.getString("filterType"))
            } catch (_: Exception) {
                BiquadFilter.FilterType.BELL
            }
            eq.addBand(
                obj.getDouble("frequency").toFloat(),
                obj.getDouble("gain").toFloat(),
                filterType,
                obj.getDouble("q")
            )
            if (obj.has("enabled")) {
                eq.setBandEnabled(i, obj.getBoolean("enabled"))
            }
        }

        eq.isEnabled = prefs.getBoolean("eqEnabled", true)
    }

    /** Standalone accessor for the same eqEnabled key saveState/restoreState use. */
    fun getEqEnabled(): Boolean = prefs.getBoolean("eqEnabled", true)

    fun saveEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("eqEnabled", enabled).apply()
    }

    // ---- Per-channel EQ persistence (Channel Side EQ) ------------------

    /** Bands → saveState JSON form; public entry points are saveLeftBands / saveRightBands. */
    private fun serializeBands(eq: ParametricEqualizer, slots: List<Int>? = null): String {
        val bands = JSONArray()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            bands.put(JSONObject().apply {
                put("frequency", band.frequency.toDouble())
                put("gain", band.gain.toDouble())
                put("filterType", band.filterType.name)
                put("q", band.q)
                put("enabled", band.enabled)
                put("channel", band.channel.name)
                if (slots != null && i < slots.size) put("slot", slots[i])
            })
        }
        return bands.toString()
    }

    /** Load a JSON band array into [eq]. True when parsing succeeded (even if empty), false on malformed JSON. */
    private fun loadBands(
        eq: ParametricEqualizer,
        jsonStr: String,
        defaultChannel: ParametricEqualizer.Channel = ParametricEqualizer.Channel.BOTH,
    ): Boolean {
        return try {
            val arr = JSONArray(jsonStr)
            eq.clearBands()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ft = try {
                    BiquadFilter.FilterType.valueOf(obj.getString("filterType"))
                } catch (_: Exception) {
                    BiquadFilter.FilterType.BELL
                }
                eq.addBand(
                    obj.getDouble("frequency").toFloat(),
                    obj.getDouble("gain").toFloat(),
                    ft,
                    obj.getDouble("q")
                )
                if (obj.has("enabled")) eq.setBandEnabled(i, obj.getBoolean("enabled"))
                // Pre-#53 saves have no "channel" — the default keeps L/R curves independent.
                eq.getBand(i)?.channel = if (obj.has("channel")) {
                    try {
                        ParametricEqualizer.Channel.valueOf(obj.getString("channel"))
                    } catch (_: Exception) {
                        defaultChannel
                    }
                } else defaultChannel
            }
            eq.isEnabled = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveLeftBands(eq: ParametricEqualizer, slots: List<Int>? = null) {
        prefs.edit().putString("leftBands", serializeBands(eq, slots)).apply()
    }

    fun saveRightBands(eq: ParametricEqualizer, slots: List<Int>? = null) {
        prefs.edit().putString("rightBands", serializeBands(eq, slots)).apply()
    }

    /** Populate [eq] from the leftBands pref; false → caller forks from bothEq. */
    fun restoreLeftBands(eq: ParametricEqualizer): Boolean {
        val s = prefs.getString("leftBands", null) ?: return false
        return loadBands(eq, s, ParametricEqualizer.Channel.LEFT)
    }

    /** The CSE shared "Both" layer's bands (+slots). */
    fun saveSharedBands(eq: ParametricEqualizer, slots: List<Int>? = null) {
        prefs.edit().putString("sharedBands", serializeBands(eq, slots)).apply()
    }
    fun restoreSharedBands(eq: ParametricEqualizer): Boolean {
        val s = prefs.getString("sharedBands", null) ?: return false
        return loadBands(eq, s)
    }
    fun getSavedSharedSlots(): List<Int>? = parseSavedSlots(prefs.getString("sharedBands", null))

    fun restoreRightBands(eq: ParametricEqualizer): Boolean {
        val s = prefs.getString("rightBands", null) ?: return false
        return loadBands(eq, s, ParametricEqualizer.Channel.RIGHT)
    }

    /** Wipe leftBands/rightBands so a later CSE-enable re-forks fresh. */
    fun clearLeftRightBands() {
        prefs.edit().remove("leftBands").remove("rightBands").apply()
    }

    fun getSavedSlots(): List<Int>? = parseSavedSlots(prefs.getString("bands", null))

    /** Per-channel slot layouts; null when absent → sequential fallback. */
    fun getSavedLeftSlots(): List<Int>? = parseSavedSlots(prefs.getString("leftBands", null))

    fun getSavedRightSlots(): List<Int>? = parseSavedSlots(prefs.getString("rightBands", null))

    /** Slot fields from a serialized band array; null if missing/malformed. */
    private fun parseSavedSlots(bandsStr: String?): List<Int>? {
        val s = bandsStr ?: return null
        return try {
            val bandsJson = JSONArray(s)
            val slots = mutableListOf<Int>()
            for (i in 0 until bandsJson.length()) {
                val obj = bandsJson.getJSONObject(i)
                if (obj.has("slot")) slots.add(obj.getInt("slot")) else return null
            }
            slots
        } catch (_: Exception) {
            null
        }
    }

    fun savePresetName(name: String) {
        prefs.edit().putString("presetName", name).apply()
    }

    fun getPresetName(): String = prefs.getString("presetName", "Flat") ?: "Flat"

    fun savePresetAutosave(enabled: Boolean) { prefs.edit().putBoolean("presetAutosave", enabled).apply() }
    fun getPresetAutosave(): Boolean = prefs.getBoolean("presetAutosave", false)

    /** Overwrites a pool preset's JSON only — never touches the name set. */
    fun updateCustomPresetJson(name: String, json: String) {
        customPresetsPrefs.edit().putString("preset_$name", json).apply()
    }

    fun saveDpBandCount(count: Int) {
        prefs.edit().putInt("dpBandCount", count).apply()
    }

    fun getDpBandCount(): Int = prefs.getInt("dpBandCount", 128)

    /** Experimental EQ band cap (issue #31): 16 default, raisable to EqStateManager.ABSOLUTE_MAX_BANDS. */
    fun getMaxEqBands(): Int = prefs.getInt("maxEqBands", 16)
    fun saveMaxEqBands(count: Int) { prefs.edit().putInt("maxEqBands", count).apply() }

    fun saveEqUiMode(mode: String) {
        prefs.edit().putString("eqUiMode", mode).apply()
    }

    fun getEqUiMode(): String {
        val mode = prefs.getString("eqUiMode", "PARAMETRIC") ?: "PARAMETRIC"
        return if (mode == "TYPED") "TABLE" else mode
    }

    fun saveBandColors(colors: Map<Int, Int>) {
        val json = JSONObject()
        for ((slot, color) in colors) {
            json.put(slot.toString(), color)
        }
        prefs.edit().putString("bandColors", json.toString()).apply()
    }

    fun getBandColors(): Map<Int, Int> {
        val str = prefs.getString("bandColors", null) ?: return emptyMap()
        val json = JSONObject(str)
        val map = mutableMapOf<Int, Int>()
        for (key in json.keys()) {
            map[key.toInt()] = json.getInt(key)
        }
        return map
    }

    // Preamp
    fun savePreampGain(gain: Float) { prefs.edit().putFloat("preampGain", gain.saneOr(0f, -20f, 20f)).apply() }
    // Per-side preamps for CSE mode (shared preamp used when CSE off).
    fun savePreampLeft(v: Float) { prefs.edit().putFloat("preampLeftDb", v.saneOr(0f, -20f, 20f)).apply() }
    fun getPreampLeft(): Float = prefs.getFloat("preampLeftDb", 0f)
    fun savePreampRight(v: Float) { prefs.edit().putFloat("preampRightDb", v.saneOr(0f, -20f, 20f)).apply() }
    fun getPreampRight(): Float = prefs.getFloat("preampRightDb", 0f)
    fun getPreampGain(): Float = prefs.getFloat("preampGain", 0f)

    // Auto-gain
    fun saveAutoGainEnabled(enabled: Boolean) { prefs.edit().putBoolean("autoGainEnabled", enabled).apply() }
    // Default ON: pulls EQ peak to ≤ 0 dB so boosts can't clip (issue #57).
    fun getAutoGainEnabled(): Boolean = prefs.getBoolean("autoGainEnabled", true)

    // Issue #58: hide the foreground notification while the EQ is off (default off).
    fun setHideNotificationWhenOff(enabled: Boolean) { prefs.edit().putBoolean("hideNotifWhenOff", enabled).apply() }
    fun getHideNotificationWhenOff(): Boolean = prefs.getBoolean("hideNotifWhenOff", false)

    // Issue #26: DP FFT frame duration (ms) — single source of truth; default 80 ms.
    fun saveDpFrameMs(ms: Float) { prefs.edit().putFloat("dpFrameMs", ms).apply() }
    fun getDpFrameMs(): Float = prefs.getFloat("dpFrameMs", 80f)

    // Pre+Post-EQ interleave (issue #26): 256 effective stairs; needs an EQ power cycle.
    fun saveDpInterleave(enabled: Boolean) { prefs.edit().putBoolean("dpInterleave", enabled).apply() }
    fun getDpInterleave(): Boolean = prefs.getBoolean("dpInterleave", false)

    // MBC volume compensation: thresholds/gates follow the media volume (opt-in).
    fun saveMbcVolumeCompEnabled(enabled: Boolean) { prefs.edit().putBoolean("mbcVolumeComp", enabled).apply() }
    fun getMbcVolumeCompEnabled(): Boolean = prefs.getBoolean("mbcVolumeComp", false)

    // Which info lines show in the notification body (issue #65).
    fun saveNotifShowVolume(b: Boolean) { prefs.edit().putBoolean("notifShowVolume", b).apply() }
    fun getNotifShowVolume(): Boolean = prefs.getBoolean("notifShowVolume", true)
    fun saveNotifShowMode(b: Boolean) { prefs.edit().putBoolean("notifShowMode", b).apply() }
    fun getNotifShowMode(): Boolean = prefs.getBoolean("notifShowMode", true)
    fun saveNotifShowPreset(b: Boolean) { prefs.edit().putBoolean("notifShowPreset", b).apply() }
    fun getNotifShowPreset(): Boolean = prefs.getBoolean("notifShowPreset", true)
    fun saveNotifShowDevice(b: Boolean) { prefs.edit().putBoolean("notifShowDevice", b).apply() }
    fun getNotifShowDevice(): Boolean = prefs.getBoolean("notifShowDevice", true)
    fun saveNotifLineOrder(order: List<String>) { prefs.edit().putString("notifLineOrder", order.joinToString(",")).apply() }
    fun getNotifLineOrder(): List<String> {
        val def = listOf("volume", "mode", "preset", "device")
        val saved = prefs.getString("notifLineOrder", null)?.split(",")?.filter { it in def } ?: return def
        return saved + def.filter { it !in saved }
    }

    // EQ mode tabs (issue #77): which modes show on the main screen and their order.
    fun saveEqModeEnabled(key: String, b: Boolean) { prefs.edit().putBoolean("eqMode_$key", b).apply() }
    fun getEqModeEnabled(key: String): Boolean =
        prefs.getBoolean("eqMode_$key", key == "parametric" || key == "table")
    fun saveEqModeOrder(order: List<String>) { prefs.edit().putString("eqModeOrder", order.joinToString(",")).apply() }
    fun getEqModeOrder(): List<String> {
        val def = listOf("parametric", "table", "graphic", "simple")
        val saved = prefs.getString("eqModeOrder", null)?.split(",")?.filter { it in def } ?: return def
        return saved + def.filter { it !in saved }
    }

    fun saveGraphHeat(enabled: Boolean) { prefs.edit().putBoolean("graphHeat", enabled).apply() }
    fun getGraphHeat(): Boolean = prefs.getBoolean("graphHeat", false)

    // Compat Mode: cap DP at 32 bands for band-limited HALs; auto-on for Google/Pixel.
    fun saveDpCompatMode(enabled: Boolean) { prefs.edit().putBoolean("dpCompatMode", enabled).apply() }
    fun getDpCompatMode(): Boolean =
        prefs.getBoolean("dpCompatMode", defaultCompatMode())
    private fun defaultCompatMode(): Boolean =
        android.os.Build.MANUFACTURER.equals("Google", true) ||
            android.os.Build.MODEL.startsWith("Pixel", true)

    // Limiter
    fun saveLimiterEnabled(enabled: Boolean) { prefs.edit().putBoolean("limiterEnabled", enabled).apply() }
    fun getLimiterEnabled(): Boolean = prefs.getBoolean("limiterEnabled", false)
    fun saveLimiterAttack(ms: Float) { prefs.edit().putFloat("limiterAttack", ms.saneOr(0.01f, 0.01f, 100f)).apply() }
    fun getLimiterAttack(): Float = prefs.getFloat("limiterAttack", 0.01f)
    fun saveLimiterRelease(ms: Float) { prefs.edit().putFloat("limiterRelease", ms.saneOr(1f, 1f, 500f)).apply() }
    fun getLimiterRelease(): Float = prefs.getFloat("limiterRelease", 1f)
    fun saveLimiterRatio(ratio: Float) { prefs.edit().putFloat("limiterRatio", ratio.saneOr(2f, 1f, 50f)).apply() }
    fun getLimiterRatio(): Float = prefs.getFloat("limiterRatio", 2f)
    fun saveLimiterThreshold(db: Float) { prefs.edit().putFloat("limiterThreshold", db.saneOr(0f, -30f, 0f)).apply() }
    fun getLimiterThreshold(): Float = prefs.getFloat("limiterThreshold", 0f)
    fun saveLimiterPostGain(db: Float) { prefs.edit().putFloat("limiterPostGain", db.saneOr(0f, -12f, 12f)).apply() }
    fun getLimiterPostGain(): Float = prefs.getFloat("limiterPostGain", 0f)

    fun saveLastFileUri(uri: Uri) {
        prefs.edit().putString("lastFileUri", uri.toString()).apply()
    }

    fun getLastFileUri(): Uri? {
        val str = prefs.getString("lastFileUri", null) ?: return null
        return Uri.parse(str)
    }

    // Spectrum visualizer
    fun saveSpectrumEnabled(enabled: Boolean) { prefs.edit().putBoolean("spectrumEnabled", enabled).apply() }
    fun getSpectrumEnabled(): Boolean = prefs.getBoolean("spectrumEnabled", false)

    // Imported presets (stored as JSON array of names + raw text stored per preset)
    fun addImportedPreset(name: String, rawText: String) {
        val list = getImportedPresets().toMutableList()
        list.removeAll { it == name }
        list.add(0, name)
        prefs.edit()
            .putString("importedPresets", org.json.JSONArray(list).toString())
            .putString("importedPreset_$name", rawText)
            .apply()
    }
    fun removeImportedPreset(name: String) {
        val list = getImportedPresets().toMutableList()
        list.remove(name)
        prefs.edit()
            .putString("importedPresets", org.json.JSONArray(list).toString())
            .remove("importedPreset_$name")
            .apply()
    }
    fun getImportedPresetText(name: String): String? = prefs.getString("importedPreset_$name", null)
    fun getImportedPresets(): List<String> {
        val str = prefs.getString("importedPresets", null) ?: return emptyList()
        val arr = org.json.JSONArray(str)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // Favorite AutoEQ presets — keyed "source\u0001name" so imports can't collide with database entries.
    private fun favKey(source: String, name: String) = "$source\u0001$name"
    fun addFavoritePreset(name: String, source: String) {
        val key = favKey(source, name)
        val list = getFavoritePresetKeys().toMutableList()
        if (key !in list) list.add(0, key)
        prefs.edit().putString("favoritePresets", org.json.JSONArray(list).toString()).apply()
    }
    fun removeFavoritePreset(name: String, source: String) {
        val key = favKey(source, name)
        val list = getFavoritePresetKeys().toMutableList()
        list.remove(key)
        prefs.edit().putString("favoritePresets", org.json.JSONArray(list).toString()).apply()
    }
    fun isFavoritePreset(name: String, source: String): Boolean =
        favKey(source, name) in getFavoritePresetKeys()
    private fun getFavoritePresetKeys(): List<String> {
        val str = prefs.getString("favoritePresets", null) ?: return emptyList()
        val arr = org.json.JSONArray(str)
        return (0 until arr.length()).map { arr.getString(it) }
    }
    /** Favorited presets, newest first, as (name, source) pairs. */
    fun getFavoritePresets(): List<Pair<String, String>> =
        getFavoritePresetKeys().mapNotNull {
            val parts = it.split('\u0001', limit = 2)
            if (parts.size == 2) parts[1] to parts[0] else null
        }

    // Pipeline order — drag-reorder persists here; the chain executor reads it back.
    fun saveAudioEffectsOrder(order: List<String>) {
        prefs.edit().putString("audioEffectsOrder", order.joinToString(",")).apply()
    }
    fun getAudioEffectsOrder(): List<String>? {
        val s = prefs.getString("audioEffectsOrder", null) ?: return null
        return s.split(',').filter { it.isNotBlank() }
    }

    /** Per-effect enable flag for the Audio Effects Pipeline (default off). */
    fun isAudioEffectEnabled(id: String): Boolean =
        prefs.getBoolean("audioEffectEnabled_$id", false)
    fun setAudioEffectEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("audioEffectEnabled_$id", enabled).apply()
    }

    // ---- Environmental Reverb: UI units are dB/per-mille; API wants mB (×100 on attach) ----
    fun saveReverbDecayTimeMs(v: Float) { prefs.edit().putFloat("reverbDecayTimeMs", v).apply() }
    fun getReverbDecayTimeMs(): Float = prefs.getFloat("reverbDecayTimeMs", 1490f)
    fun saveReverbDecayHfRatio(v: Float) { prefs.edit().putFloat("reverbDecayHfRatio", v).apply() }
    fun getReverbDecayHfRatio(): Float = prefs.getFloat("reverbDecayHfRatio", 0.83f)
    fun saveReverbReverbLevelDb(v: Float) { prefs.edit().putFloat("reverbReverbLevelDb", v).apply() }
    fun getReverbReverbLevelDb(): Float = prefs.getFloat("reverbReverbLevelDb", -4f)
    fun saveReverbRoomLevelDb(v: Float) { prefs.edit().putFloat("reverbRoomLevelDb", v).apply() }
    fun getReverbRoomLevelDb(): Float = prefs.getFloat("reverbRoomLevelDb", -4f)
    fun saveReverbReflectionsDelayMs(v: Float) { prefs.edit().putFloat("reverbReflectionsDelayMs", v).apply() }
    fun getReverbReflectionsDelayMs(): Float = prefs.getFloat("reverbReflectionsDelayMs", 7f)
    fun saveReverbReflectionsLevelDb(v: Float) { prefs.edit().putFloat("reverbReflectionsLevelDb", v).apply() }
    fun getReverbReflectionsLevelDb(): Float = prefs.getFloat("reverbReflectionsLevelDb", -10f)
    fun saveReverbDelayMs(v: Float) { prefs.edit().putFloat("reverbDelayMs", v).apply() }
    fun getReverbDelayMs(): Float = prefs.getFloat("reverbDelayMs", 11f)
    fun saveReverbRoomHFLevelDb(v: Float) { prefs.edit().putFloat("reverbRoomHFLevelDb", v).apply() }
    fun getReverbRoomHFLevelDb(): Float = prefs.getFloat("reverbRoomHFLevelDb", 0f)
    fun saveReverbDiffusionPct(v: Float) { prefs.edit().putFloat("reverbDiffusionPct", v).apply() }
    fun getReverbDiffusionPct(): Float = prefs.getFloat("reverbDiffusionPct", 100f)
    fun saveReverbDensityPct(v: Float) { prefs.edit().putFloat("reverbDensityPct", v).apply() }
    fun getReverbDensityPct(): Float = prefs.getFloat("reverbDensityPct", 100f)

    // Imported targets (stored as JSON array of names)
    fun addImportedTarget(name: String, rawText: String = "") {
        val list = getImportedTargets().toMutableList()
        list.removeAll { it == name }
        list.add(0, name)
        val editor = prefs.edit().putString("importedTargets", org.json.JSONArray(list).toString())
        if (rawText.isNotEmpty()) editor.putString("importedTarget_$name", rawText)
        editor.apply()
    }
    fun getImportedTargetText(name: String): String? = prefs.getString("importedTarget_$name", null)
    fun removeImportedTarget(name: String) {
        val list = getImportedTargets().toMutableList()
        list.remove(name)
        prefs.edit()
            .putString("importedTargets", org.json.JSONArray(list).toString())
            .remove("importedTarget_$name")
            .apply()
    }
    fun getImportedTargets(): List<String> {
        val str = prefs.getString("importedTargets", null) ?: return emptyList()
        val arr = org.json.JSONArray(str)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // Imported measurements
    fun addImportedMeasurement(name: String, rawText: String) {
        val list = getImportedMeasurements().toMutableList()
        list.removeAll { it == name }
        list.add(0, name)
        prefs.edit()
            .putString("importedMeasurements", org.json.JSONArray(list).toString())
            .putString("importedMeas_$name", rawText)
            .apply()
    }
    fun removeImportedMeasurement(name: String) {
        val list = getImportedMeasurements().toMutableList()
        list.remove(name)
        prefs.edit()
            .putString("importedMeasurements", org.json.JSONArray(list).toString())
            .remove("importedMeas_$name")
            .apply()
    }
    fun getImportedMeasurementText(name: String): String? = prefs.getString("importedMeas_$name", null)
    fun getImportedMeasurements(): List<String> {
        val str = prefs.getString("importedMeasurements", null) ?: return emptyList()
        val arr = org.json.JSONArray(str)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // Selected measurement
    fun saveSelectedMeasurement(name: String) { prefs.edit().putString("selectedMeasurement", name).apply() }
    fun getSelectedMeasurement(): String? = prefs.getString("selectedMeasurement", null)
    fun saveSelectedMeasurementInfo(info: String) { prefs.edit().putString("selectedMeasurementInfo", info).apply() }
    fun getSelectedMeasurementInfo(): String? = prefs.getString("selectedMeasurementInfo", null)

    // Power state (for instant FAB sync across screens)
    fun savePowerState(on: Boolean) { prefs.edit().putBoolean("powerOn", on).apply() }
    fun getPowerState(): Boolean = prefs.getBoolean("powerOn", false)

    // Target
    fun saveSelectedTarget(file: String) { prefs.edit().putString("selectedTarget", file).apply() }
    fun getSelectedTarget(): String? = prefs.getString("selectedTarget", null)
    fun saveSelectedTargetName(name: String) { prefs.edit().putString("selectedTargetName", name).apply() }
    fun getSelectedTargetName(): String? = prefs.getString("selectedTargetName", null)
    fun saveSelectedTargetType(type: String) { prefs.edit().putString("selectedTargetType", type).apply() }
    fun getSelectedTargetType(): String? = prefs.getString("selectedTargetType", null)

    // AutoEQ
    fun saveAutoEqName(name: String) { prefs.edit().putString("autoEqName", name).apply() }
    fun getAutoEqName(): String? = prefs.getString("autoEqName", null)
    fun saveAutoEqSource(source: String) { prefs.edit().putString("autoEqSource", source).apply() }
    fun getAutoEqSource(): String? = prefs.getString("autoEqSource", null)

    // Generated Custom EQ persistence
    fun saveGeneratedEq(apoText: String, timestamp: String) {
        prefs.edit().putString("generatedEqApo", apoText).putString("generatedEqTimestamp", timestamp).apply()
    }
    fun getGeneratedEqApo(): String? = prefs.getString("generatedEqApo", null)
    fun getGeneratedEqTimestamp(): String? = prefs.getString("generatedEqTimestamp", null)
    fun clearGeneratedEq() { prefs.edit().remove("generatedEqApo").remove("generatedEqTimestamp").apply() }

    // Spectrum Control
    fun saveFftSizeEnabled(enabled: Boolean) { prefs.edit().putBoolean("fftSizeEnabled", enabled).apply() }
    fun getFftSizeEnabled(): Boolean = prefs.getBoolean("fftSizeEnabled", false)
    fun saveFftSizeIndex(index: Int) { prefs.edit().putInt("fftSizeIndex", index).apply() }
    fun getFftSizeIndex(): Int = prefs.getInt("fftSizeIndex", 2) // default 4096
    fun savePpoEnabled(enabled: Boolean) { prefs.edit().putBoolean("ppoEnabled", enabled).apply() }
    fun getPpoEnabled(): Boolean = prefs.getBoolean("ppoEnabled", false)
    fun savePpoIndex(index: Int) { prefs.edit().putInt("ppoIndex", index).apply() }
    fun getPpoIndex(): Int = prefs.getInt("ppoIndex", 2) // default 1/6
    fun saveSpectrumRelease(value: Float) { prefs.edit().putFloat("spectrumRelease", value).apply() }
    fun getSpectrumRelease(): Float = prefs.getFloat("spectrumRelease", 0.22f)
    fun saveSpectrumColor(color: Int) { prefs.edit().putInt("spectrumColor", color).apply() }
    fun getSpectrumColor(): Int = prefs.getInt("spectrumColor", 0xFFB4B4B4.toInt()) // default gray
    fun saveSpectrumFps(fps: Int) { prefs.edit().putInt("spectrumFps", fps).apply() }
    fun getSpectrumFps(): Int = prefs.getInt("spectrumFps", 60)

    // MBC
    fun saveMbcEnabled(enabled: Boolean) { prefs.edit().putBoolean("mbcEnabled", enabled).apply() }
    fun getMbcEnabled(): Boolean = prefs.getBoolean("mbcEnabled", false)
    fun saveMbcBandCount(count: Int) { prefs.edit().putInt("mbcBandCount", count.coerceIn(1, 8)).apply() }
    fun getMbcBandCount(): Int = prefs.getInt("mbcBandCount", 3)

    fun saveMbcBand(i: Int, enabled: Boolean, cutoff: Float, attack: Float, release: Float,
                    ratio: Float, threshold: Float, knee: Float, noiseGate: Float,
                    expander: Float, preGain: Float, postGain: Float, range: Float) {
        prefs.edit()
            .putBoolean("mbc_${i}_enabled", enabled)
            .putFloat("mbc_${i}_cutoff", cutoff.saneOr(1000f, 20f, 20000f))
            .putFloat("mbc_${i}_attack", attack.saneOr(1f, 0.01f, 500f))
            .putFloat("mbc_${i}_release", release.saneOr(100f, 1f, 5000f))
            .putFloat("mbc_${i}_ratio", ratio.saneOr(2f, 1f, 50f))
            .putFloat("mbc_${i}_threshold", threshold.saneOr(0f, -60f, 0f))
            .putFloat("mbc_${i}_knee", knee.saneOr(8f, 0.01f, 24f))
            .putFloat("mbc_${i}_noiseGate", noiseGate.saneOr(-60f, -90f, 0f))
            .putFloat("mbc_${i}_expander", expander.saneOr(1f, 1f, 50f))
            .putFloat("mbc_${i}_preGain", preGain.saneOr(0f, -30f, 30f))
            .putFloat("mbc_${i}_postGain", postGain.saneOr(0f, -30f, 30f))
            .putFloat("mbc_${i}_range", range.saneOr(-12f, -12f, 0f))
            .apply()
    }

    fun getMbcBandEnabled(i: Int): Boolean = prefs.getBoolean("mbc_${i}_enabled", true)
    fun getMbcBandCutoff(i: Int, default: Float): Float = prefs.getFloat("mbc_${i}_cutoff", default)
    fun getMbcBandAttack(i: Int): Float = prefs.getFloat("mbc_${i}_attack", 1f)
    fun getMbcBandRelease(i: Int): Float = prefs.getFloat("mbc_${i}_release", 100f)
    fun getMbcBandRatio(i: Int): Float = prefs.getFloat("mbc_${i}_ratio", 2f)
    fun getMbcBandThreshold(i: Int): Float = prefs.getFloat("mbc_${i}_threshold", 0f)
    fun getMbcBandKnee(i: Int): Float = prefs.getFloat("mbc_${i}_knee", 8f)
    fun getMbcBandNoiseGate(i: Int): Float = prefs.getFloat("mbc_${i}_noiseGate", -60f)
    fun getMbcBandExpander(i: Int): Float = prefs.getFloat("mbc_${i}_expander", 1f)
    fun getMbcBandPreGain(i: Int): Float = prefs.getFloat("mbc_${i}_preGain", 0f)
    fun getMbcBandPostGain(i: Int): Float = prefs.getFloat("mbc_${i}_postGain", 0f)
    fun getMbcBandRange(i: Int, default: Float = -12f): Float = prefs.getFloat("mbc_${i}_range", default)

    // MBC Crossovers
    fun saveMbcCrossover(i: Int, freq: Float) { prefs.edit().putFloat("mbc_crossover_$i", freq.saneOr(1000f, 20f, 20000f)).apply() }
    fun getMbcCrossover(i: Int, default: Float): Float = prefs.getFloat("mbc_crossover_$i", default)

    // Simple EQ
    fun saveSimpleEqEnabled(enabled: Boolean) { prefs.edit().putBoolean("simpleEqEnabled", enabled).apply() }
    fun getSimpleEqEnabled(): Boolean = prefs.getBoolean("simpleEqEnabled", false)

    // Light/dark theme (dark default) — EqApp reads this key raw at process start; keep the name in sync.
    fun saveLightTheme(light: Boolean) { prefs.edit().putBoolean("lightTheme", light).apply() }
    fun getLightTheme(): Boolean = prefs.getBoolean("lightTheme", false)
    fun saveAmoledTheme(on: Boolean) { prefs.edit().putBoolean("amoledTheme", on).apply() }
    fun getAmoledTheme(): Boolean = prefs.getBoolean("amoledTheme", false)
    fun saveSimpleEqGains(gains: FloatArray) {
        val arr = JSONArray()
        for (g in gains) arr.put(g.toDouble())
        // Synchronous .commit(): a write lost to abrupt process death was the visible drift bug.
        prefs.edit().putString("simpleEqGains", arr.toString()).commit()
    }
    fun getSimpleEqGains(): FloatArray? {
        val str = prefs.getString("simpleEqGains", null) ?: return null
        val arr = JSONArray(str)
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }
    // Save/restore the advanced EQ state separately so switching to/from Simple doesn't destroy it
    fun saveAdvancedEqBackup(bandsJson: String) { prefs.edit().putString("advancedEqBackup", bandsJson).apply() }
    fun getAdvancedEqBackup(): String? = prefs.getString("advancedEqBackup", null)

    // Experimental lock — when locked, Experimental card is not clickable
    fun saveExperimentalUnlocked(unlocked: Boolean) { prefs.edit().putBoolean("experimentalUnlocked", unlocked).apply() }
    fun getExperimentalUnlocked(): Boolean = prefs.getBoolean("experimentalUnlocked", false)

    // Channel Side EQ — per-channel (L/R) EQ mode. Stored only; no runtime effect yet.
    fun saveChannelSideEqEnabled(enabled: Boolean) { prefs.edit().putBoolean("channelSideEqEnabled", enabled).apply() }
    fun getChannelSideEqEnabled(): Boolean = prefs.getBoolean("channelSideEqEnabled", false)

    // Channel balance — integer percent, -100 (left) to +100 (right).
    fun saveChannelBalancePercent(pct: Int) { prefs.edit().putInt("channelBalancePercent", pct.coerceIn(-100, 100)).apply() }
    fun getChannelBalancePercent(): Int = prefs.getInt("channelBalancePercent", 0)

    // Per-channel preamp gain in dB. Range ±12 dB. Stored only; no runtime hookup yet.
    fun saveLeftChannelGainDb(db: Float) { prefs.edit().putFloat("leftChannelGainDb", db.saneOr(0f, -12f, 12f)).apply() }
    fun getLeftChannelGainDb(): Float = prefs.getFloat("leftChannelGainDb", 0f)
    fun saveRightChannelGainDb(db: Float) { prefs.edit().putFloat("rightChannelGainDb", db.saneOr(0f, -12f, 12f)).apply() }
    fun getRightChannelGainDb(): Float = prefs.getFloat("rightChannelGainDb", 0f)

    /** Clamps every stored DSP-facing value to its slider range (NaN / wrong type → default); only keys that exist are touched. */
    fun sanitizeDspSettings() {
        val e = prefs.edit()
        var dirty = false
        fun f(key: String, default: Float, lo: Float, hi: Float) {
            if (!prefs.contains(key)) return
            val v = prefs.all[key] as? Float
            val s = (v ?: Float.NaN).saneOr(default, lo, hi)
            if (v == null || s != v) { e.putFloat(key, s); dirty = true }
        }
        fun i(key: String, lo: Int, hi: Int) {
            if (!prefs.contains(key)) return
            val v = prefs.all[key] as? Int
            val s = (v ?: lo).coerceIn(lo, hi)
            if (v == null || s != v) { e.putInt(key, s); dirty = true }
        }
        f("preampGain", 0f, -20f, 20f); f("preampLeftDb", 0f, -20f, 20f); f("preampRightDb", 0f, -20f, 20f)
        i("channelBalancePercent", -100, 100)
        f("leftChannelGainDb", 0f, -12f, 12f); f("rightChannelGainDb", 0f, -12f, 12f)
        f("limiterAttack", 0.01f, 0.01f, 100f); f("limiterRelease", 1f, 1f, 500f); f("limiterRatio", 2f, 1f, 50f)
        f("limiterThreshold", 0f, -30f, 0f); f("limiterPostGain", 0f, -12f, 12f)
        i("mbcBandCount", 1, 8)
        for (b in 0 until 8) {
            f("mbc_${b}_cutoff", 1000f, 20f, 20000f); f("mbc_${b}_attack", 1f, 0.01f, 500f)
            f("mbc_${b}_release", 100f, 1f, 5000f); f("mbc_${b}_ratio", 2f, 1f, 50f)
            f("mbc_${b}_threshold", 0f, -60f, 0f); f("mbc_${b}_knee", 8f, 0.01f, 24f)
            f("mbc_${b}_noiseGate", -60f, -90f, 0f); f("mbc_${b}_expander", 1f, 1f, 50f)
            f("mbc_${b}_preGain", 0f, -30f, 30f); f("mbc_${b}_postGain", 0f, -30f, 30f)
            f("mbc_${b}_range", -12f, -12f, 0f); f("mbc_crossover_$b", 1000f, 20f, 20000f)
        }
        if (dirty) e.apply()
    }

    // ---- Simple EQ Presets: saved as full-JSON pool presets; loading INTO Simple samples the composite at the 10 fixed freqs ----
    fun getSimpleEqPresetNames(): List<String> {
        return (customPresetsPrefs.getStringSet("preset_names", emptySet()) ?: emptySet()).sorted()
    }

    fun saveSimpleEqPreset(name: String, gains: FloatArray, preamp: Float = 0f) {
        val bands = JSONArray()
        for (i in SIMPLE_FREQS.indices) {
            val g = if (i < gains.size) gains[i] else 0f
            bands.put(JSONObject().apply {
                put("frequency", SIMPLE_FREQS[i].toDouble())
                put("gain", g.toDouble())
                put("q", SIMPLE_Q)
                put("filterType", BiquadFilter.FilterType.BELL.name)
                put("enabled", true)
            })
        }
        val json = JSONObject().apply {
            put("preamp", preamp.toDouble())
            put("channelSideEqEnabled", false)
            put("bands", bands)
        }
        val names = (customPresetsPrefs.getStringSet("preset_names", emptySet()) ?: emptySet()).toMutableSet() + name
        customPresetsPrefs.edit()
            .putString("preset_$name", json.toString())
            .putStringSet("preset_names", names)
            .apply()
    }

    /** Resolve any pool preset to 10 Simple-mode bar gains; null if unparseable. */
    fun getSimpleEqPresetGains(name: String): FloatArray? {
        val str = customPresetsPrefs.getString("preset_$name", null) ?: return null
        return try {
            val obj = JSONObject(str)
            val arr = obj.getJSONArray("bands")

            // Native 10-band Simple preset: read gains directly so save→load round-trips exactly.
            if (arr.length() == SIMPLE_FREQS.size) {
                var native = true
                val direct = FloatArray(SIMPLE_FREQS.size)
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val freq = b.getDouble("frequency").toFloat()
                    val isBell = (b.optString("filterType", "BELL") == BiquadFilter.FilterType.BELL.name)
                    if (!isBell || kotlin.math.abs(freq - SIMPLE_FREQS[i]) > 1f) { native = false; break }
                    direct[i] = b.getDouble("gain").toFloat().coerceIn(-12f, 12f)
                }
                if (native) return direct
            }

            // Arbitrary preset: sample the composite response at the Simple frequencies.
            val eq = ParametricEqualizer()
            eq.clearBands()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val ft = try { BiquadFilter.FilterType.valueOf(b.getString("filterType")) }
                         catch (_: Exception) { BiquadFilter.FilterType.BELL }
                eq.addBand(b.getDouble("frequency").toFloat(), b.getDouble("gain").toFloat(), ft, b.getDouble("q"))
            }
            FloatArray(SIMPLE_FREQS.size) { i ->
                eq.getFrequencyResponse(SIMPLE_FREQS[i]).coerceIn(-12f, 12f)
            }
        } catch (_: Exception) { null }
    }

    fun deleteSimpleEqPreset(name: String) {
        val names = (customPresetsPrefs.getStringSet("preset_names", emptySet()) ?: emptySet()).toMutableSet() - name
        customPresetsPrefs.edit()
            .remove("preset_$name")
            .putStringSet("preset_names", names)
            .apply()
    }

    /** Raw JSON of a pool preset — Simple picker thumbnails/subtitles. */
    fun getCustomPresetJson(name: String): String? =
        customPresetsPrefs.getString("preset_$name", null)

    /** One-time migration of legacy simple_preset_* keys into the shared pool. */
    private fun migrateLegacySimplePresets() {
        val legacyNames = prefs.getStringSet("simple_preset_names", null) ?: return
        if (legacyNames.isEmpty()) {
            prefs.edit().remove("simple_preset_names").apply()
            return
        }
        val existing = (customPresetsPrefs.getStringSet("preset_names", emptySet()) ?: emptySet()).toMutableSet()
        val editor = customPresetsPrefs.edit()
        val legacyEditor = prefs.edit()
        for (name in legacyNames) {
            val legacyStr = prefs.getString("simple_preset_$name", null)
            if (legacyStr != null && !existing.contains(name)) {
                val gainsArr = try { JSONArray(legacyStr) } catch (_: Exception) { null }
                if (gainsArr != null) {
                    val bands = JSONArray()
                    for (i in SIMPLE_FREQS.indices) {
                        val g = if (i < gainsArr.length()) gainsArr.getDouble(i) else 0.0
                        bands.put(JSONObject().apply {
                            put("frequency", SIMPLE_FREQS[i].toDouble())
                            put("gain", g)
                            put("q", SIMPLE_Q)
                            put("filterType", BiquadFilter.FilterType.BELL.name)
                            put("enabled", true)
                        })
                    }
                    val json = JSONObject().apply {
                        put("preamp", 0.0)
                        put("channelSideEqEnabled", false)
                        put("bands", bands)
                    }
                    editor.putString("preset_$name", json.toString())
                    existing.add(name)
                }
            }
            legacyEditor.remove("simple_preset_$name")
        }
        editor.putStringSet("preset_names", existing)
        editor.apply()
        legacyEditor.remove("simple_preset_names").apply()
    }

    // ---- Device bindings (per-output-device EQ auto-switching) ----

    fun saveDeviceBinding(b: Binding) {
        val json = JSONObject()
            .put("key", b.key)
            .put("label", b.label)
            .put("presetName", b.presetName)
        bindingsPrefs.edit().putString("binding_${b.key}", json.toString()).apply()
    }

    fun getDeviceBinding(key: String): Binding? {
        val str = bindingsPrefs.getString("binding_$key", null) ?: return null
        return runCatching {
            val o = JSONObject(str)
            Binding(o.getString("key"), o.getString("label"), o.getString("presetName"))
        }.getOrNull()
    }

    fun getAllDeviceBindings(): List<Binding> {
        val out = mutableListOf<Binding>()
        for ((k, _) in bindingsPrefs.all) {
            if (!k.startsWith("binding_")) continue
            val str = bindingsPrefs.getString(k, null) ?: continue
            runCatching {
                val o = JSONObject(str)
                out.add(Binding(o.getString("key"), o.getString("label"), o.getString("presetName")))
            }
        }
        return out
    }

    fun removeDeviceBinding(key: String) {
        bindingsPrefs.edit().remove("binding_$key").apply()
    }

    /** Binding lookup with a name-alias fallback for BT/USB (dual-transport MACs re-key); a hit heals under the new key. */
    fun getDeviceBindingSmart(key: String, label: String): Binding? {
        getDeviceBinding(key)?.let { return it }
        val bucket = when {
            key.startsWith("BT") -> "BT"
            key.startsWith("USB") -> "USB"
            else -> return null
        }
        val alias = com.bearinmind.equalizer314.audio.DeviceIdentity.aliasName(label) ?: return null
        val match = getAllDeviceBindings().firstOrNull { b ->
            b.key.startsWith(bucket) && b.key != key &&
                com.bearinmind.equalizer314.audio.DeviceIdentity.aliasName(b.label)
                    ?.equals(alias, ignoreCase = true) == true
        } ?: return null
        val healed = Binding(key, label, match.presetName)
        saveDeviceBinding(healed)
        return healed
    }

    /** Live-EQ snapshot taken before an auto-switch preset apply — MainActivity's Undo. */
    fun saveLastManualState(json: String?) {
        if (json == null) bindingsPrefs.edit().remove("lastManualState").apply()
        else bindingsPrefs.edit().putString("lastManualState", json).apply()
    }

    fun getLastManualState(): String? = bindingsPrefs.getString("lastManualState", null)

    /** Record a device from the routing callback for the Audio Output "devices seen" list. */
    fun rememberSeenDevice(key: String, label: String) {
        bindingsPrefs.edit().putString("seen_$key", label).apply()
    }

    /** Returns `(key, label)` pairs for every device the app has seen. */
    fun getAllSeenDevices(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((k, v) in bindingsPrefs.all) {
            if (!k.startsWith("seen_")) continue
            val label = v as? String ?: continue
            out.add(k.removePrefix("seen_") to label)
        }
        return out
    }

    fun forgetSeenDevice(key: String) {
        bindingsPrefs.edit().remove("seen_$key").apply()
    }

    /** Devices tucked away by the eye button — still bound and auto-switching, just out of the main list. */
    fun getHiddenDeviceKeys(): Set<String> =
        bindingsPrefs.getStringSet("hidden_devices", emptySet())?.toSet() ?: emptySet()

    fun setDeviceHidden(key: String, hidden: Boolean) {
        val set = getHiddenDeviceKeys().toMutableSet()
        if (hidden) set.add(key) else set.remove(key)
        bindingsPrefs.edit().putStringSet("hidden_devices", set).apply()
    }

    /** Drag-reorder ordering of seen devices; unknown devices sort alphabetically after. */
    fun saveDevicesOrder(keys: List<String>) {
        val arr = JSONArray()
        for (k in keys) arr.put(k)
        bindingsPrefs.edit().putString("devices_order", arr.toString()).apply()
    }

    fun getDevicesOrder(): List<String> {
        val str = bindingsPrefs.getString("devices_order", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(str)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    // ---- App bindings (per-package EQ on session-open broadcasts) ----

    fun saveAppBinding(binding: AppBinding) {
        val json = JSONObject()
            .put("packageName", binding.packageName)
            .put("presetName", binding.presetName)
        appBindingsPrefs.edit().putString("binding_${binding.packageName}", json.toString()).apply()
    }

    fun getAppBinding(packageName: String): AppBinding? {
        val str = appBindingsPrefs.getString("binding_$packageName", null) ?: return null
        return runCatching {
            val o = JSONObject(str)
            AppBinding(o.getString("packageName"), o.getString("presetName"))
        }.getOrNull()
    }

    fun getAllAppBindings(): List<AppBinding> {
        val out = mutableListOf<AppBinding>()
        for ((k, _) in appBindingsPrefs.all) {
            if (!k.startsWith("binding_")) continue
            val str = appBindingsPrefs.getString(k, null) ?: continue
            runCatching {
                val o = JSONObject(str)
                out.add(AppBinding(o.getString("packageName"), o.getString("presetName")))
            }
        }
        return out
    }

    fun removeAppBinding(packageName: String) {
        appBindingsPrefs.edit().remove("binding_$packageName").apply()
    }

    /** Record a session-broadcasting app so Channel Input can list it before any preset is bound. */
    fun rememberSeenApp(packageName: String) {
        appBindingsPrefs.edit().putBoolean("seen_$packageName", true).apply()
    }

    fun getAllSeenApps(): List<String> {
        val out = mutableListOf<String>()
        for ((k, _) in appBindingsPrefs.all) {
            if (!k.startsWith("seen_")) continue
            out.add(k.removePrefix("seen_"))
        }
        return out
    }

    fun forgetSeenApp(packageName: String) {
        appBindingsPrefs.edit().remove("seen_$packageName").apply()
    }

    /** Routing mode: 0 = System-wide (default), 1 = Session-based; a legacy 2 reads as System-wide. */
    fun getAudioRoutingMode(): Int =
        appBindingsPrefs.getInt("audio_routing_mode", 0)

    fun saveAudioRoutingMode(mode: Int) {
        appBindingsPrefs.edit().putInt("audio_routing_mode", mode).apply()
    }

    /** Channel Input "Apps" filter: 0 = media-capable apps only (default), 1 = show all. */
    fun getAppListFilterMode(): Int =
        appBindingsPrefs.getInt("app_list_filter_mode", 0)

    fun saveAppListFilterMode(mode: Int) {
        appBindingsPrefs.edit().putInt("app_list_filter_mode", mode).apply()
    }

    /** System-sound bypass (default on): DP disabled while notification/ring/alarm-class streams play. */
    fun getBypassSystemSounds(): Boolean =
        appBindingsPrefs.getBoolean("bypass_system_sounds", true)

    fun setBypassSystemSounds(enabled: Boolean) {
        appBindingsPrefs.edit().putBoolean("bypass_system_sounds", enabled).apply()
    }

    /** Device auto-switch master toggle (default on); off = route changes ignored, bindings kept. */
    fun getDeviceAutoSwitchEnabled(): Boolean =
        appBindingsPrefs.getBoolean("device_auto_switch_enabled", true)

    fun setDeviceAutoSwitchEnabled(enabled: Boolean) {
        appBindingsPrefs.edit().putBoolean("device_auto_switch_enabled", enabled).apply()
    }
}

/** NaN / infinite → [default], otherwise clamped to [lo]..[hi]. */
internal fun Float.saneOr(default: Float, lo: Float, hi: Float): Float =
    if (isNaN() || isInfinite()) default else coerceIn(lo, hi)
