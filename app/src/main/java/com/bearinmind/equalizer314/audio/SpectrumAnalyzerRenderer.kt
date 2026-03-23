package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.ln
import kotlin.math.exp

/**
 * Professional spectrum analyzer renderer — waveform-based approach.
 *
 * Uses getWaveForm() + own FFT instead of getFft(). This gives:
 * - Hann windowing (eliminates spectral leakage / spiky look)
 * - Zero-padding 1024→4096 (4× finer bin spacing = smooth curves)
 * - Float-precision FFT (instead of system's 8-bit internal FFT)
 * - Proper dBFS normalization (full-scale sine = 0 dB)
 *
 * Combined with 4.5 dB/oct tilt compensation, the display centers
 * around the -10 to -20 dBFS range where music naturally sits,
 * producing the flat, cohesive look of FabFilter Pro-Q and SPAN.
 *
 * Display pipeline per frame:
 * 1. WaveformFftProcessor: waveform bytes → windowed → zero-padded FFT → dBFS
 * 2. Temporal smoothing: asymmetric EMA (fast attack, slow release)
 * 3. Per-pixel rendering: log-freq mapping → octave smoothing → tilt → draw
 */
class SpectrumAnalyzerRenderer(
    private val sampleRate: Int = 48000
) {
    private val fftProcessor = WaveformFftProcessor(fftSize = 4096, sampleRate = sampleRate)

    // Temporally smoothed dB values per bin
    @Volatile
    private var smoothedDb: FloatArray? = null

    // Temporal smoothing — asymmetric EMA
    // Fast attack: peaks appear quickly (responsive to transients)
    // Slow release: smooth decay (the "liquid FabFilter" look)
    private val attackAlpha = 0.40f   // 0.3–0.5 (higher = snappier)
    private val releaseAlpha = 0.05f  // 0.03–0.08 (lower = smoother decay)

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
     * Called from Visualizer callback with RAW WAVEFORM bytes (NOT FFT bytes).
     * Switch your Visualizer listener to capture waveform instead of FFT.
     */
    fun updateWaveformData(waveform: ByteArray) {
        // Step 1: Waveform → windowed → zero-padded FFT → dBFS
        val dbValues = fftProcessor.process(waveform)
        val n = dbValues.size

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
     * Draw the spectrum behind the EQ curve.
     *
     * Recommended dB range for this approach:
     *   dbMin = -60, dbMax = 0
     *
     * With proper dBFS normalization:
     *   - Full-scale sine = 0 dBFS (top of graph)
     *   - Typical mastered music peaks: -3 to -8 dBFS
     *   - After 4.5 dB/oct tilt, music appears flat around -10 to -20 dBFS
     *   - Noise floor of 8-bit capture: ~-48 dBFS
     *   - -60 dBFS floor gives comfortable margin below noise
     */
    fun draw(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        dbMin: Float, dbMax: Float
    ) {
        val db = smoothedDb ?: return
        val n = db.size
        if (n < 2) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidthHz = fftProcessor.binWidthHz

        // Log frequency axis: 20 Hz – 20 kHz
        val logMin = ln(20f)
        val logMax = ln(20000f)
        val logRange = logMax - logMin

        // ── Step 1: Collect sample points via direct bin interpolation ──
        // No octave smoothing — show individual peaks. Hann window + zero-padding
        // already provides clean data. Linear interpolation between nearest bins
        // gives sub-bin precision for smooth curves.
        val step = 2f
        val pointCount = ((graphWidth / step).toInt()) + 1
        val pxArr = FloatArray(pointCount)
        val pyArr = FloatArray(pointCount)
        var idx = 0

        var x = 0f
        while (x <= graphWidth && idx < pointCount) {
            val norm = x / graphWidth
            val freq = exp(logMin + norm * logRange)

            // Fractional bin index for this frequency
            val binF = freq / binWidthHz
            val binLow = binF.toInt().coerceIn(1, n - 2)
            val frac = binF - binLow
            // Linear interpolation between adjacent bins
            val interpDb = db[binLow] * (1f - frac) + db[binLow + 1] * frac

            val normalizedDb = ((interpDb - dbMin) / dbRange).coerceIn(0f, 1f)
            pxArr[idx] = left + x
            pyArr[idx] = top + graphHeight * (1f - normalizedDb)
            idx++
            x += step
        }

        val count = idx
        if (count < 2) return

        // ── Step 2: Draw with Catmull-Rom splines ──
        fillPath.reset()
        strokePath.reset()

        strokePath.moveTo(pxArr[0], pyArr[0])
        fillPath.moveTo(pxArr[0], bottom)
        fillPath.lineTo(pxArr[0], pyArr[0])

        for (i in 0 until count - 1) {
            // Catmull-Rom needs 4 points: p0, p1, p2, p3
            // Clamp at boundaries
            val p0x = pxArr[maxOf(i - 1, 0)]
            val p0y = pyArr[maxOf(i - 1, 0)]
            val p1x = pxArr[i]
            val p1y = pyArr[i]
            val p2x = pxArr[i + 1]
            val p2y = pyArr[i + 1]
            val p3x = pxArr[minOf(i + 2, count - 1)]
            val p3y = pyArr[minOf(i + 2, count - 1)]

            // Catmull-Rom → cubic Bezier control points
            val cp1x = p1x + (p2x - p0x) / 6f
            val cp1y = p1y + (p2y - p0y) / 6f
            val cp2x = p2x - (p3x - p1x) / 6f
            val cp2y = p2y - (p3y - p1y) / 6f

            strokePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
            fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
        }

        fillPath.lineTo(pxArr[count - 1], bottom)
        fillPath.close()

        // Clip to graph bounds
        canvas.save()
        canvas.clipRect(left, top, right, bottom)

        // Gradient fill: brighter at top, fading toward bottom
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
