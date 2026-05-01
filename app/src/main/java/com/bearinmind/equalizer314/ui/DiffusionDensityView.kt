package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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

    // Rounded "axis pill" labels — same paint trio as the
    // EqGraphView's "Band N | freq | dB | type" pill so the styling
    // reads as part of the same app.
    private val axisPillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 20f
    }
    private val axisPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt(); style = Paint.Style.FILL
    }
    private val axisPillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt(); style = Paint.Style.STROKE
        strokeWidth = 2f
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
    // Mini dots drawn INSIDE the X/Y dot to visualize what the
    // density (count) and diffusion (jitter) values are doing.
    private val miniDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); style = Paint.Style.FILL
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

            // Horizontal line at Y=v% — label sits at the left edge,
            // gridline broken around it. The "Density" pill (drawn
            // later) sits further right, *after* this number.
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
        val dotR = 40f
        // Small inset (3 dp) so the dot doesn't quite touch the card's
        // edges — keeps it from being clipped at the rounded corners
        // while staying visually close to the crosshair endpoints. The
        // crosshair lines still draw at the un-clamped px/py and so
        // continue to reach plotL/plotR/plotT/plotB at the extremes.
        val dotMargin = dotR + 3f * density
        val dotX = px.coerceIn(plotL + dotMargin, plotR - dotMargin)
        val dotY = py.coerceIn(plotT + dotMargin, plotB - dotMargin)
        if (dragging) canvas.drawCircle(dotX, dotY, dotR + 12f * density, haloPaint)
        canvas.drawCircle(dotX, dotY, dotR, dotBgPaint)
        drawMiniDots(canvas, dotX, dotY, dotR)
        canvas.drawCircle(dotX, dotY, dotR, dotRingPaint)

        // Axis pill labels. Density's centre sits 40dp from the left
        // edge; Diffusion's centre sits the same 40dp from the bottom
        // edge so the visual gap between each pill and its axis-number
        // row is identical.
        val pillEdgeOffset = 40f * density
        drawAxisPill(canvas, "Diffusion", (plotL + plotR) / 2f, plotB - pillEdgeOffset, rotated = false)
        drawAxisPill(canvas, "Density", plotL + pillEdgeOffset, (plotT + plotB) / 2f, rotated = true)
    }

    /** Renders the mini-dot pattern inside the X/Y dot.
     *  - Density drives the count: 4 (min) → 30 (max), with the latest
     *    dot fading in via alpha as the slider crosses each integer.
     *  - Diffusion drives the jitter: at 0 % the dots sit on a perfect
     *    rowed grid; at 100 % they are randomly scattered.
     *  - Grid extent (gridR) scales smoothly with density so existing
     *    dots migrate outward as new ones fade in — no snap reshuffles.
     *  Fixed RNG seed keeps each dot's jitter stable across redraws. */
    private fun drawMiniDots(canvas: Canvas, cx: Float, cy: Float, dotR: Float) {
        val miniR = 1.5f * density
        val nDotsFloat = 4f + (densityPct / 100f) * 26f  // continuous 4..30
        val effectiveR = dotR - miniR - 3f * density
        if (effectiveR <= 0f) return
        val diffFrac = (diffusionPct / 100f).coerceIn(0f, 1f)

        // Grid extent grows with density. Floor bumped to 0.85 so
        // even at min density (4 dots in a 2×2) the cluster fills most
        // of the circle and the rowed pattern is clearly readable at
        // min diffusion. Top still grows to 0.95 at max density.
        val densityFrac = ((nDotsFloat - 4f) / 26f).coerceIn(0f, 1f)
        val gridR = effectiveR * (0.85f + 0.10f * densityFrac)
        val gridHalfW = gridR * 0.781f   // 6 cols × 5 rows fits with
        val gridHalfH = gridR * 0.625f   // square cells, corners on r
        val cellStepX = gridHalfW * 2f / 5f
        val cellStepY = gridHalfH * 2f / 4f

        val rng = Random(42L)
        val baseAlpha = miniDotPaint.alpha
        for ((i, cell) in miniDotOrder.withIndex()) {
            val alpha = (nDotsFloat - i).coerceIn(0f, 1f)
            if (alpha <= 0f) break

            val col = cell.first
            val row = cell.second
            var px = cx - gridHalfW + col * cellStepX
            var py = cy - gridHalfH + row * cellStepY

            // Diffusion scatters dots away from their grid cell.
            val jitterScale = effectiveR * 0.7f * diffFrac
            px += (rng.nextFloat() - 0.5f) * 2f * jitterScale
            py += (rng.nextFloat() - 0.5f) * 2f * jitterScale

            // Clamp to inside the X/Y dot's circle.
            val dx = px - cx
            val dy = py - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > effectiveR) {
                px = cx + dx / dist * effectiveR
                py = cy + dy / dist * effectiveR
            }

            miniDotPaint.alpha = (alpha * baseAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, miniR, miniDotPaint)
        }
        miniDotPaint.alpha = baseAlpha
    }

    /** Order in which the 30 grid cells "appear" as density grows.
     *  Uses Chebyshev distance (max of dx, dy) from the grid's centre
     *  so cells fill in clean concentric squares — 4 dots = 2×2,
     *  6 dots = 2×3, 12 dots = 4×3, etc. Tie-break by row then col so
     *  the cluster grows symmetrically around the centre. */
    private val miniDotOrder: List<Pair<Int, Int>> = run {
        val maxCols = 6
        val maxRows = 5
        val centerCol = (maxCols - 1) / 2f
        val centerRow = (maxRows - 1) / 2f
        val cells = mutableListOf<Triple<Int, Int, Float>>()
        for (col in 0 until maxCols) {
            for (row in 0 until maxRows) {
                val dx = abs(col - centerCol)
                val dy = abs(row - centerRow)
                val dist = max(dx, dy)
                cells.add(Triple(col, row, dist))
            }
        }
        cells.sortWith(compareBy({ it.third }, { it.second }, { it.first }))
        cells.map { Pair(it.first, it.second) }
    }

    private fun drawAxisPill(canvas: Canvas, text: String, cx: Float, cy: Float, rotated: Boolean) {
        val textW = axisPillTextPaint.measureText(text)
        val padH = 14f
        val padV = 8f
        val cornerR = 12f * density
        // Pill rect centered on (cx, cy) before rotation. Ascent is
        // negative; the text baseline sits below center by half-height.
        val rectW = textW + padH * 2f
        val textHeight = 20f  // matches textSize
        val rectH = textHeight + padV * 2f
        val rect = android.graphics.RectF(
            cx - rectW / 2f, cy - rectH / 2f,
            cx + rectW / 2f, cy + rectH / 2f
        )
        canvas.save()
        if (rotated) canvas.rotate(-90f, cx, cy)
        canvas.drawRoundRect(rect, cornerR, cornerR, axisPillBgPaint)
        canvas.drawRoundRect(rect, cornerR, cornerR, axisPillStrokePaint)
        canvas.drawText(text, cx - textW / 2f, cy + textHeight / 2f - 4f, axisPillTextPaint)
        canvas.restore()
    }

    private fun clipRoundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rx: Float) {
        val path = Path().apply { addRoundRect(l, t, r, b, rx, rx, Path.Direction.CW) }
        c.clipPath(path)
    }

    // When the user touches near the dot's outline, we capture the
    // offset between the dot's centre and the touch point so that
    // throughout the drag the dot's centre stays at (touch + offset)
    // — i.e. the user's finger sits on the ring rather than on top of
    // the mini-dots inside.
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pad = 14f * density
                if (event.x < plotL - pad || event.x > plotR + pad ||
                    event.y < plotT - pad || event.y > plotB + pad) return false
                // Compute the dot's current centre position so we can
                // stash the (centre − touch) offset for the drag.
                val w = (plotR - plotL).coerceAtLeast(1f)
                val hh = (plotB - plotT).coerceAtLeast(1f)
                val curDotX = plotL + (diffusionPct / 100f) * w
                val curDotY = plotB - (densityPct / 100f) * hh
                grabOffsetX = curDotX - event.x
                grabOffsetY = curDotY - event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.x + grabOffsetX, event.y + grabOffsetY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyTouch(event.x + grabOffsetX, event.y + grabOffsetY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grabOffsetX = 0f
                grabOffsetY = 0f
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
