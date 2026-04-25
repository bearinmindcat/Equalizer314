package com.bearinmind.equalizer314.autoeq

import com.bearinmind.equalizer314.dsp.BiquadFilter

/**
 * Parser for Equalizer APO "config.txt" style files. Accepts every filter
 * type APO emits, including variants that omit `Gain` or `Q` and the
 * `LS 6 dB` / `LS 12 dB` slope-qualified shelf forms that EasyEffects /
 * Owliophile export by default. Also honors APO's `Channel:` directive so
 * per-channel presets round-trip into the app's Channel Side EQ model.
 *
 * Output `filterType` strings are the *normalized* APO type tokens that
 * downstream code maps to BiquadFilter.FilterType values:
 *   PK  LSC  HSC  LS  HS  LPQ  HPQ  LP  HP  BP  NO  AP
 */
fun apoTokenToFilterType(token: String): BiquadFilter.FilterType =
    when (token) {
        "PK"  -> BiquadFilter.FilterType.BELL
        "LSC" -> BiquadFilter.FilterType.LOW_SHELF
        "HSC" -> BiquadFilter.FilterType.HIGH_SHELF
        "LS"  -> BiquadFilter.FilterType.LOW_SHELF_1
        "HS"  -> BiquadFilter.FilterType.HIGH_SHELF_1
        "LPQ" -> BiquadFilter.FilterType.LOW_PASS
        "HPQ" -> BiquadFilter.FilterType.HIGH_PASS
        "LP"  -> BiquadFilter.FilterType.LOW_PASS_1
        "HP"  -> BiquadFilter.FilterType.HIGH_PASS_1
        "BP"  -> BiquadFilter.FilterType.BAND_PASS
        "NO"  -> BiquadFilter.FilterType.NOTCH
        "AP"  -> BiquadFilter.FilterType.ALL_PASS
        else  -> BiquadFilter.FilterType.BELL
    }

object AutoEqParser {

    private val preampRegex = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

    // `Channel: L` / `Channel: R` / `Channel: L R` / `Channel: L R C` etc.
    // The capture group grabs everything after the colon; we tokenize by
    // whitespace below.
    private val channelRegex = Regex("""^\s*Channel:\s*(.+)$""", RegexOption.IGNORE_CASE)

    // Match the "Filter N: ON" prefix and capture the rest of the line for
    // per-field sub-matching. Lines with `OFF` are deliberately skipped.
    private val filterLineRegex = Regex("""Filter\s+\d+:\s+ON\s+(.*)""", RegexOption.IGNORE_CASE)

    // Type token + optional slope qualifier ("6 dB" / "12 dB") that APO only
    // attaches to LS / HS shelves.
    private val typeRegex = Regex(
        """^(PK|LSC|HSC|LS|HS|LPQ|HPQ|LP|HP|BP|NO|AP)(?:\s+(6|12)\s*dB)?""",
        RegexOption.IGNORE_CASE
    )
    private val fcRegex = Regex("""Fc\s+([\d.]+)\s*Hz""", RegexOption.IGNORE_CASE)
    private val gainRegex = Regex("""Gain\s+(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val qRegex = Regex("""Q\s+([\d.]+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): AutoEqProfile? {
        val lines = text.lines()
        var preampDb = 0f
        val allFilters = mutableListOf<AutoEqFilter>()
        val leftFilters = mutableListOf<AutoEqFilter>()
        val rightFilters = mutableListOf<AutoEqFilter>()
        var perChannel = false

        // Scope: which channels subsequent filters apply to. Default is ALL
        // (pre-directive filters apply to every channel).
        var scopeLeft = true
        var scopeRight = true

        for (line in lines) {
            preampRegex.find(line)?.let {
                preampDb = it.groupValues[1].toFloatOrNull() ?: 0f
                return@let
            }

            channelRegex.find(line)?.let { m ->
                val tokens = m.groupValues[1]
                    .split(Regex("""[\s,]+"""))
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() }
                // Stereo channels we care about. Filters scoped to other
                // channels (C, SL, SR, LFE, etc.) are ignored.
                val scL = "L" in tokens
                val scR = "R" in tokens
                if (scL || scR) {
                    scopeLeft = scL
                    scopeRight = scR
                    perChannel = perChannel || (scL xor scR)
                } else {
                    // Non-stereo scope — subsequent filters won't apply to
                    // either L or R until a future `Channel: L` / `Channel: R`.
                    scopeLeft = false
                    scopeRight = false
                }
                return@let
            }

            val lineMatch = filterLineRegex.find(line) ?: continue
            val rest = lineMatch.groupValues[1]

            val typeMatch = typeRegex.find(rest) ?: continue
            val rawType = typeMatch.groupValues[1].uppercase()
            val slope = typeMatch.groupValues[2]

            val type = when (rawType) {
                "LS" -> if (slope == "12") "LSC" else "LS"
                "HS" -> if (slope == "12") "HSC" else "HS"
                else -> rawType
            }

            val freq = fcRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val gain = gainRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val q = qRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.707f

            if (freq in 1f..100000f && gain in -30f..30f && q in 0.01f..20f) {
                val filter = AutoEqFilter(type, freq, gain, q)
                allFilters += filter
                if (scopeLeft) leftFilters += filter
                if (scopeRight) rightFilters += filter
            }
        }

        if (allFilters.isEmpty()) return null
        // When no Channel: directive was seen (or only stereo `L R` scopes),
        // L and R buckets equal the flat list; the caller can just use
        // `filters` and ignore the split.
        return if (perChannel) {
            AutoEqProfile(
                preampDb = preampDb,
                filters = allFilters,
                leftFilters = leftFilters.toList(),
                rightFilters = rightFilters.toList(),
                perChannel = true,
            )
        } else {
            AutoEqProfile(
                preampDb = preampDb,
                filters = allFilters,
                leftFilters = allFilters,
                rightFilters = allFilters,
                perChannel = false,
            )
        }
    }
}
