package com.bearinmind.equalizer314.audio

import kotlin.math.*

/**
 * Pure-Kotlin radix-2 Cooley-Tukey FFT.
 * No external dependencies (no JTransforms, no KissFFT, no NDK).
 * Fast enough for 4096 points on any modern Android device (~1ms).
 */
object SimpleFFT {

    /**
     * In-place radix-2 FFT.
     * @param re Real parts (must be power-of-2 length)
     * @param im Imaginary parts (same length, typically all zeros for real input)
     */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) {
            "Length must be equal power of 2, got $n"
        }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Cooley-Tukey butterfly
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen] * curRe - im[i + k + halfLen] * curIm
                    val vIm = re[i + k + halfLen] * curIm + im[i + k + halfLen] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + halfLen] = uRe - vRe
                    im[i + k + halfLen] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
