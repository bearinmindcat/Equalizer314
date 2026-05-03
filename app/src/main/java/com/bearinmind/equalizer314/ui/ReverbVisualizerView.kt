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
        REFLECTIONS_DELAY, REFLECTIONS_LEVEL, REVERB_DELAY,
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
        set(v) { field = v.coerceIn(0f, 1000f); invalidate() }
    /** Reverb Delay (ms) — silence between the early reflections and
     *  the start of the late reverb tail. EnvironmentalReverb API
     *  range is 0..100 ms. */
    var reverbDelayMs: Float = 11f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }

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
    // Dashed variant used when the cluster has collapsed (Early Refl
    // slider at its minimum). The dashes communicate "this region
    // exists but has no width yet."
    private val earlyBarDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 2.0f * density
        color = earlyBarColor
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(3f * density, 3f * density), 0f
        )
    }
    private val bodyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        color = bodyBarColor
    }
    // Dashed variant used when the tail has collapsed (Decay slider
    // at its minimum).
    private val bodyBarDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 1.6f * density
        color = bodyBarColor
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(3f * density, 3f * density), 0f
        )
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
    // Outline drawn around zones that allow 2-D drag (Early Reflections
    // and Decay). The dot inside the card can be dragged side-to-side
    // (existing X behaviour) and up-and-down (new Y behaviour, visual-
    // only for now).
    private val zoneCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF444444.toInt()
        strokeWidth = 1.5f * density
    }
    // Faint shaded fill drawn over the silent regions (Pre-delay and
    // Reverb Delay zones in the bar area) to communicate "intentionally
    // empty" instead of looking like dead pixels.
    private val silenceZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14FFFFFF.toInt()
    }
    // Vertical dotted line marking the centre of the chart — also the
    // left edge of the Reverb Delay zone (where its range starts).
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF666666.toInt()
        strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(4f * density, 4f * density), 0f
        )
    }
    // Early Reflections vertical-axis range — matches the Reflect (dB)
    // slider's valueFrom/valueTo in activity_environmental_reverb.xml
    // and the EnvironmentalReverb API's setReflectionsLevel range.
    private val reflectionsLevelMinDb = -90f
    private val reflectionsLevelMaxDb = 10f

    // Decay vertical-axis range — matches the Reverb (dB) slider's
    // valueFrom/valueTo (-90..+20 dB) and the API's setReverbLevel
    // range. Y position of the Decay dot maps to reverbLevelDb.
    private val reverbLevelMinDb = -90f
    private val reverbLevelMaxDb = 20f

    // HF Damping (Decay HF Ratio) range — matches the Decay HF slider's
    // valueFrom/valueTo (0.1..2.0) and the API's setDecayHFRatio range.
    // X position of the HF dot in the sub-band maps to decayHfRatio.
    private val decayHfRatioMin = 0.1f
    private val decayHfRatioMax = 2.0f

    private fun decayHfRatioToX(ratio: Float): Float {
        val frac = ((ratio - decayHfRatioMin) /
            (decayHfRatioMax - decayHfRatioMin)).coerceIn(0f, 1f)
        return zoneStart(3) + frac * (zoneEnd(3) - zoneStart(3))
    }
    private fun xToDecayHfRatio(x: Float): Float {
        val frac = ((x - zoneStart(3)) /
            (zoneEnd(3) - zoneStart(3))).coerceIn(0f, 1f)
        return decayHfRatioMin + frac * (decayHfRatioMax - decayHfRatioMin)
    }

    private fun hfDotInnerBounds(): FloatArray {
        // Returns [innerLeft, innerTop, innerRight, innerBottom].
        val r = 5.5f * density
        val dotMargin = r + 2f
        return floatArrayOf(
            zoneStart(3) + dotMargin,
            hfSubBandTop + dotMargin,
            zoneEnd(3) - dotMargin,
            hfSubBandBottom - dotMargin,
        )
    }

    // Dot is constrained to the inner half of the anti-diagonal —
    // antiDiagFrac in [0.25, 0.75] — so the Bezier control point
    // (= 2·dot − centre) stays inside the box and the curve never
    // balloons past the box's edges. The dot still passes through
    // the curve exactly at its midpoint.
    private val hfDotFracMin = 0.25f
    private val hfDotFracMax = 0.75f

    /** Map decayHfRatio onto a position along the HF sub-box's anti-
     *  diagonal: bottom-left-ish at ratio = min, top-right-ish at
     *  ratio = max, geometric centre at ratio = 1. The dot's range
     *  is restricted to [0.25, 0.75] of the anti-diagonal. */
    private fun decayHfRatioToDotPos(): Pair<Float, Float> {
        val b = hfDotInnerBounds()
        val ratioFrac = ((decayHfRatio - decayHfRatioMin) /
            (decayHfRatioMax - decayHfRatioMin)).coerceIn(0f, 1f)
        val antiDiagFrac = hfDotFracMin + ratioFrac * (hfDotFracMax - hfDotFracMin)
        val dotX = b[0] + antiDiagFrac * (b[2] - b[0])
        val dotY = b[3] - antiDiagFrac * (b[3] - b[1])
        return Pair(dotX, dotY)
    }

    /** Project an arbitrary touch (x, y) onto the anti-diagonal and
     *  convert that fraction back to a decayHfRatio value. The
     *  projection is clamped to the dot's restricted range so the
     *  curve always stays inside the box. */
    private fun pointToDecayHfRatio(x: Float, y: Float): Float {
        val b = hfDotInnerBounds()
        val ex = b[2] - b[0]
        val ey = b[1] - b[3]
        val len2 = (ex * ex + ey * ey).coerceAtLeast(1f)
        val px0 = x - b[0]
        val py0 = y - b[3]
        val rawFrac = (px0 * ex + py0 * ey) / len2
        val antiDiagFrac = rawFrac.coerceIn(hfDotFracMin, hfDotFracMax)
        val ratioFrac = ((antiDiagFrac - hfDotFracMin) /
            (hfDotFracMax - hfDotFracMin)).coerceIn(0f, 1f)
        return decayHfRatioMin + ratioFrac * (decayHfRatioMax - decayHfRatioMin)
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
        REVDELAY_CIRCLE, HF_DAMPING_CIRCLE,
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
    private var hfSubBandTop = 0f
    private var hfSubBandBottom = 0f

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
    private val earlyMinMs = 0f
    private val earlyMaxMs = 1000f
    private val revDelayMinMs = 0f
    private val revDelayMaxMs = 100f
    private val decayMinMs = 100f
    private val decayMaxMs = 20000f
    private val zoneCount = 4

    // Direct Sound capsule layout — shared between drawBars (which
    // draws the capsule itself) and zonesLeft() (which uses the
    // capsule's right edge as the left bound of the track-line).
    // edgeInset gives the capsule a small breathing margin from the
    // card's left and bottom edges so its full outline is clearly
    // visible. The Pre-delay zone starts a tiny 2 dp gap past the
    // capsule's right edge so the collapsed Early-Reflections dotted
    // line (drawn at preDelayX when both sliders are at min) reads
    // clearly instead of being hidden behind the capsule's outline.
    private val directSoundBarW = 18f * density
    private val directSoundEdgeInset = 4f * density
    private val directSoundGap = 2f * density

    private fun zonesLeft(): Float =
        plotL + directSoundEdgeInset + directSoundBarW + directSoundGap

    private fun zoneStart(zone: Int): Float {
        val left = zonesLeft()
        val w = plotR - left
        return left + w * (zone.toFloat() / zoneCount)
    }
    private fun zoneEnd(zone: Int): Float {
        val left = zonesLeft()
        val w = plotR - left
        return left + w * ((zone + 1).toFloat() / zoneCount)
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
    private fun revDelayToX(ms: Float): Float {
        val frac = ((ms - revDelayMinMs) / (revDelayMaxMs - revDelayMinMs)).coerceIn(0f, 1f)
        return zoneStart(2) + frac * (zoneEnd(2) - zoneStart(2))
    }
    private fun xToRevDelay(x: Float): Float {
        val frac = ((x - zoneStart(2)) / (zoneEnd(2) - zoneStart(2))).coerceIn(0f, 1f)
        return revDelayMinMs + frac * (revDelayMaxMs - revDelayMinMs)
    }
    private fun decayToX(ms: Float): Float {
        val frac = ((ms - decayMinMs) / (decayMaxMs - decayMinMs)).coerceIn(0f, 1f)
        return zoneStart(3) + frac * (zoneEnd(3) - zoneStart(3))
    }
    private fun xToDecay(x: Float): Float {
        val frac = ((x - zoneStart(3)) / (zoneEnd(3) - zoneStart(3))).coerceIn(0f, 1f)
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
        // (still inside the band) sit the four zone labels, each
        // centred between its zone's min/max tick marks. The bars +
        // envelope occupy the rest of the height below the band, with
        // the bottom and left edges flush against the card and the
        // right edge inset by 1 dp so all four zones share the same
        // narrower right edge.
        val sideMargin = 0f
        val bottomMargin = 0f
        val rightMargin = 4f * density
        val mainBandH = 90f * density
        val hfSubBandH = 90f * density  // matches main band so the HF box is the same height as the Decay box
        val topBandH = mainBandH + hfSubBandH
        plotL = sideMargin
        plotR = w - rightMargin
        plotT = topBandH
        plotB = h - bottomMargin
        controlBandTop = 2f * density
        controlBandBottom = mainBandH - 2f * density  // main band only — HF sub-band sits below
        hfSubBandTop = mainBandH + 2f * density
        hfSubBandBottom = topBandH - 2f * density

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
        drawSilenceZones(canvas)
        drawGhostEnvelope(canvas, decayDurationMs)
        drawBars(canvas, earlyDurationMs, decayDurationMs)
        drawFrame(canvas)
        drawCenterLine(canvas)
        drawZoneCards(canvas)
        drawHfDampingSubBox(canvas)
        drawTimeAxisLine(canvas)
        drawControlCircles(canvas)
        drawHandles(canvas)
    }

    /** Draws the HF Damping sub-box directly below the Decay zone box.
     *  The dot rides the box's ANTI-DIAGONAL — bottom-left at ratio
     *  = min, top-right at ratio = max, centre = ratio 1.0 (linear).
     *  The curve from (innerLeft, innerTop) to (innerRight, innerBottom)
     *  always passes through the dot at its midpoint (t = 0.5), so the
     *  same gesture both slides the dot along the diagonal AND keeps
     *  it on the curve as the curve flexes. */
    private val hfCurvePath = Path()
    private fun drawHfDampingSubBox(c: Canvas) {
        val cornerR = 6f * density
        val left = zoneStart(3)
        val right = zoneEnd(3)
        val top = hfSubBandTop
        val bottom = hfSubBandBottom
        c.drawRoundRect(left, top, right, bottom, cornerR, cornerR, zoneCardPaint)

        // Label "HF Damping" inside the sub-box, top-left.
        val savedAlign = controlLabelPaint.textAlign
        controlLabelPaint.textAlign = Paint.Align.LEFT
        val labelBaselineY = top + 12f * density - controlLabelPaint.ascent()
        c.drawText("HF Damping", left + 8f * density, labelBaselineY, controlLabelPaint)
        controlLabelPaint.textAlign = savedAlign

        val r = 5.5f * density
        val b = hfDotInnerBounds()
        val innerLeft = b[0]; val innerTop = b[1]
        val innerRight = b[2]; val innerBottom = b[3]
        val centerX = (innerLeft + innerRight) / 2f
        val centerY = (innerTop + innerBottom) / 2f
        val (dotX, dotY) = decayHfRatioToDotPos()

        // Anti-diagonal "rail" the dot slides along.
        c.drawLine(innerLeft, innerBottom, innerRight, innerTop, centerLinePaint)

        // Quadratic Bezier with control point Cx = 2·dot − centre,
        // Cy = 2·dot − centre — chosen so B(0.5) = (dotX, dotY) and
        // the curve passes EXACTLY through the dot at its midpoint.
        // Because the dot's range is restricted to [0.25, 0.75] of
        // the anti-diagonal, the control point is mathematically
        // guaranteed to stay inside the box, so the curve does too.
        val cx = 2f * dotX - centerX
        val cy = 2f * dotY - centerY
        hfCurvePath.reset()
        hfCurvePath.moveTo(innerLeft, innerTop)
        hfCurvePath.quadTo(cx, cy, innerRight, innerBottom)
        c.drawPath(hfCurvePath, centerLinePaint)

        handlePos[Handle.HF_DAMPING_CIRCLE] = HandlePos(dotX, dotY)
        val active = grabbed == Handle.HF_DAMPING_CIRCLE
        c.drawCircle(dotX, dotY, r, handleFillPaint)
        val ring = if (active) handleFillActivePaint else handleStrokePaint
        c.drawCircle(dotX, dotY, r, ring)
    }

    /** Vertical dotted line at the start of the Reverb Delay zone —
     *  serves as the "centre divider" of the graph and marks where
     *  the Reverb Delay drag range begins. */
    private fun drawCenterLine(c: Canvas) {
        val centerX = zoneStart(2)
        c.drawLine(centerX, plotT, centerX, plotB, centerLinePaint)
    }

    /** Faint shaded silence regions whose WIDTH tracks the Pre-delay
     *  and Reverb Delay sliders, with their TOP following the envelope
     *  line so the shading sits cleanly beneath the diagonal envelope
     *  rather than being a flat-topped rectangle:
     *    - Pre-delay shading : trapezoid bounded by zoneStart(0) on the
     *      left, the Pre-delay circle's X on the right, the envelope
     *      on top, and plotB on the bottom.
     *    - Reverb Delay shading : same shape, but bounded by
     *      zoneStart(2) and the Reverb Delay circle's X. */
    private fun drawSilenceZones(c: Canvas) {
        val preDelayX = preDelayToX(reflectionsDelayMs)
        if (preDelayX > zoneStart(0)) {
            drawSilenceTrapezoid(c, zoneStart(0), preDelayX)
        }
        val revDelayX = revDelayToX(reverbDelayMs)
        if (revDelayX > zoneStart(2)) {
            drawSilenceTrapezoid(c, zoneStart(2), revDelayX)
        }
    }

    private val silencePath = Path()
    private fun drawSilenceTrapezoid(c: Canvas, leftX: Float, rightX: Float) {
        val leftTopY = amp01ToY(envelopeAtX(leftX))
        val rightTopY = amp01ToY(envelopeAtX(rightX))
        silencePath.reset()
        silencePath.moveTo(leftX, plotB)
        silencePath.lineTo(leftX, leftTopY)
        silencePath.lineTo(rightX, rightTopY)
        silencePath.lineTo(rightX, plotB)
        silencePath.close()
        c.drawPath(silencePath, silenceZonePaint)
    }

    /** Card outlines around the Early Reflections (zone 1) and Decay
     *  (zone 3) zones. Both boxes are the SAME width because plotR is
     *  inset globally by 1 dp — the Decay box's right edge no longer
     *  needs a special inset hack. */
    private fun drawZoneCards(c: Canvas) {
        val cornerR = 8f * density
        val topPad = 2f * density
        val bottomPad = 2f * density
        for (zone in listOf(1, 3)) {
            val left = zoneStart(zone)
            val right = zoneEnd(zone)
            val top = controlBandTop + topPad
            val bottom = controlBandBottom - bottomPad
            c.drawRoundRect(left, top, right, bottom, cornerR, cornerR, zoneCardPaint)
        }
    }

    /** Bounds of a zone's drag card — used to clamp the dot's Y so it
     *  doesn't escape the visible card. Returns (top, bottom) Y. */
    private fun zoneCardYBounds(): Pair<Float, Float> {
        val r = 5.5f * density
        val topPad = 2f * density
        val bottomPad = 2f * density
        return Pair(
            controlBandTop + topPad + r + 2f,
            controlBandBottom - bottomPad - r - 2f,
        )
    }

    /** Map the Early Reflections dot's Y position back to the
     *  reflectionsLevelDb range. Top of card = max dB, bottom = min. */
    private fun reflectionsLevelDbToY(): Float {
        val (cardYTop, cardYBot) = zoneCardYBounds()
        val frac = ((reflectionsLevelDb - reflectionsLevelMinDb) /
            (reflectionsLevelMaxDb - reflectionsLevelMinDb)).coerceIn(0f, 1f)
        return cardYBot - frac * (cardYBot - cardYTop)
    }
    private fun yToReflectionsLevelDb(y: Float): Float {
        val (cardYTop, cardYBot) = zoneCardYBounds()
        val ranged = y.coerceIn(cardYTop, cardYBot)
        val frac = (cardYBot - ranged) / (cardYBot - cardYTop)
        return reflectionsLevelMinDb + frac * (reflectionsLevelMaxDb - reflectionsLevelMinDb)
    }

    /** Map the Decay dot's Y position back to the reverbLevelDb range.
     *  Top of card = max dB, bottom = min. */
    private fun reverbLevelDbToY(): Float {
        val (cardYTop, cardYBot) = zoneCardYBounds()
        val frac = ((reverbLevelDb - reverbLevelMinDb) /
            (reverbLevelMaxDb - reverbLevelMinDb)).coerceIn(0f, 1f)
        return cardYBot - frac * (cardYBot - cardYTop)
    }
    private fun yToReverbLevelDb(y: Float): Float {
        val (cardYTop, cardYBot) = zoneCardYBounds()
        val ranged = y.coerceIn(cardYTop, cardYBot)
        val frac = (cardYBot - ranged) / (cardYBot - cardYTop)
        return reverbLevelMinDb + frac * (reverbLevelMaxDb - reverbLevelMinDb)
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
        for (i in 0..zoneCount) {
            val x = lineLeft + (plotR - lineLeft) * (i.toFloat() / zoneCount)
            c.drawLine(x, lineY - tickHalf, x, lineY + tickHalf, regionDividerPaint)
        }

        // Zone labels — fixed positions, NOT attached to the dragged
        // circles. Each label is centred between its zone's min/max
        // ticks (i.e. the zone midpoint) and sits above the line.
        val labels = arrayOf("Pre-delay", "Early Reflections", "Reverb Delay", "Decay")
        val labelY = lineY - 6f * density - controlLabelPaint.descent()
        for (zone in 0 until zoneCount) {
            val zoneMidX = (zoneStart(zone) + zoneEnd(zone)) / 2f
            c.drawText(labels[zone], zoneMidX, labelY, controlLabelPaint)
        }
    }

    private fun trackLineY(): Float {
        // The track-line sits centred vertically in the top band, so
        // there's equal breathing room above (label area) and below
        // (between the line and the bars).
        return (controlBandTop + controlBandBottom) / 2f
    }

    private fun drawBackground(c: Canvas) {
        c.drawRect(plotL, plotT, plotR, plotB, bgPaint)
    }

    private fun drawGhostEnvelope(c: Canvas, decayDurationMs: Float) {
        // The envelope starts at the Direct Sound capsule's TOPMOST
        // point (the apex of its top arc, at the capsule's centre X)
        // and runs as a single straight line down to the plot's bottom-
        // right corner. The capsule is bg-filled when drawn, so the
        // portion of the line inside the capsule is masked — visually
        // the line appears to attach to the top of the capsule.
        val capsuleRight = plotL + directSoundEdgeInset + directSoundBarW
        val capsuleCenterX = capsuleRight - directSoundBarW / 2f
        val capsuleTop = amp01ToY(envelopeAtX(capsuleRight))
        ghostPath.reset()
        ghostPath.moveTo(capsuleCenterX, capsuleTop)
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

        // 1. Source signal — hollow "doughnut" capsule near the card's
        //    bottom-left corner with a small inset so its outline is
        //    fully visible (avoids clipping at the card's rounded
        //    corners). The capsule's height stays the same as before;
        //    instead, the envelope line is anchored to start at the
        //    capsule's topmost point so the line attaches to the top
        //    of the card. The interior of the capsule is filled with
        //    the card's bg colour to mask the portion of the line that
        //    passes inside the capsule.
        run {
            val barW = directSoundBarW
            val cornerR = barW / 2f  // fully rounded ends → capsule
            val left = plotL + directSoundEdgeInset
            val right = left + barW
            val top = amp01ToY(envelopeAtX(right))
            val bottom = plotB - directSoundEdgeInset
            c.drawRoundRect(left, top, right, bottom, cornerR, cornerR, bgPaint)
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

        // 2. Early reflections — cluster anchored at Pre-delay's X
        //    (preDelayX). Visual width is proportional to early-refl
        //    duration, scaled to the AVAILABLE space from preDelayX
        //    out to the end of zone 1 (the early-refl zone). So:
        //      - earlyWidth=max + preDelay=min → cluster fills zones
        //        0 + 1 (pre-delay zone is "consumed" since there's no
        //        gap before reflections start)
        //      - earlyWidth=max + preDelay=max → cluster fills zone 1
        //        only (full pre-delay gap pushes the cluster right)
        //      - earlyWidth=min + any preDelay → cluster collapses to
        //        a single overlapping line at preDelayX (no snap-open)
        run {
            val refLevel = dbToAmp01(reflectionsLevelDb)
            val earlyStartX = preDelayX
            val earlyFrac = ((earlyReflectionsWidthMs - earlyMinMs) /
                (earlyMaxMs - earlyMinMs)).coerceIn(0f, 1f)
            val earlyMaxLength = (zoneEnd(1) - earlyStartX).coerceAtLeast(0f)
            val earlyClusterLength = earlyFrac * earlyMaxLength
            val earlyEndX = (earlyStartX + earlyClusterLength)
                .coerceAtMost(zoneEnd(1))
                .coerceAtLeast(earlyStartX + 1f)
            val collapsed = earlyReflectionsWidthMs <= earlyMinMs + 0.5f
            val paint = if (collapsed) earlyBarDashedPaint else earlyBarPaint
            val nRefl = 10
            for (i in 0 until nRefl) {
                val fracT = (i + 0.5f) / nRefl
                val x = earlyStartX + fracT * (earlyEndX - earlyStartX)
                if (x > plotR) continue
                val baseAmp = envelopeAtX(x)
                val shrunk = if (i % 2 == 1) baseAmp * altShrink else baseAmp
                val amp = shrunk * refLevel
                if (amp < 0.01f) continue
                c.drawLine(x, plotB, x, amp01ToY(amp), paint)
            }
        }

        // 3. Reverb body + decay tail — starts at the Reverb Delay
        //    circle and ends at the Decay circle. When Reverb Delay
        //    is 0, the tail naturally extends back into zone 2 (the
        //    Reverb Delay zone) — there's no gap before the tail, so
        //    the decay fills both the Reverb Delay and Decay zones.
        //    As Reverb Delay grows, the gap re-opens and the tail
        //    retreats into zone 3. Body = first 40 %, tail = remaining 60 %.
        // Tail visual width is proportional to decay duration, scaled
        // to the AVAILABLE space from tailStartX out to plotR. So:
        //   - decay=max + reverbDelay=min → tail fills 50%..100%
        //     (zone 2 + zone 3, all the way to the graph's right edge)
        //   - decay=max + reverbDelay=max → tail fills zone 3 only
        //   - decay=min + any reverbDelay → tail collapses to a single
        //     line at tailStartX (no snap-open).
        val tailStartX = revDelayToX(reverbDelayMs)
        val decayFrac = ((decayTimeMs - decayMinMs) /
            (decayMaxMs - decayMinMs)).coerceIn(0f, 1f)
        val maxTailLength = (plotR - tailStartX).coerceAtLeast(0f)
        val tailLengthPx = decayFrac * maxTailLength
        val tailEndX = (tailStartX + tailLengthPx).coerceAtMost(plotR)
            .coerceAtLeast(tailStartX + 1f)
        val tailLevel = dbToAmp01(reverbLevelDb)
        val nTail = 30
        val bodyTailSplit = 0.40f
        val tailCollapsed = decayTimeMs <= decayMinMs + 0.5f
        // Bars decay from startAmp toward ZERO over the tail's span,
        // shaped by HF damping. This mimics exponential decay so the
        // bars look natural at any decay value, not just at max:
        //   - ratio = 1 → linear from startAmp to 0
        //   - ratio < 1 → concave-up, drops fast then plateaus near 0
        //     (HF dies — looks exponential)
        //   - ratio > 1 → concave-down, sustains then falls to 0
        // The bars' bottom edge always reaches ~0 at tailEndX,
        // regardless of where tailEndX sits on the chart.
        val tailSpan = (tailEndX - tailStartX).coerceAtLeast(1f)
        val ampAtTailStart = envelopeAtX(tailStartX)
        val hfDampingExp = (1f / decayHfRatio.coerceIn(0.1f, 2f)).coerceIn(0.5f, 2f)
        for (i in 0 until nTail) {
            val fracT = (i + 0.5f) / nTail
            val x = tailStartX + fracT * (tailEndX - tailStartX)
            if (x > plotR) continue
            val zoneFrac = ((x - tailStartX) / tailSpan).coerceIn(0f, 1f)
            val curvedAmp = ampAtTailStart * (1f - zoneFrac).pow(hfDampingExp)
            val shrunk = if (i % 2 == 1) curvedAmp * altShrink else curvedAmp
            val amp = shrunk * tailLevel
            if (amp < 0.01f) continue
            val paint = when {
                tailCollapsed -> bodyBarDashedPaint
                fracT < bodyTailSplit -> bodyBarPaint
                else -> tailBarPaint
            }
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
        // Four top-row thumbs, each constrained to its own quarter of
        // the chart width with a linear param-min → param-max mapping:
        //   Pre-delay     : 0..25 %   zone, 0..300 ms
        //   Early Refl    : 25..50 %  zone, 0..1000 ms
        //   Reverb Delay  : 50..75 %  zone, 0..100 ms
        //   Decay         : 75..100 % zone, 100..20 000 ms
        // The thumbs are hollow circles styled the same as the X/Y dot
        // in the density/diffusion view (bg-coloured fill so the track-
        // line punches through, light-grey ring on top), but a tiny bit
        // smaller. Zone LABELS are drawn separately, anchored to the
        // zone midpoints; see [drawTimeAxisLine].
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
        val revDelayX = clampInZone(2, revDelayToX(reverbDelayMs))
        val decayX = clampInZone(3, decayToX(decayTimeMs))

        // Early & Decay zones support 2-D drag — their dot Y can leave
        // the trackline. Pre-delay & Reverb Delay stay on the line.
        // Both dot Ys are *derived* from their dB params so each dot
        // and the matching dB slider stay in sync automatically.
        val earlyY = reflectionsLevelDbToY()
        val decayY = reverbLevelDbToY()

        handlePos[Handle.PREDELAY_CIRCLE] = HandlePos(preDelayX, cy)
        handlePos[Handle.EARLY_CIRCLE] = HandlePos(earlyX, earlyY)
        handlePos[Handle.REVDELAY_CIRCLE] = HandlePos(revDelayX, cy)
        handlePos[Handle.DECAY_CIRCLE] = HandlePos(decayX, decayY)
        handlePos.remove(Handle.SOURCE_CIRCLE)

        for ((h, pos) in listOf(
            Handle.PREDELAY_CIRCLE to (preDelayX to cy),
            Handle.EARLY_CIRCLE to (earlyX to earlyY),
            Handle.REVDELAY_CIRCLE to (revDelayX to cy),
            Handle.DECAY_CIRCLE to (decayX to decayY),
        )) {
            val (x, y) = pos
            val active = h == grabbed
            c.drawCircle(x, y, r, handleFillPaint)
            val ring = if (active) handleFillActivePaint else handleStrokePaint
            c.drawCircle(x, y, r, ring)
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
        // Only the top-row circles are user-touchable right now; ignore
        // any leftover curve-anchor handle positions.
        val touchable = setOf(
            Handle.PREDELAY_CIRCLE, Handle.EARLY_CIRCLE,
            Handle.REVDELAY_CIRCLE, Handle.DECAY_CIRCLE,
            Handle.HF_DAMPING_CIRCLE,
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
                // Constrained to the 0..25 % zone; X maps linearly
                // onto reflectionsDelayMs in [0, 300] ms.
                val newDelay = xToPreDelay(x)
                reflectionsDelayMs = newDelay
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
            }
            Handle.EARLY_CIRCLE -> {
                // Constrained to the 25..50 % zone. X maps linearly
                // onto earlyReflectionsWidthMs in [0, 1000] ms; Y maps
                // linearly onto reflectionsLevelDb in [-90, +10] dB
                // (top of card = louder, bottom = quieter). Dragging
                // the dot up makes the early-reflection bars taller;
                // the Reflect (dB) slider tracks the dot in real time.
                earlyReflectionsWidthMs = xToEarly(x)
                val newDb = yToReflectionsLevelDb(y)
                reflectionsLevelDb = newDb
                cb?.invoke(Param.REFLECTIONS_LEVEL, newDb)
            }
            Handle.REVDELAY_CIRCLE -> {
                // Constrained to the 50..75 % zone; X maps linearly
                // onto reverbDelayMs in [0, 100] ms.
                val newDelay = xToRevDelay(x)
                reverbDelayMs = newDelay
                cb?.invoke(Param.REVERB_DELAY, newDelay)
            }
            Handle.DECAY_CIRCLE -> {
                // Constrained to the 75..100 % zone. X maps linearly
                // onto decayTimeMs in [100, 20 000] ms; Y maps onto
                // reverbLevelDb in [-90, +20] dB (top = louder tail,
                // bottom = quieter). Dragging the dot up makes the
                // late-tail bars taller and the Reverb (dB) slider
                // tracks the dot in real time.
                val newDecay = xToDecay(x)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
                val newDb = yToReverbLevelDb(y)
                reverbLevelDb = newDb
                cb?.invoke(Param.REVERB_LEVEL, newDb)
            }
            Handle.HF_DAMPING_CIRCLE -> {
                // HF Damping dot rides the box's ANTI-DIAGONAL. The
                // touch position is projected onto the diagonal and
                // that projection's fraction maps to decayHfRatio in
                // [0.1, 2.0]. Bottom-left = ratio min (HF dies, curve
                // bows down). Top-right = ratio max (HF sustains,
                // curve bows up). Centre = ratio 1.0 = straight line.
                val newRatio = pointToDecayHfRatio(x, y)
                decayHfRatio = newRatio
                cb?.invoke(Param.DECAY_HF, newRatio)
            }
        }
    }
}
