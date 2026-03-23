package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.*

/**
 * Professional spectrum analyzer renderer — waveform-based approach.
 *
 * Pipeline per frame:
 * 1. WaveformFftProcessor: waveform bytes → Hann window → zero-pad → FFT → dBFS
 * 2. Convert dB → linear magnitude for proper noise-reducing temporal smoothing
 * 3. Asymmetric EMA in linear domain (fast attack, slow release)
 * 4. Convert back to dB after smoothing
 * 5. Log-frequency mapping: interpolate for sub-bin, peak for multi-bin
 * 6. +3 dB/oct tilt compensation (pivot at 1 kHz)
 * 7. Render with cubic Bézier paths + gradient fill
 */
class SpectrumAnalyzerRenderer(
    private val sampleRate: Int = 48000
) {
    private val fftProcessor = WaveformFftProcessor(fftSize = 4096, sampleRate = sampleRate)

    // Temporally smoothed LINEAR magnitudes per bin (not dB)
    // Smoothing in linear domain properly reduces noise floor by √N frames
    @Volatile
    private var smoothedLinear: FloatArray? = null

    // Asymmetric ballistics — applied to linear magnitudes
    private val attackAlpha = 0.6f    // fast rise (~2-3 frames to reach 90%)
    private val releaseAlpha = 0.22f  // ~6 frames to decay

    // Reusable drawing objects
    private val fillPath = Path()
    private val strokePath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(80, 200, 200, 200)
    }

    /**
     * Called from Visualizer callback with RAW WAVEFORM bytes.
     */
    fun updateWaveformData(waveform: ByteArray) {
        val dbValues = fftProcessor.process(waveform)
        val n = dbValues.size

        // Convert dB → linear magnitude for smoothing
        // Smoothing in linear domain averages noise properly (reduces by √N)
        // instead of dB domain which biases toward peaks
        val currentLinear = FloatArray(n) { i ->
            10f.pow(dbValues[i] / 20f)
        }

        // Asymmetric EMA in linear domain
        var prev = smoothedLinear
        if (prev == null || prev.size != n) {
            prev = FloatArray(n) // zeros = silence
        }
        val result = FloatArray(n)
        for (i in 0 until n) {
            val alpha = if (currentLinear[i] > prev[i]) attackAlpha else releaseAlpha
            result[i] = alpha * currentLinear[i] + (1f - alpha) * prev[i]
        }
        smoothedLinear = result
    }

    fun draw(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        dbMin: Float, dbMax: Float
    ) {
        val linear = smoothedLinear ?: return
        val n = linear.size
        if (n < 2) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidthHz = fftProcessor.binWidthHz

        // Convert smoothed linear back to dB for display
        val db = FloatArray(n) { i ->
            if (linear[i] > 1e-10f) (20f * log10(linear[i])).coerceAtLeast(-96f)
            else -96f
        }

        // Log frequency mapping: 20 Hz – 20 kHz
        val logMin = log10(20f)
        val logMax = log10(20000f)
        val logRange = logMax - logMin

        // Collect display points — one per pixel column
        val displayWidth = graphWidth.toInt().coerceAtLeast(1)
        val xArr = FloatArray(displayWidth)
        val yArr = FloatArray(displayWidth)

        for (x in 0 until displayWidth) {
            // Frequency range for this pixel column
            val logFreqLow = logMin + logRange * x / displayWidth
            val logFreqHigh = logMin + logRange * (x + 1) / displayWidth
            val freqLow = 10f.pow(logFreqLow)
            val freqHigh = 10f.pow(logFreqHigh)
            val freq = 10f.pow((logFreqLow + logFreqHigh) / 2f) // center freq

            val binLow = (freqLow / binWidthHz).toInt().coerceIn(1, n - 1)
            val binHigh = (freqHigh / binWidthHz).toInt().coerceIn(1, n - 1)

            val rawDb: Float
            if (binLow == binHigh) {
                // Sub-bin resolution: interpolate between adjacent bins
                val exactBin = freq / binWidthHz
                val lower = exactBin.toInt().coerceIn(1, n - 2)
                val frac = exactBin - lower
                rawDb = db[lower] * (1f - frac) + db[lower + 1] * frac
            } else {
                // Multiple bins per pixel: take peak (preserves spectral peaks)
                var peak = -96f
                for (b in binLow..binHigh.coerceAtMost(n - 1)) {
                    if (db[b] > peak) peak = db[b]
                }
                rawDb = peak
            }

            // +3 dB/octave tilt compensation (pivot at 1 kHz)
            val tiltDb = 3.0f * (log10(freq / 1000f) / 0.30103f)
            val displayDb = rawDb + tiltDb

            val normalized = ((displayDb - dbMin) / dbRange).coerceIn(0f, 1f)
            xArr[x] = left + x
            yArr[x] = top + graphHeight * (1f - normalized)
        }

        // Render with cubic Bézier paths (horizontal tangent approach from guide)
        fillPath.reset()
        strokePath.reset()

        // Subsample for Bézier control points — every 2 pixels
        val step = 2
        val first = true
        strokePath.moveTo(xArr[0], yArr[0])
        fillPath.moveTo(xArr[0], bottom)
        fillPath.lineTo(xArr[0], yArr[0])

        var i = step
        while (i < displayWidth) {
            val x0 = xArr[i - step]
            val y0 = yArr[i - step]
            val x1 = xArr[i]
            val y1 = yArr[i]
            val midX = (x0 + x1) / 2f
            // Cubic Bézier with horizontal tangents at control points
            strokePath.cubicTo(midX, y0, midX, y1, x1, y1)
            fillPath.cubicTo(midX, y0, midX, y1, x1, y1)
            i += step
        }

        // Connect to last point if we didn't land exactly on it
        val lastIdx = displayWidth - 1
        if (i - step < lastIdx) {
            strokePath.lineTo(xArr[lastIdx], yArr[lastIdx])
            fillPath.lineTo(xArr[lastIdx], yArr[lastIdx])
        }

        fillPath.lineTo(xArr[lastIdx], bottom)
        fillPath.close()

        // Clip to graph bounds and draw
        canvas.save()
        canvas.clipRect(left, top, right, bottom)

        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(45, 180, 180, 180),
                Color.argb(8, 100, 100, 100)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(strokePath, strokePaint)
        canvas.restore()
    }

    fun release() {
        smoothedLinear = null
    }
}
