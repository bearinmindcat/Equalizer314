package com.bearinmind.equalizer314.autoeq

import kotlin.math.ln
import kotlin.math.pow

data class FreqResponse(
    val frequencies: FloatArray,
    val levels: FloatArray
)

object FreqResponseParser {

    fun parse(text: String): FreqResponse? {
        val freqs = mutableListOf<Float>()
        val levels = mutableListOf<Float>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Split by comma, tab, or whitespace
            val parts = trimmed.split(",", "\t", " ").filter { it.isNotBlank() }
            if (parts.size < 2) continue

            val freq = parts[0].toFloatOrNull() ?: continue
            val level = parts[1].toFloatOrNull() ?: continue

            if (freq < 1f || freq > 100000f) continue
            if (level < -100f || level > 100f) continue

            freqs.add(freq)
            levels.add(level)
        }

        return if (freqs.size >= 10) {
            FreqResponse(freqs.toFloatArray(), levels.toFloatArray())
        } else null
    }

    /** Linear interpolation to resample FR at target frequencies */
    fun interpolateAt(fr: FreqResponse, targetFreqs: FloatArray): FloatArray {
        val result = FloatArray(targetFreqs.size)
        for (i in targetFreqs.indices) {
            val f = targetFreqs[i]
            // Find bracketing indices
            var lo = 0
            var hi = fr.frequencies.size - 1
            if (f <= fr.frequencies[lo]) { result[i] = fr.levels[lo]; continue }
            if (f >= fr.frequencies[hi]) { result[i] = fr.levels[hi]; continue }

            // Binary search
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (fr.frequencies[mid] <= f) lo = mid else hi = mid
            }

            // Linear interpolation in log-frequency domain
            val logF = ln(f.toDouble())
            val logLo = ln(fr.frequencies[lo].toDouble())
            val logHi = ln(fr.frequencies[hi].toDouble())
            val t = ((logF - logLo) / (logHi - logLo)).coerceIn(0.0, 1.0)
            result[i] = (fr.levels[lo] + t * (fr.levels[hi] - fr.levels[lo])).toFloat()
        }
        return result
    }

    /** Generate n log-spaced frequencies from 20Hz to 20kHz */
    fun logSpace(n: Int, minHz: Float = 20f, maxHz: Float = 20000f): FloatArray {
        val logMin = ln(minHz.toDouble())
        val logMax = ln(maxHz.toDouble())
        return FloatArray(n) { i ->
            kotlin.math.exp(logMin + i * (logMax - logMin) / (n - 1)).toFloat()
        }
    }
}
