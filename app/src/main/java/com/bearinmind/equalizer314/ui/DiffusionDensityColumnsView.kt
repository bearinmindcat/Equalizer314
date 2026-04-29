package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Two-column slider meter for the EnvironmentalReverb's Diffusion +
 * Density. Mirrors [LimiterCeilingView]'s two-column "Ceiling / GR"
 * styling — same dark column backgrounds, light grey fills, grid lines
 * in the centre gap, titles up top, values at the bottom — but with
 * draggable fills tied to 0..100 %.
 */
class DiffusionDensityColumnsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    var diffusionPct: Float = 100f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var densityPct: Float = 100f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }

    var onChanged: ((Float, Float) -> Unit)? = null

    private val columnBgPaint = Paint().apply { color = 0xCC2A2A2A.toInt() }
    private val fillPaint = Paint().apply { color = 0xFFBBBBBB.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt(); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 16f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FFFFFF.toInt(); style = Paint.Style.FILL
    }
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt(); strokeWidth = 2f
    }

    private val topPad = 50f
    private val bottomPad = 40f
    private val sidePad = 8f

    private fun meterTop() = topPad
    private fun meterBottom(h: Float) = h - bottomPad
    private fun meterHeight(h: Float) = meterBottom(h) - meterTop()

    private fun pctToY(pct: Float, h: Float): Float {
        val norm = (100f - pct.coerceIn(0f, 100f)) / 100f
        return meterTop() + meterHeight(h) * norm
    }
    private fun yToPct(y: Float, h: Float): Float {
        val norm = (y - meterTop()) / meterHeight(h)
        return (100f - norm * 100f).coerceIn(0f, 100f)
    }

    private var diffLeft = 0f
    private var diffRight = 0f
    private var densLeft = 0f
    private var densRight = 0f

    private fun computeColumns(w: Float) {
        val gap = 36f
        val colWidth = (w - sidePad * 2 - gap) / 2f * 0.7f
        val totalWidth = colWidth * 2 + gap
        val startX = (w - totalWidth) / 2f
        diffLeft = startX
        diffRight = diffLeft + colWidth
        densLeft = diffRight + gap
        densRight = densLeft + colWidth
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        computeColumns(w)
        val mt = meterTop()
        val mb = meterBottom(h)
        val labelCenterX = (diffRight + densLeft) / 2f
        val cornerR = 8f

        canvas.drawRoundRect(diffLeft, mt, diffRight, mb, cornerR, cornerR, columnBgPaint)
        canvas.drawRoundRect(densLeft, mt, densRight, mb, cornerR, cornerR, columnBgPaint)

        for (pct in listOf(100f, 75f, 50f, 25f, 0f)) {
            val y = pctToY(pct, h)
            canvas.drawLine(diffLeft, y, diffRight, y, gridPaint)
            canvas.drawLine(densLeft, y, densRight, y, gridPaint)
            canvas.drawText("${pct.toInt()}", labelCenterX, y + 5f, labelPaint)
        }

        val diffY = pctToY(diffusionPct, h)
        canvas.save()
        clipRoundRect(canvas, diffLeft, mt, diffRight, mb, cornerR)
        fillPaint.alpha = 50
        canvas.drawRect(diffLeft, diffY, diffRight, mb, fillPaint)
        canvas.restore()
        canvas.drawLine(diffLeft + 2f, diffY, diffRight - 2f, diffY, handleLinePaint)
        if (draggingDiff) {
            canvas.drawRoundRect(
                diffLeft + 2f, diffY - 20f, diffRight - 2f, diffY + 20f,
                10f, 10f, haloPaint
            )
        }

        val densY = pctToY(densityPct, h)
        canvas.save()
        clipRoundRect(canvas, densLeft, mt, densRight, mb, cornerR)
        fillPaint.alpha = 50
        canvas.drawRect(densLeft, densY, densRight, mb, fillPaint)
        canvas.restore()
        canvas.drawLine(densLeft + 2f, densY, densRight - 2f, densY, handleLinePaint)
        if (draggingDens) {
            canvas.drawRoundRect(
                densLeft + 2f, densY - 20f, densRight - 2f, densY + 20f,
                10f, 10f, haloPaint
            )
        }

        canvas.drawText("Diff", (diffLeft + diffRight) / 2f, 32f, titlePaint)
        canvas.drawText("Dens", (densLeft + densRight) / 2f, 32f, titlePaint)

        canvas.drawText(
            String.format("%.0f", diffusionPct),
            (diffLeft + diffRight) / 2f, h - 10f, valuePaint
        )
        canvas.drawText(
            String.format("%.0f", densityPct),
            (densLeft + densRight) / 2f, h - 10f, valuePaint
        )
    }

    private fun clipRoundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rx: Float) {
        val path = Path().apply { addRoundRect(l, t, r, b, rx, rx, Path.Direction.CW) }
        c.clipPath(path)
    }

    private var draggingDiff = false
    private var draggingDens = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        computeColumns(w)
        val padX = 14f * resources.displayMetrics.density
        val mt = meterTop()
        val mb = meterBottom(h)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inDiff = event.x in (diffLeft - padX)..(diffRight + padX) &&
                    event.y in mt..mb
                val inDens = event.x in (densLeft - padX)..(densRight + padX) &&
                    event.y in mt..mb
                when {
                    inDiff -> { draggingDiff = true }
                    inDens -> { draggingDens = true }
                    else -> return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.y, h)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingDiff && !draggingDens) return false
                applyTouch(event.y, h)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingDiff = false
                draggingDens = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(y: Float, h: Float) {
        val newPct = yToPct(y, h)
        if (draggingDiff) diffusionPct = newPct
        if (draggingDens) densityPct = newPct
        onChanged?.invoke(diffusionPct, densityPct)
    }
}
