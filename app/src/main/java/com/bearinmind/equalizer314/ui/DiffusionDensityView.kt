package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * X/Y editor for the EnvironmentalReverb's Diffusion (X) and Density (Y).
 * Uses [LimiterCeilingView]-style paints (dark backgrounds, light grey
 * grid + fill, halo on drag) and stretches to fill its card edge-to-edge,
 * with a small inset for axis labels and the bottom value readout.
 */
class DiffusionDensityView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** 0..100 % */
    var diffusionPct: Float = 100f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    /** 0..100 % */
    var densityPct: Float = 100f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }

    /** Fired live during drag with (diffusion, density) in 0..100 %. */
    var onChanged: ((Float, Float) -> Unit)? = null

    private val density = context.resources.displayMetrics.density

    // Background matches the main EQ graph + MBC graph (#1E1E1E),
    // with the same darker grid line color (#3A3A3A).
    private val plotBgPaint = Paint().apply { color = 0xFF1E1E1E.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt(); strokeWidth = 1f
    }
    // Same paint as the main EQ / MBC graph's grid labels: #888888 grey,
    // 24-pixel text. Drawn into the gridline gap so the line doesn't cut
    // through the digit.
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 24f
    }
    // Dot styling matched to MBC's gate / compressor curves: dark
    // graph-color fill so it punches through the gridlines, with a
    // light-grey ring on top.
    private val dotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38AAAAAA.toInt(); style = Paint.Style.FILL
    }
    // Dotted crosshair, same dash style the limiter ceiling line uses.
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66BBBBBB.toInt(); strokeWidth = 1f * density
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }

    // The plot now spans the full view; labels are overlaid inside.
    private var plotL = 0f; private var plotT = 0f
    private var plotR = 0f; private var plotB = 0f
    private var dragging = false

    private fun computePlot(w: Float, h: Float) {
        plotL = 0f
        plotT = 0f
        plotR = w
        plotB = h
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        computePlot(w, h)

        // Plot background fills the whole view, edge-to-edge.
        canvas.drawRect(plotL, plotT, plotR, plotB, plotBgPaint)

        // Gridlines at 20, 40, 60, 80 % on both axes. Labels sit ON the
        // line (X values near the bottom, Y values near the left) with
        // the gridline broken around the label so the digits aren't cut
        // through — same convention used elsewhere in the app.
        val gridValues = intArrayOf(20, 40, 60, 80)
        val plotW = plotR - plotL
        val plotH = plotB - plotT
        val labelBounds = android.graphics.Rect()
        val labelGap = 4f * density
        for (v in gridValues) {
            val label = "$v"
            gridLabelPaint.getTextBounds(label, 0, label.length, labelBounds)
            val labelW = labelBounds.width().toFloat()
            val labelH = labelBounds.height().toFloat()

            // Vertical line at X=v% — label sits near the bottom edge.
            val gx = plotL + (v / 100f) * plotW
            val xLabelBaselineY = plotB - 6f * density
            val xLabelTopY = xLabelBaselineY - labelH - labelGap
            val xLabelBottomY = xLabelBaselineY + labelGap
            canvas.drawLine(gx, plotT, gx, xLabelTopY, gridPaint)
            canvas.drawLine(gx, xLabelBottomY, gx, plotB, gridPaint)
            canvas.drawText(label, gx - labelW / 2f, xLabelBaselineY, gridLabelPaint)

            // Horizontal line at Y=v% — label sits near the left edge.
            val gy = plotB - (v / 100f) * plotH
            val yLabelLeftX = plotL + 6f * density
            val yLabelRightX = yLabelLeftX + labelW + labelGap
            canvas.drawLine(plotL, gy, yLabelLeftX - labelGap, gy, gridPaint)
            canvas.drawLine(yLabelRightX, gy, plotR, gy, gridPaint)
            canvas.drawText(label, yLabelLeftX, gy + labelH / 2f, gridLabelPaint)
        }

        // Crosshair through the current point — uses the raw (un-
        // clamped) point so the lines run all the way to the edges.
        val px = plotL + (diffusionPct / 100f) * (plotR - plotL)
        val py = plotB - (densityPct / 100f) * (plotB - plotT)
        canvas.drawLine(plotL, py, plotR, py, crosshairPaint)
        canvas.drawLine(px, plotT, px, plotB, crosshairPaint)

        // Dot is clamped inside the graph so it stays fully visible at
        // 0/100 — same trick the gate / compressor curves use. Filled
        // with the graph-bg colour and ringed in light grey to match
        // those views exactly.
        val dotR = 20f
        val dotMargin = dotR + 2f
        val dotX = px.coerceIn(plotL + dotMargin, plotR - dotMargin)
        val dotY = py.coerceIn(plotT + dotMargin, plotB - dotMargin)
        if (dragging) canvas.drawCircle(dotX, dotY, 24f * density, haloPaint)
        canvas.drawCircle(dotX, dotY, dotR, dotBgPaint)
        canvas.drawCircle(dotX, dotY, dotR, dotRingPaint)
    }

    private fun clipRoundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rx: Float) {
        val path = Path().apply { addRoundRect(l, t, r, b, rx, rx, Path.Direction.CW) }
        c.clipPath(path)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pad = 14f * density
                if (event.x < plotL - pad || event.x > plotR + pad ||
                    event.y < plotT - pad || event.y > plotB + pad) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyTouch(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float, y: Float) {
        val w = (plotR - plotL).coerceAtLeast(1f)
        val hh = (plotB - plotT).coerceAtLeast(1f)
        val newDiff = ((x - plotL) / w * 100f).coerceIn(0f, 100f)
        val newDens = ((plotB - y) / hh * 100f).coerceIn(0f, 100f)
        diffusionPct = newDiff
        densityPct = newDens
        onChanged?.invoke(newDiff, newDens)
    }
}
