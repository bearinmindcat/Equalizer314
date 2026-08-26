package com.bearinmind.equalizer314.state

import android.content.Context
import com.bearinmind.equalizer314.autoeq.AutoEqFilter
import com.bearinmind.equalizer314.autoeq.AutoEqParser
import com.bearinmind.equalizer314.autoeq.apoTokenToFilterType
import org.json.JSONArray
import org.json.JSONObject

/** Shared preset-file import: native Equalizer314 .json, APO .txt, and the legacy
 *  EQ314 chain section — one parser for every import entry point (issue #78). */
object PresetFileIo {

    /** True when [text] carries more than plain APO (native JSON or a chain section). */
    fun hasChainData(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") || trimmed.contains("# EQ314 Specific")
    }

    fun uniquePresetName(context: Context, base: String): String {
        val prefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        if (!prefs.contains("preset_$base")) return base
        var i = 2
        while (prefs.contains("preset_$base ($i)")) i++
        return "$base ($i)"
    }

    /** Persist [presetJson] under [name] in the user presets store. */
    fun saveUserPreset(context: Context, name: String, presetJson: JSONObject) {
        val prefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val names = prefs.getStringSet("preset_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        names.add(name)
        prefs.edit()
            .putString("preset_$name", presetJson.toString())
            .putStringSet("preset_names", names)
            .apply()
    }

    /** Parse a native preset JSON or an APO .txt (incl. the EQ314 chain section) into preset JSON. */
    fun parseImportedPreset(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val obj = try { JSONObject(trimmed) } catch (_: Exception) { return null }
            // Native concept nests bands under "eq"; our preset JSON keeps them top-level.
            if (!obj.has("bands") && obj.optJSONObject("eq")?.has("bands") == true) {
                obj.put("bands", obj.getJSONObject("eq").getJSONArray("bands"))
            }
            return if (obj.has("bands")) obj else null
        }
        val profile = AutoEqParser.parse(trimmed) ?: return null
        if (profile.filters.isEmpty() && profile.leftFilters.isEmpty()) return null
        fun bandsOf(filters: List<AutoEqFilter>): JSONArray {
            val arr = JSONArray()
            for (f in filters) {
                arr.put(JSONObject().apply {
                    put("frequency", f.frequency.toDouble())
                    put("gain", f.gain.toDouble())
                    put("q", f.q.toDouble())
                    put("filterType", apoTokenToFilterType(f.filterType).name)
                    put("enabled", true)
                })
            }
            return arr
        }
        val json = JSONObject()
        json.put("preamp", profile.preampDb.toDouble())
        if (profile.perChannel) {
            json.put("channelSideEqEnabled", true)
            json.put("leftBands", bandsOf(profile.leftFilters))
            json.put("rightBands", bandsOf(profile.rightFilters))
            json.put("bands", bandsOf(profile.leftFilters))
        } else {
            json.put("channelSideEqEnabled", false)
            json.put("bands", bandsOf(profile.filters))
        }
        parseEq314ChainSection(trimmed, json)
        return json
    }

    /** Parse the "# EQ314 Specific" MBC / Limiter export lines back into preset JSON blocks. */
    private fun parseEq314ChainSection(text: String, json: JSONObject) {
        val mbcHead = Regex("""^MBC:\s+(ON|OFF)\s+Bands\s+(\d+)""", RegexOption.IGNORE_CASE)
        val mbcBand = Regex(
            """^MBC\s+(\d+):\s+(ON|OFF)\s+Fc\s+([\d.]+)\s*Hz\s+Atk\s+(-?[\d.]+)\s+Rel\s+(-?[\d.]+)\s+Ratio\s+(-?[\d.]+)\s+Thr\s+(-?[\d.]+)\s*dB\s+Knee\s+(-?[\d.]+)\s+Gate\s+(-?[\d.]+)\s+Exp\s+(-?[\d.]+)\s+Pre\s+(-?[\d.]+)\s+Post\s+(-?[\d.]+)""",
            RegexOption.IGNORE_CASE)
        val mbcCross = Regex("""^MBC Crossovers:\s+([\d.,\s]+)\s*Hz""", RegexOption.IGNORE_CASE)
        val limiterLine = Regex(
            """^Limiter:\s+(ON|OFF)\s+Atk\s+(-?[\d.]+)\s+Rel\s+(-?[\d.]+)\s+Ratio\s+(-?[\d.]+)\s+Thr\s+(-?[\d.]+)\s*dB\s+Post\s+(-?[\d.]+)""",
            RegexOption.IGNORE_CASE)
        var mbcObj: JSONObject? = null
        val bandArr = JSONArray()
        for (raw in text.lines()) {
            val line = raw.trim()
            mbcHead.find(line)?.let { m ->
                mbcObj = JSONObject().apply {
                    put("enabled", m.groupValues[1].equals("ON", true))
                    put("bandCount", m.groupValues[2].toInt())
                    put("bands", bandArr)
                }
            }
            mbcBand.find(line)?.let { m ->
                bandArr.put(JSONObject().apply {
                    put("enabled", m.groupValues[2].equals("ON", true))
                    put("cutoff", m.groupValues[3].toDouble())
                    put("attack", m.groupValues[4].toDouble())
                    put("release", m.groupValues[5].toDouble())
                    put("ratio", m.groupValues[6].toDouble())
                    put("threshold", m.groupValues[7].toDouble())
                    put("knee", m.groupValues[8].toDouble())
                    put("noiseGate", m.groupValues[9].toDouble())
                    put("expander", m.groupValues[10].toDouble())
                    put("preGain", m.groupValues[11].toDouble())
                    put("postGain", m.groupValues[12].toDouble())
                })
            }
            mbcCross.find(line)?.let { m ->
                val arr = JSONArray()
                m.groupValues[1].split(",").forEach { s ->
                    s.trim().toDoubleOrNull()?.let { arr.put(it) }
                }
                mbcObj?.put("crossovers", arr)
            }
            limiterLine.find(line)?.let { m ->
                json.put("limiter", JSONObject().apply {
                    put("enabled", m.groupValues[1].equals("ON", true))
                    put("attack", m.groupValues[2].toDouble())
                    put("release", m.groupValues[3].toDouble())
                    put("ratio", m.groupValues[4].toDouble())
                    put("threshold", m.groupValues[5].toDouble())
                    put("postGain", m.groupValues[6].toDouble())
                })
            }
        }
        mbcObj?.let { json.put("mbc", it) }
    }
}
