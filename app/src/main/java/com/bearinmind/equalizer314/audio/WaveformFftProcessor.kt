package com.bearinmind.equalizer314.audio

import kotlin.math.*

/**
 * Processes raw waveform bytes from Android's Visualizer.getWaveForm()
 * into a proper dB spectrum suitable for professional-looking display.
 *
 * KEY INSIGHT: Instead of using Visualizer.getFft() (which gives pre-cooked
 * 8-bit FFT with no windowing control), we take the raw waveform and do
 * our own windowing + zero-padded FFT. This produces dramatically better
 * results despite using the same 8-bit source data:
 *
 * - Hann window eliminates spectral leakage (the spiky look)
 * - Zero-padding 1024→4096 gives 4× finer bin spacing (smoother curves)
 * - Float-precision FFT instead of system's 8-bit internal FFT
 * - Proper normalization since we control every step
 *
 * The 8-bit dynamic range (~48 dB) is the hard ceiling, but for a visual
 * display behind an EQ curve this is more than adequate. Pro-Q's visible
 * range is typically only ~60 dB and 48 dB covers the meaningful content.
 */
class WaveformFftProcessor(
    private val fftSize: Int = 4096,   // zero-padded output size
    private val sampleRate: Int = 48000
) {
    // Pre-computed Hann window for the capture size (1024)
    // Will be lazily initialized on first use with actual capture size
    private var hannWindow: FloatArray? = null
    private var lastCaptureSize: Int = 0

    // Reusable FFT buffers (avoid allocation per frame)
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)

    // Output: magnitude in dB for each bin (fftSize/2 bins)
    val binCount: Int get() = fftSize / 2
    val binWidthHz: Float get() = sampleRate.toFloat() / fftSize

    /**
     * Process raw waveform bytes → dB spectrum.
     *
     * @param waveform Raw bytes from Visualizer.getWaveForm().
     *                 Unsigned 8-bit: 0–255, center at 128.
     * @return FloatArray of dB values for fftSize/2 bins.
     *         0 dB = full-scale sine wave peak.
     *         Typical music content: -5 to -45 dB.
     */
    fun process(waveform: ByteArray): FloatArray {
        val captureSize = waveform.size

        // Build/rebuild Hann window if capture size changed
        if (captureSize != lastCaptureSize) {
            hannWindow = FloatArray(captureSize) { n ->
                (0.5f - 0.5f * cos(2.0 * PI * n / captureSize)).toFloat()
            }
            lastCaptureSize = captureSize
        }
        val window = hannWindow!!

        // ── Step 1: Convert unsigned 8-bit → float [-1.0, +1.0] + apply Hann window ──
        // Visualizer.getWaveForm() returns unsigned bytes: 0=min, 128=center, 255=max
        fftReal.fill(0f)
        fftImag.fill(0f)

        for (i in 0 until captureSize) {
            // Convert unsigned byte to float: (byte & 0xFF) gives 0–255, subtract 128 → -128..+127
            val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            fftReal[i] = sample * window[i]
            // Remaining indices stay 0 (zero-padding to fftSize)
        }

        // ── Step 2: FFT ──
        SimpleFFT.fft(fftReal, fftImag)

        // ── Step 3: Magnitude → dB ──
        // Normalization factor: corrects for Hann window energy loss and single-sided spectrum.
        //   - ×2 for discarding negative-frequency half
        //   - ×2 for Hann window average value of 0.5
        //   - ÷captureSize for FFT normalization (NOT fftSize — we only had captureSize real samples)
        // Combined: 4.0 / captureSize
        // A full-scale sine (amplitude 1.0) will read 0 dB after this normalization.
        val normFactor = 4f / captureSize

        val dbOut = FloatArray(binCount)
        for (k in 0 until binCount) {
            val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) * normFactor
            dbOut[k] = if (mag > 1e-10f) {
                (20f * log10(mag)).coerceAtLeast(-96f)
            } else {
                -96f
            }
        }

        return dbOut
    }

    /**
     * Get the frequency for a given bin index.
     */
    fun binToFrequency(bin: Int): Float = bin * sampleRate.toFloat() / fftSize
}
