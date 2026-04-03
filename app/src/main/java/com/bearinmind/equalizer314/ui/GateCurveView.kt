package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Noise gate / expander transfer function graph.
 * Exact same structure as CompressorCurveView, just with gate math.
 */
class GateCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    var gateThreshold: Float = -90f
        set(value) { field = value; invalidate() }
    var expanderRatio: Float = 1f
        set(value) { field = value.coerceIn(1f, 50f); invalidate() }

    // Compressor reference (for showing dulled dot)
    var compressorThreshold: Float = -12f
        set(value) { field = value; invalidate() }

    var selectedBand: Int = 0
        set(value) { field = value; invalidate() }

    var onGateThresholdChanged: ((Float) -> Unit)? = null
    var onExpanderRatioChanged: ((Float) -> Unit)? = null

    private val minDb = -60f
    private val maxDb = 20f

    private var draggingThreshold = false
    private var draggingRatio = false
    private var ratioTouchInputDb = 0f

    private var lastTapTime = 0L
    private var lastTapType = 0  // 1=threshold, 2=ratio
    private val doubleTapTimeout = 300L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasDragged = false
    private var justReset = false
    private var pendingDragType = 0

    companion object {
        const val DEFAULT_GATE_THRESHOLD = -60f
        const val DEFAULT_EXPANDER_RATIO = 1f
    }

    // Paints — identical to CompressorCurveView
    private val bgPaint = Paint().apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt(); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val threshLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAABBBBBB.toInt(); strokeWidth = 1.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val dotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 24f
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38AAAAAA.toInt(); style = Paint.Style.FILL
    }
    private val dotNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    // Same padding as CompressorCurveView
    private val gPad = 4f

    private fun dbToX(db: Float): Float =
        gPad + (width - 2 * gPad) * (db - minDb) / (maxDb - minDb)

    private fun dbToY(db: Float): Float =
        gPad + (height - 2 * gPad) * (1f - (db - minDb) / (maxDb - minDb))

    private fun xToDb(x: Float): Float =
        minDb + (x - gPad) / (width - 2 * gPad) * (maxDb - minDb)

    private fun yToDb(y: Float): Float =
        maxDb - (y - gPad) / (height - 2 * gPad) * (maxDb - minDb)

    /** Gate/expander transfer function */
    private fun gateOutput(inputDb: Float): Float {
        if (inputDb >= gateThreshold || expanderRatio <= 1f) return inputDb
        return gateThreshold + (inputDb - gateThreshold) * expanderRatio
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Title — centered between 10 and 20 dB on Y axis (top of graph)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER
        }
        val titleY = (dbToY(10f) + dbToY(20f)) / 2f + 8f
        canvas.drawText("Gate (below threshold)", w / 2f, titleY, titlePaint)

        // Grid every 10 dB (labels drawn at end of onDraw)
        for (db in minDb.toInt()..maxDb.toInt() step 10) {
            val x = dbToX(db.toFloat())
            val y = dbToY(db.toFloat())
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Unity diagonal
        canvas.drawLine(dbToX(minDb), dbToY(minDb), dbToX(maxDb), dbToY(maxDb), diagonalPaint)

        // Gate curve — draw as two straight segments for perfect corners
        // Segment 1: expander line from minDb to gateThreshold
        // Segment 2: 1:1 diagonal from gateThreshold to maxDb
        val curvePath = Path()
        val gtClamped = gateThreshold.coerceIn(minDb, maxDb)

        // Start from the bottom-left (expander output at minDb)
        val startOut = gateOutput(minDb)
        curvePath.moveTo(dbToX(minDb), dbToY(startOut))

        // Line to the gate threshold point (exact corner)
        curvePath.lineTo(dbToX(gtClamped), dbToY(gtClamped))

        // Line along 1:1 diagonal to top-right
        curvePath.lineTo(dbToX(maxDb), dbToY(maxDb))

        canvas.drawPath(curvePath, curvePaint)

        // Threshold dot position — same clamping as compressor
        val threshOut = gateOutput(gateThreshold)
        val rawDotX = dbToX(gateThreshold)
        val rawDotY = dbToY(threshOut)
        val dotMargin = 22f
        val dotX = rawDotX.coerceIn(dotMargin, w - dotMargin)
        val dotY = rawDotY.coerceIn(dotMargin, h - dotMargin)

        // Dashed crosshairs
        canvas.drawLine(rawDotX, 0f, rawDotX, h, threshLinePaint)
        canvas.drawLine(0f, rawDotY, w, rawDotY, threshLinePaint)

        // Halo stroke along the expander line when dragging ratio
        if (draggingRatio) {
            val haloDp = 24f * resources.displayMetrics.density
            val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x38AAAAAA.toInt(); strokeWidth = haloDp * 2f
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            }
            val highlightPaint = Paint(curvePaint).apply {
                strokeWidth = 5f; color = 0x66FFFFFF.toInt()
            }
            val haloPath = Path()
            haloPath.moveTo(dbToX(minDb), dbToY(gateOutput(minDb)))
            haloPath.lineTo(dbToX(gtClamped), dbToY(gtClamped))
            canvas.drawPath(haloPath, haloStrokePaint)
            canvas.drawPath(haloPath, highlightPaint)
        }

        // Threshold dot halo
        if (draggingThreshold) {
            canvas.drawCircle(dotX, dotY, 24f * resources.displayMetrics.density, haloPaint)
        }

        // Threshold dot (outline only, same as compressor)
        canvas.drawCircle(dotX, dotY, 20f, dotBgPaint)
        canvas.drawCircle(dotX, dotY, 20f, dotRingPaint)
        val bandNumber = (selectedBand + 1).toString()
        val textY = dotY + (dotNumberPaint.textSize / 3)
        canvas.drawText(bandNumber, dotX, textY, dotNumberPaint)

        // Ghost compressor dot — same size as main dot, dimmed
        val compRefX = dbToX(compressorThreshold.coerceIn(minDb, maxDb)).coerceIn(dotMargin, w - dotMargin)
        val compRefY = dbToY(compressorThreshold.coerceIn(minDb, maxDb)).coerceIn(dotMargin, h - dotMargin)
        val ghostRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val ghostTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt(); textSize = 14f
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawCircle(compRefX, compRefY, 20f, dotBgPaint)
        canvas.drawCircle(compRefX, compRefY, 20f, ghostRingPaint)
        canvas.drawText(bandNumber, compRefX, compRefY + ghostTextPaint.textSize / 3f, ghostTextPaint)

        // Grid dB labels — drawn last to ensure visibility
        for (db in minDb.toInt()..maxDb.toInt() step 10) {
            if (db > minDb.toInt() && db < maxDb.toInt()) {
                val y = dbToY(db.toFloat())
                canvas.drawText("$db", 10f, y + 8f, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = event.x; touchStartY = event.y; hasDragged = false

                // Threshold dot
                val threshOut = gateOutput(gateThreshold)
                val dotX = dbToX(gateThreshold)
                val dotY = dbToY(threshOut)
                val distThresh = sqrt((event.x - dotX).pow(2) + (event.y - dotY).pow(2))

                val now = System.currentTimeMillis()

                if (distThresh < 55f) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 1) {
                        gateThreshold = DEFAULT_GATE_THRESHOLD
                        onGateThresholdChanged?.invoke(DEFAULT_GATE_THRESHOLD)
                        lastTapTime = 0L
                        justReset = true
                        invalidate()
                        return true
                    }
                    lastTapTime = now; lastTapType = 1
                    pendingDragType = 1
                    invalidate()
                    return true
                }

                // Touching the expander line = drag ratio
                val gtClamped = gateThreshold.coerceIn(minDb, maxDb)
                val lineX1 = dbToX(minDb); val lineY1 = dbToY(gateOutput(minDb))
                val lineX2 = dbToX(gtClamped); val lineY2 = dbToY(gtClamped)
                val lineDist = distToSegment(event.x, event.y, lineX1, lineY1, lineX2, lineY2)
                if (lineDist < 50f) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 2) {
                        expanderRatio = DEFAULT_EXPANDER_RATIO
                        onExpanderRatioChanged?.invoke(DEFAULT_EXPANDER_RATIO)
                        lastTapTime = 0L
                        justReset = true
                        invalidate()
                        return true
                    }
                    lastTapTime = now; lastTapType = 2
                    pendingDragType = 2
                    val touchInputDb = xToDb(event.x)
                    ratioTouchInputDb = touchInputDb.coerceIn(minDb, gtClamped - 10f)
                    invalidate()
                    return true
                }

                // Also allow touching the 1:1 portion (above gate threshold)
                val diag1X = dbToX(gtClamped); val diag1Y = dbToY(gtClamped)
                val diag2X = dbToX(maxDb); val diag2Y = dbToY(maxDb)
                val diagDist = distToSegment(event.x, event.y, diag1X, diag1Y, diag2X, diag2Y)
                if (diagDist < 50f) {
                    draggingThreshold = true
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (justReset) return true

                if (!hasDragged) {
                    val dx = event.x - touchStartX; val dy = event.y - touchStartY
                    if (dx * dx + dy * dy < 64f) return true
                    hasDragged = true
                    if (pendingDragType == 1) draggingThreshold = true
                    else if (pendingDragType == 2) draggingRatio = true
                }
                if (draggingThreshold) {
                    val newGate = xToDb(event.x).coerceIn(-60f, 0f)
                    val snapped = Math.round(newGate * 10f) / 10f
                    gateThreshold = snapped
                    onGateThresholdChanged?.invoke(snapped)
                    return true
                }
                if (draggingRatio) {
                    // Follow finger in all directions — update reference point from finger X
                    val targetOutputDb = yToDb(event.y)
                    val inputDb = xToDb(event.x).coerceIn(minDb, gateThreshold - 0.5f)
                    val inputDiff = inputDb - gateThreshold  // negative (below gate)
                    val outputDiff = targetOutputDb - gateThreshold  // negative (below gate)
                    val clampedOutputDiff = outputDiff.coerceIn(inputDiff * 50f, inputDiff)
                    val newExpRatio = if (inputDiff < -0.01f) (clampedOutputDiff / inputDiff).coerceIn(1f, 50f) else 1f
                    val snapped = Math.round(newExpRatio * 100f) / 100f
                    expanderRatio = snapped
                    onExpanderRatioChanged?.invoke(snapped)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingThreshold = false
                draggingRatio = false
                pendingDragType = 0
                justReset = false
                invalidate()
            }
        }
        return true
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1))
        val t = ((px - x1) * dx + (py - y1) * dy) / len2
        val ct = t.coerceIn(0f, 1f)
        val projX = x1 + ct * dx; val projY = y1 + ct * dy
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
    }
}
