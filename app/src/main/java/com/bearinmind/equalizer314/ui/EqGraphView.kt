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

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var parametricEq: ParametricEqualizer? = null
    private val bandPoints = mutableListOf<BandPoint>()
    private var activeBandIndex: Int? = null

    // EQ UI mode
    var eqUiMode: EqUiMode = EqUiMode.PARAMETRIC
        set(value) {
            field = value
            invalidate()
        }

    // Show solid EQ frequency response curve (the main grey line)
    var showEqCurve = true
    // Show dashed tanh saturation curve
    var showSaturationCurve = true
    // Vertical padding (top/bottom) for labels — default 80f for 246dp graph.
    // Set lower for mini/scaled-down graphs (e.g. 40f for 123dp).
    var verticalPadding = 80f
    // Fill the area between the EQ curve and the 0dB line with a translucent color
    var showCurveFill = false
    // When true, band points are non-interactive (no dragging, no labels inside dots)
    // and drawn smaller — purely visual indicators.
    var readOnlyPoints = false

    private var spectrumAnalyzer: SpectrumAnalyzer? = null
    private var spectrumData: FloatArray? = null
    private var spectrumEnabled = true

    // DP band visualization
    private var dpCenterFrequencies: FloatArray? = null
    private var dpGains: FloatArray? = null
    var showDpBands = false
    var showBandPoints = true

    // MBC band visualization
    var mbcCrossovers: FloatArray? = null       // crossover frequencies (N-1 for N bands)
    var mbcBandColors: IntArray? = null          // one color per band (unused for now but kept)
    var mbcBandGains: FloatArray? = null         // per-band preGain in dB
    var mbcBandPostGains: FloatArray? = null    // per-band postGain in dB
    var mbcBandThresholds: FloatArray? = null   // per-band compressor threshold
    var mbcBandRatios: FloatArray? = null       // per-band compressor ratio
    var mbcBandKnees: FloatArray? = null        // per-band knee width
    var mbcBandGateThresholds: FloatArray? = null // per-band noise gate threshold
    var mbcBandExpanderRatios: FloatArray? = null // per-band expander ratio
    // RANGE FEATURE COMMENTED OUT — range data not displayed or drawn
    // var mbcBandRanges: FloatArray? = null        // per-band range in dB (negative, max gain reduction cap)
    var mbcSelectedBand: Int = 0
    var onMbcCrossoverChanged: ((crossoverIndex: Int, frequency: Float) -> Unit)? = null
    var onMbcBandGainChanged: ((bandIndex: Int, gain: Float) -> Unit)? = null
    // RANGE FEATURE COMMENTED OUT
    // var onMbcBandRangeChanged: ((bandIndex: Int, range: Float) -> Unit)? = null
    var onMbcBandSelected: ((bandIndex: Int) -> Unit)? = null
    private var mbcHaloAlpha = 0f
    private var mbcHaloAnimator: android.animation.ValueAnimator? = null
    private var mbcHaloType = 0  // 1=postGain, 2=range
    private var mbcHaloBand = -1
    private var draggingCrossover: Int = -1
    private var draggingMbcBand: Int = -1
    private var lastMbcTapTime = 0L
    private var lastMbcTapBand = -1
    var onMbcBandGainReset: ((bandIndex: Int) -> Unit)? = null
    // RANGE FEATURE COMMENTED OUT
    // private var draggingMbcRange: Int = -1

    private val mbcCrossoverLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAABBBBBB.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val mbcCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val mbcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val mbcDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val mbcDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // RANGE FEATURE COMMENTED OUT — range paint objects
    // private val mbcRangeCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    //     color = 0xAA999999.toInt()
    //     strokeWidth = 2f
    //     style = Paint.Style.STROKE
    //     pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    // }
    //
    // private val mbcRangeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    //     color = 0x30444444.toInt()
    //     style = Paint.Style.FILL
    // }
    //
    // private val mbcRangeDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    //     color = 0xFF777777.toInt()
    //     strokeWidth = 2f
    //     style = Paint.Style.STROKE
    // }

    private val mbcTriTouchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14AAAAAA.toInt()  // colorPrimary (#AAAAAA) at 8% alpha — exact Material3 Slider halo
        style = Paint.Style.FILL
    }

    private val dpCurveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA555555.toInt()  // dark grey solid line
        strokeWidth = 2f
        style = Paint.Style.STROKE
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

    var minGain = -20f
    var maxGain = 20f

    var onBandChangedListener: ((bandIndex: Int, frequency: Float, gain: Float) -> Unit)? = null
    var onBandSelectedListener: ((bandIndex: Int?) -> Unit)? = null
    var onLongPressListener: (() -> Unit)? = null
    // Slot labels for band dots (e.g., [0, 2, 4, 6] → display as "1", "3", "5", "7")
    private var bandSlotLabels: List<Int>? = null
    private var bandColorMap: Map<Int, Int>? = null

    fun setBandSlotLabels(slots: List<Int>) {
        bandSlotLabels = slots
        invalidate()
    }

    fun setBandColors(colors: Map<Int, Int>) {
        bandColorMap = colors
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

    /** Spectrum renderer — set by MainActivity when visualizer is active */
    var spectrumRenderer: com.bearinmind.equalizer314.audio.SpectrumAnalyzerRenderer? = null

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

        val vPad = verticalPadding
        val graphWidth = width.toFloat()
        val graphHeight = height - 2 * vPad

        canvas.drawColor(0xFF1E1E1E.toInt())

        drawGrid(canvas, vPad, graphWidth, graphHeight)

        // MBC band regions (behind everything else)
        if (mbcCrossovers != null) {
            drawMbcBands(canvas, vPad, graphWidth, graphHeight)
        }

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
        if (showEqCurve) drawCurve(canvas, vPad, graphWidth, graphHeight)

        if (showBandPoints) {
            drawPoints(canvas)

            activeBandIndex?.let { index ->
                if (index < bandPoints.size) {
                    drawActivePointLabel(canvas, bandPoints[index])
                }
            }
        }

        drawGridLabels(canvas, vPad, graphWidth, graphHeight)
    }

    private fun drawGrid(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val dbSteps = 4
        for (i in 0..dbSteps) {
            val y = vPad + (graphHeight * i / dbSteps)
            val db = maxGain - (maxGain - minGain) * i / dbSteps

            val dbLabel = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            val labelWidth = textPaint.measureText(dbLabel)
            val lineStartX = 10f + labelWidth + 6f

            canvas.drawLine(lineStartX, y, width.toFloat(), y, gridPaint)
        }

        val freqMarkers = listOf(100f, 1000f, 10000f)
        for (freq in freqMarkers) {
            if (freq >= graphMinFreq && freq <= graphMaxFreq) {
                val x = freqToX(freq, graphWidth)
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            }
        }

        // Draw graph border
        canvas.drawLine(0f, 0f, 0f, height.toFloat(), gridPaint)                         // left edge
        canvas.drawLine(graphWidth, 0f, graphWidth, height.toFloat(), gridPaint)           // right edge
        val topLabel = "+${maxGain.toInt()}"
        val topLabelEnd = 10f + textPaint.measureText(topLabel) + 6f
        canvas.drawLine(topLabelEnd, vPad, graphWidth, vPad, gridPaint)                   // top edge
        canvas.drawLine(0f, vPad + graphHeight, graphWidth, vPad + graphHeight, gridPaint) // bottom edge
    }

    private fun drawGridLabels(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 24f
        }

        val dbSteps = 4
        for (i in 0..dbSteps) {
            val y = vPad + (graphHeight * i / dbSteps)
            val db = maxGain - (maxGain - minGain) * i / dbSteps
            val dbLabel = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            val textY = if (i == 0) {
                val bounds = android.graphics.Rect()
                labelPaint.getTextBounds(dbLabel, 0, dbLabel.length, bounds)
                y - bounds.top.toFloat() - 3f
            } else y + 8f
            canvas.drawText(dbLabel, 10f, textY, labelPaint)
        }

        val freqMarkers = listOf(100f, 1000f, 10000f)
        for (freq in freqMarkers) {
            if (freq >= graphMinFreq && freq <= graphMaxFreq) {
                val x = freqToX(freq, graphWidth)
                val freqLabel = when {
                    freq >= 1000f -> "${(freq / 1000).toInt()}k"
                    else -> "${freq.toInt()}"
                }
                val labelWidth = labelPaint.measureText(freqLabel)
                canvas.drawText(freqLabel, x - labelWidth / 2f, vPad + graphHeight + 30f, labelPaint)
            }
        }
    }

    private var cachedSpectrumHash = 0
    private var cachedNormalizedSpectrum: FloatArray? = null

    private fun drawSpectrum(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        // Use SpectrumAnalyzerRenderer if available
        val renderer = spectrumRenderer
        if (renderer != null) {
            // Compute EQ frequency response per display pixel for dual spectrum
            val eq = parametricEq
            // EQ frequency response + MBC pre-gain combined
            val displayWidth = graphWidth.toInt().coerceAtLeast(1)
            val logMin = kotlin.math.log10(20f)
            val logMax = kotlin.math.log10(20000f)
            val logRange = logMax - logMin
            val crossovers = mbcCrossovers
            val gains = mbcBandGains

            val eqResponse: FloatArray? = if ((eq != null && eq.getBandCount() > 0) || (crossovers != null && gains != null)) {
                FloatArray(displayWidth) { x ->
                    val logFreqMid = logMin + logRange * (x + 0.5f) / displayWidth
                    val freq = 10f.toDouble().pow((logFreqMid).toDouble()).toFloat()
                    var db = 0f
                    // Add parametric EQ response
                    if (eq != null && eq.getBandCount() > 0) {
                        db += eq.getFrequencyResponse(freq)
                    }
                    // MBC pre-gain does NOT affect output spectrum — it only affects compressor sidechain
                    db
                }
            } else null
            renderer.draw(canvas, 0f, 0f, graphWidth, height.toFloat(),
                dbMin = -60f, dbMax = 0f, eqResponseDb = eqResponse)
            return
        }

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

    /** Draw smooth spectrum from Visualizer (pre-normalized 0..1 dB values) */
    /** Draw spectrum — magnitudes are pre-normalized 0..1 from VisualizerHelper */
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

        // Fill between curve and 0dB line
        if (showCurveFill && pathStarted) {
            val zeroY = vPad + graphHeight * (1f - (0f - minGain) / (maxGain - minGain))
            val fillPath = Path(path)
            fillPath.lineTo(graphWidth, zeroY)
            fillPath.lineTo(0f, zeroY)
            fillPath.close()
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x30AAAAAA.toInt()  // translucent grey fill
                style = Paint.Style.FILL
            }
            canvas.drawPath(fillPath, fillPaint)
        }

        // In MBC mode, draw EQ curve as dotted + dimmer
        if (mbcCrossovers != null) {
            val dottedPaint = Paint(curvePaint).apply {
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
                alpha = 50
            }
            canvas.drawPath(path, dottedPaint)
        } else {
            canvas.drawPath(path, curvePaint)
        }
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

    private val coloredRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val coloredFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val coloredNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun getBandColor(index: Int): Int? {
        val slots = bandSlotLabels ?: return null
        val colors = bandColorMap ?: return null
        if (index >= slots.size) return null
        return colors[slots[index]]
    }

    // ---- Linkwitz-Riley 4th-order crossover math (Giannoulis et al. / Linkwitz-Riley) ----
    // LR4 = two cascaded 2nd-order Butterworth filters
    // Lowpass amplitude:  |H_LP(f)| = 1 / (1 + (f/fc)^4)
    // Highpass amplitude: |H_HP(f)| = (f/fc)^4 / (1 + (f/fc)^4)
    // LP + HP = 1 (flat sum at all frequencies), -6 dB each at crossover

    private fun lr4LowpassAmplitude(f: Float, fc: Float): Float {
        val ratio = f / fc
        val r4 = ratio * ratio * ratio * ratio  // (f/fc)^4
        return 1f / (1f + r4)
    }

    private fun lr4HighpassAmplitude(f: Float, fc: Float): Float {
        val ratio = f / fc
        val r4 = ratio * ratio * ratio * ratio
        return r4 / (1f + r4)
    }

    /**
     * Compute the crossover filter amplitude for a given band at a given frequency.
     * Band 0 (lowest):  LP at crossover[0]
     * Band i (middle):  HP at crossover[i-1] × LP at crossover[i]
     * Band N-1 (highest): HP at crossover[N-2]
     */
    private fun mbcBandAmplitude(bandIndex: Int, freq: Float, crossovers: FloatArray): Float {
        val bandCount = crossovers.size + 1
        var amplitude = 1f
        // Highpass from the crossover below this band
        if (bandIndex > 0) {
            amplitude *= lr4HighpassAmplitude(freq, crossovers[bandIndex - 1])
        }
        // Lowpass from the crossover above this band
        if (bandIndex < bandCount - 1) {
            amplitude *= lr4LowpassAmplitude(freq, crossovers[bandIndex])
        }
        return amplitude
    }

    /**
     * Compute the MBC composite gain in dB at a given frequency.
     * Each band contributes: crossover_amplitude × band_gain_linear
     * Total is summed in linear domain, then converted back to dB.
     *
     * gain_dB(f) = 20 * log10( Σ_i [ bandAmplitude_i(f) × 10^(bandGain_i / 20) ] )
     */
    private fun mbcGainAtFreq(freq: Float, crossovers: FloatArray, gains: FloatArray): Float {
        val bandCount = crossovers.size + 1
        var totalLinear = 0f
        for (i in 0 until bandCount) {
            val amplitude = mbcBandAmplitude(i, freq, crossovers)
            val gain = gains.getOrElse(i) { 0f }
            val bandLinear = 10f.pow(gain / 20f)
            totalLinear += amplitude * bandLinear
        }
        return 20f * log10(totalLinear.coerceAtLeast(0.00001f))
    }

    // ---- Compressor static gain curve (Giannoulis/Massberg/Reiss 2012) ----
    // Soft knee gain computer:
    //   if x < (T - W/2):   gc = 0
    //   if (T-W/2) ≤ x ≤ (T+W/2):  gc = (1/R - 1) × (x - T + W/2)² / (2W)
    //   if x > (T + W/2):   gc = (1/R - 1) × (x - T)
    // Where T = threshold, R = ratio, W = knee width, x = input dB, gc = gain change dB

    private fun compressorGainDb(inputDb: Float, threshold: Float, ratio: Float, kneeWidth: Float): Float {
        if (ratio <= 1f) return 0f  // no compression
        return when {
            inputDb < (threshold - kneeWidth / 2f) -> 0f
            inputDb > (threshold + kneeWidth / 2f) -> (1f / ratio - 1f) * (inputDb - threshold)
            else -> {
                // Soft knee region
                val diff = inputDb - threshold + kneeWidth / 2f
                (1f / ratio - 1f) * diff * diff / (2f * kneeWidth)
            }
        }
    }

    // Expander/noise gate gain (downward expansion below noise gate threshold)
    // For signals below noiseGateThreshold, gain = expanderRatio × (input - noiseGateThreshold)
    // Expander/gate: attenuates signal below noise gate threshold.
    // GR is always negative (or zero) — pushes quiet signals further down.
    private fun expanderGainDb(inputDb: Float, noiseGateThreshold: Float, expanderRatio: Float): Float {
        if (inputDb >= noiseGateThreshold || expanderRatio <= 1f) return 0f
        // (expanderRatio - 1) is positive, (inputDb - NGT) is negative → result is negative ✓
        return (expanderRatio - 1f) * (inputDb - noiseGateThreshold)
    }

    private fun drawMbcBands(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val crossovers = mbcCrossovers ?: return
        val gains = mbcBandGains ?: return
        val bandCount = crossovers.size + 1
        if (gains.size < bandCount) return

        val zeroY = vPad + graphHeight * (1f - (0f - minGain) / (maxGain - minGain))

        // --- Draw MBC frequency response curve using LR4 crossover math ---
        val numSamples = (graphWidth / 2f).toInt().coerceAtLeast(100)

        val curvePath = Path()
        for (s in 0..numSamples) {
            val x = graphWidth * s.toFloat() / numSamples
            val freq = xToFreq(x, graphWidth)
            val gainDb = mbcGainAtFreq(freq, crossovers, gains).coerceIn(minGain, maxGain)
            val y = vPad + graphHeight * (1f - (gainDb - minGain) / (maxGain - minGain))
            if (s == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        canvas.drawPath(curvePath, mbcCurvePaint)

        // RANGE FEATURE COMMENTED OUT — range curve drawing section
        // val ranges = mbcBandRanges
        // if (ranges != null && ranges.size >= bandCount) {
        //     val rangeGains = FloatArray(bandCount) { gains[it] + ranges[it] }
        //
        //     val rangeCurvePath = Path()
        //     val rangeFillPath = Path()
        //     val postGainYs = FloatArray(numSamples + 1)
        //
        //     for (s in 0..numSamples) {
        //         val x = graphWidth * s.toFloat() / numSamples
        //         val freq = xToFreq(x, graphWidth)
        //         val postGainDb = mbcGainAtFreq(freq, crossovers, gains).coerceIn(minGain, maxGain)
        //         postGainYs[s] = vPad + graphHeight * (1f - (postGainDb - minGain) / (maxGain - minGain))
        //         val rangeDb = mbcGainAtFreq(freq, crossovers, rangeGains).coerceIn(minGain, maxGain)
        //         val ry = vPad + graphHeight * (1f - (rangeDb - minGain) / (maxGain - minGain))
        //
        //         if (s == 0) {
        //             rangeCurvePath.moveTo(x, ry)
        //             rangeFillPath.moveTo(x, postGainYs[s])
        //             rangeFillPath.lineTo(x, ry)
        //         } else {
        //             rangeCurvePath.lineTo(x, ry)
        //             rangeFillPath.lineTo(x, ry)
        //         }
        //     }
        //
        //     for (s in numSamples downTo 0) {
        //         val x = graphWidth * s.toFloat() / numSamples
        //         rangeFillPath.lineTo(x, postGainYs[s])
        //     }
        //     rangeFillPath.close()
        //
        //     // Per-band colored fills
        //     val bandColorArr = mbcBandColors
        //     for (b in 0 until bandCount) {
        //         val bColor = if (bandColorArr != null && b < bandColorArr.size && bandColorArr[b] != 0) bandColorArr[b] else null
        //         val leftFreq = if (b == 0) graphMinFreq else crossovers[b - 1]
        //         val rightFreq = if (b == bandCount - 1) graphMaxFreq else crossovers[b]
        //         val leftS = ((log10(leftFreq) - log10(graphMinFreq)) / (log10(graphMaxFreq) - log10(graphMinFreq)) * numSamples).toInt().coerceIn(0, numSamples)
        //         val rightS = ((log10(rightFreq) - log10(graphMinFreq)) / (log10(graphMaxFreq) - log10(graphMinFreq)) * numSamples).toInt().coerceIn(0, numSamples)
        //
        //         val bandFillPath = Path()
        //         bandFillPath.moveTo(graphWidth * leftS.toFloat() / numSamples, postGainYs[leftS])
        //         for (s in leftS..rightS) {
        //             bandFillPath.lineTo(graphWidth * s.toFloat() / numSamples, postGainYs[s])
        //         }
        //         for (s in rightS downTo leftS) {
        //             val x = graphWidth * s.toFloat() / numSamples
        //             val freq = xToFreq(x, graphWidth)
        //             val rangeDb = mbcGainAtFreq(freq, crossovers, rangeGains).coerceIn(minGain, maxGain)
        //             val ry = vPad + graphHeight * (1f - (rangeDb - minGain) / (maxGain - minGain))
        //             bandFillPath.lineTo(x, ry)
        //         }
        //         bandFillPath.close()
        //
        //         if (bColor != null) {
        //             val coloredFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        //                 color = bColor; style = Paint.Style.FILL; alpha = 40
        //             }
        //             canvas.drawPath(bandFillPath, coloredFill)
        //         } else {
        //             canvas.drawPath(bandFillPath, mbcRangeFillPaint)
        //         }
        //     }
        //     canvas.drawPath(rangeCurvePath, mbcRangeCurvePaint)
        // }

        // --- Draw crossover lines + drag ripple ---
        for (i in crossovers.indices) {
            val x = freqToX(crossovers[i], graphWidth)
            canvas.drawLine(x, vPad, x, vPad + graphHeight, mbcCrossoverLinePaint)

            // Ripple glow when dragging this crossover (full height of graph)
            if (i == draggingCrossover) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFBBBBBB.toInt(); alpha = 40; style = Paint.Style.FILL
                }
                val glowPad = 24f
                val cornerR = 10f
                val glowRect = android.graphics.RectF(x - glowPad, vPad, x + glowPad, vPad + graphHeight)
                canvas.drawRoundRect(glowRect, cornerR, cornerR, glowPaint)
            }
        }

        // --- Draw draggable triangles at band center frequencies ---
        // ▼ pointing down = postGain (output level)
        // ▲ pointing up = range (max gain reduction)
        val triPath = Path()

        for (i in 0 until bandCount) {
            val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
            val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
            val centerFreq = 10f.pow((log10(leftFreq) + log10(rightFreq)) / 2f)
            val dotX = freqToX(centerFreq, graphWidth)
            val dotY = vPad + graphHeight * (1f - (gains[i] - minGain) / (maxGain - minGain))

            val isSelected = (i == mbcSelectedBand)
            val isDraggingGain = (draggingMbcBand == i)
            // RANGE FEATURE COMMENTED OUT
            // val isDraggingRange = (draggingMbcRange == i)
            val r = 28f // triangle radius
            val cornerRadius = 8f

            // Check for band color
            val bandColors = mbcBandColors
            val bandColor = if (bandColors != null && i < bandColors.size && bandColors[i] != 0) bandColors[i] else null

            // PostGain ▼ — tip touches TOP of the postGain line, body extends above
            triPath.reset()
            triPath.moveTo(dotX, dotY)                          // tip ON the line
            triPath.lineTo(dotX - r * 0.866f, dotY - r * 1.5f) // top-left
            triPath.lineTo(dotX + r * 0.866f, dotY - r * 1.5f) // top-right
            triPath.close()

            // Rounded corners
            val roundedBgPaint = Paint(pointBgPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
            canvas.drawPath(triPath, roundedBgPaint)

            val showGainHalo = isDraggingGain || (mbcHaloAlpha > 0f && mbcHaloType == 1 && mbcHaloBand == i && draggingMbcBand < 0)
            if (showGainHalo) {
                val triCenterY = dotY - r * 1.0f
                val haloDp = 24f * resources.displayMetrics.density
                mbcTriTouchPaint.alpha = (mbcHaloAlpha * 0x38).toInt()
                canvas.drawCircle(dotX, triCenterY, haloDp, mbcTriTouchPaint)
            }

            if (bandColor != null) {
                if (isSelected) {
                    // Selected: fill + ring
                    val colorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = bandColor; style = Paint.Style.FILL; alpha = 200
                        pathEffect = CornerPathEffect(cornerRadius)
                    }
                    canvas.drawPath(triPath, colorFillPaint)
                }
                // Always draw color outline
                val colorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bandColor; style = Paint.Style.STROKE; strokeWidth = if (isSelected) 3f else 2f
                    pathEffect = CornerPathEffect(cornerRadius)
                }
                canvas.drawPath(triPath, colorRingPaint)
            } else if (isSelected) {
                val fillPaint = Paint(activePointFillPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                val ringPaint = Paint(activePointRingPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                canvas.drawPath(triPath, fillPaint)
                canvas.drawPath(triPath, ringPaint)
            } else {
                val ringPaint = Paint(pointRingPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                canvas.drawPath(triPath, ringPaint)
            }

            // RANGE FEATURE COMMENTED OUT — Range triangle drawing
            // if (ranges != null && ranges.size > i) {
            //     val rangeGainDb = (gains[i] + ranges[i]).coerceIn(minGain, maxGain)
            //     val rangeDotY = vPad + graphHeight * (1f - (rangeGainDb - minGain) / (maxGain - minGain))
            //
            //     triPath.reset()
            //     triPath.moveTo(dotX, rangeDotY)
            //     triPath.lineTo(dotX - r * 0.866f, rangeDotY + r * 1.5f)
            //     triPath.lineTo(dotX + r * 0.866f, rangeDotY + r * 1.5f)
            //     triPath.close()
            //
            //     canvas.drawPath(triPath, roundedBgPaint)
            //
            //     val showRangeHalo = isDraggingRange || (mbcHaloAlpha > 0f && mbcHaloType == 2 && mbcHaloBand == i && draggingMbcRange < 0)
            //     if (showRangeHalo) {
            //         val rangeTriCenterY = rangeDotY + r * 1.0f
            //         val haloDp = 24f * resources.displayMetrics.density
            //         mbcTriTouchPaint.alpha = (mbcHaloAlpha * 0x38).toInt()
            //         canvas.drawCircle(dotX, rangeTriCenterY, haloDp, mbcTriTouchPaint)
            //     }
            //
            //     if (bandColor != null) {
            //         val colorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            //             color = bandColor; style = Paint.Style.STROKE; strokeWidth = if (isSelected) 2.5f else 2f
            //             pathEffect = CornerPathEffect(cornerRadius)
            //         }
            //         canvas.drawPath(triPath, colorRingPaint)
            //     } else if (isSelected) {
            //         mbcRangeDotRingPaint.color = 0xFFAAAAAA.toInt()
            //         mbcRangeDotRingPaint.strokeWidth = 2.5f
            //     } else {
            //         mbcRangeDotRingPaint.color = 0xFF666666.toInt()
            //         mbcRangeDotRingPaint.strokeWidth = 2f
            //     }
            //     val rangeRingPaint = Paint(mbcRangeDotRingPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
            //     canvas.drawPath(triPath, rangeRingPaint)
            // }
        }
    }

    private fun drawPoints(canvas: Canvas) {
        // Read-only mode: small dots only, no labels, no interaction
        if (readOnlyPoints) {
            val smallRadius = 6f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFCCCCCC.toInt()
                style = Paint.Style.FILL
            }
            for (point in bandPoints) {
                canvas.drawCircle(point.x, point.y, smallRadius, dotPaint)
            }
            return
        }

        for (i in bandPoints.indices) {
            val point = bandPoints[i]
            val isActive = i == activeBandIndex
            val bandEnabled = parametricEq?.getBand(i)?.enabled != false
            val customColor = getBandColor(i)

            // In Table mode, don't draw disabled band dots on the graph
            if (!bandEnabled && eqUiMode == EqUiMode.TABLE) continue

            // Solid background fill to mask grid lines under all dots
            canvas.drawCircle(point.x, point.y, 20f, pointBgPaint)

            val bandNumber = getBandLabel(i)
            if (!bandEnabled) {
                canvas.drawCircle(point.x, point.y, 20f, disabledPointPaint)
                val textY = point.y + (disabledPointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, disabledPointNumberPaint)
            } else if (isActive) {
                if (customColor != null) {
                    coloredFillPaint.color = customColor
                    canvas.drawCircle(point.x, point.y, 20f, coloredFillPaint)
                    coloredRingPaint.color = customColor
                    coloredRingPaint.strokeWidth = 3f
                    canvas.drawCircle(point.x, point.y, 20f, coloredRingPaint)
                } else {
                    canvas.drawCircle(point.x, point.y, 20f, activePointFillPaint)
                    canvas.drawCircle(point.x, point.y, 20f, activePointRingPaint)
                }
                val textY = point.y + (activePointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, if (customColor != null) coloredNumberPaint else activePointNumberPaint)
            } else {
                if (customColor != null) {
                    coloredRingPaint.color = customColor
                    coloredRingPaint.strokeWidth = 2f
                    canvas.drawCircle(point.x, point.y, 20f, coloredRingPaint)
                } else {
                    canvas.drawCircle(point.x, point.y, 20f, pointRingPaint)
                }
                val textY = point.y + (pointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, pointNumberPaint)
            }
        }
    }


    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 20f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
        style = Paint.Style.FILL
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun drawActivePointLabel(canvas: Canvas, point: BandPoint) {
        val currentFilterType = parametricEq?.getBand(point.bandIndex)?.filterType?.name ?: "BELL"
        val actualGain = parametricEq?.getBand(point.bandIndex)?.gain ?: point.gain
        val label = "Band ${getBandLabel(point.bandIndex)}: ${formatFrequency(point.frequency.toInt())} | ${String.format("%.1f dB", actualGain)} | $currentFilterType"

        val labelWidth = labelPaint.measureText(label)
        val padH = 14f
        val padV = 8f
        val cornerRadius = 12f * resources.displayMetrics.density
        val labelX = (width - labelWidth) / 2f
        val labelY = 42f

        val rect = android.graphics.RectF(
            labelX - padH, labelY - 24f,
            labelX + labelWidth + padH, labelY + padV
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, labelBgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, labelStrokePaint)
        canvas.drawText(label, labelX, labelY, labelPaint)
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

    /** Find which MBC band a frequency belongs to based on crossover points */
    private fun getMbcBandForFreq(freq: Float, crossovers: FloatArray): Int {
        for (i in crossovers.indices) {
            if (freq < crossovers[i]) return i
        }
        return crossovers.size  // last band
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

    private fun animateMbcHaloIn() {
        mbcHaloAnimator?.cancel()
        mbcHaloAnimator = android.animation.ValueAnimator.ofFloat(mbcHaloAlpha, 1f).apply {
            duration = 100
            addUpdateListener { mbcHaloAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateMbcHaloOut() {
        mbcHaloAnimator?.cancel()
        mbcHaloAnimator = android.animation.ValueAnimator.ofFloat(mbcHaloAlpha, 0f).apply {
            duration = 200
            addUpdateListener { mbcHaloAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun handleMbcTouch(event: MotionEvent, crossovers: FloatArray): Boolean {
        val graphWidth = width.toFloat()
        val vPad = 80f
        val graphHeight = height - 2 * vPad
        val gains = mbcBandGains ?: return true
        val bandCount = crossovers.size + 1
        val hitRadius = 50f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                draggingCrossover = -1
                draggingMbcBand = -1
                // RANGE FEATURE COMMENTED OUT
                // draggingMbcRange = -1

                // Find the closest dot (postGain) across all bands
                var bestDist = hitRadius
                var bestType = 0 // 0=none, 1=postGain
                var bestBand = -1
                // RANGE FEATURE COMMENTED OUT
                // val ranges = mbcBandRanges

                for (i in 0 until bandCount) {
                    val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
                    val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
                    val centerFreq = 10f.pow((log10(leftFreq) + log10(rightFreq)) / 2f)
                    val dotX = freqToX(centerFreq, graphWidth)

                    // PostGain ▼ — triangle body is ABOVE the line, center is offset up
                    val dotY = vPad + graphHeight * (1f - (gains[i] - minGain) / (maxGain - minGain))
                    val triCenterY = dotY - 28f * 0.75f // center of triangle body above line
                    val distGain = Math.sqrt(((event.x - dotX) * (event.x - dotX) + (event.y - triCenterY) * (event.y - triCenterY)).toDouble()).toFloat()
                    if (distGain < bestDist) {
                        bestDist = distGain; bestType = 1; bestBand = i
                    }

                    // RANGE FEATURE COMMENTED OUT — range touch hit detection
                    // if (ranges != null && i < ranges.size) {
                    //     val rangeGainDb = (gains[i] + ranges[i]).coerceIn(minGain, maxGain)
                    //     val rangeDotY = vPad + graphHeight * (1f - (rangeGainDb - minGain) / (maxGain - minGain))
                    //     val rangeTriCenterY = rangeDotY + 28f * 0.75f
                    //     val distRange = Math.sqrt(((event.x - dotX) * (event.x - dotX) + (event.y - rangeTriCenterY) * (event.y - rangeTriCenterY)).toDouble()).toFloat()
                    //     if (distRange < bestDist) {
                    //         bestDist = distRange; bestType = 2; bestBand = i
                    //     }
                    // }
                }

                if (bestBand >= 0 && bestType > 0) {
                    // Double-tap detection — reset gain to 0 dB
                    val now = System.currentTimeMillis()
                    if (bestType == 1 && bestBand == lastMbcTapBand && now - lastMbcTapTime < 300L) {
                        // Double-tap on postGain triangle — reset to 0
                        gains[bestBand] = 0f
                        onMbcBandGainChanged?.invoke(bestBand, 0f)
                        onMbcBandGainReset?.invoke(bestBand)
                        lastMbcTapTime = 0L
                        lastMbcTapBand = -1
                        invalidate()
                        return true
                    }
                    lastMbcTapTime = now
                    lastMbcTapBand = bestBand

                    if (bestType == 1) draggingMbcBand = bestBand
                    // RANGE FEATURE COMMENTED OUT
                    // else draggingMbcRange = bestBand
                    mbcSelectedBand = bestBand
                    mbcHaloType = bestType
                    mbcHaloBand = bestBand
                    onMbcBandSelected?.invoke(bestBand)
                    animateMbcHaloIn()
                    invalidate()
                    return true
                }

                // Check if touching near a crossover line
                for (i in crossovers.indices) {
                    val lineX = freqToX(crossovers[i], graphWidth)
                    if (abs(event.x - lineX) < hitRadius) {
                        draggingCrossover = i
                        return true
                    }
                }

                // Tap on a band region — select it
                val tappedFreq = xToFreq(event.x, graphWidth)
                for (i in 0 until bandCount) {
                    val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
                    val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
                    if (tappedFreq in leftFreq..rightFreq) {
                        mbcSelectedBand = i
                        onMbcBandSelected?.invoke(i)
                        invalidate()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingMbcBand >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val newGain = yToGain(event.y, vPad, graphHeight).coerceIn(minGain, maxGain)
                    gains[draggingMbcBand] = newGain
                    onMbcBandGainChanged?.invoke(draggingMbcBand, newGain)
                    invalidate()
                    return true
                }
                // RANGE FEATURE COMMENTED OUT — range drag handling
                // if (draggingMbcRange >= 0) {
                //     parent?.requestDisallowInterceptTouchEvent(true)
                //     val ranges = mbcBandRanges ?: return true
                //     val newAbsGain = yToGain(event.y, vPad, graphHeight)
                //     val newRange = (newAbsGain - gains[draggingMbcRange]).coerceIn(-60f, 0f)
                //     ranges[draggingMbcRange] = newRange
                //     onMbcBandRangeChanged?.invoke(draggingMbcRange, newRange)
                //     invalidate()
                //     return true
                // }
                if (draggingCrossover >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val newFreq = xToFreq(event.x, graphWidth)
                    val minFreq = if (draggingCrossover == 0) 30f
                                  else crossovers[draggingCrossover - 1] * 1.2f
                    val maxFreq = if (draggingCrossover == crossovers.size - 1) 18000f
                                  else crossovers[draggingCrossover + 1] / 1.2f
                    crossovers[draggingCrossover] = newFreq.coerceIn(minFreq, maxFreq)
                    onMbcCrossoverChanged?.invoke(draggingCrossover, crossovers[draggingCrossover])
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // RANGE FEATURE COMMENTED OUT — draggingMbcRange references removed
                if (draggingMbcBand >= 0) animateMbcHaloOut()
                draggingCrossover = -1
                draggingMbcBand = -1
                // draggingMbcRange = -1
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnlyPoints) return false  // non-interactive visual only
        // MBC crossover touch handling
        val crossovers = mbcCrossovers
        if (crossovers != null && mbcBandGains != null) {
            return handleMbcTouch(event, crossovers)
        }

        if (eqUiMode == EqUiMode.TABLE || !showBandPoints) {
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

                val tapped = findClosestPoint(event.x, event.y)
                if (tapped != null) activeBandIndex = tapped
                if (tapped != null) {
                    val currentTime = System.currentTimeMillis()
                    if (activeBandIndex == lastTapBandIndex && currentTime - lastTapTime < doubleTapTimeout) {
                        cancelLongPressTimer()
                        resetBandToZero(activeBandIndex!!)
                        justResetBand = true
                        lastTapTime = 0L
                        lastTapBandIndex = null
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

    // All 16 log-spaced positions for double-tap reset (matches ParametricEqualizer defaults)
    private val defaultFrequencies: List<Float> by lazy {
        val all = com.bearinmind.equalizer314.dsp.ParametricEqualizer.logSpacedFrequencies(16)
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
