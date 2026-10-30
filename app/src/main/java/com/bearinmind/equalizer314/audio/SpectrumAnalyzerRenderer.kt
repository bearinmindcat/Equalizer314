package com.bearinmind.equalizer314.audio

import android.graphics.*
import com.bearinmind.equalizer314.autoeq.FreqResponse
import com.bearinmind.equalizer314.autoeq.FreqResponseParser
import kotlin.math.*

/**
 * Spectrum analyzer renderer for the Precision Surgical EQ.
 *
 * All FFT analysis is delegated to [LongWindowAnalyzer]: waveform captures
 * from session 0 are accumulated into a long analysis window (2048–65536
 * samples) whose true frequency resolution scales with the current zoom
 * span — so a surgical view of 20–300 Hz can resolve fractions of a Hz.
 *
 * Extras over a plain analyzer:
 *  - Zoom-aware log-frequency mapping (visible range set by the graph view).
 *  - Optional target-curve ghost overlay + red/green delta fill (live vs.
 *    target), auto-aligned with a rolling median offset.
 *  - `spectrumDbAt()` + `findDominantPeak()` for the spectrum tracker.
 */
class SpectrumAnalyzerRenderer(
    sampleRate: Int = 48000
) {
    companion object {
        const val MIN_VISIBLE_HZ = 1f
        const val MAX_VISIBLE_HZ = 30000f
    }

    private val analyzer = LongWindowAnalyzer(targetSize = 4096, sampleRate = sampleRate)

    /** Real output sample rate, refreshed from Visualizer callbacks. */
    @Volatile
    var sampleRate: Int = sampleRate
        private set

    fun setSampleRate(sr: Int) {
        if (sr <= 0) return
        sampleRate = sr
        analyzer.sampleRate = sr
    }

    /** Manual FFT size (Spectrum Control). When set, zoom adaptivity is off. */
    @Volatile
    private var manualFftSize: Int? = null

    /** Change FFT size at runtime (manual mode; disables zoom adaptivity). Resets smoothing state. */
    fun setFftSize(size: Int) {
        manualFftSize = size
        analyzer.clearZoomSpan()
        analyzer.setTargetSize(size)
    }

    /** Back to zoom-adaptive window sizing (manual override off). */
    fun clearManualFftSize() {
        manualFftSize = null
        analyzer.clearZoomSpan()
        analyzer.setTargetSize(4096)
    }

    fun getFftSize(): Int = analyzer.getTargetSize()
    fun getEffectiveSize(): Int = analyzer.effectiveSize
    fun getBinWidthHz(): Float = analyzer.binWidthHz

    /** PPO (Points Per Octave) smoothing. 0 = disabled. Typical: 1, 3, 6, 12, 24, 48 */
    @Volatile
    var ppoSmoothing: Int = 0
        set(value) {
            field = value
            analyzer.ppoSmoothing = value
        }

    @Volatile
    var releaseAlpha: Float = 0.22f
        set(value) {
            field = value
            analyzer.releaseAlpha = value
        }

    fun getSmoothedLinear(): FloatArray? = analyzer.getSmoothedLinear()

    // ── Zoom-aware frequency mapping ────────────────────────────────────
    @Volatile
    private var visibleLoHz = 20f
    @Volatile
    private var visibleHiHz = 20000f

    fun setVisibleFrequencyRange(loHz: Float, hiHz: Float) {
        val lo = loHz.coerceIn(MIN_VISIBLE_HZ, MAX_VISIBLE_HZ)
        val hi = hiHz.coerceIn(lo + 0.01f, MAX_VISIBLE_HZ)
        visibleLoHz = lo
        visibleHiHz = hi
        if (manualFftSize == null) {
            // Zoom-adaptive analysis window: more bins for a narrower span
            analyzer.setZoomSpan(hi / lo)
        } else {
            analyzer.clearZoomSpan()
        }
    }

    // ── Target curve ghost + delta ──────────────────────────────────────
    @Volatile
    var targetCurve: FreqResponse? = null
        set(value) {
            field = value
            autoAlignOffsetDb = 0f
        }

    @Volatile
    var targetLabel: String? = null

    @Volatile
    var showTargetGhost = false

    @Volatile
    var showTargetDelta = false

    /** Manual user offset in dB added to the target curve before drawing. */
    @Volatile
    var targetOffsetDb = 0f

    /** When true the renderer keeps aligning the ghost to the live spectrum at the median. */
    @Volatile
    var targetAutoAlign = true

    @Volatile
    private var autoAlignOffsetDb = 0f

    private val alignProbeFreqs: FloatArray by lazy {
        FreqResponseParser.logSpace(33, 20f, 20000f)
    }

    // ── Colors / drawing objects ────────────────────────────────────────
    fun setSpectrumColor(color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        inputStrokePaint.color = Color.argb(70, r, g, b)
        outputStrokePaint.color = Color.argb(100, r, g, b)
        spectrumR = r; spectrumG = g; spectrumB = b
    }
    private var spectrumR = 180; private var spectrumG = 180; private var spectrumB = 180

    // MBC gain computer — computes per-band GR from actual spectrum levels each frame
    var mbcGainComputer: MbcGainComputer? = null
    var mbcBandSettings: Array<MbcGainComputer.BandSettings>? = null

    // Opacity for fade-out when music stops (1.0 = fully visible)
    @Volatile
    var opacity = 1f
        private set

    // Reusable drawing objects — input spectrum
    private val inputFillPath = Path()
    private val inputStrokePath = Path()

    // Reusable drawing objects — output spectrum
    private val outputFillPath = Path()
    private val outputStrokePath = Path()

    // Reusable drawing objects — target ghost + delta
    private val ghostPath = Path()
    private val deltaAbovePath = Path()
    private val deltaBelowPath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Ghost overlay (target curve) — dashed translucent blue line. */
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        color = 0x662196F3
        pathEffect = DashPathEffect(floatArrayOf(10f, 12f), 0f)
    }

    /** Delta fill: live ABOVE target (louder) = green; BELOW = red. */
    private val deltaPaintAbove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x30219A81
    }

    private val deltaPaintBelow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x30E53935
    }

    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(70, 180, 180, 180)
    }

    private val outputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(100, 220, 220, 220)
    }

    /**
     * Called from Visualizer callback with RAW WAVEFORM bytes and the real
     * capture sampling rate.
     */
    fun updateWaveformData(waveform: ByteArray, samplingRate: Int? = null) {
        if (samplingRate != null) setSampleRate(samplingRate)
        opacity = 1f  // reset fade on real audio
        analyzer.appendWaveform(waveform)
    }

    /** Feed zeros into the EMA so it decays naturally toward silence */
    fun feedSilence() {
        analyzer.decay(0.92f)
    }

    /** Restore opacity to full (called when playback resumes) */
    fun resetOpacity() { opacity = 1f }

    /** Reduce opacity by [amount] per call. At 0, draw() returns early. */
    fun fadeOut(amount: Float) {
        opacity = (opacity - amount).coerceAtLeast(0f)
        if (opacity <= 0f) {
            analyzer.releaseAll()
        }
    }

    // ── Tracker support ─────────────────────────────────────────────────
    /**
     * Spectrum dB (tilt-compensated) at [freqHz] for tracker marker
     * positioning. Null when no analysis data yet.
     */
    fun spectrumDbAt(freqHz: Float): Float? {
        val linear = analyzer.getSmoothedLinear() ?: return null
        val n = linear.size
        if (n < 3) return null
        val exactBin = freqHz / analyzer.binWidthHz
        val lower = exactBin.toInt().coerceIn(1, n - 2)
        val frac = (exactBin - lower).coerceIn(0f, 1f)
        val mag = linear[lower] * (1f - frac) + linear[lower + 1] * frac
        if (mag <= 1e-10f) return null
        val db = 20f * log10(mag)
        val tiltDb = 3.0f * (log10(freqHz / 1000f) / 0.30103f)
        return db + tiltDb
    }

    /**
     * Dominant frequency in [minHz, maxHz] above [minDb] (tilt-compensated
     * display convention). The raw search+threshold happens pre-tilt, so the
     * caller threshold is shifted by the tilt at the band midpoint.
     */
    fun findDominantPeak(minHz: Float, maxHz: Float, minDb: Float = -60f): LongWindowAnalyzer.Peak? {
        val midTilt = 3.0f * (log10(((minHz + maxHz) / 2f) / 1000f) / 0.30103f)
        return analyzer.findDominantPeak(minHz, maxHz, minDb - midTilt)
    }

    // ── Draw ────────────────────────────────────────────────────────────
    /**
     * @param eqResponseDb Optional EQ frequency response in dB per display pixel.
     *                     When provided, draws dual spectrum: dim input + bright output.
     *                     When null, draws single spectrum (input only).
     */
    fun draw(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        dbMin: Float, dbMax: Float,
        eqResponseDb: FloatArray? = null
    ) {
        val linear = analyzer.getSmoothedLinear() ?: return
        val n = linear.size
        if (n < 2) return
        if (opacity <= 0f) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidthHz = analyzer.binWidthHz

        // Pre-computed PPO-smoothed dB (from analyzer worker, when enabled)
        val ppoDb = analyzer.getPpoDb()

        fun dbOf(bin: Int): Float {
            val d = ppoDb
            if (d != null && d.size == n) return d[bin]
            val mag = linear[bin]
            return if (mag > 1e-10f) (20f * log10(mag)).coerceAtLeast(-96f) else -96f
        }

        // When MBC is active the gain math needs the full per-bin dB vector
        val mbcComputer = mbcGainComputer
        val mbcSettings = mbcBandSettings
        if (mbcComputer != null && mbcSettings != null) {
            val fullDb = FloatArray(n) { i -> dbOf(i) }
            mbcComputer.computeAllBandGains(fullDb, sampleRate, analyzer.effectiveSize, mbcSettings)
        }

        // Log frequency mapping over the ZOOMED visible range
        val visLo = visibleLoHz
        val visHi = visibleHiHz
        val logMin = log10(visLo)
        val logMax = log10(visHi)
        val logRange = logMax - logMin

        // Collect display points — one per pixel column
        val displayWidth = graphWidth.toInt().coerceAtLeast(1)
        val xArr = FloatArray(displayWidth)
        val inputY = FloatArray(displayWidth)
        val outputY = if (eqResponseDb != null || mbcComputer != null) FloatArray(displayWidth) else null

        for (x in 0 until displayWidth) {
            val logFreqLow = logMin + logRange * x / displayWidth
            val logFreqHigh = logMin + logRange * (x + 1) / displayWidth
            val freqLow = 10f.pow(logFreqLow)
            val freqHigh = 10f.pow(logFreqHigh)
            val freq = 10f.pow((logFreqLow + logFreqHigh) / 2f)

            val binLow = (freqLow / binWidthHz).toInt().coerceIn(1, n - 1)
            val binHigh = (freqHigh / binWidthHz).toInt().coerceIn(1, n - 1)

            val rawDb: Float
            if (binLow == binHigh) {
                val exactBin = freq / binWidthHz
                val lower = exactBin.toInt().coerceIn(1, n - 2)
                val frac = exactBin - lower
                rawDb = dbOf(lower) * (1f - frac) + dbOf(lower + 1) * frac
            } else {
                var peak = -96f
                for (b in binLow..binHigh.coerceAtMost(n - 1)) {
                    val v = dbOf(b)
                    if (v > peak) peak = v
                }
                rawDb = peak
            }

            // +3 dB/octave tilt compensation (pivot at 1 kHz)
            val tiltDb = 3.0f * (log10(freq / 1000f) / 0.30103f)
            val inputDb = rawDb + tiltDb

            val inputNorm = ((inputDb - dbMin) / dbRange).coerceIn(0f, 1f)
            xArr[x] = left + x
            inputY[x] = top + graphHeight * (1f - inputNorm)

            // Output = input + EQ response only (MBC shown on its own GR trace view)
            if (outputY != null) {
                var outputDb = inputDb
                if (eqResponseDb != null && x < eqResponseDb.size) outputDb += eqResponseDb[x]
                val outputNorm = ((outputDb - dbMin) / dbRange).coerceIn(0f, 1f)
                outputY[x] = top + graphHeight * (1f - outputNorm)
            }
        }

        val step = 2
        val lastIdx = displayWidth - 1

        // Clip to graph bounds + apply fade opacity
        val layerAlpha = (opacity * 255).toInt().coerceIn(0, 255)
        canvas.saveLayerAlpha(left, top, right, bottom, layerAlpha)
        canvas.clipRect(left, top, right, bottom)

        // ── Draw INPUT spectrum (background layer) ──
        inputFillPath.reset()
        inputStrokePath.reset()
        inputFillPath.moveTo(xArr[0], bottom)
        inputFillPath.lineTo(xArr[0], inputY[0])
        inputStrokePath.moveTo(xArr[0], inputY[0])
        var i = step
        while (i < displayWidth) {
            val midX = (xArr[i - step] + xArr[i]) / 2f
            inputFillPath.cubicTo(midX, inputY[i - step], midX, inputY[i], xArr[i], inputY[i])
            inputStrokePath.cubicTo(midX, inputY[i - step], midX, inputY[i], xArr[i], inputY[i])
            i += step
        }
        if (i - step < lastIdx) {
            inputFillPath.lineTo(xArr[lastIdx], inputY[lastIdx])
            inputStrokePath.lineTo(xArr[lastIdx], inputY[lastIdx])
        }
        inputFillPath.lineTo(xArr[lastIdx], bottom)
        inputFillPath.close()

        if (outputY != null) {
            // Dual mode: common overlap area = 80 alpha (main spectrum), difference areas
            // (boost/cut leftover) = 5 alpha. commonY uses LOWER of input/output (lower dB = higher Y).
            val commonY = FloatArray(displayWidth) { x ->
                maxOf(inputY[x], outputY[x])  // max Y = lower on screen = lower dB
            }

            // Draw input fill at 5 alpha (covers cut leftover areas)
            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(5, spectrumR, spectrumG, spectrumB), Color.argb(2, spectrumR / 2, spectrumG / 2, spectrumB / 2)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(inputFillPath, fillPaint)

            // Draw output fill at 5 alpha (covers boost areas above input)
            outputFillPath.reset()
            outputStrokePath.reset()
            outputFillPath.moveTo(xArr[0], bottom)
            outputFillPath.lineTo(xArr[0], outputY[0])
            outputStrokePath.moveTo(xArr[0], outputY[0])
            i = step
            while (i < displayWidth) {
                val midX = (xArr[i - step] + xArr[i]) / 2f
                outputFillPath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                outputStrokePath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                i += step
            }
            if (i - step < lastIdx) {
                outputFillPath.lineTo(xArr[lastIdx], outputY[lastIdx])
                outputStrokePath.lineTo(xArr[lastIdx], outputY[lastIdx])
            }
            outputFillPath.lineTo(xArr[lastIdx], bottom)
            outputFillPath.close()
            canvas.drawPath(outputFillPath, fillPaint)

            // Draw common area at 80 alpha (the bright main spectrum)
            val commonFillPath = Path()
            commonFillPath.moveTo(xArr[0], bottom)
            commonFillPath.lineTo(xArr[0], commonY[0])
            i = step
            while (i < displayWidth) {
                val midX = (xArr[i - step] + xArr[i]) / 2f
                commonFillPath.cubicTo(midX, commonY[i - step], midX, commonY[i], xArr[i], commonY[i])
                i += step
            }
            if (i - step < lastIdx) commonFillPath.lineTo(xArr[lastIdx], commonY[lastIdx])
            commonFillPath.lineTo(xArr[lastIdx], bottom)
            commonFillPath.close()

            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(80, spectrumR, spectrumG, spectrumB), Color.argb(15, spectrumR / 2, spectrumG / 2, spectrumB / 2)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(commonFillPath, fillPaint)

            // Draw outlines
            canvas.drawPath(inputStrokePath, inputStrokePaint)
            canvas.drawPath(outputStrokePath, outputStrokePaint)
        } else {
            // Single spectrum mode — normal brightness
            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(80, spectrumR, spectrumG, spectrumB), Color.argb(15, spectrumR / 2, spectrumG / 2, spectrumB / 2)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(inputFillPath, fillPaint)
            canvas.drawPath(inputStrokePath, inputStrokePaint)
        }

        // ── Target ghost + delta ──
        val fr = targetCurve
        if (fr != null && (showTargetGhost || showTargetDelta) && fr.frequencies.size >= 2) {
            updateAutoAlign(
                alignProbeFreqs.size,
                { col -> columnDbVis(col, inputY, top, graphHeight, dbMax, dbRange) },
                visLo, visHi, displayWidth
            ) { f -> targetDbAt(fr, f) }

            if (showTargetGhost || showTargetDelta) {
                val ghostY = FloatArray(displayWidth)
                for (x in 0 until displayWidth) {
                    val logFreq = logMin + logRange * (x + 0.5f) / displayWidth
                    val freq = 10f.pow(logFreq)
                    val targetDb = targetDbAt(fr, freq) + targetOffsetDb + autoAlignOffsetDb
                    val norm = ((targetDb - dbMin) / dbRange).coerceIn(0f, 1f)
                    ghostY[x] = top + graphHeight * (1f - norm)
                }

                if (showTargetGhost) {
                    ghostPath.reset()
                    ghostPath.moveTo(xArr[0], ghostY[0])
                    i = step
                    while (i < displayWidth) {
                        val midX = (xArr[i - step] + xArr[i]) / 2f
                        ghostPath.cubicTo(midX, ghostY[i - step], midX, ghostY[i], xArr[i], ghostY[i])
                        i += step
                    }
                    if (i - step < lastIdx) ghostPath.lineTo(xArr[lastIdx], ghostY[lastIdx])
                    canvas.drawPath(ghostPath, ghostPaint)
                }

                if (showTargetDelta) {
                    // Band between live and ghost, split by which is louder.
                    // Each contiguous above/below run is one closed subpath.
                    buildDeltaBands(deltaAbovePath, xArr, inputY, ghostY, above = true)
                    buildDeltaBands(deltaBelowPath, xArr, inputY, ghostY, above = false)
                    canvas.drawPath(deltaAbovePath, deltaPaintAbove)
                    canvas.drawPath(deltaBelowPath, deltaPaintBelow)
                }
            }
        }

        canvas.restore()
    }

    private fun columnDbVis(
        x: Int, inputY: FloatArray, top: Float, graphHeight: Float,
        dbMax: Float, dbRange: Float
    ): Float = dbMax - ((inputY[x] - top) / graphHeight).coerceIn(0f, 1f) * dbRange

    private fun buildDeltaBands(
        path: Path, xArr: FloatArray, inputY: FloatArray, ghostY: FloatArray, above: Boolean
    ) {
        path.reset()
        val w = xArr.size
        var x = 0
        while (x < w) {
            val inRun = if (above) inputY[x] < ghostY[x] else inputY[x] > ghostY[x]
            if (inRun) {
                // follow live curve, then return along ghost curve
                path.moveTo(xArr[x], inputY[x])
                var end = x
                while (end + 1 < w && (if (above) inputY[end + 1] < ghostY[end + 1] else inputY[end + 1] > ghostY[end + 1])) {
                    end++
                    path.lineTo(xArr[end], inputY[end])
                }
                for (xi in end downTo x) {
                    path.lineTo(xArr[xi], ghostY[xi])
                }
                path.close()
                x = end + 1
            } else {
                x++
            }
        }
    }

    private fun targetDbAt(fr: FreqResponse, freq: Float): Float {
        val fs = fr.frequencies
        val ls = fr.levels
        val m = fs.size
        if (m < 2) return ls[0]
        if (freq <= fs[0]) return ls[0]
        if (freq >= fs[m - 1]) return ls[m - 1]
        var lo = 0
        var hi = m - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (fs[mid] <= freq) lo = mid else hi = mid
        }
        val logF = ln(freq.toDouble())
        val logLo = ln(fs[lo].toDouble())
        val logHi = ln(fs[hi].toDouble())
        val t = ((logF - logLo) / (logHi - logLo)).coerceIn(0.0, 1.0)
        return (ls[lo] * (1.0 - t) + ls[hi] * t).toFloat()
    }

    /**
     * Rolling median of (live − target) over a sparse probe grid inside the
     * visible range — updates the auto-align offset with a slow EMA so the
     * ghost drifts gently to the live level instead of bouncing.
     */
    private fun updateAutoAlign(
        probeCount: Int,
        liveDbAt: (Int) -> Float,
        visLo: Float, visHi: Float,
        displayWidth: Int,
        targetDbOf: (Float) -> Float
    ) {
        if (!showTargetDelta || !targetAutoAlign) return
        if (probeCount <= 6) return
        val probes = alignProbeFreqs
        val logLo = ln(visLo.toDouble())
        val logHi = ln(visHi.toDouble())
        val logSpan = logHi - logLo
        val diffs = FloatArray(probeCount)
        var idx = 0
        for (p in 0 until probeCount) {
            val f = probes[p]
            if (f < visLo || f > visHi) continue
            val colFloat = ((ln(f.toDouble()) - logLo) / logSpan * displayWidth).toFloat()
            val col = colFloat.toInt().coerceIn(0, displayWidth - 1)
            val liveDb = liveDbAt(col)
            if (liveDb <= -95f) continue   // silent bin — skip
            diffs[idx++] = liveDb - targetDbOf(f)
        }
        if (idx < 6) return
        val used = diffs.copyOf(idx)
        used.sort()
        val median = used[idx / 2]
        autoAlignOffsetDb += 0.06f * (median - autoAlignOffsetDb)
    }

    fun release() {
        analyzer.releaseAll()
        opacity = 0f
    }

    fun shutdownWorker() {
        analyzer.shutdown()
    }
}
