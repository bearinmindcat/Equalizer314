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
 * Compressor static characteristic (transfer function) graph.
 * X-axis = input level (dBFS), Y-axis = output level (dBFS).
 *
 * Giannoulis/Massberg/Reiss 2012 soft-knee gain computer:
 *   Below knee:  output = input
 *   In knee:     output = input + (1/R - 1)(input - T + W/2)² / (2W)
 *   Above knee:  output = T + (input - T) / R
 *
 * Drag threshold dot along diagonal. Drag curve above threshold up/down for ratio.
 */
class CompressorCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var threshold: Float = -2f
        set(value) { field = value; invalidate() }
    var ratio: Float = 10f
        set(value) { field = value.coerceIn(1f, 50f); invalidate() }
    var kneeWidth: Float = 3.5f
        set(value) { field = value; invalidate() }

    var selectedBand: Int = 0
        set(value) { field = value; invalidate() }

    var showKneeZoom: Boolean = false
        set(value) { field = value; invalidate() }

    var onThresholdChanged: ((Float) -> Unit)? = null
    var onRatioChanged: ((Float) -> Unit)? = null

    private val minDb = -60f
    private val maxDb = 0f

    private var draggingThreshold = false
    private var draggingRatio = false
    private var ratioTouchInputDb = 0f

    // Paints — matching EqGraphView
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
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt(); style = Paint.Style.FILL
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

    // Small internal padding to keep content clear of card corner clipping
    private val gPad = 4f

    private fun dbToX(db: Float): Float =
        gPad + (width - 2 * gPad) * (db - minDb) / (maxDb - minDb)

    private fun dbToY(db: Float): Float =
        gPad + (height - 2 * gPad) * (1f - (db - minDb) / (maxDb - minDb))

    private fun xToDb(x: Float): Float =
        minDb + (x - gPad) / (width - 2 * gPad) * (maxDb - minDb)

    private fun yToDb(y: Float): Float =
        maxDb - (y - gPad) / (height - 2 * gPad) * (maxDb - minDb)

    private fun compressorOutput(inputDb: Float): Float {
        val t = threshold; val r = ratio; val w = kneeWidth
        return when {
            inputDb < t - w / 2f -> inputDb
            inputDb <= t + w / 2f -> {
                val diff = inputDb - t + w / 2f
                inputDb + (1f / r - 1f) * diff * diff / (2f * w)
            }
            else -> t + (inputDb - t) / r
        }
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

        // Compressor curve
        val curvePath = Path()
        val steps = 150
        for (s in 0..steps) {
            val inDb = minDb + (maxDb - minDb) * s / steps
            val outDb = compressorOutput(inDb).coerceIn(minDb, maxDb)
            val x = dbToX(inDb); val y = dbToY(outDb)
            if (s == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        canvas.drawPath(curvePath, curvePaint)

        // Threshold dot position — clamp inward enough to always be fully visible
        val threshOut = compressorOutput(threshold)
        val rawDotX = dbToX(threshold)
        val rawDotY = dbToY(threshOut)
        val dotMargin = 22f
        val dotX = rawDotX.coerceIn(dotMargin, w - dotMargin)
        val dotY = rawDotY.coerceIn(dotMargin, h - dotMargin)

        // Dashed crosshairs at threshold
        canvas.drawLine(rawDotX, 0f, rawDotX, h, threshLinePaint)
        canvas.drawLine(0f, rawDotY, w, rawDotY, threshLinePaint)

        // --- Knee visualization: perpendicular tick marks at T-W/2 and T+W/2 ---
        if (kneeWidth > 0.1f) {
            val kneeStartDb = threshold - kneeWidth / 2f
            val kneeEndDb = threshold + kneeWidth / 2f

            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFAAAAAA.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
            }
            val tickLen = 20f
            val epsilon = 0.5f

            fun drawPerpendicularTick(inDb: Float) {
                val outDb = compressorOutput(inDb)
                val cx = dbToX(inDb); val cy = dbToY(outDb)
                val outBefore = compressorOutput(inDb - epsilon)
                val outAfter = compressorOutput(inDb + epsilon)
                val tx = dbToX(inDb + epsilon) - dbToX(inDb - epsilon)
                val ty = dbToY(outAfter) - dbToY(outBefore)
                val len = kotlin.math.sqrt(tx * tx + ty * ty)
                if (len > 0.01f) {
                    val px = -ty / len * tickLen
                    val py = tx / len * tickLen
                    canvas.drawLine(cx - px, cy - py, cx + px, cy + py, tickPaint)
                }
            }

            drawPerpendicularTick(kneeStartDb)
            drawPerpendicularTick(kneeEndDb)
        }

        // Halo stroke along the curve above threshold when dragging ratio
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
            var hStarted = false
            for (s in 0..steps) {
                val inDb = minDb + (maxDb - minDb) * s / steps
                if (inDb < threshold) continue
                val outDb = compressorOutput(inDb).coerceIn(minDb, maxDb)
                val x = dbToX(inDb); val y = dbToY(outDb)
                if (!hStarted) { haloPath.moveTo(x, y); hStarted = true }
                else haloPath.lineTo(x, y)
            }
            canvas.drawPath(haloPath, haloStrokePaint)
            canvas.drawPath(haloPath, highlightPaint)
        }

        // Threshold dot halo
        if (draggingThreshold) {
            canvas.drawCircle(dotX, dotY, 24f * resources.displayMetrics.density, haloPaint)
        }

        // --- Knee zoom bubble: shows zoomed-in knee area when adjusting knee slider ---
        if (showKneeZoom) {
            val zoomRadius = 60f
            // Position above the dot if possible, below if at top edge
            val zoomCenterX = dotX.coerceIn(zoomRadius + 5f, w - zoomRadius - 5f)
            val aboveY = dotY - zoomRadius - 28f
            val belowY = dotY + zoomRadius + 28f
            val zoomCenterY = if (aboveY >= zoomRadius + 5f) aboveY
                              else belowY.coerceAtMost(h - zoomRadius - 5f)

            // Background circle
            val zoomBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xEE1E1E1E.toInt(); style = Paint.Style.FILL
            }
            val zoomRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFAAAAAA.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
            }
            canvas.drawCircle(zoomCenterX, zoomCenterY, zoomRadius, zoomBgPaint)
            canvas.drawCircle(zoomCenterX, zoomCenterY, zoomRadius, zoomRingPaint)

            // Clip to circle and draw zoomed knee
            canvas.save()
            val clipPath = Path().apply { addCircle(zoomCenterX, zoomCenterY, zoomRadius - 2f, Path.Direction.CW) }
            canvas.clipPath(clipPath)

            // Fixed zoom range: always show ±15 dB around threshold
            val zoomRange = 15f
            val zoomMinDb = threshold - zoomRange
            val zoomMaxDb = threshold + zoomRange

            val zPad = 4f
            val zDiam = (zoomRadius - zPad) * 2f
            fun zoomDbToX(db: Float): Float =
                zoomCenterX - zoomRadius + zPad + zDiam * (db - zoomMinDb) / (zoomMaxDb - zoomMinDb)
            fun zoomDbToY(db: Float): Float =
                zoomCenterY + zoomRadius - zPad - zDiam * (db - zoomMinDb) / (zoomMaxDb - zoomMinDb)

            // Zoomed grid
            val zoomGridPaint = Paint(gridPaint).apply { strokeWidth = 0.5f }
            val gridStepZoom = if (zoomRange > 10f) 10f else if (zoomRange > 3f) 5f else 1f
            var gDb = (kotlin.math.ceil(zoomMinDb / gridStepZoom) * gridStepZoom).toFloat()
            while (gDb <= zoomMaxDb) {
                val gx = zoomDbToX(gDb); val gy = zoomDbToY(gDb)
                canvas.drawLine(gx, zoomCenterY - zoomRadius, gx, zoomCenterY + zoomRadius, zoomGridPaint)
                canvas.drawLine(zoomCenterX - zoomRadius, gy, zoomCenterX + zoomRadius, gy, zoomGridPaint)
                gDb += gridStepZoom
            }

            // Zoomed diagonal
            canvas.drawLine(zoomDbToX(zoomMinDb), zoomDbToY(zoomMinDb), zoomDbToX(zoomMaxDb), zoomDbToY(zoomMaxDb), diagonalPaint)

            // Zoomed curve
            val zoomCurvePaint = Paint(curvePaint).apply { strokeWidth = 3f }
            val zoomPath = Path()
            for (s in 0..80) {
                val inDb = zoomMinDb + (zoomMaxDb - zoomMinDb) * s / 80f
                val outDb = compressorOutput(inDb)
                val zx = zoomDbToX(inDb); val zy = zoomDbToY(outDb)
                if (s == 0) zoomPath.moveTo(zx, zy) else zoomPath.lineTo(zx, zy)
            }
            canvas.drawPath(zoomPath, zoomCurvePaint)

            canvas.restore()
        }

        // Threshold dot (outline only, clamped to stay visible)
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
                val threshOut = compressorOutput(threshold)
                val dotX = dbToX(threshold)
                val dotY = dbToY(threshOut)
                val distThresh = sqrt((event.x - dotX).pow(2) + (event.y - dotY).pow(2))

                if (distThresh < 55f) {
                    draggingThreshold = true
                    invalidate()
                    return true
                }

                // Touching the curve above threshold = drag ratio
                val touchInputDb = xToDb(event.x)
                if (touchInputDb > threshold - 5f) {
                    val curveY = dbToY(compressorOutput(touchInputDb.coerceIn(minDb, maxDb)))
                    if (abs(event.y - curveY) < 50f) {
                        draggingRatio = true
                        ratioTouchInputDb = touchInputDb.coerceIn(threshold + 1f, maxDb)
                        invalidate()
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingThreshold) {
                    val newThresh = xToDb(event.x).coerceIn(-60f, 0f)
                    val snapped = Math.round(newThresh * 10f) / 10f
                    threshold = snapped
                    onThresholdChanged?.invoke(snapped)
                    return true
                }
                if (draggingRatio) {
                    // Curve follows finger: R = (input - T) / (output - T)
                    val targetOutputDb = yToDb(event.y)
                    val inputDb = ratioTouchInputDb.coerceAtLeast(threshold + 0.5f)
                    val overshoot = inputDb - threshold
                    val outputOvershoot = (targetOutputDb - threshold).coerceIn(0.01f, overshoot)
                    val newRatio = (overshoot / outputOvershoot).coerceIn(1f, 50f)
                    val snapped = Math.round(newRatio * 100f) / 100f
                    ratio = snapped
                    onRatioChanged?.invoke(snapped)
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
