package com.bearinmind.equalizer314.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Parses Android Visualizer FFT byte array into magnitude and dB arrays.
 *
 * Android's Visualizer.getFft() format:
 * - Byte[0] = DC component (real only)
 * - Byte[1] = Nyquist component (real only)
 * - Byte[2k], Byte[2k+1] = real, imaginary of bin k (for k >= 1)
 * - All values are SIGNED bytes (-128 to 127)
 */
object FftParser {

    /** Parse FFT bytes → linear magnitude array */
    fun parseMagnitudes(fftBytes: ByteArray): FloatArray {
        val n = fftBytes.size / 2
        val magnitudes = FloatArray(n)

        // DC component
        magnitudes[0] = abs(fftBytes[0].toFloat())

        // Complex bins: interleaved real/imaginary pairs
        for (k in 1 until n) {
            val real = fftBytes[2 * k].toFloat()
            val imag = fftBytes[2 * k + 1].toFloat()
            magnitudes[k] = sqrt(real * real + imag * imag)
        }

        return magnitudes
    }

    /** Convert magnitude array → dB array, normalized to peak */
    fun magnitudeToDb(magnitudes: FloatArray, floorDb: Float = -100f): FloatArray {
        val peak = magnitudes.max().coerceAtLeast(1f)
        return FloatArray(magnitudes.size) { i ->
            if (magnitudes[i] > 0f) {
                (20f * log10(magnitudes[i] / peak)).coerceAtLeast(floorDb)
            } else {
                floorDb
            }
        }
    }
}
