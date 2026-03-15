package com.bearinmind.equalizer314.dsp

import kotlin.math.pow
import kotlin.math.tanh

/**
 * Parametric Equalizer - Custom DSP implementation
 * Allows full control over frequency, gain, and filter type for each band
 */
class ParametricEqualizer(private val sampleRate: Int = 44100) {

    data class EqualizerBand(
        var frequency: Float,      // Hz (20-20000)
        var gain: Float,            // dB (-20 to +20)
        var filterType: BiquadFilter.FilterType,
        var q: Double = 0.707,      // Q factor (0.1 to 10.0)
        var enabled: Boolean = true
    )

    private val bands = mutableListOf<EqualizerBand>()
    private val filters = mutableListOf<BiquadFilter>()

    var isEnabled = false
        set(value) {
            field = value
            if (!value) {
                filters.forEach { it.reset() }
            }
        }

    init {
        addDefaultBands()
    }

    private fun addDefaultBands() {
        // Start with 4 bands at every-other position of the 8-point log grid
        // This gives even visual spacing across the graph (10–22000 Hz)
        val allFreqs = logSpacedFrequencies(8)
        val initialIndices = listOf(0, 2, 4, 6) // positions 1, 3, 5, 7
        for (idx in initialIndices) {
            addBand(allFreqs[idx], 0f, BiquadFilter.FilterType.BELL)
        }
    }

    companion object {
        /** Compute n log-spaced frequencies across 10–22000 Hz */
        fun logSpacedFrequencies(n: Int): FloatArray {
            val logMin = kotlin.math.log10(10f)
            val logMax = kotlin.math.log10(22000f)
            return FloatArray(n) { i -> 10f.pow(logMin + i * (logMax - logMin) / (n - 1)) }
        }
    }

    fun clearBands() {
        bands.clear()
        filters.clear()
    }

    fun addBand(frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = 0.707) {
        val band = EqualizerBand(frequency, gain, filterType, q, true)
        bands.add(band)

        val filter = BiquadFilter(frequency, gain, filterType, sampleRate, q).apply {
            useVicanekMethod = true
        }
        filters.add(filter)
    }

    fun insertBand(index: Int, frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = 0.707) {
        val band = EqualizerBand(frequency, gain, filterType, q, true)
        bands.add(index, band)

        val filter = BiquadFilter(frequency, gain, filterType, sampleRate, q).apply {
            useVicanekMethod = true
        }
        filters.add(index, filter)
    }

    fun removeBand(index: Int) {
        if (index in bands.indices) {
            bands.removeAt(index)
            filters.removeAt(index)
        }
    }

    fun updateBand(index: Int, frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = bands.getOrNull(index)?.q ?: 0.707) {
        if (index in bands.indices) {
            bands[index].frequency = frequency
            bands[index].gain = gain
            bands[index].filterType = filterType
            bands[index].q = q

            filters[index].updateParameters(frequency, gain, filterType, q)
        }
    }

    fun setBandEnabled(index: Int, enabled: Boolean) {
        if (index in bands.indices) {
            bands[index].enabled = enabled
        }
    }

    fun getBand(index: Int): EqualizerBand? = bands.getOrNull(index)

    fun getBandCount(): Int = bands.size

    fun getAllBands(): List<EqualizerBand> = bands.toList()

    /**
     * Process stereo audio buffer
     * Input/output format: interleaved stereo (L, R, L, R, ...)
     */
    fun process(buffer: FloatArray) {
        if (!isEnabled) return

        var i = 0
        while (i < buffer.size - 1) {
            for (j in filters.indices) {
                if (bands[j].enabled) {
                    filters[j].processStereoInPlace(buffer, i)
                }
            }

            buffer[i] = tanh(buffer[i].toDouble()).toFloat()
            buffer[i + 1] = tanh(buffer[i + 1].toDouble()).toFloat()

            i += 2
        }
    }

    fun reset() {
        filters.forEach { it.reset() }
    }

    fun getFrequencyResponse(frequency: Float): Float {
        var totalMagnitude = 1f

        for (i in filters.indices) {
            if (bands[i].enabled) {
                val magnitude = filters[i].getFrequencyResponse(frequency)
                totalMagnitude *= magnitude
            }
        }

        return 20f * kotlin.math.log10(totalMagnitude.coerceAtLeast(0.0001f))
    }

    /**
     * Returns the effective frequency response after tanh saturation,
     * assuming a 0 dBFS reference input. Normalized so flat EQ = 0 dB.
     * Shows how much tanh compresses boosts at full volume.
     */
    fun getFrequencyResponseWithSaturation(frequency: Float): Float {
        var totalMagnitude = 1f

        for (i in filters.indices) {
            if (bands[i].enabled) {
                totalMagnitude *= filters[i].getFrequencyResponse(frequency)
            }
        }

        val tanhRef = tanh(1.0) // baseline: tanh applied to flat signal
        val saturated = tanh(totalMagnitude.toDouble()) / tanhRef
        return 20f * kotlin.math.log10(saturated.coerceAtLeast(0.0001).toFloat())
    }

    fun loadPreset(presetName: String) {
        when (presetName) {
            "Flat" -> {
                bands.forEachIndexed { index, _ ->
                    updateBand(index, bands[index].frequency, 0f, bands[index].filterType, bands[index].q)
                }
            }
            "Bass Boost" -> {
                bands.forEachIndexed { index, _ ->
                    // Boost low bands, leave rest flat
                    val ratio = 1f - (index.toFloat() / (bands.size - 1).coerceAtLeast(1))
                    val gain = (ratio * 8f).coerceAtLeast(0f)
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
            "Treble Boost" -> {
                bands.forEachIndexed { index, _ ->
                    val ratio = index.toFloat() / (bands.size - 1).coerceAtLeast(1)
                    val gain = (ratio * 8f).coerceAtLeast(0f)
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
            "Vocal Enhance" -> {
                bands.forEachIndexed { index, _ ->
                    // Mid-focused boost
                    val center = (bands.size - 1) / 2f
                    val dist = kotlin.math.abs(index - center) / center.coerceAtLeast(1f)
                    val gain = (1f - dist) * 4f - 1f
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
        }
    }
}
