package com.bearinmind.equalizer314.audio

import kotlin.math.exp
import kotlin.math.ln

/**
 * Logarithmic frequency ↔ pixel mapping.
 * Maps 20 Hz – 20000 Hz to pixel positions on a log scale.
 */
object FrequencyMapper {

    private const val MIN_FREQ = 20f
    private const val MAX_FREQ = 20000f
    private val LOG_MIN = ln(MIN_FREQ)
    private val LOG_MAX = ln(MAX_FREQ)
    private val LOG_RANGE = LOG_MAX - LOG_MIN

    /** Frequency → normalized position (0..1) on log scale */
    fun frequencyToNorm(freq: Float): Float {
        if (freq <= MIN_FREQ) return 0f
        if (freq >= MAX_FREQ) return 1f
        return ((ln(freq) - LOG_MIN) / LOG_RANGE)
    }

    /** Frequency → pixel X position */
    fun frequencyToX(freq: Float, viewWidth: Float): Float {
        return frequencyToNorm(freq) * viewWidth
    }

    /** Pixel X position → frequency */
    fun xToFrequency(x: Float, viewWidth: Float): Float {
        val norm = (x / viewWidth).coerceIn(0f, 1f)
        return exp(LOG_MIN + norm * LOG_RANGE)
    }

    /** FFT bin index → frequency */
    fun binToFrequency(binIndex: Int, sampleRate: Int, fftSize: Int): Float {
        return binIndex.toFloat() * sampleRate.toFloat() / fftSize.toFloat()
    }

    /** Frequency → fractional FFT bin index */
    fun frequencyToBin(freq: Float, sampleRate: Int, fftSize: Int): Float {
        return freq * fftSize.toFloat() / sampleRate.toFloat()
    }
}
