package com.bearinmind.equalizer314.dsp

import android.os.Handler
import android.os.Looper
import kotlin.math.log10

/**
 * Real-time spectrum analyzer for audio visualization
 * Based on audio-analyzer-for-android by bewantbe (Apache 2.0 License)
 */
class SpectrumAnalyzer(
    private val sampleRate: Int = 44100,
    private val fftLen: Int = 2048,
    private val hopLen: Int = 1024,
    private val nFFTAverage: Int = 1
) {
    private val fft = FFT(fftLen)

    private val spectrumAmpIn = FloatArray(fftLen)
    private var spectrumAmpPt = 0

    private val numBins = fftLen / 2 + 1
    private val spectrumAmpOutCum = DoubleArray(numBins)
    private val spectrumAmpOut = DoubleArray(numBins)
    private val spectrumAmpOutDB = DoubleArray(numBins)
    private var nAnalysed = 0

    private var smoothedSpectrum: FloatArray? = null
    private val smoothingFactor = 0.25f
    private val peakDecayFactor = 0.92f

    @Volatile
    private var latestSpectrumDB: FloatArray? = null

    @Volatile
    private var latestBandSpectrum: FloatArray? = null

    private val spectrumUpdateListeners = mutableListOf<(FloatArray) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastUpdateTime = 0L
    private val minUpdateIntervalMs = 16L  // ~60 FPS

    private val bandFrequencies = floatArrayOf(
        31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )
    val numBands: Int = bandFrequencies.size

    init {
        fft.initHannWindow(fftLen)
    }

    fun addSpectrumUpdateListener(listener: (FloatArray) -> Unit) {
        synchronized(spectrumUpdateListeners) {
            spectrumUpdateListeners.add(listener)
        }
    }

    fun removeSpectrumUpdateListener(listener: (FloatArray) -> Unit) {
        synchronized(spectrumUpdateListeners) {
            spectrumUpdateListeners.remove(listener)
        }
    }

    fun feedSamples(samples: FloatArray, isStereo: Boolean = true) {
        val step = if (isStereo) 2 else 1

        var dsPt = 0
        while (dsPt < samples.size) {
            val sample = if (isStereo && dsPt + 1 < samples.size) {
                (samples[dsPt] + samples[dsPt + 1]) / 2f
            } else {
                samples[dsPt]
            }
            dsPt += step

            if (spectrumAmpPt < fftLen) {
                spectrumAmpIn[spectrumAmpPt++] = sample
            }

            if (spectrumAmpPt == fftLen) {
                computeFFT()

                if (hopLen < fftLen) {
                    System.arraycopy(spectrumAmpIn, hopLen, spectrumAmpIn, 0, fftLen - hopLen)
                }
                spectrumAmpPt = fftLen - hopLen
            }
        }
    }

    private fun computeFFT() {
        val windowedSamples = fft.applyWindow(spectrumAmpIn)
        val power = fft.computePowerSpectrum(windowedSamples)

        for (i in 0 until numBins) {
            spectrumAmpOutCum[i] += power[i]
        }
        nAnalysed++

        if (nAnalysed >= nFFTAverage) {
            finalizeSpectrum()
        }
    }

    private fun finalizeSpectrum() {
        for (i in 0 until numBins) {
            spectrumAmpOut[i] = spectrumAmpOutCum[i] / nAnalysed
        }

        for (i in 0 until numBins) {
            spectrumAmpOutDB[i] = 10.0 * log10(spectrumAmpOut[i].coerceAtLeast(1e-18))
        }

        spectrumAmpOutCum.fill(0.0)
        nAnalysed = 0

        val spectrumFloat = FloatArray(numBins)
        for (i in 0 until numBins) {
            spectrumFloat[i] = spectrumAmpOutDB[i].toFloat()
        }

        if (smoothedSpectrum == null || smoothedSpectrum!!.size != numBins) {
            smoothedSpectrum = spectrumFloat.clone()
        } else {
            for (i in 0 until numBins) {
                val current = spectrumFloat[i]
                val previous = smoothedSpectrum!![i]

                smoothedSpectrum!![i] = if (current > previous) {
                    previous + (current - previous) * 0.6f
                } else {
                    previous * peakDecayFactor + current * (1f - peakDecayFactor)
                }
            }
        }

        latestSpectrumDB = smoothedSpectrum!!.clone()
        latestBandSpectrum = computeBandSpectrum(latestSpectrumDB!!)

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= minUpdateIntervalMs) {
            lastUpdateTime = now
            latestSpectrumDB?.let { fullSpectrum ->
                val listenersCopy = synchronized(spectrumUpdateListeners) {
                    spectrumUpdateListeners.toList()
                }
                if (listenersCopy.isNotEmpty()) {
                    mainHandler.post {
                        listenersCopy.forEach { listener ->
                            listener.invoke(fullSpectrum)
                        }
                    }
                }
            }
        }
    }

    fun getBinFrequency(binIndex: Int): Float {
        return binIndex.toFloat() * sampleRate / fftLen
    }

    fun getNumBins(): Int = numBins

    private fun computeBandSpectrum(spectrum: FloatArray): FloatArray {
        val bandValues = FloatArray(numBands)

        for (bandIndex in 0 until numBands) {
            val lowFreq = if (bandIndex == 0) 20f else bandFrequencies[bandIndex - 1]
            val highFreq = if (bandIndex == numBands - 1) {
                (sampleRate / 2f).coerceAtMost(20000f)
            } else {
                bandFrequencies[bandIndex]
            }

            val lowBin = fft.frequencyToBin(lowFreq, sampleRate)
            val highBin = fft.frequencyToBin(highFreq, sampleRate)

            var sum = 0f
            var count = 0
            for (bin in lowBin..highBin.coerceAtMost(spectrum.size - 1)) {
                sum += spectrum[bin]
                count++
            }

            bandValues[bandIndex] = if (count > 0) sum / count else -120f
        }

        return bandValues
    }

    fun getLatestSpectrum(): FloatArray? = latestSpectrumDB?.clone()

    fun getLatestBandSpectrum(): FloatArray? = latestBandSpectrum?.clone()

    fun getBandFrequency(bandIndex: Int): Float {
        return if (bandIndex in bandFrequencies.indices) bandFrequencies[bandIndex] else 0f
    }

    fun getMagnitudeAtFrequency(frequency: Float): Float {
        val spectrum = latestSpectrumDB ?: return -120f
        val bin = fft.frequencyToBin(frequency, sampleRate)
        return if (bin in spectrum.indices) spectrum[bin] else -120f
    }

    fun getFrequencyResolution(): Float = sampleRate.toFloat() / fftLen

    fun getMaxFrequency(): Float = sampleRate / 2f

    fun reset() {
        spectrumAmpIn.fill(0f)
        spectrumAmpPt = 0
        spectrumAmpOutCum.fill(0.0)
        spectrumAmpOut.fill(0.0)
        spectrumAmpOutDB.fill(log10(0.0))
        nAnalysed = 0
        smoothedSpectrum = null
        latestSpectrumDB = null
        latestBandSpectrum = null
    }

    fun normalizeSpectrum(spectrum: FloatArray, minDb: Float = -90f, maxDb: Float = 0f): FloatArray {
        val normalized = FloatArray(spectrum.size)
        val range = maxDb - minDb

        for (i in spectrum.indices) {
            normalized[i] = ((spectrum[i] - minDb) / range).coerceIn(0f, 1f)
        }

        return normalized
    }

    fun smoothSpectrum(spectrum: FloatArray, windowSize: Int = 5): FloatArray {
        if (spectrum.isEmpty() || windowSize <= 1) return spectrum

        val smoothed = FloatArray(spectrum.size)
        val halfWindow = windowSize / 2

        for (i in spectrum.indices) {
            var sum = 0f
            var count = 0

            for (j in (i - halfWindow)..(i + halfWindow)) {
                if (j >= 0 && j < spectrum.size) {
                    sum += spectrum[j]
                    count++
                }
            }

            smoothed[i] = sum / count
        }

        return smoothed
    }
}
