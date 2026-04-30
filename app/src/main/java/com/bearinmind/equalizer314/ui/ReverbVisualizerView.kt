package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * "Swarm-Reverb"-style amplitude visualization for EnvironmentalReverb.
 * Draws thin vertical bars rising from the baseline, each one
 * representing a reflection in the IR. Pink-to-purple vertical gradient
 * shared across all bars; dark moody gradient background. A faint
 * exponential-decay envelope sits behind the bars as a ghost reference.
 *
 * Three regions still present (Pre-delay | Early Reflections | Decay)
 * with the same labels, total-decay-time arrow, amplitude axis label,
 * and five drag handles.
 */
class ReverbVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Param {
        DECAY_TIME, DECAY_HF, REVERB_LEVEL, ROOM_LEVEL,
        REFLECTIONS_DELAY, REFLECTIONS_LEVEL,
    }

    /** Identities of the bottom-row control circles. */
    enum class Control { SOURCE_SIGNAL, PRE_DELAY }

    var onParameterChanged: ((Param, Float) -> Unit)? = null

    var decayTimeMs: Float = 1490f; set(v) { field = v; invalidate() }
    var decayHfRatio: Float = 0.83f; set(v) { field = v; invalidate() }
    var reverbLevelDb: Float = -26f; set(v) { field = v; invalidate() }
    var roomLevelDb: Float = -10f; set(v) { field = v; invalidate() }
    var reflectionsDelayMs: Float = 7f; set(v) { field = v; invalidate() }
    var reflectionsLevelDb: Float = -10f; set(v) { field = v; invalidate() }
    var diffusionPct: Float = 100f; set(v) { field = v; invalidate() }
    var densityPct: Float = 100f; set(v) { field = v; invalidate() }
    /** Visual-only width of the early-reflection cluster. Adjusted by
     *  the Early Reflections drag circle. Not yet plumbed into a real
     *  EnvironmentalReverb parameter. */
    var earlyReflectionsWidthMs: Float = 268f
        set(v) { field = v.coerceIn(50f, 1000f); invalidate() }

    private val density = context.resources.displayMetrics.density

    // ---- Color palette (all-grey, "Visualizing Reverb" reference) ----

    private val bgColor = 0xFF1A1A1A.toInt()
    private val directBarColor = 0xFFE8E8E8.toInt()  // brightest — direct sound
    private val earlyBarColor = 0xFFBBBBBB.toInt()   // early reflections
    private val bodyBarColor = 0xFF888888.toInt()    // reverb body
    private val tailBarColor = 0xFF555555.toInt()    // decay tail
    private val ghostEnvelopeColor = 0x44999999.toInt()

    // ---- Paints --------------------------------------------------------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }
    private val directBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
        color = directBarColor
    }
    private val earlyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * density
        color = earlyBarColor
    }
    private val bodyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        color = bodyBarColor
    }
    private val tailBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.3f * density
        color = tailBarColor
    }
    private val ghostEnvelopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ghostEnvelopeColor
        strokeWidth = 1.4f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val frameLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val regionDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55AAAAAA.toInt()
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val amplitudeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 10f * density
        typeface = Typeface.DEFAULT
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE2E2E2.toInt()
    }
    private val handleFillActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD54F.toInt()
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC000000.toInt()
        strokeWidth = 1f * density
    }

    private val ghostPath = Path()

    private val controlLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }

    // ---- Handles -------------------------------------------------------

    private enum class Handle {
        ROOM, REFLECTIONS, REVERB, DECAY_HF, DECAY_END,
        SOURCE_CIRCLE, PREDELAY_CIRCLE, EARLY_CIRCLE,
    }
    private data class HandlePos(val x: Float, val y: Float)
    private val handlePos = HashMap<Handle, HandlePos>()
    private var grabbed: Handle? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    private var plotL = 0f; private var plotT = 0f
    private var plotR = 0f; private var plotB = 0f
    private var xMaxMs = 0f
    private var preEnd = 0f
    private var earlyEnd = 0f
    private var decayEnd = 0f
    private var controlBandTop = 0f
    private var controlBandBottom = 0f

    // Single linear time axis across the whole chart so dragging
    // pre-delay / early reflections / decay all move at a consistent
    // ms-per-pixel rate. xMaxMs reserves the API-max pre-delay slot
    // (300 ms) regardless of the current value, so widening the
    // pre-delay doesn't rubber-band the chart's overall scale.
    private val preDelayMaxMs = 300f

    private fun timeToX(ms: Float) = plotL + (ms / xMaxMs) * (plotR - plotL)
    private fun xToTime(x: Float) = ((x - plotL) / (plotR - plotL)) * xMaxMs
    private fun dbToAmp01(db: Float) = ((db + 90f) / 90f).coerceIn(0f, 1f)
    private fun amp01ToY(a: Float) = plotB - a.coerceIn(0f, 1f) * (plotB - plotT)
    private fun yToAmp01(y: Float) = ((plotB - y) / (plotB - plotT)).coerceIn(0f, 1f)
    private fun amp01ToDb(a: Float) = a.coerceIn(0f, 1f) * 90f - 90f

    // ---- Drawing -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Minimal margins — no axis labels, no region labels, no top
        // arrow. Just a small breathing space around the plot and the
        // bottom control band.
        val leftLabelW = 6f * density
        val bottomLabelH = 6f * density
        val topArrowH = 6f * density
        val controlBandH = 30f * density
        plotL = leftLabelW
        plotT = topArrowH
        plotR = w - 6f * density
        plotB = h - bottomLabelH - controlBandH
        controlBandTop = plotB + bottomLabelH * 0.4f
        controlBandBottom = h - 2f * density

        // Linear graph for now — no Decay HF Ratio non-linearity, no
        // Density / Diffusion influence on the rendered bars. xMaxMs
        // is capped so the pre-delay (0..300 ms) always claims a
        // visible chunk of chart width regardless of decay length;
        // long decays clip off the right edge instead.
        val earlyDurationMs = earlyReflectionsWidthMs
        val decayDurationMs = decayTimeMs
        val naturalMaxMs = preDelayMaxMs + earlyDurationMs + decayDurationMs + 200f
        xMaxMs = naturalMaxMs.coerceIn(1000f, 1500f)
        preEnd = timeToX(reflectionsDelayMs)
        earlyEnd = timeToX(reflectionsDelayMs + earlyDurationMs)
        decayEnd = timeToX((reflectionsDelayMs + earlyDurationMs + decayDurationMs).coerceAtMost(xMaxMs))

        drawBackground(canvas)
        drawGhostEnvelope(canvas, decayDurationMs)
        drawBars(canvas, earlyDurationMs, decayDurationMs)
        drawFrame(canvas)
        drawControlCircles(canvas)
        drawHandles(canvas)
    }

    private fun drawBackground(c: Canvas) {
        c.drawRect(plotL, plotT, plotR, plotB, bgPaint)
    }

    private fun drawGhostEnvelope(c: Canvas, decayDurationMs: Float) {
        // The envelope is a single straight line from the top-left
        // corner of the plot to the bottom-right. Pre-delay and
        // early-reflections width move the BARS along the time axis,
        // but the envelope shape stays fixed.
        ghostPath.reset()
        ghostPath.moveTo(plotL, plotT)
        ghostPath.lineTo(plotR, plotB)
        c.drawPath(ghostPath, ghostEnvelopePaint)
    }

    /** Linear amplitude (0..1) at a given x: 1.0 at the left edge,
     *  0.0 at the right edge. All bars sample this so their heights
     *  drop top-left to bottom-right regardless of region. */
    private fun envelopeAtX(x: Float): Float {
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        return (1f - (x - plotL) / plotW).coerceIn(0f, 1f)
    }

    private fun drawBars(c: Canvas, earlyDurationMs: Float, decayDurationMs: Float) {
        val refl = reflectionsDelayMs
        val refLevel = dbToAmp01(reflectionsLevelDb)
        val roomLevel = dbToAmp01(roomLevelDb)
        val tailStartMs = refl + earlyDurationMs

        // Every bar samples its height from [envelopeAtX], a single
        // straight line from (plotL, plotT) to (plotR, plotB). That
        // way the swarm always traces a clean diagonal from upper-left
        // to lower-right regardless of where pre-delay or the early-
        // reflection cluster lands on the time axis.

        // 1. Source signal — single bar at t=0, inset by half the
        //    stroke width so the line reads fully on-screen.
        run {
            val inset = directBarPaint.strokeWidth * 0.5f + 1f * density
            val x0 = (timeToX(0f) + inset).coerceAtLeast(plotL + inset)
            val amp = envelopeAtX(x0)
            c.drawLine(x0, plotB, x0, amp01ToY(amp), directBarPaint)
        }

        // 2. Early reflections — fixed count, evenly spaced.
        val nRefl = 10
        for (i in 0 until nRefl) {
            val fracT = (i + 0.5f) / nRefl
            val t = refl + earlyDurationMs * fracT
            val x = timeToX(t)
            val amp = envelopeAtX(x)
            if (amp < 0.01f) continue
            c.drawLine(x, plotB, x, amp01ToY(amp), earlyBarPaint)
        }

        // 3. Reverb body + decay tail — fixed count, evenly spaced.
        //    Body = first 40 % of the tail region, tail = remaining 60 %.
        val bodyTailSplit = 0.40f
        val nTail = 60
        val tailDur = decayDurationMs.coerceAtLeast(50f)
        for (i in 0 until nTail) {
            val tailFrac = (i + 0.5f) / nTail
            val tMs = tailStartMs + tailDur * tailFrac
            val x = timeToX(tMs)
            if (x > plotR) continue
            val amp = envelopeAtX(x)
            if (amp < 0.01f) continue
            val paint = if (tailFrac < bodyTailSplit) bodyBarPaint else tailBarPaint
            c.drawLine(x, plotB, x, amp01ToY(amp), paint)
        }

        // Cache handle anchor positions on the underlying envelope.
        // Curve-anchor handles aren't drawn anymore but the cached
        // positions are kept for any future re-enable.
        handlePos[Handle.ROOM] = HandlePos(timeToX(0f), amp01ToY(roomLevel))
        handlePos[Handle.REFLECTIONS] = HandlePos(timeToX(refl), amp01ToY(refLevel))
        handlePos[Handle.REVERB] = HandlePos(timeToX(tailStartMs), amp01ToY(dbToAmp01(reverbLevelDb)))
        run {
            val midFrac = 0.5f
            val midT = tailStartMs + decayDurationMs * midFrac
            val midDb = reverbLevelDb - 60f * midFrac
            handlePos[Handle.DECAY_HF] = HandlePos(timeToX(midT), amp01ToY(dbToAmp01(midDb)))
        }
        handlePos[Handle.DECAY_END] = HandlePos(decayEnd, plotB)
    }

    private fun drawFrame(c: Canvas) {
        // Just the bottom baseline. No top arrow, no side rails, no
        // region dividers — keep the plot as clean as possible.
        c.drawLine(plotL, plotB, plotR, plotB, frameLinePaint)
    }

    private fun drawRegionLabels(c: Canvas) {
        val labelY = plotB + 12f * density
        val preCenter = (plotL + preEnd) / 2f
        val earlyCenter = (preEnd + earlyEnd) / 2f
        val decayCenter = (earlyEnd + plotR) / 2f
        c.drawText("Pre-delay", preCenter, labelY, labelPaint)
        c.drawText("Early Reflections", earlyCenter, labelY, labelPaint)
        c.drawText("Decay", decayCenter, labelY, labelPaint)
    }

    private fun drawAmplitudeLabel(c: Canvas) {
        c.save()
        val cx = plotL - 12f * density
        val cy = (plotT + plotB) / 2f
        c.rotate(-90f, cx, cy)
        amplitudeLabelPaint.textAlign = Paint.Align.CENTER
        c.drawText("amplitude", cx, cy + 4f * density, amplitudeLabelPaint)
        c.restore()
    }

    private fun drawTimeAxisLabel(c: Canvas) {
        // X-axis label, right-aligned just below the region labels —
        // mirrors the "amplitude" Y-axis label on the left.
        val savedAlign = amplitudeLabelPaint.textAlign
        amplitudeLabelPaint.textAlign = Paint.Align.RIGHT
        c.drawText("Time (ms)", plotR, plotB + 24f * density, amplitudeLabelPaint)
        amplitudeLabelPaint.textAlign = savedAlign
    }

    private fun drawHandles(c: Canvas) {
        // Curve-anchor handles intentionally not drawn — only the two
        // bottom-row circles (Pre-delay, Early Reflections) are user-
        // facing right now. [drawControlCircles] renders those.
    }

    private fun drawControlCircles(c: Canvas) {
        // Two bottom-row circles only:
        //   Pre-delay  : Reflections Delay (horizontal drag)
        //   Early Refl : centre of the early-reflection cluster — drag
        //                horizontally to shift the cluster in time.
        // Source-signal handle was removed so the graph stays clean.
        val cy = (controlBandTop + controlBandBottom) / 2f
        val r = 9f * density

        // Pre-delay circle anchors at the END of the gap (= start of
        // the cluster). Early circle anchors at the END of the early-
        // reflections cluster, so dragging it widens / narrows the
        // cluster independently of pre-delay.
        val preDelayX = timeToX(reflectionsDelayMs).coerceIn(plotL + r, plotR - r)
        val earlyX = timeToX(reflectionsDelayMs + earlyReflectionsWidthMs)
            .coerceIn(plotL + r, plotR - r)

        handlePos[Handle.PREDELAY_CIRCLE] = HandlePos(preDelayX, cy)
        handlePos[Handle.EARLY_CIRCLE] = HandlePos(earlyX, cy)
        handlePos.remove(Handle.SOURCE_CIRCLE)

        for ((h, x) in listOf(
            Handle.PREDELAY_CIRCLE to preDelayX,
            Handle.EARLY_CIRCLE to earlyX,
        )) {
            val active = h == grabbed
            val fill = if (active) handleFillActivePaint else handleFillPaint
            c.drawCircle(x, cy, r, fill)
            c.drawCircle(x, cy, r, handleStrokePaint)
            val label = when (h) {
                Handle.PREDELAY_CIRCLE -> "Pre-delay"
                Handle.EARLY_CIRCLE -> "Early Refl"
                else -> ""
            }
            c.drawText(label, x, controlBandBottom - 1f * density, controlLabelPaint)
        }
    }

    // ---- Touch ---------------------------------------------------------

    private val hitRadiusPx: Float get() = 22f * density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = nearestHandle(event.x, event.y) ?: return false
                grabbed = target
                val pos = handlePos[target] ?: return false
                grabOffsetX = pos.x - event.x
                grabOffsetY = pos.y - event.y
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = grabbed ?: return false
                applyHandleDrag(target, event.x + grabOffsetX, event.y + grabOffsetY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                grabbed = null
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestHandle(x: Float, y: Float): Handle? {
        // Only the two bottom-row circles are user-touchable right
        // now; ignore any leftover curve-anchor handle positions.
        val touchable = setOf(Handle.PREDELAY_CIRCLE, Handle.EARLY_CIRCLE)
        var best: Handle? = null
        var bestDist = hitRadiusPx
        for ((h, p) in handlePos) {
            if (h !in touchable) continue
            val d = hypot((p.x - x).toDouble(), (p.y - y).toDouble()).toFloat()
            if (d < bestDist) { bestDist = d; best = h }
        }
        return best
    }

    private fun applyHandleDrag(h: Handle, x: Float, y: Float) {
        val cb = onParameterChanged
        when (h) {
            Handle.ROOM -> {
                val newDb = amp01ToDb(yToAmp01(y)).coerceIn(-90f, 0f)
                roomLevelDb = newDb
                cb?.invoke(Param.ROOM_LEVEL, newDb)
            }
            Handle.REFLECTIONS -> {
                val newDelay = xToTime(x).coerceIn(0f, 300f)
                val newDb = amp01ToDb(yToAmp01(y)).coerceIn(-90f, 10f)
                reflectionsDelayMs = newDelay
                reflectionsLevelDb = newDb
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
                cb?.invoke(Param.REFLECTIONS_LEVEL, newDb)
            }
            Handle.REVERB -> {
                val newDb = amp01ToDb(yToAmp01(y)).coerceIn(-90f, 20f)
                reverbLevelDb = newDb
                cb?.invoke(Param.REVERB_LEVEL, newDb)
            }
            Handle.DECAY_HF -> {
                val midAmp = yToAmp01(y)
                val midDb = amp01ToDb(midAmp)
                val drop = (reverbLevelDb - midDb).coerceIn(0.5f, 60f)
                val curveExp = (Math.log(60.0 / drop) / Math.log(2.0)).toFloat()
                val ratio = (2f - curveExp).coerceIn(0.1f, 2.0f)
                decayHfRatio = ratio
                cb?.invoke(Param.DECAY_HF, ratio)
            }
            Handle.DECAY_END -> {
                val tEnd = xToTime(x)
                val tailStartMs = reflectionsDelayMs + max(decayTimeMs * 0.18f, 80f)
                val effective = (tEnd - tailStartMs).coerceAtLeast(50f)
                val newDecay = (effective / decayHfRatio.coerceAtLeast(0.1f))
                    .coerceIn(100f, 20000f)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
            }
            Handle.SOURCE_CIRCLE -> {
                // Vertical drag adjusts Room Level. The Y coordinate
                // is read against the plot's Y range, not the control
                // band itself — drag up from the circle to raise the
                // source-signal bar.
                val newDb = amp01ToDb(yToAmp01(y)).coerceIn(-90f, 0f)
                roomLevelDb = newDb
                cb?.invoke(Param.ROOM_LEVEL, newDb)
            }
            Handle.PREDELAY_CIRCLE -> {
                // Horizontal drag adjusts Reflections Delay. The
                // circle sits at the END of the pre-delay gap, so
                // delay = (circle_x → time) directly.
                val newDelay = xToTime(x).coerceIn(0f, 300f)
                reflectionsDelayMs = newDelay
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
            }
            Handle.EARLY_CIRCLE -> {
                // Horizontal drag adjusts the WIDTH of the early-
                // reflection cluster (visual-only state). Pre-delay
                // stays put; only the cluster's right edge moves.
                val newWidth = (xToTime(x) - reflectionsDelayMs).coerceIn(50f, 1000f)
                earlyReflectionsWidthMs = newWidth
            }
        }
    }
}
