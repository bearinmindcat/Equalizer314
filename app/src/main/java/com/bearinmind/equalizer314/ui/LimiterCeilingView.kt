package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Ableton-style limiter display with two columns:
 * - Left: "Ceiling" — shows input level pumping with draggable ceiling line.
 *   Signal below ceiling = green, signal above ceiling = red (clipped).
 * - Right: "GR" — gain reduction meter (fills from top, orange)
 */
class LimiterCeilingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var ceilingDb: Float = -0.5f
        set(value) { field = value.coerceIn(dbMin, dbMax); invalidate() }

    // Target values (set from outside)
    var inputDb: Float = -30f
    var grDb: Float = 0f

    // Smoothed values for display (asymmetric: fast attack, slow release)
    private var smoothedInput = -30f
    private var smoothedGr = 0f
    private val attackAlpha = 0.5f    // fast rise
    private val releaseAlpha = 0.08f  // slow fall

    var onCeilingChanged: ((Float) -> Unit)? = null

    private val dbMin = -30f
    private val dbMax = 0f
    private val dbRange = dbMax - dbMin

    private var dragging = false
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L
    private var touchStartY = 0f
    private var hasDragged = false

    // Peak hold for input
    private var peakDb = -60f
    private var peakHoldFrames = 0
    private val peakHoldMax = 30

    companion object {
        const val DEFAULT_CEILING = -0.5f
    }

    private val bgPaint = Paint().apply { color = 0xFF1E1E1E.toInt() }
    private val columnBgPaint = Paint().apply { color = 0xFF2A2A2A.toInt() }
    private val inputBelowPaint = Paint().apply { color = 0xFFBBBBBB.toInt() } // grey
    private val inputAbovePaint = Paint().apply { color = 0xFF999999.toInt() } // darker grey (above ceiling)
    private val ceilingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt(); strokeWidth = 3f
    }
    private val grFillPaint = Paint().apply { color = 0xFFAAAAAA.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt(); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val grValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 2f
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FFFFFF.toInt(); style = Paint.Style.FILL
    }

    private val topPad = 50f
    private val bottomPad = 40f
    private val sidePad = 16f

    private fun meterTop() = topPad
    private fun meterBottom(h: Float) = h - bottomPad
    private fun meterHeight(h: Float) = meterBottom(h) - meterTop()

    // Ceiling/GR scale: 0 to -30
    private fun dbToY(db: Float, h: Float): Float {
        val norm = (dbMax - db) / dbRange
        return meterTop() + meterHeight(h) * norm
    }

    private fun yToDb(y: Float, h: Float): Float {
        val norm = (y - meterTop()) / meterHeight(h)
        return (dbMax - norm * dbRange).coerceIn(dbMin, dbMax)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Smooth input and GR with asymmetric ballistics
        val inputAlpha = if (inputDb > smoothedInput) attackAlpha else releaseAlpha
        smoothedInput = inputAlpha * inputDb + (1f - inputAlpha) * smoothedInput

        val grAlpha = if (grDb < smoothedGr) attackAlpha else releaseAlpha
        smoothedGr = grAlpha * grDb + (1f - grAlpha) * smoothedGr

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val colWidth = (w - sidePad * 2) * 0.3f
        val ceilingLeft = sidePad
        val ceilingRight = ceilingLeft + colWidth
        val grLeft = w - sidePad - colWidth
        val grRight = w - sidePad
        val labelCenterX = (ceilingRight + grLeft) / 2f

        val mt = meterTop()
        val mb = meterBottom(h)
        val cornerR = 8f

        // Column backgrounds
        canvas.drawRoundRect(ceilingLeft, mt, ceilingRight, mb, cornerR, cornerR, columnBgPaint)
        canvas.drawRoundRect(grLeft, mt, grRight, mb, cornerR, cornerR, columnBgPaint)

        // Grid lines + dB labels
        // Ceiling column labels (0 to -30)
        for (db in listOf(0f, -6f, -12f, -18f, -24f, -30f)) {
            val y = dbToY(db, h)
            val label = if (db == 0f) "0" else "${db.toInt()}"
            canvas.drawText(label, ceilingLeft - 4f, y + 7f, labelPaint.apply { textAlign = Paint.Align.RIGHT })
        }
        // GR column labels (0 to -12, tighter scale)
        for (db in listOf(0f, -3f, -6f, -9f, -12f)) {
            // Map GR dB to Y position using the GR's own scale
            val norm = (0f - db) / 12f
            val y = meterTop() + meterHeight(h) * norm
            canvas.drawLine(grLeft, y, grRight, y, gridPaint)
            val label = if (db == 0f) "0" else "${db.toInt()}"
            canvas.drawText(label, grRight + 4f, y + 7f, labelPaint.apply { textAlign = Paint.Align.LEFT })
        }
        // Grid on ceiling column (same positions)
        for (db in listOf(0f, -6f, -12f, -18f, -24f, -30f)) {
            val y = dbToY(db, h)
            canvas.drawLine(ceilingLeft, y, ceilingRight, y, gridPaint)
        }

        // ── Input level in ceiling column (uses smoothed value) ──
        val ceilingY = dbToY(ceilingDb, h)
        val displayInput = smoothedInput.coerceIn(dbMin, 0f)
        val inputY = dbToY(displayInput, h)

        canvas.save()
        canvas.clipRoundRect(ceilingLeft, mt, ceilingRight, mb, cornerR, cornerR)

        if (displayInput > ceilingDb) {
            inputBelowPaint.alpha = 160
            canvas.drawRect(ceilingLeft, ceilingY, ceilingRight, mb, inputBelowPaint)
            inputAbovePaint.alpha = 200
            canvas.drawRect(ceilingLeft, inputY, ceilingRight, ceilingY, inputAbovePaint)
        } else {
            inputBelowPaint.alpha = 160
            canvas.drawRect(ceilingLeft, inputY, ceilingRight, mb, inputBelowPaint)
        }
        canvas.restore()

        // Ceiling line (draggable)
        canvas.drawLine(ceilingLeft - 4f, ceilingY, ceilingRight + 4f, ceilingY, ceilingLinePaint)

        // Peak hold line
        if (displayInput > peakDb) {
            peakDb = displayInput; peakHoldFrames = 0
        } else {
            peakHoldFrames++
            if (peakHoldFrames > peakHoldMax) peakDb = (peakDb - 0.5f).coerceAtLeast(dbMin)
        }
        val peakY = dbToY(peakDb.coerceAtLeast(dbMin), h)
        if (peakDb > dbMin + 1f) {
            canvas.save()
            canvas.clipRoundRect(ceilingLeft, mt, ceilingRight, mb, cornerR, cornerR)
            canvas.drawLine(ceilingLeft + 2f, peakY, ceilingRight - 2f, peakY, peakLinePaint)
            canvas.restore()
        }

        // Drag halo
        if (dragging) {
            canvas.drawRoundRect(
                ceilingLeft + 2f, ceilingY - 20f,
                ceilingRight - 2f, ceilingY + 20f,
                10f, 10f, haloPaint
            )
        }

        // ── GR fill (orange, from top down — uses smoothed value, tighter 0 to -12 dB scale) ──
        val grScaleMin = -12f
        val displayGr = smoothedGr.coerceIn(grScaleMin, 0f)
        fun grToY(gr: Float): Float {
            val norm = (0f - gr) / (0f - grScaleMin)
            return meterTop() + meterHeight(h) * norm.coerceIn(0f, 1f)
        }
        canvas.save()
        canvas.clipRoundRect(grLeft, mt, grRight, mb, cornerR, cornerR)
        if (displayGr < -0.1f) {
            grFillPaint.alpha = 180
            canvas.drawRect(grLeft, mt, grRight, grToY(displayGr), grFillPaint)
        }
        canvas.restore()

        // ── Titles ──
        canvas.drawText("Ceiling", (ceilingLeft + ceilingRight) / 2f, 32f, titlePaint)
        canvas.drawText("GR", (grLeft + grRight) / 2f, 32f, titlePaint)

        // ── Values at bottom ──
        canvas.drawText(String.format("%.1f dB", ceilingDb), (ceilingLeft + ceilingRight) / 2f, h - 10f, valuePaint)
        val grText = if (displayGr < -0.1f) String.format("%.1f dB", displayGr) else "0.0 dB"
        canvas.drawText(grText, (grLeft + grRight) / 2f, h - 10f, grValuePaint)
    }

    private fun Canvas.clipRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float) {
        val path = Path().apply { addRoundRect(l, t, r, b, rx, ry, Path.Direction.CW) }
        clipPath(path)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val colWidth = (w - sidePad * 2) * 0.3f
        val ceilingLeft = sidePad
        val ceilingRight = ceilingLeft + colWidth

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= ceilingLeft - 20f && event.x <= ceilingRight + 20f) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapTimeout) {
                        ceilingDb = DEFAULT_CEILING
                        onCeilingChanged?.invoke(ceilingDb)
                        lastTapTime = 0L
                        invalidate()
                        return true
                    }
                    lastTapTime = now
                    touchStartY = event.y
                    hasDragged = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasDragged) {
                    val dy = event.y - touchStartY
                    if (dy * dy > 64f) { hasDragged = true; dragging = true }
                }
                if (dragging) {
                    val newDb = yToDb(event.y, height.toFloat())
                    val snapped = (Math.round(newDb * 2f) / 2f).coerceIn(dbMin, dbMax)
                    ceilingDb = snapped
                    onCeilingChanged?.invoke(snapped)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false; invalidate()
            }
        }
        return true
    }
}
