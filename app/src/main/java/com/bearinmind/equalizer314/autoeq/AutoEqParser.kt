package com.bearinmind.equalizer314.autoeq

import com.bearinmind.equalizer314.dsp.BiquadFilter

/** Equalizer APO config parser: every APO filter token incl. slope-qualified shelves and the `Channel:` directive; emits normalized tokens (PK LSC HSC LS HS LPQ HPQ LP HP BP NO AP). */
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

    // `Channel: L` / `Channel: L R C` etc. — everything after the colon is tokenized below.
    private val channelRegex = Regex("""^\s*Channel:\s*(.+)$""", RegexOption.IGNORE_CASE)

    // "Filter N: ON" prefix + the rest of the line for per-field matching; OFF lines are skipped.
    private val filterLineRegex = Regex("""Filter\s+\d+:\s+ON\s+(.*)""", RegexOption.IGNORE_CASE)

    // Type token + optional slope qualifier ("6 dB" / "12 dB", LS/HS shelves only).
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
        var preampLeft: Float? = null
        var preampRight: Float? = null
        val allFilters = mutableListOf<AutoEqFilter>()
        val leftFilters = mutableListOf<AutoEqFilter>()
        val rightFilters = mutableListOf<AutoEqFilter>()
        val sharedFilters = mutableListOf<AutoEqFilter>()
        var perChannel = false

        // Channel scope for subsequent filters; default ALL until a `Channel:` directive.
        var scopeLeft = true
        var scopeRight = true

        for (line in lines) {
            preampRegex.find(line)?.let {
                val v = it.groupValues[1].toFloatOrNull() ?: 0f
                // Scope-aware: a Preamp under `Channel: L`/`R` belongs to that side only.
                when {
                    scopeLeft && scopeRight -> preampDb = v
                    scopeLeft -> preampLeft = v
                    scopeRight -> preampRight = v
                }
                return@let
            }

            channelRegex.find(line)?.let { m ->
                val tokens = m.groupValues[1]
                    .split(Regex("""[\s,]+"""))
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() }
                // Only L/R matter; "ALL" selects both, other channels (C, SL, SR, LFE…) are ignored.
                val scAll = "ALL" in tokens
                val scL = scAll || "L" in tokens
                val scR = scAll || "R" in tokens
                if (scL || scR) {
                    scopeLeft = scL
                    scopeRight = scR
                    perChannel = perChannel || (scL xor scR)
                } else {
                    // Non-stereo scope: nothing applies to L or R until the next `Channel:` line.
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

            // APO's plain LS/HS are the standard (Q-capable) shelves; only an explicit "6 dB" slope is the 1st-order shelf.
            val type = when (rawType) {
                "LS" -> if (slope == "6") "LS" else "LSC"
                "HS" -> if (slope == "6") "HS" else "HSC"
                else -> rawType
            }

            val freq = fcRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val gain = gainRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val q = qRegex.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.707f

            if (freq in 1f..100000f && gain in -30f..30f && q in 0.01f..20f) {
                val filter = AutoEqFilter(type, freq, gain, q)
                allFilters += filter
                when {
                    scopeLeft && scopeRight -> sharedFilters += filter
                    scopeLeft -> leftFilters += filter
                    scopeRight -> rightFilters += filter
                }
            }
        }

        if (allFilters.isEmpty()) return null
        // Without per-channel scopes the L and R buckets equal the flat list.
        return if (perChannel) {
            AutoEqProfile(
                preampDb = preampDb,
                filters = allFilters,
                leftFilters = leftFilters.toList(),
                rightFilters = rightFilters.toList(),
                perChannel = true,
                sharedFilters = sharedFilters.toList(),
                preampLeftDb = preampLeft,
                preampRightDb = preampRight,
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
