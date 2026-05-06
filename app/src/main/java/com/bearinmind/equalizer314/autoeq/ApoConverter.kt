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

        // AutoEQ "GraphicEQ" single-line format (used by Wavelet's
        // headphone-correction exports + AutoEQ's downloads):
        //   "GraphicEQ: 20 -5.5; 21 -5.5; 22 -5.5; ..."
        tryAutoEqGraphicEq(trimmed)?.let { return Result.Ok(it, "AutoEQ GraphicEQ") }

        // Already APO? (Wavelet / Equalizer APO config.txt)
        if (looksLikeApo(trimmed)) {
            return Result.Ok(trimmed, "Wavelet / APO (passthrough)")
        }

        // Try JSON shapes
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val json = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                tryWrappedPresetArray(json)?.let { return Result.Ok(it, "EQ preset (wrapped JSON)") }
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

    // ---- AutoEQ GraphicEQ -----------------------------------------------

    /**
     * Parses AutoEQ's "GraphicEQ" single-line format.
     *
     *   "GraphicEQ: 20 -5.5; 21 -5.5; 22 -5.5; 23 -5.5; ..."
     *
     * AutoEQ outputs ~120 points at roughly 1/24-octave spacing. Emitting
     * one PK per point would give 120+ filters, which is unusable in a
     * normal parametric EQ. Instead we feed the curve through [EqFitter]
     * to fit it to a small number of well-placed PK / shelf filters
     * (default 10) — same algorithm AutoEQ itself uses to generate
     * parametric presets from frequency-response measurements.
     */
    private fun tryAutoEqGraphicEq(s: String): String? {
        if (!s.lowercase().startsWith("graphiceq")) return null
        // Strip the "GraphicEQ:" or "GraphicEQ " prefix.
        val payload = s.substringAfter(':', "").ifEmpty { s.substringAfter(' ', "") }.trim()
        if (payload.isEmpty()) return null

        val rawFreqs = mutableListOf<Float>()
        val rawLevels = mutableListOf<Float>()
        for (chunk in payload.split(';')) {
            val pair = chunk.trim().split(Regex("\\s+"), limit = 2)
            if (pair.size != 2) continue
            val f = pair[0].toFloatOrNull() ?: continue
            val g = pair[1].toFloatOrNull() ?: continue
            if (f <= 0f) continue
            rawFreqs.add(f)
            rawLevels.add(g)
        }
        if (rawFreqs.size < 10) return null

        // Sort by frequency (some files come in order, but be safe).
        val indices = rawFreqs.indices.sortedBy { rawFreqs[it] }
        val target = FreqResponse(
            FloatArray(indices.size) { rawFreqs[indices[it]] },
            FloatArray(indices.size) { rawLevels[indices[it]] },
        )
        // Treat the GraphicEQ curve as the TARGET and a flat 0 dB
        // line as the measurement. EqFitter then computes correction
        // filters whose composite response matches the GraphicEQ curve.
        val flatMeasurement = FreqResponse(
            target.frequencies,
            FloatArray(target.frequencies.size) { 0f },
        )
        val profile = try {
            EqFitter.computeCorrection(flatMeasurement, target, numBands = 10)
        } catch (e: Exception) {
            // If the fitter blows up for any reason, fall back to a
            // dense PK emit (still better than no import at all).
            val bands = indices.map { rawFreqs[it] to rawLevels[it] }
            return formatPeakingFilters(bands, preampDb = 0f, q = 6f)
        }

        // Render the fitted profile as APO text.
        val sb = StringBuilder()
        sb.append("Preamp: ").append(formatDb(profile.preampDb.toDouble())).append(" dB\n")
        for ((idx, f) in profile.filters.withIndex()) {
            val token = when (f.filterType.uppercase()) {
                "LSC", "LOWSHELF", "LS" -> "LSC"
                "HSC", "HIGHSHELF", "HS" -> "HSC"
                else -> "PK"
            }
            sb.append("Filter ").append(idx + 1).append(": ON ").append(token)
                .append(" Fc ").append(roundFreq(f.frequency.toDouble())).append(" Hz")
                .append(" Gain ").append(formatDb(f.gain.toDouble())).append(" dB")
                .append(" Q ").append(formatQ(f.q.toDouble())).append('\n')
        }
        return sb.toString()
    }

    // ---- Heuristics ------------------------------------------------------

    private fun looksLikeApo(s: String): Boolean {
        val low = s.lowercase()
        return Regex("""(?im)^\s*preamp:""").containsMatchIn(s) ||
            Regex("""(?im)^\s*filter\s+\d+\s*:""").containsMatchIn(s) ||
            // Wavelet shares a "Filter N: ..." block with no Preamp; still APO.
            low.contains(" pk ") || low.contains(" lsc ") || low.contains(" hsc ")
    }

    // ---- Wrapped preset array (e.g. third-party EQ exports) -------------

    /**
     * Handles JSON of the form `[{"name": ..., "preamp": ..., "bands": [...]}]`
     * — a top-level array whose first element is a preset wrapper. Each
     * band uses numeric `type` codes:
     *   0 = low-shelf (LSC), 1 = high-shelf (HSC), 2 = peaking (PK).
     * `q == 0` is treated as "no Q specified" → fall back to 1.41 (the
     * graphic-EQ default), since some exporters zero the field for
     * graphic-style bands.
     */
    private fun tryWrappedPresetArray(any: Any): String? {
        val arr = (any as? JSONArray) ?: return null
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        val bands = first.optJSONArray("bands") ?: first.optJSONArray("Bands") ?: return null
        if (bands.length() == 0) return null
        // Sniff: at least one band must look like a band object (have a
        // frequency field). Otherwise this is some other shape that
        // happens to have a "bands" key.
        val firstBand = bands.optJSONObject(0) ?: return null
        if (firstBand.optDoubleAny("frequency", "freq", "f", "hz") == null) return null

        val sb = StringBuilder()
        val preamp = first.optDoubleAny("preamp", "Preamp", "preampDb") ?: 0.0
        sb.append("Preamp: ").append(formatDb(preamp)).append(" dB\n")
        var idx = 1
        for (i in 0 until bands.length()) {
            val b = bands.optJSONObject(i) ?: continue
            val freq = b.optDoubleAny("frequency", "freq", "f", "hz") ?: continue
            val gain = b.optDoubleAny("gain", "g", "db") ?: continue
            val rawQ = b.optDoubleAny("q", "Q") ?: 0.0
            val q = if (rawQ <= 0.0) 1.41 else rawQ
            val token = mapBandTypeCode(b.opt("type"))
            sb.append("Filter ").append(idx++).append(": ON ").append(token)
                .append(" Fc ").append(roundFreq(freq)).append(" Hz")
                .append(" Gain ").append(formatDb(gain)).append(" dB")
                .append(" Q ").append(formatQ(q)).append('\n')
        }
        return if (idx > 1) sb.toString() else null
    }

    /** Numeric or string type → APO filter token. Numeric codes follow
     *  the convention used by the wrapped-preset format above. */
    private fun mapBandTypeCode(typeRaw: Any?): String = when (typeRaw) {
        is Number -> when (typeRaw.toInt()) {
            0 -> "LSC"
            1 -> "HSC"
            2 -> "PK"
            else -> "PK"
        }
        is String -> mapPowerampType(typeRaw) ?: "PK"
        else -> "PK"
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
