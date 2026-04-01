package com.bearinmind.equalizer314.autoeq

object AutoEqParser {

    private val preampRegex = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRegex = Regex(
        """Filter\s+\d+:\s+ON\s+(PK|LSC|HSC|LPQ|HPQ)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+(-?[\d.]+)\s+dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): AutoEqProfile? {
        val lines = text.lines()
        var preampDb = 0f
        val filters = mutableListOf<AutoEqFilter>()

        for (line in lines) {
            val preampMatch = preampRegex.find(line)
            if (preampMatch != null) {
                preampDb = preampMatch.groupValues[1].toFloatOrNull() ?: 0f
                continue
            }

            val filterMatch = filterRegex.find(line)
            if (filterMatch != null) {
                val type = filterMatch.groupValues[1].uppercase()
                val freq = filterMatch.groupValues[2].toFloatOrNull() ?: continue
                val gain = filterMatch.groupValues[3].toFloatOrNull() ?: continue
                val q = filterMatch.groupValues[4].toFloatOrNull() ?: continue

                if (freq in 1f..100000f && gain in -30f..30f && q in 0.01f..20f) {
                    filters.add(AutoEqFilter(type, freq, gain, q))
                }
            }
        }

        return if (filters.isNotEmpty()) AutoEqProfile(preampDb, filters) else null
    }
}
