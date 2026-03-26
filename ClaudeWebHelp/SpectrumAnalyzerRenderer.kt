package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Professional spectrum analyzer renderer.
 *
 * Pipeline (from the spec):
 * 1. Parse FFT bytes → magnitude
 * 2. Normalize to dBFS: 20 × log10(|X[k]| × 2.0 / (N/2))
 * 3. Temporal smoothing (asymmetric EMA: fast attack, slow release)
 * 4. 1/6-octave smoothing (max-bin within proportional-bandwidth windows)
 * 5. 4.5 dB/octave tilt compensation: +4.5 × log2(freq/1000)
 * 6. Log-frequency axis mapping (iterate pixels, not bins)
 * 7. Render with fill + stroke, clipped to graph area
 */
class SpectrumAnalyzerRenderer(
    private val sampleRate: Int = 48000
) {
    // Smoothed per-bin dB values (temporal smoothing applied here)
    @Volatile
    private var smoothedDb: FloatArray? = null
    private var fftBinCount: Int = 512

    // Temporal smoothing coefficients
    private val attackAlpha = 0.35f   // fast rise
    private val releaseAlpha = 0.06f  // slow fall — FabFilter-like liquid decay

    // Reusable path objects
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

    /** Called from Visualizer callback thread */
    fun updateFftData(fftBytes: ByteArray) {
        val n = fftBytes.size / 2  // number of bins
        fftBinCount = n
        val binWidth = sampleRate.toFloat() / (n * 2)

        // Step 1: Parse bytes → magnitude → dBFS
        // Normalize: 20 * log10(mag * 2.0 / (N/2))
        // For Visualizer bytes, N/2 = n, and mag is sqrt(real²+imag²)
        // Window compensation ≈ 2.0 for Hann
        val dbValues = FloatArray(n)
        for (k in 1 until n) {
            val real = fftBytes[2 * k].toFloat()
            val imag = fftBytes[2 * k + 1].toFloat()
            val mag = sqrt(real * real + imag * imag)

            // dBFS with window compensation (×2) and FFT normalization (÷N/2)
            val normalized = mag * 2f / n
            dbValues[k] = if (normalized > 0.0001f) {
                20f * log10(normalized)
            } else {
                -96f
            }
        }
        dbValues[0] = dbValues.getOrElse(1) { -96f }

        // Step 2: Temporal smoothing (asymmetric EMA)
        var prev = smoothedDb
        if (prev == null || prev.size != n) {
            prev = FloatArray(n) { -96f }
        }
        val result = FloatArray(n)
        for (i in 0 until n) {
            val alpha = if (dbValues[i] > prev[i]) attackAlpha else releaseAlpha
            result[i] = alpha * dbValues[i] + (1f - alpha) * prev[i]
        }
        smoothedDb = result
    }

    /**
     * Draw the spectrum.
     * dbMin/dbMax define the visible dB range on the graph.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
             dbMin: Float, dbMax: Float) {
        val db = smoothedDb ?: return
        val n = db.size
        if (n < 2) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidth = sampleRate.toFloat() / (n * 2)

        // Pre-compute 1/6-octave smoothed + tilt-compensated values
        // We do this per-pixel, not per-bin

        fillPath.reset()
        strokePath.reset()

        var firstPoint = true
        val step = 2f

        // Log frequency constants
        val logMin = ln(20f)
        val logMax = ln(20000f)
        val logRange = logMax - logMin

        var x = 0f
        while (x <= graphWidth) {
            // Pixel → frequency (log scale)
            val norm = x / graphWidth
            val freq = exp(logMin + norm * logRange)

            // 1/6-octave smoothing: find max bin within ±1/12 octave of this frequency
            // Band edges: freq * 2^(-1/12) to freq * 2^(1/12)
            val octaveRatio = 1.0595f  // 2^(1/12)
            val lowFreq = freq / octaveRatio
            val highFreq = freq * octaveRatio
            val lowBin = (lowFreq / binWidth).toInt().coerceIn(1, n - 1)
            val highBin = (highFreq / binWidth).toInt().coerceIn(lowBin, n - 1)

            // Max-bin selection within the window (preserves peaks, like SPAN)
            var maxDb = -96f
            for (b in lowBin..highBin) {
                if (db[b] > maxDb) maxDb = db[b]
            }

            // 4.5 dB/octave tilt compensation: +4.5 × log2(freq/1000)
            val octavesFrom1k = log10(freq / 1000f) / 0.30103f  // log2 via log10
            val tiltDb = octavesFrom1k * 4.5f
            val displayDb = maxDb + tiltDb

            // dB → Y pixel
            val normalizedDb = ((displayDb - dbMin) / dbRange).coerceIn(0f, 1f)
            val y = top + graphHeight * (1f - normalizedDb)
            val px = left + x

            if (firstPoint) {
                strokePath.moveTo(px, y)
                fillPath.moveTo(px, bottom)
                fillPath.lineTo(px, y)
                firstPoint = false
            } else {
                strokePath.lineTo(px, y)
                fillPath.lineTo(px, y)
            }

            x += step
        }

        if (firstPoint) return

        fillPath.lineTo(right, bottom)
        fillPath.close()

        // Clip to graph area
        canvas.save()
        canvas.clipRect(left, top, right, bottom)

        // Gradient fill
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
        smoothedDb = null
    }
}
