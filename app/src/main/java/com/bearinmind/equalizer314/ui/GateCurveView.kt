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

    var gateThreshold: Float = -90f
        set(value) { field = value; invalidate() }
    var expanderRatio: Float = 1f
        set(value) { field = value.coerceIn(1f, 50f); invalidate() }

    var selectedBand: Int = 0
        set(value) { field = value; invalidate() }

    var onGateThresholdChanged: ((Float) -> Unit)? = null
    var onExpanderRatioChanged: ((Float) -> Unit)? = null

    private val minDb = -60f
    private val maxDb = 0f

    private var draggingThreshold = false
    private var draggingRatio = false
    private var ratioTouchInputDb = 0f

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

        // Grid every 10 dB with labels (skip 0 and -60)
        for (db in minDb.toInt()..maxDb.toInt() step 10) {
            val x = dbToX(db.toFloat())
            val y = dbToY(db.toFloat())
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawLine(0f, y, w, y, gridPaint)

            if (db > minDb.toInt() && db < maxDb.toInt()) {
                canvas.drawText("$db", 10f, y + 8f, labelPaint)
            }
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                // Threshold dot
                val threshOut = gateOutput(gateThreshold)
                val dotX = dbToX(gateThreshold)
                val dotY = dbToY(threshOut)
                val distThresh = sqrt((event.x - dotX).pow(2) + (event.y - dotY).pow(2))

                if (distThresh < 55f) {
                    draggingThreshold = true
                    invalidate()
                    return true
                }

                // Touching the curve below threshold = drag expander ratio
                val touchInputDb = xToDb(event.x)
                if (touchInputDb < gateThreshold + 5f) {
                    val curveY = dbToY(gateOutput(touchInputDb.coerceIn(minDb, maxDb)))
                    if (abs(event.y - curveY) < 50f) {
                        draggingRatio = true
                        ratioTouchInputDb = touchInputDb.coerceIn(minDb, gateThreshold - 1f)
                        invalidate()
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingThreshold) {
                    val newGate = xToDb(event.x).coerceIn(-60f, 0f)
                    val snapped = Math.round(newGate * 10f) / 10f
                    gateThreshold = snapped
                    onGateThresholdChanged?.invoke(snapped)
                    return true
                }
                if (draggingRatio) {
                    // Mirror compressor: R = outputUndershoot / undershoot, curve follows finger
                    val targetOutputDb = yToDb(event.y)
                    val inputDb = ratioTouchInputDb.coerceAtMost(gateThreshold - 0.5f)
                    val undershoot = gateThreshold - inputDb
                    val outputUndershoot = (gateThreshold - targetOutputDb).coerceIn(undershoot, undershoot * 50f)
                    val newExpRatio = (outputUndershoot / undershoot).coerceIn(1f, 50f)
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
                invalidate()
            }
        }
        return true
    }
}
