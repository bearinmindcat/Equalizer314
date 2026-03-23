package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.ln
import kotlin.math.log10
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

        fillPath.reset()
        strokePath.reset()

        var firstPoint = true
        val step = 2f  // every 2 pixels (smooth enough, half the work)

        // Log frequency axis: 20 Hz – 20 kHz
        val logMin = ln(20f)
        val logMax = ln(20000f)
        val logRange = logMax - logMin

        var x = 0f
        while (x <= graphWidth) {
            // ── Pixel → frequency (log scale) ──
            val norm = x / graphWidth
            val freq = exp(logMin + norm * logRange)

            // ── 1/6-octave smoothing (max-bin within ±1/12 octave) ──
            // This averages out noise while preserving peaks, like SPAN's approach.
            // At 4096 FFT / 48kHz, bins are ~11.7 Hz apart.
            // At 100 Hz, ±1/12 octave = ~94–106 Hz = ~1 bin (not much smoothing)
            // At 5 kHz, ±1/12 octave = ~4700–5300 Hz = ~51 bins (significant smoothing)
            // This natural widening is exactly what makes high frequencies look clean.
            val octaveRatio = 1.0595f  // 2^(1/12)
            val lowFreq = freq / octaveRatio
            val highFreq = freq * octaveRatio
            val lowBin = (lowFreq / binWidthHz).toInt().coerceIn(1, n - 1)
            val highBin = (highFreq / binWidthHz).toInt().coerceIn(lowBin, n - 1)

            var maxDb = -96f
            for (b in lowBin..highBin) {
                if (db[b] > maxDb) maxDb = db[b]
            }

            // ── 4.5 dB/octave tilt compensation ──
            // THE key technique that makes pro analyzers look flat.
            // Music has roughly -4.5 dB/oct spectral slope. This cancels it.
            // Pivot at 1 kHz: frequencies above get boosted on display,
            // frequencies below get attenuated on display.
            val octavesFrom1k = log10(freq / 1000f) / 0.30103f  // log2 via log10
            val tiltDb = octavesFrom1k * 4.5f
            val displayDb = maxDb + tiltDb

            // ── dB → Y pixel ──
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
