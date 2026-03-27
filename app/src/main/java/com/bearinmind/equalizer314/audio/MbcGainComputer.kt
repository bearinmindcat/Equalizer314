package com.bearinmind.equalizer314.audio

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Computes per-band gain reduction for MBC visualization.
 *
 * KEY PRINCIPLE: Gain reduction depends on the ACTUAL INPUT LEVEL each frame,
 * not just on the compressor settings. This is what makes compression different
 * from a simple gain knob.
 *
 * Usage in your renderer:
 *   1. Each frame, pass the current FFT spectrum to computeAllBandGains()
 *   2. Use getGainAtFrequency() when drawing each pixel of the output spectrum
 *   3. Output pixel dB = input pixel dB + gainAtFrequency
 */
class MbcGainComputer(private val numBands: Int) {

    // Per-band computed gains (preGain + GR + postGain), updated each frame
    private val bandTotalGains = FloatArray(numBands)

    // Smoothed GR for animation (asymmetric attack/release)
    private val smoothedGR = FloatArray(numBands)

    // Smoothed compressor-only GR (excludes expander/gate) for trace display
    private val smoothedCompressorGR = FloatArray(numBands)

    // Crossover frequencies between bands (size = numBands - 1)
    private var crossoverFreqs = FloatArray(0)

    data class BandSettings(
        val preGain: Float,            // dB
        val postGain: Float,           // dB
        val threshold: Float,          // dB
        val ratio: Float,              // e.g. 4.0 for 4:1
        val kneeWidth: Float,          // dB
        val noiseGateThreshold: Float, // dB
        val expanderRatio: Float,      // e.g. 2.0
        val lowCutoff: Float,          // Hz (20 for first band)
        val highCutoff: Float          // Hz (20000 for last band)
    )

    /**
     * The compressor transfer function.
     *
     * Given an input level in dB, returns the gain reduction in dB.
     * Return value is ALWAYS <= 0 for compression (ratio > 1).
     * Return value is 0 when input is below threshold (no compression happening).
     *
     * This is the industry-standard soft-knee formula from Giannoulis et al. (JAES 2012).
     */
    fun computeGainReduction(inputDb: Float, threshold: Float, ratio: Float, kneeWidth: Float): Float {
        if (ratio <= 1f) return 0f  // ratio of 1:1 = no compression

        val slope = 1f / ratio - 1f  // always negative for compression

        if (kneeWidth <= 0f) {
            // Hard knee
            return if (inputDb <= threshold) 0f
            else slope * (inputDb - threshold)
        }

        // Soft knee: 3 regions
        val halfKnee = kneeWidth / 2f

        return when {
            inputDb < threshold - halfKnee -> {
                // Below knee: no compression at all
                0f
            }
            inputDb > threshold + halfKnee -> {
                // Above knee: full compression
                slope * (inputDb - threshold)
            }
            else -> {
                // Inside knee: smooth quadratic transition
                val x = inputDb - threshold + halfKnee
                slope * x * x / (2f * kneeWidth)
            }
        }
    }

    /**
     * Compute the expander/gate gain reduction for levels below the noise gate threshold.
     *
     * Returns <= 0 (attenuation). When expanderRatio = 1, returns 0 (no gating).
     * Higher expanderRatio = more aggressive gating.
     */
    fun computeExpanderGR(inputDb: Float, noiseGateThreshold: Float, expanderRatio: Float): Float {
        if (expanderRatio <= 1f || inputDb >= noiseGateThreshold) return 0f
        // Below gate threshold: attenuate based on how far below
        val belowBy = noiseGateThreshold - inputDb  // positive value
        return -(expanderRatio - 1f) * belowBy       // negative = attenuation
    }

    /**
     * Measure the RMS level of a frequency band from the FFT spectrum.
     *
     * @param spectrumDb  Per-bin dB values (your smoothed spectrum data)
     * @param sampleRate  Audio sample rate (e.g. 48000)
     * @param fftSize     Total FFT size (e.g. 1024 for 512 bins)
     * @param lowCutoff   Band's low crossover frequency in Hz
     * @param highCutoff  Band's high crossover frequency in Hz
     * @return RMS level in dB
     */
    fun measureBandLevel(
        spectrumDb: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        lowCutoff: Float,
        highCutoff: Float
    ): Float {
        val binWidth = sampleRate.toFloat() / fftSize
        val lowBin = (lowCutoff / binWidth).toInt().coerceIn(1, spectrumDb.size - 1)
        val highBin = (highCutoff / binWidth).toInt().coerceIn(lowBin, spectrumDb.size - 1)

        if (lowBin >= highBin) return -96f

        // Convert dB back to linear power, average, convert back to dB
        var sumPower = 0.0
        var count = 0
        for (bin in lowBin..highBin) {
            sumPower += 10.0.pow(spectrumDb[bin].toDouble() / 10.0)
            count++
        }

        return if (count > 0 && sumPower > 1e-12) {
            (10.0 * log10(sumPower / count)).toFloat()
        } else {
            -96f
        }
    }

