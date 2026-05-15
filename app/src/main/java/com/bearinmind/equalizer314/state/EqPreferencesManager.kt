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

    /** A device → preset binding. `key` is the stable device identity
     *  (e.g. `"BT:00:1A:7D:DA:71:13"`), `label` is the human-friendly
     *  name shown in the UI, `presetName` is a key into `custom_presets`. */
    data class Binding(val key: String, val label: String, val presetName: String)

    /** A per-app → preset binding for sessions that broadcast
     *  OPEN_AUDIO_EFFECT_CONTROL_SESSION. */
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

    // ---- Per-channel EQ persistence (Channel Side EQ) ------------------

    /** Serialize a ParametricEqualizer's bands to the compact JSON-array
     *  form the saveState / restoreState path uses. Private helper; the
     *  public entry points are saveLeftBands / saveRightBands. */
    private fun serializeBands(eq: ParametricEqualizer): String {
        val bands = JSONArray()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            bands.put(JSONObject().apply {
                put("frequency", band.frequency.toDouble())
                put("gain", band.gain.toDouble())
                put("filterType", band.filterType.name)
                put("q", band.q)
                put("enabled", band.enabled)
            })
        }
        return bands.toString()
    }

    /** Load a JSON-string band array into the given EQ. Returns true when
     *  parsing succeeded (even if the array was empty), false on malformed
     *  JSON. */
    private fun loadBands(eq: ParametricEqualizer, jsonStr: String): Boolean {
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
            }
            eq.isEnabled = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveLeftBands(eq: ParametricEqualizer) {
        prefs.edit().putString("leftBands", serializeBands(eq)).apply()
    }

    fun saveRightBands(eq: ParametricEqualizer) {
        prefs.edit().putString("rightBands", serializeBands(eq)).apply()
    }

    /** Populate [eq] from the `leftBands` pref. Returns true when the pref
     *  existed and parsed; false otherwise (caller should fall back to
     *  forking from `bothEq`). */
    fun restoreLeftBands(eq: ParametricEqualizer): Boolean {
        val s = prefs.getString("leftBands", null) ?: return false
        return loadBands(eq, s)
    }

    fun restoreRightBands(eq: ParametricEqualizer): Boolean {
        val s = prefs.getString("rightBands", null) ?: return false
        return loadBands(eq, s)
    }

    /** Wipe the saved `leftBands` / `rightBands` prefs. Called when the
     *  underlying "both" EQ has been replaced (non-CSE preset load, reset
     *  to defaults, etc.) so a subsequent CSE-enable re-forks from the new
     *  state instead of resurrecting stale per-channel divergence. */
    fun clearLeftRightBands() {
        prefs.edit().remove("leftBands").remove("rightBands").apply()
    }

    fun getSavedSlots(): List<Int>? {
        val bandsStr = prefs.getString("bands", null) ?: return null
        val bandsJson = JSONArray(bandsStr)
        val slots = mutableListOf<Int>()
        for (i in 0 until bandsJson.length()) {
            val obj = bandsJson.getJSONObject(i)
            if (obj.has("slot")) {
                slots.add(obj.getInt("slot"))
            } else {
                return null // no slot data saved
            }
        }
        return slots
    }

    fun savePresetName(name: String) {
        prefs.edit().putString("presetName", name).apply()
    }

    fun getPresetName(): String = prefs.getString("presetName", "Flat") ?: "Flat"

    fun saveDpBandCount(count: Int) {
        prefs.edit().putInt("dpBandCount", count).apply()
    }

    fun getDpBandCount(): Int = prefs.getInt("dpBandCount", 128)

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
    fun savePreampGain(gain: Float) { prefs.edit().putFloat("preampGain", gain).apply() }
    fun getPreampGain(): Float = prefs.getFloat("preampGain", 0f)

    // Auto-gain
    fun saveAutoGainEnabled(enabled: Boolean) { prefs.edit().putBoolean("autoGainEnabled", enabled).apply() }
    fun getAutoGainEnabled(): Boolean = prefs.getBoolean("autoGainEnabled", false)

    // Limiter
    fun saveLimiterEnabled(enabled: Boolean) { prefs.edit().putBoolean("limiterEnabled", enabled).apply() }
    fun getLimiterEnabled(): Boolean = prefs.getBoolean("limiterEnabled", false)
    fun saveLimiterAttack(ms: Float) { prefs.edit().putFloat("limiterAttack", ms).apply() }
    fun getLimiterAttack(): Float = prefs.getFloat("limiterAttack", 0.01f)
    fun saveLimiterRelease(ms: Float) { prefs.edit().putFloat("limiterRelease", ms).apply() }
    fun getLimiterRelease(): Float = prefs.getFloat("limiterRelease", 1f)
    fun saveLimiterRatio(ratio: Float) { prefs.edit().putFloat("limiterRatio", ratio).apply() }
    fun getLimiterRatio(): Float = prefs.getFloat("limiterRatio", 2f)
    fun saveLimiterThreshold(db: Float) { prefs.edit().putFloat("limiterThreshold", db).apply() }
    fun getLimiterThreshold(): Float = prefs.getFloat("limiterThreshold", 0f)
    fun saveLimiterPostGain(db: Float) { prefs.edit().putFloat("limiterPostGain", db).apply() }
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

    // Favorite AutoEQ presets — keyed by "source\u0001name" so an imported
    // preset whose name collides with a database entry is tracked separately.
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
    /** Returns favorited presets in display order (newest first) as
     *  (name, source) pairs. */
    fun getFavoritePresets(): List<Pair<String, String>> =
        getFavoritePresetKeys().mapNotNull {
            val parts = it.split('\u0001', limit = 2)
            if (parts.size == 2) parts[1] to parts[0] else null
        }

    // Audio Effects Pipeline — ordered list of effect IDs (enum names) that
    // represents the intended processing order. UI persists drag-reorder
    // here; the chain executor reads this back to know which effects run
    // and in what order. Stored as a comma-separated list because the set
    // is small and stable.
    fun saveAudioEffectsOrder(order: List<String>) {
        prefs.edit().putString("audioEffectsOrder", order.joinToString(",")).apply()
    }
    fun getAudioEffectsOrder(): List<String>? {
        val s = prefs.getString("audioEffectsOrder", null) ?: return null
        return s.split(',').filter { it.isNotBlank() }
    }

    /** Per-effect enable flag for the Audio Effects Pipeline. Defaults to
     *  false — effects are off until the user toggles their power button. */
    fun isAudioEffectEnabled(id: String): Boolean =
        prefs.getBoolean("audioEffectEnabled_$id", false)
    fun setAudioEffectEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("audioEffectEnabled_$id", enabled).apply()
    }

    // ---- Environmental Reverb -----------------------------------------
    // User-facing units: dB for *Level fields (API uses mB; convert × 100
    // when attaching to the AudioEffect), per-mille for diffusion/density,
    // ratio for decayHfRatio. Defaults match Android's documented values.
    fun saveReverbDecayTimeMs(v: Float) { prefs.edit().putFloat("reverbDecayTimeMs", v).apply() }
    fun getReverbDecayTimeMs(): Float = prefs.getFloat("reverbDecayTimeMs", 1490f)
    fun saveReverbDecayHfRatio(v: Float) { prefs.edit().putFloat("reverbDecayHfRatio", v).apply() }
    fun getReverbDecayHfRatio(): Float = prefs.getFloat("reverbDecayHfRatio", 0.83f)
    fun saveReverbReverbLevelDb(v: Float) { prefs.edit().putFloat("reverbReverbLevelDb", v).apply() }
    fun getReverbReverbLevelDb(): Float = prefs.getFloat("reverbReverbLevelDb", -26f)
    fun saveReverbRoomLevelDb(v: Float) { prefs.edit().putFloat("reverbRoomLevelDb", v).apply() }
    fun getReverbRoomLevelDb(): Float = prefs.getFloat("reverbRoomLevelDb", -10f)
    fun saveReverbReflectionsDelayMs(v: Float) { prefs.edit().putFloat("reverbReflectionsDelayMs", v).apply() }
    fun getReverbReflectionsDelayMs(): Float = prefs.getFloat("reverbReflectionsDelayMs", 7f)
    fun saveReverbReflectionsLevelDb(v: Float) { prefs.edit().putFloat("reverbReflectionsLevelDb", v).apply() }
    fun getReverbReflectionsLevelDb(): Float = prefs.getFloat("reverbReflectionsLevelDb", -10f)
    fun saveReverbDelayMs(v: Float) { prefs.edit().putFloat("reverbDelayMs", v).apply() }
    fun getReverbDelayMs(): Float = prefs.getFloat("reverbDelayMs", 11f)
    fun saveReverbEarlyReflectionsWidthMs(v: Float) { prefs.edit().putFloat("reverbEarlyReflectionsWidthMs", v).apply() }
    fun getReverbEarlyReflectionsWidthMs(): Float = prefs.getFloat("reverbEarlyReflectionsWidthMs", 268f)
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
    fun saveMbcBandCount(count: Int) { prefs.edit().putInt("mbcBandCount", count).apply() }
    fun getMbcBandCount(): Int = prefs.getInt("mbcBandCount", 3)

    fun saveMbcBand(i: Int, enabled: Boolean, cutoff: Float, attack: Float, release: Float,
                    ratio: Float, threshold: Float, knee: Float, noiseGate: Float,
                    expander: Float, preGain: Float, postGain: Float, range: Float) {
        prefs.edit()
            .putBoolean("mbc_${i}_enabled", enabled)
            .putFloat("mbc_${i}_cutoff", cutoff)
            .putFloat("mbc_${i}_attack", attack)
            .putFloat("mbc_${i}_release", release)
            .putFloat("mbc_${i}_ratio", ratio)
            .putFloat("mbc_${i}_threshold", threshold)
            .putFloat("mbc_${i}_knee", knee)
            .putFloat("mbc_${i}_noiseGate", noiseGate)
            .putFloat("mbc_${i}_expander", expander)
            .putFloat("mbc_${i}_preGain", preGain)
            .putFloat("mbc_${i}_postGain", postGain)
            .putFloat("mbc_${i}_range", range)
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
    fun saveMbcCrossover(i: Int, freq: Float) { prefs.edit().putFloat("mbc_crossover_$i", freq).apply() }
    fun getMbcCrossover(i: Int, default: Float): Float = prefs.getFloat("mbc_crossover_$i", default)

    // Simple EQ
    fun saveSimpleEqEnabled(enabled: Boolean) { prefs.edit().putBoolean("simpleEqEnabled", enabled).apply() }
    fun getSimpleEqEnabled(): Boolean = prefs.getBoolean("simpleEqEnabled", false)
    fun saveSimpleEqGains(gains: FloatArray) {
        val arr = JSONArray()
        for (g in gains) arr.put(g.toDouble())
        // .commit() (synchronous) instead of .apply() — Simple gains
        // are the user's source of truth for their per-band edits and
        // a write lost to abrupt process death (Bluetooth A2DP teardown,
        // force-stop) was the visible drift bug. .commit() guarantees
        // the bytes hit disk before this call returns.
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

    // Channel balance — integer percent from -100 (fully left) to +100 (fully right), 0 = neutral.
    // Stored only; no runtime DSP hookup yet.
    fun saveChannelBalancePercent(pct: Int) { prefs.edit().putInt("channelBalancePercent", pct).apply() }
    fun getChannelBalancePercent(): Int = prefs.getInt("channelBalancePercent", 0)

    // Per-channel preamp gain in dB. Range ±12 dB. Stored only; no runtime hookup yet.
    fun saveLeftChannelGainDb(db: Float) { prefs.edit().putFloat("leftChannelGainDb", db).apply() }
    fun getLeftChannelGainDb(): Float = prefs.getFloat("leftChannelGainDb", 0f)
    fun saveRightChannelGainDb(db: Float) { prefs.edit().putFloat("rightChannelGainDb", db).apply() }
    fun getRightChannelGainDb(): Float = prefs.getFloat("rightChannelGainDb", 0f)

    // Simple EQ Presets
    fun getSimpleEqPresetNames(): List<String> {
        return (prefs.getStringSet("simple_preset_names", emptySet()) ?: emptySet()).sorted()
    }
    fun saveSimpleEqPreset(name: String, gains: FloatArray) {
        val arr = JSONArray()
        for (g in gains) arr.put(g.toDouble())
        val names = (prefs.getStringSet("simple_preset_names", emptySet()) ?: emptySet()).toMutableSet() + name
        prefs.edit()
            .putString("simple_preset_$name", arr.toString())
            .putStringSet("simple_preset_names", names)
            .apply()
    }
    fun getSimpleEqPresetGains(name: String): FloatArray? {
        val str = prefs.getString("simple_preset_$name", null) ?: return null
        val arr = JSONArray(str)
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }
    fun deleteSimpleEqPreset(name: String) {
        val names = (prefs.getStringSet("simple_preset_names", emptySet()) ?: emptySet()).toMutableSet() - name
        prefs.edit()
            .remove("simple_preset_$name")
            .putStringSet("simple_preset_names", names)
            .apply()
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

    /** Snapshot of the live EQ state taken just before an auto-switch
     *  applied a device-bound preset, so MainActivity's Undo snackbar
     *  can restore it. Stored as the same JSON shape custom presets use,
     *  but in its own key so it can't collide with named presets. */
    fun saveLastManualState(json: String?) {
        if (json == null) bindingsPrefs.edit().remove("lastManualState").apply()
        else bindingsPrefs.edit().putString("lastManualState", json).apply()
    }

    fun getLastManualState(): String? = bindingsPrefs.getString("lastManualState", null)

    /** True the first time we ever saw `key`. Used by the Audio Output
     *  screen to populate the "devices seen" list — every new device
     *  that arrives in the routing callback is recorded here. */
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

    /** Persist the user's drag-to-reorder ordering of seen devices.
     *  Stored as a JSON array of device keys. Devices not in this list
     *  (e.g. a newly-discovered device) fall back to alphabetical-by-key
     *  ordering at the end. */
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

    /** Records that a session-broadcasting app has been seen so the
     *  Channel Input screen can list it even before the user
     *  explicitly binds a preset. */
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

    /** Routing mode for how the EQ attaches to audio:
     *   0 = GLOBAL_ONLY — session 0 only (current default behaviour)
     *   1 = PER_APP_ONLY — only attach when apps broadcast a session
     *   2 = BOTH — session 0 plus per-app overlays
     */
    fun getAudioRoutingMode(): Int =
        appBindingsPrefs.getInt("audio_routing_mode", 0)

    fun saveAudioRoutingMode(mode: Int) {
        appBindingsPrefs.edit().putInt("audio_routing_mode", mode).apply()
    }
}
