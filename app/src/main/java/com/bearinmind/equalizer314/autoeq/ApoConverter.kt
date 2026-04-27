package com.bearinmind.equalizer314.autoeq

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts EQ export files from Wavelet and Poweramp into APO config-style
 * text that the rest of the app's importers already understand.
 *
 * Wavelet exports are already APO format (Preamp + Filter N: ON ... lines)
 * so they pass through with a tag note. Poweramp exports are JSON in one
 * of a few shapes:
 *   - Parametric ("PowerampEqualizer.parametric"): array of bands with
 *     freq / gain / q (and sometimes type) → emitted as PK / LSC / HSC.
 *   - Graphic (key/value pairs like "55hz=0.0;77hz=0.5;...") packed into
 *     "EqualizerSettings" or as a flat object → emitted as PK at the
 *     fixed graphic-EQ centers.
 *
 * The converter is permissive: it tries each known shape and emits the
 * first one that produces filters. Unknown shapes return null with a
 * descriptive [Result.error] string.
 */
object ApoConverter {

    sealed class Result {
        data class Ok(val apoText: String, val sourceLabel: String) : Result()
        data class Err(val message: String) : Result()
    }

    fun convert(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Err("File is empty")

        // Already APO? (Wavelet / Equalizer APO config.txt)
        if (looksLikeApo(trimmed)) {
            return Result.Ok(trimmed, "Wavelet / APO (passthrough)")
        }

        // Try JSON shapes
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val json = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                tryPowerampParametric(json)?.let { return Result.Ok(it, "Poweramp parametric EQ") }
                tryPowerampGraphic(json)?.let { return Result.Ok(it, "Poweramp graphic EQ") }
                tryWaveletPreset(json)?.let { return Result.Ok(it, "Wavelet preset (JSON)") }
                return Result.Err("JSON didn't match any known Wavelet / Poweramp shape")
            } catch (e: Exception) {
                return Result.Err("Malformed JSON: ${e.message}")
            }
        }

        // Try Poweramp's "key=value;..." graphic-EQ string blob
        tryPowerampSettingsString(trimmed)?.let { return Result.Ok(it, "Poweramp graphic EQ (settings string)") }

        return Result.Err("Unrecognised file format")
    }

    // ---- Heuristics ------------------------------------------------------

    private fun looksLikeApo(s: String): Boolean {
        val low = s.lowercase()
        return Regex("""(?im)^\s*preamp:""").containsMatchIn(s) ||
            Regex("""(?im)^\s*filter\s+\d+\s*:""").containsMatchIn(s) ||
            // Wavelet shares a "Filter N: ..." block with no Preamp; still APO.
            low.contains(" pk ") || low.contains(" lsc ") || low.contains(" hsc ")
    }

    // ---- Poweramp parametric --------------------------------------------

    private fun tryPowerampParametric(any: Any): String? {
        // Expected: { "ParametricEq": { "Bands": [{f, g, q, type}, ...] } }
        // or top-level array of such bands, or { "bands": [...] }.
        val bandsArr: JSONArray = when {
            any is JSONArray -> any
            any is JSONObject && any.has("ParametricEq") -> {
                val pe = any.optJSONObject("ParametricEq") ?: return null
                pe.optJSONArray("Bands") ?: pe.optJSONArray("bands") ?: return null
            }
            any is JSONObject && any.has("bands") -> any.optJSONArray("bands") ?: return null
            any is JSONObject && any.has("Bands") -> any.optJSONArray("Bands") ?: return null
            else -> return null
        }

        val sb = StringBuilder()
        sb.append("Preamp: 0.0 dB\n")
        var idx = 1
        for (i in 0 until bandsArr.length()) {
            val b = bandsArr.optJSONObject(i) ?: continue
            val freq = b.optDoubleAny("freq", "f", "frequency", "hz") ?: continue
            val gain = b.optDoubleAny("gain", "g", "db") ?: continue
            val q = b.optDoubleAny("q", "Q", "bandwidth") ?: 0.707
            val typeRaw = b.optString("type", b.optString("Type", "peaking"))
            val token = mapPowerampType(typeRaw)
            if (token == null) continue
            sb.append("Filter ").append(idx++).append(": ON ").append(token)
                .append(" Fc ").append(roundFreq(freq)).append(" Hz")
                .append(" Gain ").append(formatDb(gain)).append(" dB")
                .append(" Q ").append(formatQ(q)).append('\n')
        }
        return if (idx > 1) sb.toString() else null
    }

    private fun mapPowerampType(raw: String): String? = when (raw.lowercase().trim()) {
        "peaking", "peak", "pk", "bell" -> "PK"
        "lowshelf", "low_shelf", "low-shelf", "ls", "lsc" -> "LSC"
        "highshelf", "high_shelf", "high-shelf", "hs", "hsc" -> "HSC"
        "lowpass", "low_pass", "lp", "lpq" -> "LPQ"
        "highpass", "high_pass", "hp", "hpq" -> "HPQ"
        "bandpass", "band_pass", "bp" -> "BP"
        "notch", "no" -> "NO"
        "allpass", "all_pass", "ap" -> "AP"
        else -> "PK" // fall back to peaking — APO importer accepts it
    }

    // ---- Poweramp graphic (JSON or string) ------------------------------

    /** Match the canonical Poweramp graphic-EQ centers (10-band ISO):
     *  31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k. */
    private val graphicCenters = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private fun tryPowerampGraphic(any: Any): String? {
        if (any !is JSONObject) return null
        val src = any.optJSONObject("Equalizer") ?: any.optJSONObject("EqualizerSettings") ?: any
        val bands = mutableListOf<Pair<Float, Float>>()  // freq, gain
        var preamp = 0f
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = src.opt(k) ?: continue
            val gain = (v as? Number)?.toFloat() ?: v.toString().toFloatOrNull() ?: continue
            val freq = parseFreqKey(k)
            if (freq != null) bands.add(freq to gain)
            else if (k.equals("preamp", ignoreCase = true)) preamp = gain
        }
        if (bands.isEmpty()) return null
        bands.sortBy { it.first }
        return formatPeakingFilters(bands, preamp, q = 1.41f)
    }

    private fun tryPowerampSettingsString(s: String): String? {
        // Format: "preamp=0.000;55hz=0.250;77hz=0.000;..."
        if (!s.contains('=') || !s.contains(';')) return null
        val bands = mutableListOf<Pair<Float, Float>>()
        var preamp = 0f
        for (chunk in s.split(';')) {
            val parts = chunk.split('=', limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val gain = parts[1].trim().toFloatOrNull() ?: continue
            val freq = parseFreqKey(key)
            if (freq != null) bands.add(freq to gain)
            else if (key.equals("preamp", ignoreCase = true)) preamp = gain
        }
        if (bands.isEmpty()) return null
        bands.sortBy { it.first }
        return formatPeakingFilters(bands, preamp, q = 1.41f)
    }

    /** Parses keys like "55hz", "1khz", "16k", "1000Hz". Returns null when
     *  the key isn't a frequency. */
    private fun parseFreqKey(key: String): Float? {
        val k = key.lowercase().replace(" ", "").trim()
        val m = Regex("""^([0-9]*\.?[0-9]+)(k?)(hz)?$""").matchEntire(k) ?: return null
        val (num, kFlag, _) = m.destructured
        val v = num.toFloatOrNull() ?: return null
        return if (kFlag == "k") v * 1000f else v
    }

    // ---- Wavelet preset JSON --------------------------------------------

    private fun tryWaveletPreset(any: Any): String? {
        // Wavelet's preset JSON, when not just an APO string, looks like:
        //   { "name": "...", "preamp": -3.0, "filters": [{type, freq, gain, q}, ...] }
        // (The schema may vary by version; we accept the obvious keys.)
        if (any !is JSONObject) return null
        val arr = any.optJSONArray("filters")
            ?: any.optJSONArray("Filters")
            ?: any.optJSONArray("bands")
            ?: return null
        val sb = StringBuilder()
        val preamp = any.optDoubleAny("preamp", "Preamp", "preampDb") ?: 0.0
        sb.append("Preamp: ").append(formatDb(preamp)).append(" dB\n")
        var idx = 1
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val freq = b.optDoubleAny("frequency", "freq", "f", "hz") ?: continue
            val gain = b.optDoubleAny("gain", "g", "db") ?: continue
            val q = b.optDoubleAny("q", "Q") ?: 0.707
            val token = mapPowerampType(b.optString("type", "peaking"))
            sb.append("Filter ").append(idx++).append(": ON ").append(token)
                .append(" Fc ").append(roundFreq(freq)).append(" Hz")
                .append(" Gain ").append(formatDb(gain)).append(" dB")
                .append(" Q ").append(formatQ(q)).append('\n')
        }
        return if (idx > 1) sb.toString() else null
    }

    // ---- APO formatting helpers -----------------------------------------

    private fun formatPeakingFilters(
        bands: List<Pair<Float, Float>>,
        preampDb: Float,
        q: Float,
    ): String {
        val sb = StringBuilder()
        sb.append("Preamp: ").append(formatDb(preampDb.toDouble())).append(" dB\n")
        for ((i, fg) in bands.withIndex()) {
            val (freq, gain) = fg
            sb.append("Filter ").append(i + 1).append(": ON PK")
                .append(" Fc ").append(roundFreq(freq.toDouble())).append(" Hz")
                .append(" Gain ").append(formatDb(gain.toDouble())).append(" dB")
                .append(" Q ").append(formatQ(q.toDouble())).append('\n')
        }
        return sb.toString()
    }

    private fun roundFreq(f: Double): String {
        val v = f.toFloat()
        return when {
            v < 100f -> String.format(java.util.Locale.US, "%.1f", v)
            v < 10000f -> String.format(java.util.Locale.US, "%d", v.toInt())
            else -> String.format(java.util.Locale.US, "%d", v.toInt())
        }
    }

    private fun formatDb(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun formatQ(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    // ---- Loose JSON access ----------------------------------------------

    private fun JSONObject.optDoubleAny(vararg keys: String): Double? {
        for (k in keys) {
            if (has(k)) {
                val v = opt(k)
                if (v is Number) return v.toDouble()
                if (v is String) v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
}
