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
    // Slider-thumb paints — mirror the density/diffusion view's dot
    // styling EXACTLY: bg-coloured fill so the track-line punches
    // cleanly through the ring, a 2.5 px light-grey ring on top, amber
    // ring when grabbed. (Stroke width is raw px to match the X/Y dot.)
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }
    private val handleFillActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD54F.toInt()
        strokeWidth = 2.5f
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFDDDDDD.toInt()
        strokeWidth = 2.5f
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

    // The chart is divided into three equal-width zones across the top
    // control line:
    //   [Direct Sound][   Pre-delay   ][ Early Reflections ][   Decay   ]
    //                     0..33 %           33..66 %             66..100 %
    // The zones (and the track-line they sit on) start AFTER the
    // Direct Sound capsule on the left, not at plotL — so the capsule
    // doesn't overlap the pre-delay range. Each circle is constrained
    // to its own zone with a linear param-min → max mapping; tick marks
    // at 0/33/66/100 % mark the min/max of each zone.
    private val preDelayMinMs = 0f
    private val preDelayMaxMs = 300f
    private val earlyMinMs = 50f
    private val earlyMaxMs = 1000f
    private val decayMinMs = 100f
    private val decayMaxMs = 20000f

    // Direct Sound capsule layout — shared between drawBars (which
    // draws the capsule itself) and zonesLeft() (which uses the
    // capsule's right edge as the left bound of the track-line).
    private val directSoundBarW = 18f * density
    private val directSoundSideInset = 4f * density
    private val directSoundGap = 4f * density

    private fun zonesLeft(): Float =
        plotL + directSoundSideInset + directSoundBarW + directSoundGap

    private fun zoneStart(zone: Int): Float {
        val left = zonesLeft()
        val w = plotR - left
        return left + w * (zone / 3f)
    }
    private fun zoneEnd(zone: Int): Float {
        val left = zonesLeft()
        val w = plotR - left
        return left + w * ((zone + 1) / 3f)
    }
    private fun preDelayToX(ms: Float): Float {
        val frac = ((ms - preDelayMinMs) / (preDelayMaxMs - preDelayMinMs)).coerceIn(0f, 1f)
        return zoneStart(0) + frac * (zoneEnd(0) - zoneStart(0))
    }
    private fun xToPreDelay(x: Float): Float {
        val frac = ((x - zoneStart(0)) / (zoneEnd(0) - zoneStart(0))).coerceIn(0f, 1f)
        return preDelayMinMs + frac * (preDelayMaxMs - preDelayMinMs)
    }
    private fun earlyToX(ms: Float): Float {
        val frac = ((ms - earlyMinMs) / (earlyMaxMs - earlyMinMs)).coerceIn(0f, 1f)
        return zoneStart(1) + frac * (zoneEnd(1) - zoneStart(1))
    }
    private fun xToEarly(x: Float): Float {
        val frac = ((x - zoneStart(1)) / (zoneEnd(1) - zoneStart(1))).coerceIn(0f, 1f)
        return earlyMinMs + frac * (earlyMaxMs - earlyMinMs)
    }
    private fun decayToX(ms: Float): Float {
        val frac = ((ms - decayMinMs) / (decayMaxMs - decayMinMs)).coerceIn(0f, 1f)
        return zoneStart(2) + frac * (zoneEnd(2) - zoneStart(2))
    }
    private fun xToDecay(x: Float): Float {
        val frac = ((x - zoneStart(2)) / (zoneEnd(2) - zoneStart(2))).coerceIn(0f, 1f)
        return decayMinMs + frac * (decayMaxMs - decayMinMs)
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

        // The three control circles sit on a single horizontal track-
        // line near the bottom of a top control band. Above the line
        // (still inside the band) sit the three zone labels, each
        // centred between its zone's min/max tick marks. The bars +
        // envelope occupy the rest of the height below the band.
        val sideMargin = 6f * density
        val bottomMargin = 6f * density
        val topBandH = 42f * density
        plotL = sideMargin
        plotR = w - sideMargin
        plotT = topBandH
        plotB = h - bottomMargin
        controlBandTop = 2f * density
        controlBandBottom = topBandH - 2f * density

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
        // Single horizontal track-line across the top band that the
        // three circles sit on. Starts at the right edge of the Direct
        // Sound capsule (zonesLeft()) instead of plotL, so the line
        // doesn't extend "past" / behind the capsule. Sits low in the
        // band so the zone labels have room to live ABOVE the line,
        // centred between each zone's min/max tick marks.
        val lineY = trackLineY()
        val lineLeft = zonesLeft()
        c.drawLine(lineLeft, lineY, plotR, lineY, regionDividerPaint)
        val tickHalf = 5f * density
        for (i in 0..3) {
            val x = lineLeft + (plotR - lineLeft) * (i / 3f)
            c.drawLine(x, lineY - tickHalf, x, lineY + tickHalf, regionDividerPaint)
        }

        // Zone labels — fixed positions, NOT attached to the dragged
        // circles. Each label is centred between its zone's min/max
        // ticks (i.e. the zone midpoint) and sits above the line.
        val labels = arrayOf("Pre-delay", "Early Reflections", "Decay")
        val labelY = lineY - 6f * density - controlLabelPaint.descent()
        for (zone in 0..2) {
            val zoneMidX = (zoneStart(zone) + zoneEnd(zone)) / 2f
            c.drawText(labels[zone], zoneMidX, labelY, controlLabelPaint)
        }
    }

    private fun trackLineY(): Float {
        // The track-line sits low in the top band so labels fit above.
        return controlBandBottom - 12f * density
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

        val preDelayX = preDelayToX(reflectionsDelayMs)
        val earlyEndAbs = earlyToX(earlyReflectionsWidthMs)
        val decayEndAbs = decayToX(decayTimeMs)

        // 1. Source signal — hollow "doughnut" capsule at the very left
        //    edge. Outlined rounded-rect with empty interior; the
        //    vertical label "Direct Sound" runs up the inside.
        run {
            val barW = directSoundBarW
            val sideInset = directSoundSideInset
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
        // Three top-row thumbs, each constrained to its own third of
        // the chart width with a linear param-min → param-max mapping.
        //   Pre-delay  : 0..33 % zone, 0..300 ms
        //   Early Refl : 33..66 % zone, 50..1000 ms
        //   Decay      : 66..100 % zone, 100..20 000 ms
        // The thumbs are hollow circles styled the same as the X/Y dot
        // in the density/diffusion view (bg-coloured fill so the
        // track-line punches through, light-grey ring on top), but a
        // tiny bit smaller. Zone LABELS are drawn separately, anchored
        // to the zone midpoints; see [drawTimeAxisLine].
        //
        // Each circle is clamped INSIDE its zone (margin = r + 2 px) so
        // it never visually pokes past its tick mark — same trick the
        // X/Y graph's dot uses. The underlying parameter still spans
        // its full min..max range; only the circle's visible position
        // is inset.
        val cy = trackLineY()
        val r = 5.5f * density
        val dotMargin = r + 2f

        fun clampInZone(zone: Int, x: Float) =
            x.coerceIn(zoneStart(zone) + dotMargin, zoneEnd(zone) - dotMargin)

        val preDelayX = clampInZone(0, preDelayToX(reflectionsDelayMs))
        val earlyX = clampInZone(1, earlyToX(earlyReflectionsWidthMs))
        val decayX = clampInZone(2, decayToX(decayTimeMs))

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
            c.drawCircle(x, cy, r, handleFillPaint)
            val ring = if (active) handleFillActivePaint else handleStrokePaint
            c.drawCircle(x, cy, r, ring)
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
                // Constrained to the 0..33 % zone; X maps linearly
                // onto reflectionsDelayMs in [0, 300] ms.
                val newDelay = xToPreDelay(x)
                reflectionsDelayMs = newDelay
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
            }
            Handle.EARLY_CIRCLE -> {
                // Constrained to the 33..66 % zone; X maps linearly
                // onto earlyReflectionsWidthMs in [50, 1000] ms.
                earlyReflectionsWidthMs = xToEarly(x)
            }
            Handle.DECAY_CIRCLE -> {
                // Constrained to the 66..100 % zone; X maps linearly
                // onto decayTimeMs in [100, 20 000] ms.
                val newDecay = xToDecay(x)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
            }
        }
    }
}
