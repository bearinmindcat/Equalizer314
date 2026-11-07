package com.bearinmind.equalizer314.audio

import kotlin.math.*

/**
 * Processes raw waveform bytes from Visualizer.getWaveForm() into a dB spectrum for display.
 * Instead of Visualizer.getFft() (pre-cooked 8-bit, no windowing control), we window + zero-pad
 * FFT the raw waveform ourselves:
 * - Hann window eliminates spectral leakage
 * - Zero-padding (e.g. 1024 → 4096 / custom size) gives finer bin spacing
 * - Float-precision FFT vs system's 8-bit internal FFT
 * - Proper normalization (single capture block, artifact-free)
 */
class WaveformFftProcessor(
    val fftSize: Int = 4096,   // zero-padded output size (must be power of 2)
    val sampleRate: Int = 48000
) {
    private var hannWindow: FloatArray? = null
    private var lastCaptureSize: Int = 0

    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)

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
        val captureSize = minOf(waveform.size, fftSize)

        if (captureSize != lastCaptureSize) {
            hannWindow = FloatArray(captureSize) { n ->
                (0.5f - 0.5f * cos(2.0 * PI * n / captureSize)).toFloat()
            }
            lastCaptureSize = captureSize
        }
        val window = hannWindow ?: return FloatArray(binCount) { -96f }

        fftReal.fill(0f)
        fftImag.fill(0f)

        for (i in 0 until captureSize) {
            val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            fftReal[i] = sample * window[i]
        }

        SimpleFFT.fft(fftReal, fftImag)

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

    /** Frequency for a given bin index. */
    fun binToFrequency(bin: Int): Float = bin * sampleRate.toFloat() / fftSize
}
