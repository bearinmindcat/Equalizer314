package com.bearinmind.equalizer314.autoeq

/**
 * Parser for Equalizer APO "config.txt" style lines. Accepts every filter
 * type APO emits, including variants that omit `Gain` or `Q` and the
 * `LS 6 dB` / `LS 12 dB` slope-qualified shelf forms that EasyEffects /
 * Owliophile export by default.
 *
 * Output `filterType` strings are the *normalized* APO type tokens that
 * downstream code maps to BiquadFilter.FilterType values:
 *   PK  LSC  HSC  LS  HS  LPQ  HPQ  LP  HP  BP  NO  AP
 *
 * "LS 12 dB" is normalised to LSC (same 2nd-order shelf), "LS 6 dB" to LS
 * (1st-order shelf); same for HS.
 */
object AutoEqParser {

    private val preampRegex = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

    // Match the "Filter N: ON" prefix and capture the rest of the line for
    // per-field sub-matching. Keeps the regex readable and handles the fact
    // that different APO filter types have different fields.
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
        val filters = mutableListOf<AutoEqFilter>()

        for (line in lines) {
            preampRegex.find(line)?.let {
                preampDb = it.groupValues[1].toFloatOrNull() ?: 0f
                return@let
            }

            val lineMatch = filterLineRegex.find(line) ?: continue
            val rest = lineMatch.groupValues[1]

            val typeMatch = typeRegex.find(rest) ?: continue
            val rawType = typeMatch.groupValues[1].uppercase()
            val slope = typeMatch.groupValues[2]   // "" / "6" / "12"

            // Collapse "LS 12 dB" → LSC, "LS 6 dB" stays LS.
            val type = when (rawType) {
                "LS" -> if (slope == "12") "LSC" else "LS"
                "HS" -> if (slope == "12") "HSC" else "HS"
                else -> rawType
            }

            val freq = fcRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            // Gain is optional (LP / HP / BP / NO / AP have no Gain field).
            val gain = gainRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            // Q is optional (LS / HS / LP / HP are 1st-order, no Q); default
            // to Butterworth 0.707 so downstream code always has something.
            val q = qRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.707f

            if (freq in 1f..100000f && gain in -30f..30f && q in 0.01f..20f) {
                filters.add(AutoEqFilter(type, freq, gain, q))
            }
        }

        return if (filters.isNotEmpty()) AutoEqProfile(preampDb, filters) else null
    }
}
