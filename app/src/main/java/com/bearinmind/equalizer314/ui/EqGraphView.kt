package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.SpectrumAnalyzer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Custom view for displaying and interacting with parametric equalizer
 * Allows both horizontal (frequency) and vertical (gain) dragging
 * Similar to Ableton's EQ Eight
 */
class EqGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var parametricEq: ParametricEqualizer? = null
    private val bandPoints = mutableListOf<BandPoint>()
    private var activeBandIndex: Int? = null

    // EQ UI mode
    var eqUiMode: EqUiMode = EqUiMode.PARAMETRIC
        set(value) {
            field = value
            invalidate()
        }

    // Show dashed tanh saturation curve
    var showSaturationCurve = true

    private var spectrumAnalyzer: SpectrumAnalyzer? = null
    private var spectrumData: FloatArray? = null
    private var spectrumEnabled = true

    // DP band visualization
    private var dpCenterFrequencies: FloatArray? = null
    private var dpGains: FloatArray? = null
    var showDpBands = false

    private val dpCurveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA555555.toInt()  // dark grey solid line
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val dpBandDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()  // dark grey dots
        style = Paint.Style.FILL
    }

    // Graphic mode paints
    private val graphicBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88AAAAAA.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val graphicConnectLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // Double-click detection
    private var lastTapTime = 0L
    private var lastTapBandIndex: Int? = null
    private val doubleTapTimeout = 300L
    private var justResetBand = false

    // Drag threshold
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private val dragThreshold = 8f

    // Long-press detection for empty space
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val longPressTimeout = 500L

    // Paint objects
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val zeroDB_LinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()  // grey curve
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pointBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()  // matches graph background to mask grid lines
        style = Paint.Style.FILL
    }

    private val pointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()  // grey ring
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val activePointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()  // lighter grey when active
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val activePointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt()  // grey fill when active
        style = Paint.Style.FILL
    }

    private val disabledPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()  // dim grey for disabled bands
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val disabledPointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val pointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val activePointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()  // black text when active
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 24f
    }

    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 22f
    }

    private val saturatedCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF9800.toInt() // semi-transparent orange
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val spectrumPath = Path()
    private val spectrumFillPath = Path()

    private val graphMinFreq = 10f
    private val graphMaxFreq = 20000f

    private val minGain = -12f
    private val maxGain = 12f

    var onBandChangedListener: ((bandIndex: Int, frequency: Float, gain: Float) -> Unit)? = null
    var onBandSelectedListener: ((bandIndex: Int?) -> Unit)? = null
    var onLongPressListener: (() -> Unit)? = null
    // Slot labels for band dots (e.g., [0, 2, 4, 6] → display as "1", "3", "5", "7")
    private var bandSlotLabels: List<Int>? = null

    fun setBandSlotLabels(slots: List<Int>) {
        bandSlotLabels = slots
        invalidate()
    }

    private fun getBandLabel(index: Int): String {
        val slots = bandSlotLabels
        return if (slots != null && index < slots.size) {
            (slots[index] + 1).toString()
        } else {
            (index + 1).toString()
        }
    }

    data class BandPoint(
        val bandIndex: Int,
        var frequency: Float,
        var gain: Float,
        var x: Float = 0f,
        var y: Float = 0f
    )

    fun setParametricEqualizer(eq: ParametricEqualizer) {
        parametricEq = eq
        bandPoints.clear()

        val bands = eq.getAllBands()
        for (i in bands.indices) {
            bandPoints.add(BandPoint(i, bands[i].frequency, bands[i].gain))
        }

        invalidate()
    }

    fun setSpectrumAnalyzer(analyzer: SpectrumAnalyzer) {
        spectrumAnalyzer = analyzer

        analyzer.addSpectrumUpdateListener { spectrum ->
            spectrumData = spectrum
            postInvalidate()
        }
    }

    fun setSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
        invalidate()
    }

    fun isSpectrumEnabled(): Boolean = spectrumEnabled

    fun updateDpBandData(centerFreqs: FloatArray, gains: FloatArray) {
        dpCenterFrequencies = centerFreqs
        dpGains = gains
        invalidate()
    }

    fun updateBandLevels() {
        parametricEq?.let { eq ->
            val bands = eq.getAllBands()
            for (i in bandPoints.indices) {
                if (i < bands.size) {
                    bandPoints[i].frequency = bands[i].frequency
                    bandPoints[i].gain = bands[i].gain
                }
            }
            invalidate()
        }
    }

    fun setFilterType(bandIndex: Int, filterType: BiquadFilter.FilterType) {
        if (bandIndex in bandPoints.indices) {
            val point = bandPoints[bandIndex]
            val currentQ = parametricEq?.getBand(bandIndex)?.q ?: 0.707
            // LP/HP filters don't use gain — reset to 0 when switching to them
            val gain = if (filterType == BiquadFilter.FilterType.LOW_PASS || filterType == BiquadFilter.FilterType.HIGH_PASS) {
                point.gain = 0f
                0f
            } else {
                point.gain
            }
            parametricEq?.updateBand(bandIndex, point.frequency, gain, filterType, currentQ)
            invalidate()
        }
    }

    fun setQ(bandIndex: Int, q: Double) {
        if (bandIndex in bandPoints.indices) {
            val point = bandPoints[bandIndex]
            val currentFilterType = parametricEq?.getBand(bandIndex)?.filterType ?: BiquadFilter.FilterType.BELL
            parametricEq?.updateBand(bandIndex, point.frequency, point.gain, currentFilterType, q)
            invalidate()
        }
    }

    fun getActiveBandIndex(): Int? = activeBandIndex

    fun setActiveBand(index: Int) {
        activeBandIndex = index
        invalidate()
    }

    fun clearActiveBand() {
        activeBandIndex = null
        onBandSelectedListener?.invoke(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (bandPoints.isEmpty()) {
            val text = "Parametric EQ not initialized"
            canvas.drawText(text, width / 2f - textPaint.measureText(text) / 2f, height / 2f, textPaint)
            return
        }

        val vPad = 80f
        val graphWidth = width.toFloat()
        val graphHeight = height - 2 * vPad

        canvas.drawColor(0xFF1E1E1E.toInt())

        drawGrid(canvas, vPad, graphWidth, graphHeight)

        if (spectrumEnabled) {
            drawSpectrum(canvas, vPad, graphWidth, graphHeight)
        }

        parametricEq?.let { eq ->
            val bands = eq.getAllBands()
            for (i in bandPoints.indices) {
                if (i < bands.size) {
                    // Don't overwrite the point being actively dragged
                    if (isDragging && i == activeBandIndex) continue
                    bandPoints[i].frequency = bands[i].frequency
                    bandPoints[i].gain = bands[i].gain
                }
            }
        }

        // Draw DP band sample points (behind curve and dots)
        if (showDpBands) {
            drawDpBands(canvas, vPad, graphWidth, graphHeight)
        }

        calculatePointPositions(vPad, graphWidth, graphHeight)
        drawCurve(canvas, vPad, graphWidth, graphHeight)

        drawPoints(canvas)

        activeBandIndex?.let { index ->
            if (index < bandPoints.size) {
                drawActivePointLabel(canvas, bandPoints[index])
            }
        }
    }

    private fun drawGrid(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val dbSteps = 8
        for (i in 0..dbSteps) {
            val y = vPad + (graphHeight * i / dbSteps)
            val db = maxGain - (maxGain - minGain) * i / dbSteps

            if (db == 0f) {
                canvas.drawLine(0f, y, width.toFloat(), y, zeroDB_LinePaint)
            } else {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }

            if (i % 2 == 0) {
                val dbLabel = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
                canvas.drawText(dbLabel, 10f, y + 8f, textPaint)
            }
        }

        val freqMarkers = listOf(100f, 1000f, 10000f)
        for (freq in freqMarkers) {
            if (freq >= graphMinFreq && freq <= graphMaxFreq) {
                val x = freqToX(freq, graphWidth)
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)

                val freqLabel = when {
                    freq >= 1000f -> "${(freq / 1000).toInt()}k"
                    else -> "${freq.toInt()}"
                }
                val labelWidth = textPaint.measureText(freqLabel)
                canvas.drawText(freqLabel, x - labelWidth / 2f, vPad + graphHeight + 30f, textPaint)
            }
        }

        // Draw graph border
        canvas.drawLine(0f, 0f, 0f, height.toFloat(), gridPaint)                         // left edge
        canvas.drawLine(graphWidth, 0f, graphWidth, height.toFloat(), gridPaint)           // right edge
        canvas.drawLine(0f, vPad, graphWidth, vPad, gridPaint)                             // top edge
        canvas.drawLine(0f, vPad + graphHeight, graphWidth, vPad + graphHeight, gridPaint) // bottom edge
    }

    private var cachedSpectrumHash = 0
    private var cachedNormalizedSpectrum: FloatArray? = null

    private fun drawSpectrum(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val spectrum = spectrumData ?: return
        val analyzer = spectrumAnalyzer ?: return

        if (spectrum.isEmpty()) return

        spectrumPath.reset()
        spectrumFillPath.reset()

        val spectrumHash = spectrum.contentHashCode()
        val normalizedSpectrum = if (spectrumHash == cachedSpectrumHash && cachedNormalizedSpectrum != null) {
            cachedNormalizedSpectrum!!
        } else {
            val smoothedSpectrum = analyzer.smoothSpectrum(spectrum, windowSize = 3)
            val normalized = analyzer.normalizeSpectrum(smoothedSpectrum, minDb = -90f, maxDb = 0f)
            cachedNormalizedSpectrum = normalized
            cachedSpectrumHash = spectrumHash
            normalized
        }

        var pathStarted = false
        var lastX = 0f
        val bottomY = vPad + graphHeight

        fun getMagnitudeAtFreq(targetFreq: Float): Float {
            val sampleRate = 44100f
            val fftLen = 2048
            val binWidth = sampleRate / fftLen

            val binIndexFloat = targetFreq / binWidth
            val lowerBin = binIndexFloat.toInt().coerceIn(0, spectrum.size - 1)
            val upperBin = (lowerBin + 1).coerceIn(0, spectrum.size - 1)

            var magnitude: Float

            if (lowerBin == upperBin || upperBin >= spectrum.size) {
                magnitude = normalizedSpectrum[lowerBin]
            } else {
                val lowerFreq = lowerBin * binWidth
                val upperFreq = upperBin * binWidth
                val ratio = (targetFreq - lowerFreq) / (upperFreq - lowerFreq)
                magnitude = normalizedSpectrum[lowerBin] + ratio * (normalizedSpectrum[upperBin] - normalizedSpectrum[lowerBin])
            }

            parametricEq?.let { eq ->
                val eqResponse = eq.getFrequencyResponse(targetFreq)
                val spectrumDb = -90f + magnitude * 90f
                val adjustedDb = spectrumDb + eqResponse
                magnitude = ((adjustedDb + 90f) / 90f).coerceIn(0f, 1f)
            }

            return magnitude
        }

        val leftEdgeX = 0f
        val leftEdgeMag = getMagnitudeAtFreq(graphMinFreq)
        val leftEdgeY = vPad + graphHeight * (1f - leftEdgeMag)

        spectrumPath.moveTo(leftEdgeX, leftEdgeY)
        spectrumFillPath.moveTo(leftEdgeX, bottomY)
        spectrumFillPath.lineTo(leftEdgeX, leftEdgeY)
        pathStarted = true
        lastX = leftEdgeX

        val numBins = spectrum.size
        val hasEQ = parametricEq != null
        val eq = parametricEq

        var i = 0
        while (i < numBins) {
            val freq = analyzer.getBinFrequency(i)

            if (freq < graphMinFreq) { i++; continue }
            if (freq > graphMaxFreq) break

            val x = freqToX(freq, graphWidth)

            var magnitude = normalizedSpectrum[i]

            if (hasEQ) {
                val eqResponse = eq!!.getFrequencyResponse(freq)
                val spectrumDb = -90f + magnitude * 90f
                val adjustedDb = spectrumDb + eqResponse
                magnitude = ((adjustedDb + 90f) / 90f).coerceIn(0f, 1f)
            }

            val y = vPad + graphHeight * (1f - magnitude)

            spectrumPath.lineTo(x, y)
            spectrumFillPath.lineTo(x, y)
            lastX = x

            val skipAmount = when {
                freq < 500 -> 1
                freq < 2000 -> 2
                else -> 3
            }
            i += skipAmount
        }

        val rightEdgeX = graphWidth
        val rightEdgeMag = getMagnitudeAtFreq(graphMaxFreq)
        val rightEdgeY = vPad + graphHeight * (1f - rightEdgeMag)

        spectrumPath.lineTo(rightEdgeX, rightEdgeY)
        spectrumFillPath.lineTo(rightEdgeX, rightEdgeY)
        lastX = rightEdgeX

        if (pathStarted) {
            spectrumFillPath.lineTo(lastX, bottomY)
            spectrumFillPath.close()

            val gradient = LinearGradient(
                0f, vPad, 0f, bottomY,
                intArrayOf(0x80888888.toInt(), 0x40444444.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            spectrumFillPaint.shader = gradient
            canvas.drawPath(spectrumFillPath, spectrumFillPaint)
            canvas.drawPath(spectrumPath, spectrumLinePaint)
        }
    }

    private fun calculatePointPositions(vPad: Float, graphWidth: Float, graphHeight: Float) {
        for (point in bandPoints) {
            point.x = freqToX(point.frequency, graphWidth).coerceIn(23f, graphWidth - 23f)

            val filterType = parametricEq?.getBand(point.bandIndex)?.filterType
            if (filterType == BiquadFilter.FilterType.LOW_PASS || filterType == BiquadFilter.FilterType.HIGH_PASS) {
                // For LP/HP, Y position represents Q (top=high Q, bottom=low Q)
                val q = parametricEq?.getBand(point.bandIndex)?.q ?: 0.707
                val qNormalized = ((q - 0.1) / (12.0 - 0.1)).toFloat().coerceIn(0f, 1f)
                point.y = vPad + graphHeight * (1f - qNormalized)
            } else {
                val gainNormalized = (point.gain - minGain) / (maxGain - minGain)
                point.y = vPad + graphHeight * (1f - gainNormalized.coerceIn(0f, 1f))
            }
        }
    }

    private fun drawCurve(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val eq = parametricEq ?: return
        if (bandPoints.isEmpty()) return

        val path = Path()
        val saturatedPath = Path()
        val numSamples = 220
        var pathStarted = false
        var showSaturated = false

        // Graph spans full width: x=0 → 10 Hz, x=width → 22000 Hz
        // All frequencies within valid biquad range, no Nyquist issues
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)

        for (i in 0 until numSamples) {
            val x = graphWidth * i / (numSamples - 1)
            val logFreq = logMin + (x / graphWidth) * (logMax - logMin)
            val freq = 10f.pow(logFreq)

            val responsedB = eq.getFrequencyResponse(freq)
            val saturatedDb = eq.getFrequencyResponseWithSaturation(freq)

            if (responsedB.isNaN() || responsedB.isInfinite()) continue

            val gainNormalized = (responsedB - minGain) / (maxGain - minGain)
            val y = vPad + graphHeight * (1f - gainNormalized)

            val satGainNormalized = (saturatedDb - minGain) / (maxGain - minGain)
            val satY = if (saturatedDb.isNaN() || saturatedDb.isInfinite()) y
                else vPad + graphHeight * (1f - satGainNormalized)

            if (abs(responsedB - saturatedDb) > 0.5f) showSaturated = true

            if (!pathStarted) {
                path.moveTo(x, y)
                saturatedPath.moveTo(x, satY)
                pathStarted = true
            } else {
                path.lineTo(x, y)
                saturatedPath.lineTo(x, satY)
            }
        }

        canvas.drawPath(path, curvePaint)
        if (showSaturated && showSaturationCurve) {
            canvas.drawPath(saturatedPath, saturatedCurvePaint)
        }
    }

    private fun drawDpBands(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val centers = dpCenterFrequencies ?: return
        val gains = dpGains ?: return
        if (centers.size != gains.size || centers.isEmpty()) return

        // Build a path connecting all DP sample points with a dotted line
        val path = Path()
        var started = false

        for (i in centers.indices) {
            val freq = centers[i]
            if (freq < graphMinFreq || freq > graphMaxFreq) continue

            val x = freqToX(freq, graphWidth)
            val gainNorm = ((gains[i] - minGain) / (maxGain - minGain)).coerceIn(0f, 1f)
            val y = vPad + graphHeight * (1f - gainNorm)

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw the dotted line connecting all points
        canvas.drawPath(path, dpCurveLinePaint)
    }

    private fun drawGraphicBars(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        if (bandPoints.isEmpty()) return

        // 0dB Y position
        val zeroDbNorm = (0f - minGain) / (maxGain - minGain)
        val zeroDbY = vPad + graphHeight * (1f - zeroDbNorm)

        // Draw vertical bars from 0dB to each dot, and a connecting line
        val connectPath = Path()
        val sortedPoints = bandPoints.sortedBy { it.frequency }

        for ((idx, point) in sortedPoints.withIndex()) {
            // Vertical bar
            canvas.drawLine(point.x, zeroDbY, point.x, point.y, graphicBarPaint)

            // Connecting line
            if (idx == 0) {
                connectPath.moveTo(point.x, point.y)
            } else {
                connectPath.lineTo(point.x, point.y)
            }
        }

        canvas.drawPath(connectPath, graphicConnectLinePaint)
    }

    private fun drawPoints(canvas: Canvas) {
        for (i in bandPoints.indices) {
            val point = bandPoints[i]
            val isActive = i == activeBandIndex
            val bandEnabled = parametricEq?.getBand(i)?.enabled != false

            // Solid background fill to mask grid lines under all dots
            canvas.drawCircle(point.x, point.y, 20f, pointBgPaint)

            val bandNumber = getBandLabel(i)
            if (!bandEnabled) {
                canvas.drawCircle(point.x, point.y, 20f, disabledPointPaint)
                val textY = point.y + (disabledPointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, disabledPointNumberPaint)
            } else if (isActive) {
                canvas.drawCircle(point.x, point.y, 20f, activePointFillPaint)
                canvas.drawCircle(point.x, point.y, 20f, activePointRingPaint)
                val textY = point.y + (activePointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, activePointNumberPaint)
            } else {
                canvas.drawCircle(point.x, point.y, 20f, pointRingPaint)
                val textY = point.y + (pointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, pointNumberPaint)
            }
        }
    }


    private fun drawActivePointLabel(canvas: Canvas, point: BandPoint) {
        val currentFilterType = parametricEq?.getBand(point.bandIndex)?.filterType?.name ?: "BELL"
        val actualGain = parametricEq?.getBand(point.bandIndex)?.gain ?: point.gain
        val label = "Band ${getBandLabel(point.bandIndex)}: ${formatFrequency(point.frequency.toInt())} | ${String.format("%.1f dB", actualGain)} | $currentFilterType"

        val labelWidth = titleTextPaint.measureText(label)
        val labelX = (width - labelWidth) / 2f
        val labelY = 40f

        val bgPaint = Paint().apply {
            color = 0xDD000000.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(labelX - 10f, labelY - 30f, labelX + labelWidth + 10f, labelY + 10f, bgPaint)
        canvas.drawText(label, labelX, labelY, titleTextPaint)
    }

    private fun formatFrequency(hz: Int): String {
        return when {
            hz >= 1000 -> {
                val kHz = hz / 1000.0
                if (kHz >= 10) "${kHz.toInt()}k"
                else if (kHz % 1.0 == 0.0) "${kHz.toInt()}k"
                else String.format("%.1fk", kHz)
            }
            else -> "$hz"
        }
    }

    private fun freqToX(freq: Float, graphWidth: Float): Float {
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = log10(freq)
        return graphWidth * (logFreq - logMin) / (logMax - logMin)
    }

    private fun xToFreq(x: Float, graphWidth: Float): Float {
        val normalizedX = (x / graphWidth).coerceIn(0f, 1f)
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = logMin + normalizedX * (logMax - logMin)
        return 10f.pow(logFreq).coerceIn(graphMinFreq, graphMaxFreq)
    }

    private fun yToGain(y: Float, vPad: Float, graphHeight: Float): Float {
        val normalizedY = ((y - vPad) / graphHeight).coerceIn(0f, 1f)
        return maxGain - normalizedY * (maxGain - minGain)
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (eqUiMode == EqUiMode.TABLE) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y

                // Prevent ScrollView from intercepting while we wait for long-press
                parent?.requestDisallowInterceptTouchEvent(true)

                // Start long-press timer for ALL taps (cancelled if drag starts)
                cancelLongPressTimer()
                longPressRunnable = Runnable {
                    // Fire long press and clear active band so drag doesn't start after
                    activeBandIndex = null
                    isDragging = false
                    onLongPressListener?.invoke()
                }
                longPressHandler.postDelayed(longPressRunnable!!, longPressTimeout)

                activeBandIndex = findClosestPoint(event.x, event.y)
                if (activeBandIndex != null) {
                    val currentTime = System.currentTimeMillis()
                    if (activeBandIndex == lastTapBandIndex && currentTime - lastTapTime < doubleTapTimeout) {
                        cancelLongPressTimer()
                        resetBandToZero(activeBandIndex!!)
                        justResetBand = true
                        lastTapTime = 0L
                        lastTapBandIndex = null
                        activeBandIndex = null
                        invalidate()
                        return true
                    }

                    lastTapTime = currentTime
                    lastTapBandIndex = activeBandIndex
                    isDragging = false

                    parent?.requestDisallowInterceptTouchEvent(true)
                    onBandSelectedListener?.invoke(activeBandIndex)
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (justResetBand) return true

                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // Cancel long-press if finger moves too far
                if (distance > dragThreshold) {
                    cancelLongPressTimer()
                }

                activeBandIndex?.let {
                    if (!isDragging) {
                        if (distance < dragThreshold) return true
                        isDragging = true
                    }

                    parent?.requestDisallowInterceptTouchEvent(true)
                    updatePointPosition(event.x, event.y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                parent?.requestDisallowInterceptTouchEvent(false)
                justResetBand = false
                isDragging = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    // All 8 log-spaced positions for double-tap reset
    private val defaultFrequencies: List<Float> by lazy {
        val all = com.bearinmind.equalizer314.dsp.ParametricEqualizer.logSpacedFrequencies(8)
        all.toList()
    }

    private fun resetBandToZero(bandIndex: Int) {
        val point = bandPoints[bandIndex]
        val defaultFreq = if (bandIndex < defaultFrequencies.size) defaultFrequencies[bandIndex] else point.frequency
        point.frequency = defaultFreq
        point.gain = 0f

        parametricEq?.updateBand(bandIndex, defaultFreq, 0f, BiquadFilter.FilterType.BELL, 0.707)

        onBandChangedListener?.invoke(bandIndex, defaultFreq, 0f)
        onBandSelectedListener?.invoke(bandIndex)
    }

    private fun findClosestPoint(x: Float, y: Float): Int? {
        var closestIndex: Int? = null
        var minDistance = Float.MAX_VALUE

        for (i in bandPoints.indices) {
            val point = bandPoints[i]
            val distance = Math.hypot((x - point.x).toDouble(), (y - point.y).toDouble()).toFloat()

            if (distance < minDistance && distance < 100f) {
                minDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }

    private fun updatePointPosition(x: Float, y: Float) {
        activeBandIndex?.let { index ->
            val point = bandPoints[index]
            val vPad = 80f
            val graphWidth = width.toFloat()
            val graphHeight = height - 2 * vPad

            // In GRAPHIC mode, keep frequency fixed — only allow gain changes
            val newFreq = if (eqUiMode == EqUiMode.GRAPHIC) {
                point.frequency
            } else {
                xToFreq(x, graphWidth)
            }
            point.frequency = newFreq

            val currentFilterType = parametricEq?.getBand(index)?.filterType ?: BiquadFilter.FilterType.BELL
            val isLpHp = currentFilterType == BiquadFilter.FilterType.LOW_PASS || currentFilterType == BiquadFilter.FilterType.HIGH_PASS

            if (isLpHp) {
                // For LP/HP, Y-drag controls Q instead of gain
                val normalizedY = ((y - vPad) / graphHeight).coerceIn(0f, 1f)
                val newQ = (0.1 + (1f - normalizedY) * (12.0 - 0.1)).coerceIn(0.1, 12.0)
                parametricEq?.updateBand(index, newFreq, 0f, currentFilterType, newQ)
            } else {
                val newGain = yToGain(y, vPad, graphHeight)
                point.gain = newGain
                val currentQ = parametricEq?.getBand(index)?.q ?: 0.707
                parametricEq?.updateBand(index, newFreq, newGain, currentFilterType, currentQ)
            }
            onBandChangedListener?.invoke(index, newFreq, point.gain)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minWidth = 600
        val minHeight = 400
        val width = resolveSize(minWidth, widthMeasureSpec)
        val height = resolveSize(minHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }
}
