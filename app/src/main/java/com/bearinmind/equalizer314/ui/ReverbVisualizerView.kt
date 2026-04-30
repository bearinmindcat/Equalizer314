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
    // Hollow "doughnut" capsule for the Direct Sound bar. Outlined,
    // empty interior so the vertical "Direct Sound" label fits inside.
    private val directRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = directBarColor
    }
    private val directLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = directBarColor
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
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
        SOURCE_CIRCLE, PREDELAY_CIRCLE, EARLY_CIRCLE, DECAY_CIRCLE,
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

    // The bars in the graph use a fixed layout — they don't shift when
    // pre-delay / early-reflections / decay change, so the chart stays
    // visually stable. The bottom-row circles live on their own LINEAR
    // ms axis (0..ctrlMaxMs) — moving them now feels like sliding along
    // a steady ruler, not a stretchy log scale. The fixed max is large
    // enough to give each section a usable drag range; values beyond it
    // (e.g. very long decays) just park the circle at the right edge.
    private val ctrlMaxMs = 3000f
    private val preDelayMaxMs = 300f

    private fun ctrlMsToX(ms: Float): Float {
        val clamped = ms.coerceIn(0f, ctrlMaxMs)
        return plotL + (clamped / ctrlMaxMs) * (plotR - plotL)
    }
    private fun ctrlXToMs(x: Float): Float {
        val frac = ((x - plotL) / (plotR - plotL)).coerceIn(0f, 1f)
        return frac * ctrlMaxMs
    }

    // Legacy linear-time helpers — only used to cache the (now-unused)
    // curve-anchor handle positions. Bars no longer go through these.
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

        // Bars use a fixed layout (don't move with parameters); circles
        // slide on a stable log-ms axis below. xMaxMs is kept around for
        // legacy handle anchor caching but no longer drives bar layout.
        val earlyDurationMs = earlyReflectionsWidthMs
        val decayDurationMs = decayTimeMs
        xMaxMs = (preDelayMaxMs + earlyDurationMs + decayDurationMs + 200f)
            .coerceIn(1000f, 10000f)
        preEnd = timeToX(reflectionsDelayMs)
        earlyEnd = timeToX(reflectionsDelayMs + earlyDurationMs)
        decayEnd = timeToX((reflectionsDelayMs + earlyDurationMs + decayDurationMs).coerceAtMost(xMaxMs))

        drawBackground(canvas)
        drawGhostEnvelope(canvas, decayDurationMs)
        drawBars(canvas, earlyDurationMs, decayDurationMs)
        drawFrame(canvas)
        drawTimeAxisLine(canvas)
        drawControlCircles(canvas)
        drawHandles(canvas)
    }

    private fun drawTimeAxisLine(c: Canvas) {
        // Thin "ms" reference line just under the bars, no labels —
        // the three control circles below sit on this shared axis.
        val y = plotB + (controlBandTop - plotB) * 0.55f
        c.drawLine(plotL, y, plotR, y, regionDividerPaint)
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
        // Bars are anchored to the same X positions as the bottom-row
        // circles, so dragging a circle visibly slides its region:
        //   Source     : always at plotL
        //   Early refl : Pre-delay circle X → Early Refl circle X
        //   Body+tail  : Early Refl circle X → Decay circle X
        // Heights conform to the fixed top-left → bottom-right envelope
        // (envelopeAtX). Every other bar is scaled shorter (alt-bar
        // shrink) to mimic the constructive/destructive interference
        // pattern of overlapping reflections in a real reverb tail.
        val altShrink = 0.65f

        val preDelayX = ctrlMsToX(reflectionsDelayMs)
        val earlyEndAbs = ctrlMsToX(reflectionsDelayMs + earlyReflectionsWidthMs)
        val decayEndAbs = ctrlMsToX(reflectionsDelayMs + earlyReflectionsWidthMs + decayTimeMs)

        // 1. Source signal — hollow "doughnut" capsule at the very left
        //    edge. Outlined rounded-rect with empty interior; the
        //    vertical label "Direct Sound" runs up the inside.
        run {
            val barW = 18f * density
            val sideInset = 4f * density
            val left = plotL + sideInset
            val right = left + barW
            val top = amp01ToY(envelopeAtX(left + barW / 2f)) + directRingPaint.strokeWidth * 0.5f
            val bottom = plotB - directRingPaint.strokeWidth * 0.5f
            val cornerR = barW / 2f  // fully rounded ends → capsule
            c.drawRoundRect(left, top, right, bottom, cornerR, cornerR, directRingPaint)

            // Vertical text inside, rotated -90° so it reads bottom →
            // top. Centred in the capsule's interior.
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            c.save()
            c.rotate(-90f, cx, cy)
            val baselineOffset = -(directLabelPaint.ascent() + directLabelPaint.descent()) / 2f
            c.drawText("Direct Sound", cx, cy + baselineOffset, directLabelPaint)
            c.restore()
        }

        // 2. Early reflections — 10 bars between Pre-delay and Early
        //    Refl circle positions.
        val earlyStartX = preDelayX
        val earlyEndX = earlyEndAbs.coerceAtLeast(earlyStartX + 1f)
        val nRefl = 10
        for (i in 0 until nRefl) {
            val fracT = (i + 0.5f) / nRefl
            val x = earlyStartX + fracT * (earlyEndX - earlyStartX)
            if (x > plotR) continue
            val baseAmp = envelopeAtX(x)
            val amp = if (i % 2 == 1) baseAmp * altShrink else baseAmp
            if (amp < 0.01f) continue
            c.drawLine(x, plotB, x, amp01ToY(amp), earlyBarPaint)
        }

        // 3. Reverb body + decay tail — 60 bars between Early Refl and
        //    Decay circle positions. Body = first 40 %, tail = the
        //    remaining 60 %.
        val tailStartX = earlyEndX
        val tailEndX = decayEndAbs.coerceIn(tailStartX + 1f, plotR)
        val nTail = 60
        val bodyTailSplit = 0.40f
        for (i in 0 until nTail) {
            val fracT = (i + 0.5f) / nTail
            val x = tailStartX + fracT * (tailEndX - tailStartX)
            if (x > plotR) continue
            val baseAmp = envelopeAtX(x)
            val amp = if (i % 2 == 1) baseAmp * altShrink else baseAmp
            if (amp < 0.01f) continue
            val paint = if (fracT < bodyTailSplit) bodyBarPaint else tailBarPaint
            c.drawLine(x, plotB, x, amp01ToY(amp), paint)
        }

        // Curve-anchor handles aren't drawn anymore; keep cached anchor
        // positions on the legacy linear-time axis for any future re-
        // enable.
        val refl = reflectionsDelayMs
        val refLevel = dbToAmp01(reflectionsLevelDb)
        val roomLevel = dbToAmp01(roomLevelDb)
        val tailStartMs = refl + earlyDurationMs
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
        // Three bottom-row circles, all on the same log-ms axis. Each
        // circle's position represents the cumulative time at which
        // that region ENDS:
        //   Pre-delay  → reflectionsDelayMs
        //   Early Refl → reflectionsDelayMs + earlyReflectionsWidthMs
        //   Decay      → ... + decayTimeMs
        // Log scale is used so all three params fit on one shared axis
        // even though their natural ranges span 0–300 ms / 50–1000 ms /
        // 100–20 000 ms.
        val cy = (controlBandTop + controlBandBottom) / 2f
        val r = 9f * density

        val preDelayX = ctrlMsToX(reflectionsDelayMs).coerceIn(plotL + r, plotR - r)
        val earlyX = ctrlMsToX(reflectionsDelayMs + earlyReflectionsWidthMs)
            .coerceIn(plotL + r, plotR - r)
        val decayX = ctrlMsToX(reflectionsDelayMs + earlyReflectionsWidthMs + decayTimeMs)
            .coerceIn(plotL + r, plotR - r)

        handlePos[Handle.PREDELAY_CIRCLE] = HandlePos(preDelayX, cy)
        handlePos[Handle.EARLY_CIRCLE] = HandlePos(earlyX, cy)
        handlePos[Handle.DECAY_CIRCLE] = HandlePos(decayX, cy)
        handlePos.remove(Handle.SOURCE_CIRCLE)

        for ((h, x) in listOf(
            Handle.PREDELAY_CIRCLE to preDelayX,
            Handle.EARLY_CIRCLE to earlyX,
            Handle.DECAY_CIRCLE to decayX,
        )) {
            val active = h == grabbed
            val fill = if (active) handleFillActivePaint else handleFillPaint
            c.drawCircle(x, cy, r, fill)
            c.drawCircle(x, cy, r, handleStrokePaint)
            val label = when (h) {
                Handle.PREDELAY_CIRCLE -> "Pre-delay"
                Handle.EARLY_CIRCLE -> "Early Refl"
                Handle.DECAY_CIRCLE -> "Decay"
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
        // Only the bottom-row circles are user-touchable right now;
        // ignore any leftover curve-anchor handle positions.
        val touchable = setOf(
            Handle.PREDELAY_CIRCLE, Handle.EARLY_CIRCLE, Handle.DECAY_CIRCLE,
        )
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
                // All three circles share the log-ms axis. Pre-delay
                // circle sits at reflectionsDelayMs, so the drag X
                // maps directly onto that param.
                val newDelay = ctrlXToMs(x).coerceIn(0f, 300f)
                reflectionsDelayMs = newDelay
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
            }
            Handle.EARLY_CIRCLE -> {
                // Circle sits at (pre-delay + early width), so the
                // new width = (circle_x → ms) minus pre-delay.
                val newWidth = (ctrlXToMs(x) - reflectionsDelayMs).coerceIn(50f, 1000f)
                earlyReflectionsWidthMs = newWidth
            }
            Handle.DECAY_CIRCLE -> {
                // Circle sits at (pre-delay + early + decay), so the
                // new decay = (circle_x → ms) minus the rest.
                val newDecay = (ctrlXToMs(x) - reflectionsDelayMs - earlyReflectionsWidthMs)
                    .coerceIn(100f, 20000f)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
            }
        }
    }
}
