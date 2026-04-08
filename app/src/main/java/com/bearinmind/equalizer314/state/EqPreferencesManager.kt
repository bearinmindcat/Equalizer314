package com.bearinmind.equalizer314.state

import android.content.Context
import android.net.Uri
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import org.json.JSONArray
import org.json.JSONObject

class EqPreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)

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
}
