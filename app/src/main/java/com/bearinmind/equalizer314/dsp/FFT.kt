package com.bearinmind.equalizer314.dsp

import kotlin.math.*

/**
 * Fast Fourier Transform implementation for real-time audio spectrum analysis
 * Uses Cooley-Tukey radix-2 DIT algorithm
 *
 * Based on audio-analyzer-for-android by bewantbe (Apache 2.0 License)
 */
class FFT(private val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be a power of 2, got $size"
        }
    }

    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)
    private var wnd: DoubleArray? = null
    private var wndEnergyFactor: Double = 1.0

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    fun initHannWindow(fftLen: Int) {
        wnd = DoubleArray(fftLen)

        for (i in 0 until fftLen) {
            wnd!![i] = 0.5 * (1.0 - cos(2.0 * PI * i / (fftLen - 1.0))) * 2.0
        }

        var normalizeFactor = 0.0
        for (i in 0 until fftLen) {
            normalizeFactor += wnd!![i]
        }
        normalizeFactor = fftLen / normalizeFactor

        wndEnergyFactor = 0.0
        for (i in 0 until fftLen) {
            wnd!![i] *= normalizeFactor
            wndEnergyFactor += wnd!![i] * wnd!![i]
        }
        wndEnergyFactor = fftLen / wndEnergyFactor
    }

    fun applyWindow(input: FloatArray): DoubleArray {
        if (wnd == null || wnd!!.size != input.size) {
            initHannWindow(input.size)
        }

        val windowed = DoubleArray(input.size)
        for (i in input.indices) {
            windowed[i] = input[i].toDouble() * wnd!![i]
        }
        return windowed
    }

    fun computePowerSpectrum(windowedInput: DoubleArray): DoubleArray {
        require(windowedInput.size == size) {
            "Input size ${windowedInput.size} doesn't match FFT size $size"
        }

        for (i in 0 until size) {
            real[i] = windowedInput[i]
            imag[i] = 0.0
        }

        fft()

        val numBins = size / 2 + 1
        val power = DoubleArray(numBins)
        val scaler = 4.0 / (size.toDouble() * size.toDouble())

        power[0] = (real[0] * real[0] + imag[0] * imag[0]) * scaler / 4.0

        for (i in 1 until numBins - 1) {
            power[i] = (real[i] * real[i] + imag[i] * imag[i]) * scaler
        }

        val nyquist = numBins - 1
        power[nyquist] = (real[nyquist] * real[nyquist] + imag[nyquist] * imag[nyquist]) * scaler / 4.0

        return power
    }

    fun computePowerSpectrumDB(windowedInput: DoubleArray): DoubleArray {
        val power = computePowerSpectrum(windowedInput)

        for (i in power.indices) {
            power[i] = 10.0 * log10(power[i].coerceAtLeast(1e-18))
        }

        return power
    }

    fun getWindowEnergyFactor(): Double = wndEnergyFactor

    private fun fft() {
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
            var k = size / 2
            while (k <= j) { j -= k; k /= 2 }
            j += k
        }

        var step = 1
        while (step < size) {
            val halfStep = step
            step *= 2
            val tableFactor = size / step

            for (i in 0 until size step step) {
                var tableIndex = 0
                for (k in 0 until halfStep) {
                    val evenIndex = i + k
                    val oddIndex = evenIndex + halfStep

                    val tReal = cosTable[tableIndex] * real[oddIndex] - sinTable[tableIndex] * imag[oddIndex]
                    val tImag = cosTable[tableIndex] * imag[oddIndex] + sinTable[tableIndex] * real[oddIndex]

                    real[oddIndex] = real[evenIndex] - tReal
                    imag[oddIndex] = imag[evenIndex] - tImag
                    real[evenIndex] += tReal
                    imag[evenIndex] += tImag

                    tableIndex += tableFactor
                }
            }
        }
    }

    fun binToFrequency(binIndex: Int, sampleRate: Int): Float {
        return binIndex.toFloat() * sampleRate / size
    }

    fun frequencyToBin(frequency: Float, sampleRate: Int): Int {
        return (frequency * size / sampleRate).toInt().coerceIn(0, size / 2)
    }
}
