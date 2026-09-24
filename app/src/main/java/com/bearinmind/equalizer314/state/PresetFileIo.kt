package com.bearinmind.equalizer314.state

import android.content.Context
import com.bearinmind.equalizer314.autoeq.AutoEqFilter
import com.bearinmind.equalizer314.autoeq.AutoEqParser
import com.bearinmind.equalizer314.autoeq.apoTokenToFilterType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Shared preset-file import/export: native Equalizer314 .json, APO .txt, and the legacy EQ314 chain section. */
object PresetFileIo {
    /** Total filters in a preset JSON — CSE presets sum left + right + shared instead of the legacy `bands` copy. */
    fun filterCount(json: String?): Int {
        val obj = try { JSONObject(json ?: return 0) } catch (_: Exception) { return 0 }
        if (obj.optBoolean("channelSideEqEnabled", false) && obj.has("leftBands")) {
            return (obj.optJSONArray("leftBands")?.length() ?: 0) +
                (obj.optJSONArray("rightBands")?.length() ?: 0) +
                (obj.optJSONArray("sharedBands")?.length() ?: 0)
        }
        return obj.optJSONArray("bands")?.length() ?: 0
    }


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

    /** APO .txt for a preset (EQ only; the chain travels via .json), shared by both export buttons. */
    fun toApoText(obj: JSONObject): String {
        val sb = StringBuilder()
        // Locale.US: comma-decimal locales wrote "Q 0,71", which APO and our importer can't read.
        sb.append("Preamp: ${String.format(Locale.US, "%.1f", obj.optDouble("preamp", 0.0))} dB\n")
        fun appendFilters(bands: JSONArray, indexOffset: Int = 0) {
            for (i in 0 until bands.length()) {
                val b = bands.getJSONObject(i)
                // BP/NO/AP/LP/HP have no Gain; 6 dB shelves and 1st-order LP/HP have no Q.
                val apoType: String
                val hasGain: Boolean
                val hasQ: Boolean
                when (b.getString("filterType")) {
                    "BELL"         -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                    "LOW_SHELF"    -> { apoType = "LSC"; hasGain = true;  hasQ = true  }
                    "HIGH_SHELF"   -> { apoType = "HSC"; hasGain = true;  hasQ = true  }
                    "LOW_PASS"     -> { apoType = "LPQ"; hasGain = false; hasQ = true  }
                    "HIGH_PASS"    -> { apoType = "HPQ"; hasGain = false; hasQ = true  }
                    "LOW_SHELF_1"  -> { apoType = "LS 6dB"; hasGain = true; hasQ = false }
                    "HIGH_SHELF_1" -> { apoType = "HS 6dB"; hasGain = true; hasQ = false }
                    "LOW_PASS_1"   -> { apoType = "LP";  hasGain = false; hasQ = false }
                    "HIGH_PASS_1"  -> { apoType = "HP";  hasGain = false; hasQ = false }
                    "BAND_PASS"    -> { apoType = "BP";  hasGain = false; hasQ = true  }
                    "NOTCH"        -> { apoType = "NO";  hasGain = false; hasQ = true  }
                    "ALL_PASS"     -> { apoType = "AP";  hasGain = false; hasQ = true  }
                    else           -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                }
                // One string per line: release D8 (AGP 8.2) dropped Q appended to a per-line StringBuilder (#100, #114).
                val gainPart = if (hasGain) " Gain ${String.format(Locale.US, "%.1f", b.getDouble("gain"))} dB" else ""
                val qPart = if (hasQ) " Q ${String.format(Locale.US, "%.2f", b.getDouble("q"))}" else ""
                sb.append("Filter ${i + 1 + indexOffset}: ON $apoType Fc ${b.getDouble("frequency").toInt()} Hz$gainPart$qPart\n")
            }
        }
        val cseOn = obj.optBoolean("channelSideEqEnabled", false)
        if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
            val leftArr = obj.getJSONArray("leftBands")
            sb.append("Channel: L\n")
            appendFilters(leftArr)
            sb.append("Channel: R\n")
            appendFilters(obj.getJSONArray("rightBands"), indexOffset = leftArr.length())
        } else {
            appendFilters(obj.getJSONArray("bands"))
        }
        return sb.toString()
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
            json.put("preampLeft", (profile.preampLeftDb ?: profile.preampDb).toDouble())
            json.put("preampRight", (profile.preampRightDb ?: profile.preampDb).toDouble())
            json.put("leftBands", bandsOf(profile.leftFilters))
            json.put("rightBands", bandsOf(profile.rightFilters))
            if (profile.sharedFilters.isNotEmpty()) {
                json.put("sharedBands", bandsOf(profile.sharedFilters))
            }
            // Flat fallback = the left channel's full curve (shared + L-exclusive).
            json.put("bands", bandsOf(profile.sharedFilters + profile.leftFilters))
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