    /**
     * Main entry point: compute gain for all bands from current FFT data.
     * Call this EVERY FRAME in your renderer.
     *
     * @param spectrumDb     Current per-bin dB values from the FFT
     * @param sampleRate     Audio sample rate
     * @param fftSize        Total FFT size
     * @param bandSettings   Array of settings for each MBC band
     */
    fun computeAllBandGains(
        spectrumDb: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        bandSettings: Array<BandSettings>
    ) {
        // Store crossover frequencies for use in getGainAtFrequency()
        if (crossoverFreqs.size != numBands - 1) {
            crossoverFreqs = FloatArray(numBands - 1)
        }
        for (i in 0 until numBands - 1) {
            crossoverFreqs[i] = bandSettings[i].highCutoff
        }

        for (i in 0 until numBands.coerceAtMost(bandSettings.size)) {
            val s = bandSettings[i]

            // Step 1: Measure band level from actual FFT data
            val bandLevel = measureBandLevel(spectrumDb, sampleRate, fftSize, s.lowCutoff, s.highCutoff)

            // Step 2: PreGain is applied before the compressor sees the signal
            val levelAfterPreGain = bandLevel + s.preGain

            // Step 3: Compute compressor GR from the ACTUAL MEASURED LEVEL
            val compressorGR = computeGainReduction(levelAfterPreGain, s.threshold, s.ratio, s.kneeWidth)

            // Step 4: Compute expander/gate GR
            val expanderGR = computeExpanderGR(levelAfterPreGain, s.noiseGateThreshold, s.expanderRatio)

            // Step 5: Total GR is the sum of compressor and expander
            val totalGR = compressorGR + expanderGR

            // Step 6: Smooth GR for animation (asymmetric: fast attack, slow release)
            val alpha = if (totalGR < smoothedGR[i]) {
                0.35f   // GR increasing (more negative) → fast attack
            } else {
                0.06f   // GR decreasing (returning toward 0) → slow release
            }
            smoothedGR[i] = alpha * totalGR + (1f - alpha) * smoothedGR[i]

            // Smooth compressor-only GR separately (for trace display)
            val compAlpha = if (compressorGR < smoothedCompressorGR[i]) 0.35f else 0.06f
            smoothedCompressorGR[i] = compAlpha * compressorGR + (1f - compAlpha) * smoothedCompressorGR[i]

            // Step 7: Total gain = preGain + smoothed GR + postGain
            bandTotalGains[i] = s.preGain + smoothedGR[i] + s.postGain
        }
    }

    /**
     * Get the total gain at a specific frequency, with crossover blending.
     * Call this for each pixel when drawing the output spectrum.
     *
     * @param freq  Frequency in Hz
     * @return Total gain in dB to add to the input spectrum at this frequency
     */
    fun getGainAtFrequency(freq: Float): Float {
        if (numBands <= 1) return bandTotalGains.getOrElse(0) { 0f }

        // Find which band this frequency belongs to
        var band = 0
        for (i in crossoverFreqs.indices) {
            if (freq >= crossoverFreqs[i]) band = i + 1
        }

        // Check if we're near a crossover boundary — if so, blend
        if (band > 0 && band <= crossoverFreqs.size) {
            val fc = crossoverFreqs[band - 1]
            val octavesFromCrossover = ln(freq / fc) / ln(2f)
            // Blend within ±0.5 octaves of crossover
            if (octavesFromCrossover > -0.5f && octavesFromCrossover < 0.5f) {
                val t = octavesFromCrossover + 0.5f  // 0..1
                val smooth = t * t * (3f - 2f * t)   // smoothstep
                val gainA = bandTotalGains[(band - 1).coerceIn(0, numBands - 1)]
                val gainB = bandTotalGains[band.coerceIn(0, numBands - 1)]
                return gainA * (1f - smooth) + gainB * smooth
            }
        }

        return bandTotalGains[band.coerceIn(0, numBands - 1)]
    }

    /**
     * Get the current smoothed gain reduction (not total gain) for display purposes.
     * E.g. for showing a GR meter per band.
     */
    fun getSmoothedGR(bandIndex: Int): Float = smoothedGR.getOrElse(bandIndex) { 0f }

    /** Compressor-only GR (excludes expander/gate). Use for the GR trace display. */
    fun getSmoothedCompressorGR(bandIndex: Int): Float = smoothedCompressorGR.getOrElse(bandIndex) { 0f }

    fun release() {
        smoothedGR.fill(0f)
        smoothedCompressorGR.fill(0f)
        bandTotalGains.fill(0f)
    }
}
