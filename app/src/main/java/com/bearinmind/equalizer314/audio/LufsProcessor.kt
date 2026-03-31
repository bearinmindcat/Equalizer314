package com.bearinmind.equalizer314.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * LUFS = Loudness Units relative to Full Scale
 *
 * K-weighted LUFS Momentary loudness processor.
 * Implements ITU-R BS.1770-4 K-weighting + 400ms sliding window RMS.
 *
 * LUFS is the international standard for measuring perceived loudness.
 * Used by streaming platforms (Spotify: -14 LUFS, YouTube: -13, Apple Music: -16)
 * and broadcast standard EBU R128.
 *
 * K-weighting = two cascaded biquad filters:
 * 1. Pre-filter (high shelf): +4 dB above ~1.5 kHz — models head/ear canal resonance
 * 2. RLB filter (highpass): rolls off below ~100 Hz — de-emphasizes bass (ears less sensitive)
 *
 * Then: RMS over a 400ms sliding window → LUFS = -0.691 + 10 * log10(meanSquare)
 *
 * Coefficients for 48 kHz sample rate from ITU-R BS.1770-4.
 * For other sample rates, coefficients must be recalculated.
 *
 * Documentation & references used:
 * - ITU-R BS.1770-4 (the actual standard defining K-weighting and LUFS measurement)
 * - pyloudnorm (github.com/csteinmetz1/pyloudnorm) — MIT, Python reference implementation
 *   Source of the exact biquad coefficients used here
 * - libebur128 (github.com/jiixyj/libebur128) — MIT, C implementation of EBU R128
 *   Cross-referenced filter coefficients and sliding window approach
 * - FabFilter Pro-L 2 documentation — confirmed LUFS Momentary (400ms) for loudness line
 *
 * Pre-filter coefficients (48 kHz):
 *   b0=1.53512485958697, b1=-2.69169618940638, b2=1.19839281085285
 *   a1=-1.69065929318241, a2=0.73248077421585
 *
 * RLB highpass coefficients (48 kHz):
 *   b0=1.0, b1=-2.0, b2=1.0
 *   a1=-1.99004745483398, a2=0.99007225036621
 */
class LufsProcessor(private val sampleRate: Int = 48000) {

    // ── Pre-filter (high shelf) biquad coefficients ──
    // ITU-R BS.1770-4 coefficients for 48 kHz
    private val preB0 = 1.53512485958697
    private val preB1 = -2.69169618940638
    private val preB2 = 1.19839281085285
    private val preA1 = -1.69065929318241
    private val preA2 = 0.73248077421585

    // Pre-filter state
    private var preX1 = 0.0
    private var preX2 = 0.0
    private var preY1 = 0.0
    private var preY2 = 0.0

    // ── RLB (revised low-frequency B-curve) highpass biquad ──
    private val rlbB0 = 1.0
    private val rlbB1 = -2.0
    private val rlbB2 = 1.0
    private val rlbA1 = -1.99004745483398
    private val rlbA2 = 0.99007225036621

    // RLB filter state
    private var rlbX1 = 0.0
    private var rlbX2 = 0.0
    private var rlbY1 = 0.0
    private var rlbY2 = 0.0

    // ── 400ms sliding window for Momentary LUFS ──
    // At ~20 captures/sec from Visualizer, 400ms ≈ 8 captures
    // But we compute per-capture RMS and smooth, so we use a ring buffer of squared values
    private val windowSize = 12  // ~400ms at 30fps timer
    private val windowBuffer = FloatArray(windowSize)
    private var windowIdx = 0
    private var windowFilled = false

    /**
     * Process a waveform capture from the Visualizer.
     * Applies K-weighting, computes mean square, stores in window.
     *
     * @param waveform Raw unsigned 8-bit bytes from Visualizer
     * @return Momentary LUFS value in dB (typically -60 to 0)
     */
    fun processWaveform(waveform: ByteArray): Float {
        var sumSquared = 0.0
        var count = 0

        for (b in waveform) {
            val sample = ((b.toInt() and 0xFF) - 128) / 128.0

            // Stage 1: Pre-filter (high shelf)
            val preOut = preB0 * sample + preB1 * preX1 + preB2 * preX2 - preA1 * preY1 - preA2 * preY2
            preX2 = preX1; preX1 = sample
            preY2 = preY1; preY1 = preOut

            // Stage 2: RLB highpass
            val rlbOut = rlbB0 * preOut + rlbB1 * rlbX1 + rlbB2 * rlbX2 - rlbA1 * rlbY1 - rlbA2 * rlbY2
            rlbX2 = rlbX1; rlbX1 = preOut
            rlbY2 = rlbY1; rlbY1 = rlbOut

            // Accumulate squared K-weighted output
            sumSquared += rlbOut * rlbOut
            count++
        }

        // Mean square for this capture
        val meanSquare = if (count > 0) (sumSquared / count).toFloat() else 0f

        // Store in sliding window
        windowBuffer[windowIdx % windowSize] = meanSquare
        windowIdx++
        if (windowIdx >= windowSize) windowFilled = true

        // Compute Momentary LUFS from window
        val windowLen = if (windowFilled) windowSize else windowIdx
        var windowSum = 0f
        for (i in 0 until windowLen) {
            windowSum += windowBuffer[i]
        }
        val windowMeanSquare = windowSum / windowLen

        // Convert to LUFS: -0.691 + 10 * log10(meanSquare)
        // The -0.691 offset is from ITU-R BS.1770 (adjusts to match subjective loudness scale)
        return if (windowMeanSquare > 1e-10f) {
            (-0.691f + 10f * log10(windowMeanSquare)).coerceIn(-80f, 10f)
        } else {
            -80f
        }
    }

    fun reset() {
        preX1 = 0.0; preX2 = 0.0; preY1 = 0.0; preY2 = 0.0
        rlbX1 = 0.0; rlbX2 = 0.0; rlbY1 = 0.0; rlbY2 = 0.0
        windowBuffer.fill(0f)
        windowIdx = 0
        windowFilled = false
    }
}
