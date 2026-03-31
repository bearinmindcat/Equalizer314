package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Limiter scrolling level display — same architecture as MBC GrTraceView.
 * No staging queue, no internal timer.
 * Activity's 33ms timer calls pushFrame() + invalidate() each frame.
 */
class LimiterWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bufferSize = 450
    private var writeIdx = 0
    private val inputHistory = FloatArray(bufferSize) { -80f }
    private val grHistory = FloatArray(bufferSize)

    var ceilingDb: Float = -0.5f

    // dB scale: +10 at top, -40 at bottom
    private val dbMax = 10f
    private val dbMin = -40f
    private val dbRange = dbMax - dbMin

    private val inputFillPath = Path()
    private val inputStrokePath = Path()
    private val grLinePath = Path()
    private val grFillPath = Path()

    private val bgPaint = Paint().apply { color = 0xFF1A1A1A.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt(); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 24f
    }
    private val ceilingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF6666.toInt(); strokeWidth = 1.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }
    private val ceilingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
    }
    private val levelColor = 0xFFBBBBBB.toInt()
    private val inputFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.FILL; alpha = 15
    }
    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 35
    }
    private val grTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE57373.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val grFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE57373.toInt(); style = Paint.Style.FILL; alpha = 25
    }

    /** Called from activity's 33ms timer — one value per frame, exactly like MBC GrTraceView */
    fun pushFrame(inputDb: Float, grDb: Float) {
        val idx = writeIdx % bufferSize
        inputHistory[idx] = inputDb.coerceIn(-80f, 10f)
        grHistory[idx] = grDb.coerceIn(-30f, 0f)
        writeIdx++
    }

    fun flushToSilence() {}

    private fun ringIdx(sampleOffset: Int): Int {
        return ((writeIdx - bufferSize + sampleOffset) % bufferSize + bufferSize) % bufferSize
    }

    private fun dbToY(db: Float, h: Float): Float {
        val clamped = db.coerceIn(dbMin, dbMax)
        return h * (dbMax - clamped) / dbRange
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        for (db in listOf(0f, -10f, -20f, -30f)) {
            val y = dbToY(db, h)
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = if (db == 0f) "0" else "${db.toInt()}"
            canvas.drawText(label, 10f, y + 8f, labelPaint)
        }

        val pxPerSample = w / bufferSize

        // ── INPUT LEVEL ──
        inputFillPath.reset(); inputStrokePath.reset()
        inputFillPath.moveTo(0f, h)
        for (s in 0 until bufferSize) {
            val idx = ringIdx(s)
            val y = dbToY(inputHistory[idx], h)
            val x = s * pxPerSample
            inputFillPath.lineTo(x, y)
            if (s == 0) inputStrokePath.moveTo(x, y) else inputStrokePath.lineTo(x, y)
        }
        inputFillPath.lineTo(w, h); inputFillPath.close()
        canvas.drawPath(inputFillPath, inputFillPaint)
        canvas.drawPath(inputStrokePath, inputStrokePaint)

        // ── GR TRACE ──
        grLinePath.reset(); grFillPath.reset()
        grFillPath.moveTo(0f, 0f)
        for (s in 0 until bufferSize) {
            val idx = ringIdx(s)
            val x = s * pxPerSample
            val y = dbToY(grHistory[idx], h)
            if (s == 0) { grLinePath.moveTo(x, y); grFillPath.lineTo(x, y) }
            else { grLinePath.lineTo(x, y); grFillPath.lineTo(x, y) }
        }
        grFillPath.lineTo(w, 0f); grFillPath.close()
        canvas.drawPath(grFillPath, grFillPaint)
        canvas.drawPath(grLinePath, grTracePaint)

        // Dragging glow
        if (draggingCeiling) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF6666.toInt(); alpha = 40; style = Paint.Style.FILL
            }
            val ceilGlowY = dbToY(ceilingDb, h)
            canvas.drawRoundRect(0f, ceilGlowY - 24f, w, ceilGlowY + 24f, 10f, 10f, glowPaint)
        }

        // Ceiling line with centered dB label
        val ceilY = dbToY(ceilingDb, h)
        val ceilText = String.format("%.1f", ceilingDb)
        val ceilTextWidth = ceilingTextPaint.measureText(ceilText)
        val ceilCenterX = w / 2f
        val gapPad = 8f
        val gapLeft = ceilCenterX - ceilTextWidth / 2f - gapPad
        val gapRight = ceilCenterX + ceilTextWidth / 2f + gapPad
        if (gapLeft > 0f) canvas.drawLine(0f, ceilY, gapLeft, ceilY, ceilingLinePaint)
        if (gapRight < w) canvas.drawLine(gapRight, ceilY, w, ceilY, ceilingLinePaint)
        canvas.drawText(ceilText, ceilCenterX, ceilY + 7f, ceilingTextPaint)
    }

    var onCeilingChanged: ((Float) -> Unit)? = null
    private var draggingCeiling = false
    private var lastCeilingTapTime = 0L

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val h = height.toFloat()
        if (h <= 0) return true
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val ceilY = dbToY(ceilingDb, h)
                if (kotlin.math.abs(event.y - ceilY) < 40f) {
                    val now = System.currentTimeMillis()
                    if (now - lastCeilingTapTime < 300L) {
                        ceilingDb = 0f
                        onCeilingChanged?.invoke(ceilingDb)
                        lastCeilingTapTime = 0L
                        invalidate()
                        return true
                    }
                    lastCeilingTapTime = now
                    draggingCeiling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (draggingCeiling) {
                    val newDb = dbMax - (event.y / h) * dbRange
                    ceilingDb = (Math.round(newDb * 2f) / 2f).coerceIn(dbMin, dbMax)
                    onCeilingChanged?.invoke(ceilingDb)
                    invalidate()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingCeiling = false
            }
        }
        return true
    }
}
