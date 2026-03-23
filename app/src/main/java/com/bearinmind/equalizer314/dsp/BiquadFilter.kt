package com.bearinmind.equalizer314.dsp

import kotlin.math.*

/**
 * Biquad filter implementation for parametric EQ
 * Supports Bell, Low Shelf, High Shelf, Low Pass, High Pass filters
 */
class BiquadFilter(
    var frequency: Float,
    var gain: Float,
    var filterType: FilterType,
    private val sampleRate: Int = 44100,
    var q: Double = 0.707  // Q factor (0.1 to 10.0, default Butterworth)
) {
    enum class FilterType {
        BELL,           // Boost/cut at specific frequency
        LOW_SHELF,      // Boost/cut low frequencies
        HIGH_SHELF,     // Boost/cut high frequencies
        LOW_PASS,       // Cut high frequencies
        HIGH_PASS       // Cut low frequencies
    }

    // Biquad coefficients
    private var a0 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0

    // State variables for filtering (separate for left and right channels)
    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0

    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    // Use improved algorithm for bell filters
    var useVicanekMethod = false

    companion object {
        private const val ANTI_DENORMAL = 1.0e-20
    }

    init {
        calculateCoefficients()
    }

    fun updateParameters(freq: Float, gainDb: Float, type: FilterType, qFactor: Double = 0.707) {
        frequency = freq
        gain = gainDb
        filterType = type
        q = qFactor
        calculateCoefficients()
    }

    private fun calculateCoefficients() {
        // Raw omega — no bilinear pre-warping for any filter type.
        // Pre-warping uses tan() which diverges near Nyquist, causing
        // warped/distorted curves at high frequencies. Raw omega avoids this.
        val omega = 2.0 * PI * frequency / sampleRate
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)

        val alpha = sinOmega / (2.0 * q)

        val A = 10.0.pow(gain / 40.0)
        val sqrtA = sqrt(A)

        if (useVicanekMethod && filterType == FilterType.BELL) {
            calculateVicanekBell(omega, cosOmega, sinOmega)
            return
        }

        when (filterType) {
            FilterType.BELL -> {
                val A = 10.0.pow(gain / 40.0)

                if (abs(gain) < 0.01) {
                    b0 = 1.0; b1 = 0.0; b2 = 0.0
                    a0 = 1.0; a1 = 0.0; a2 = 0.0
                } else {
                    b0 = 1.0 + alpha * A
                    b1 = -2.0 * cosOmega
                    b2 = 1.0 - alpha * A
                    a0 = 1.0 + alpha / A
                    a1 = -2.0 * cosOmega
                    a2 = 1.0 - alpha / A
                }
            }

            FilterType.LOW_SHELF -> {
                val beta = sqrt(A) / q
                b0 = A * ((A + 1) - (A - 1) * cosOmega + beta * sinOmega)
                b1 = 2 * A * ((A - 1) - (A + 1) * cosOmega)
                b2 = A * ((A + 1) - (A - 1) * cosOmega - beta * sinOmega)
                a0 = (A + 1) + (A - 1) * cosOmega + beta * sinOmega
                a1 = -2 * ((A - 1) + (A + 1) * cosOmega)
                a2 = (A + 1) + (A - 1) * cosOmega - beta * sinOmega
            }

            FilterType.HIGH_SHELF -> {
                val beta = sqrt(A) / q
                b0 = A * ((A + 1) + (A - 1) * cosOmega + beta * sinOmega)
                b1 = -2 * A * ((A - 1) + (A + 1) * cosOmega)
                b2 = A * ((A + 1) + (A - 1) * cosOmega - beta * sinOmega)
                a0 = (A + 1) - (A - 1) * cosOmega + beta * sinOmega
                a1 = 2 * ((A - 1) - (A + 1) * cosOmega)
                a2 = (A + 1) - (A - 1) * cosOmega - beta * sinOmega
            }

            FilterType.LOW_PASS -> {
                b0 = (1.0 - cosOmega) / 2.0
                b1 = 1.0 - cosOmega
                b2 = (1.0 - cosOmega) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }

            FilterType.HIGH_PASS -> {
                b0 = (1.0 + cosOmega) / 2.0
                b1 = -(1.0 + cosOmega)
                b2 = (1.0 + cosOmega) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
        }

        // Normalize coefficients
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
    }

    private fun calculateVicanekBell(omega: Double, cosOmega: Double, sinOmega: Double) {
        val G = 10.0.pow(gain / 20.0)
        val omega0 = omega
        val q_factor = 1.0 / (2.0 * q)

        val expTerm = exp(-q_factor * omega0)

        // Underdamped (Q > 0.5): complex conjugate poles → cos
        // Overdamped (Q < 0.5): two real poles → cosh
        a1 = if (q_factor < 1.0) {
            -2.0 * expTerm * cos(sqrt(1.0 - q_factor * q_factor) * omega0)
        } else {
            -2.0 * expTerm * cosh(sqrt(q_factor * q_factor - 1.0) * omega0)
        }
        a2 = exp(-2.0 * q_factor * omega0)

        val phi0 = 1.0 - sin(omega0 / 2.0).pow(2)
        val phi1 = sin(omega0 / 2.0).pow(2)
        val phi2 = 4.0 * phi0 * phi1

        val A0 = (1.0 + a1 + a2).pow(2)
        val A1 = (1.0 - a1 + a2).pow(2)
        val A2 = -4.0 * a2

        val R1 = (A0 * phi0 + A1 * phi1 + A2 * phi2) * G * G
        val R2 = (-A0 + A1 + 4.0 * (phi0 - phi1) * A2) * G * G

        val B0 = A0
        val B2 = (R1 - R2 * phi1 - B0) / (4.0 * phi1 * phi1)
        val B1 = R2 + B0 + 4.0 * (phi1 - phi0) * B2

        val sqrtB1 = sqrt(max(0.0, B1))
        val W = 0.5 * (sqrt(B0) + sqrtB1)
        b0 = 0.5 * (W + sqrt(max(0.0, W * W + B2)))
        b1 = 0.5 * (sqrt(B0) - sqrtB1)
        b2 = if (b0 > 1e-12) -B2 / (4.0 * b0) else 0.0
    }

    /**
     * Process stereo sample pair in-place.
     * buffer[offset] = left, buffer[offset+1] = right
     */
    fun processStereoInPlace(buffer: FloatArray, offset: Int) {
        val inputL = buffer[offset].toDouble()
        val inputR = buffer[offset + 1].toDouble()

        val outputL = b0 * inputL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L; x1L = inputL; y2L = y1L; y1L = outputL + ANTI_DENORMAL

        val outputR = b0 * inputR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R; x1R = inputR; y2R = y1R; y1R = outputR + ANTI_DENORMAL

        buffer[offset] = outputL.toFloat()
        buffer[offset + 1] = outputR.toFloat()
    }

    fun reset() {
        x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
        x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
    }

    fun getFrequencyResponse(freq: Float): Float {
        val omega = 2.0 * PI * freq / sampleRate
        val z = Complex(cos(omega), sin(omega))
        val zInv = z.inverse()
        val zInv2 = zInv.times(zInv)

        val numerator = Complex(b0, 0.0)
            .plus(Complex(b1, 0.0).times(zInv))
            .plus(Complex(b2, 0.0).times(zInv2))

        val denominator = Complex(1.0, 0.0)
            .plus(Complex(a1, 0.0).times(zInv))
            .plus(Complex(a2, 0.0).times(zInv2))

        val response = numerator.div(denominator)
        val mag = response.magnitude().toFloat()

        return if (mag.isNaN() || mag.isInfinite()) 1f else mag
    }

    private data class Complex(val real: Double, val imag: Double) {
        fun plus(other: Complex) = Complex(real + other.real, imag + other.imag)
        fun times(other: Complex) = Complex(
            real * other.real - imag * other.imag,
            real * other.imag + imag * other.real
        )
        fun div(other: Complex): Complex {
            val denominator = other.real * other.real + other.imag * other.imag
            return Complex(
                (real * other.real + imag * other.imag) / denominator,
                (imag * other.real - real * other.imag) / denominator
            )
        }
        fun inverse() = Complex(real / (real * real + imag * imag), -imag / (real * real + imag * imag))
        fun magnitude() = sqrt(real * real + imag * imag)
    }
}
