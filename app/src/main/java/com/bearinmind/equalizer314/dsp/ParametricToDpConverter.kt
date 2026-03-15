package com.bearinmind.equalizer314.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Converts parametric EQ settings (with Q, filter types, bell curves)
 * into DynamicsProcessing-compatible band gains.
 *
 * Samples the composite frequency response of the parametric EQ at N
 * log-spaced frequencies, producing (cutoffFrequency, gain) pairs that
 * approximate the parametric curves using DynamicsProcessing's FFT engine.
 */
object ParametricToDpConverter {

    var numBands: Int = 128
        private set
    private const val MIN_FREQ = 10f
    private const val MAX_FREQ = 22000f

    private var _cutoffFrequencies: FloatArray? = null

    /** Log-spaced cutoff frequencies (upper edge of each band). Recomputed when numBands changes. */
    val cutoffFrequencies: FloatArray
        get() {
            var cached = _cutoffFrequencies
            if (cached == null || cached.size != numBands) {
                cached = computeCutoffs(numBands)
                _cutoffFrequencies = cached
            }
            return cached
        }

    fun setNumBands(count: Int) {
        numBands = count.coerceIn(128, 1024)
        _cutoffFrequencies = null // invalidate cache
    }

    private fun computeCutoffs(bandCount: Int): FloatArray {
        val cutoffs = FloatArray(bandCount)
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)
        for (i in 0 until bandCount) {
            val logFreq = logMin + (i + 1).toFloat() / bandCount * (logMax - logMin)
            cutoffs[i] = 10f.pow(logFreq)
        }
        return cutoffs
    }

    /** Center frequencies (geometric mean of each band's edges). */
    val centerFrequencies: FloatArray
        get() {
            val cutoffs = cutoffFrequencies
            return FloatArray(numBands) { i ->
                val lower = if (i == 0) MIN_FREQ else cutoffs[i - 1]
                sqrt(lower * cutoffs[i])
            }
        }

    /**
     * Sample the parametric EQ's composite frequency response at each band's
     * geometric center, returning the gain in dB for each band.
     */
    fun convert(eq: ParametricEqualizer): FloatArray {
        val centers = centerFrequencies
        return FloatArray(numBands) { i -> eq.getFrequencyResponse(centers[i]) }
    }
}
