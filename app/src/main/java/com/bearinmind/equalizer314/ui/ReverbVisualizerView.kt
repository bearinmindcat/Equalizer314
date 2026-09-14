package com.bearinmind.equalizer314.ui

import com.bearinmind.equalizer314.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Amplitude visualization for EnvironmentalReverb: thin vertical bars (one per IR
 * reflection) over a ghost decay envelope, with three labelled regions
 * (Pre-delay | Early Reflections | Decay) and drag handles.
 */
class ReverbVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Param {
        DECAY_TIME, DECAY_HF, REVERB_LEVEL, ROOM_LEVEL,
        REFLECTIONS_DELAY, REFLECTIONS_LEVEL, REVERB_DELAY,
        ROOM_HF_LEVEL,
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
    /** Visual-only early-reflection cluster width; EnvironmentalReverb has no API
     *  for this, the graph just needs a constant spread. */
    private val earlyReflectionsWidthMs: Float = 268f
    /** Reverb Delay (ms): silence between early reflections and the late tail.
     *  EnvironmentalReverb API range 0..100 ms. */
    var reverbDelayMs: Float = 11f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    /** Room HF Level (dB) — static high-frequency shelf cut applied
     *  to the wet output. EnvironmentalReverb API range −90..0 dB. */
    var roomHFLevelDb: Float = 0f
        set(v) { field = v.coerceIn(-90f, 0f); invalidate() }

    private val density = context.resources.displayMetrics.density

    // ---- Color palette (all-grey, "Visualizing Reverb" reference) ----

    private val bgColor = 0xFF1A1A1A.toInt()
    private val directBarColor = 0xFFE8E8E8.toInt()  // brightest — direct sound
    private val earlyBarColor = 0xFFBBBBBB.toInt()   // early reflections
    // Regular (non-HF-damped) decay drawn brighter so it reads as the primary
    // signal; HF-damped portions get a darker shade.
    private val bodyBarColor = 0xFFBBBBBB.toInt()    // regular decay (bright)
    private val tailBarColor = bodyBarColor          // decay tail (unified)
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
    // Dashed variant when the cluster is collapsed (Early Refl slider at min):
    // "region exists but has no width yet."
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
    // HF overlay bar drawn at the HF-damped height (decayHfRatio exponent):
    // equals the regular bar at ratio 1, shorter <1 (HF dies faster), taller >1.
    private val hfBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        // Darker shade for HF-damped portion, paired with brighter bodyBarColor.
        color = 0xFF3A3A3A.toInt()
    }
    // Segment drawn ABOVE the linear envelope when HF-damped > linear
    // (decayHfRatio > 1).
    private val hfExcessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        // Same dark shade as the HF base so all HF portions read as one tone,
        // leaving bodyBarColor exclusively to the regular decay.
        color = 0xFF3A3A3A.toInt()
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
    // Thumb paints mirror the density/diffusion view's dot: bg fill so the
    // track-line punches through, 2.5 px light-grey ring, amber when grabbed
    // (stroke width raw px to match the X/Y dot).
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
    // Soft grey halo around the grabbed dot; alpha fades in on ACTION_DOWN and
    // out on ACTION_UP (matches the app's "active" feedback language).
    private val rippleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFBBBBBB.toInt()
    }
    private var lastGrabbedHandle: Handle? = null
    private var rippleStateChangeMs = 0L
    private val rippleFadeInMs = 120L
    private val rippleFadeOutMs = 220L
    // Outline around zones allowing 2-D drag (Early Reflections, Decay).
    private val zoneCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF444444.toInt()
        strokeWidth = 1.5f * density
    }
    // Faint fill over the silent regions (Pre-delay, Reverb Delay) so they read
    // as intentionally empty.
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
    // Dashed Bezier in the HF Damping sub-box; colour recomputed each frame from
    // roomHFLevelDb so it tracks the HF Level visualisation.
    private val hfCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF666666.toInt()
        strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(4f * density, 4f * density), 0f
        )
    }
    // White dotted line tracing the linear (non-HF) decay envelope; tiny
    // round-capped "on" segments give a dotted (not dashed) look.
    private val lfStreamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(0.1f * density, 4f * density), 0f
        )
    }
    private val hfStreamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Dark grey keeps the HF-damped curve distinct from the linear LF stream.
        color = 0xFF555555.toInt()
        strokeWidth = 1.6f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(5f * density, 4f * density), 0f
        )
        strokeJoin = Paint.Join.ROUND
    }
    private val streamOverlayPath = Path()
    // Early Reflections Y-axis range — matches the Reflect (dB) slider in
    // activity_environmental_reverb.xml and the setReflectionsLevel API range.
    private val reflectionsLevelMinDb = -90f
    private val reflectionsLevelMaxDb = 10f

    // Decay Y-axis range — matches the Reverb (dB) slider (-90..+20 dB) and the
    // setReverbLevel API range; Decay dot Y maps to reverbLevelDb.
    private val reverbLevelMinDb = -90f
    // LVREV caps reverb level at 0 mB (relative attenuation — can't boost the wet
    // above room level); the doc's +2000 mB is rejected, so the dot tops out at 0 dB.
    private val reverbLevelMaxDb = 0f

    // HF Damping range — matches the Decay HF slider (0.1..2.0) and the
    // setDecayHFRatio API range; HF dot X maps to decayHfRatio.
    private val decayHfRatioMin = 0.1f
    private val decayHfRatioMax = 2.0f

    // HF Level range — matches setRoomHFLevel (−90..0 dB); dot X maps to roomHFLevelDb.
    private val roomHFLevelMinDb = -90f
    private val roomHFLevelMaxDb = 0f

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

    // Dot constrained to antiDiagFrac [0.25, 0.75] so the Bezier control point
    // (= 2·dot − centre) stays inside the box; dot passes through the curve at its midpoint.
    private val hfDotFracMin = 0.25f
    private val hfDotFracMax = 0.75f

    /** Map decayHfRatio to a position on the HF sub-box anti-diagonal (min =
     *  bottom-left, max = top-right, ratio 1 = centre), restricted to [0.25, 0.75]. */
    private fun decayHfRatioToDotPos(): Pair<Float, Float> {
        val b = hfDotInnerBounds()
        val ratioFrac = ((decayHfRatio - decayHfRatioMin) /
            (decayHfRatioMax - decayHfRatioMin)).coerceIn(0f, 1f)
        val antiDiagFrac = hfDotFracMin + ratioFrac * (hfDotFracMax - hfDotFracMin)
        val dotX = b[0] + antiDiagFrac * (b[2] - b[0])
        val dotY = b[3] - antiDiagFrac * (b[3] - b[1])
        return Pair(dotX, dotY)
    }

    /** Project a touch onto the anti-diagonal and map back to decayHfRatio;
     *  clamped to the dot's restricted range so the curve stays inside the box. */
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

    /** HF Level sub-box in zone 2's column of the HF sub-band (next to HF
     *  Damping); dot is an X-only slider like the Reverb Delay circle. */
    private fun roomHFLevelInnerBounds(): FloatArray {
        val r = 5.5f * density
        val dotMargin = r + 2f
        return floatArrayOf(
            zoneStart(2) + dotMargin,
            hfSubBandTop + dotMargin,
            zoneEnd(2) - dotMargin,
            hfSubBandBottom - dotMargin,
        )
    }
    private fun roomHFLevelToX(): Float {
        val b = roomHFLevelInnerBounds()
        val frac = ((roomHFLevelDb - roomHFLevelMinDb) /
            (roomHFLevelMaxDb - roomHFLevelMinDb)).coerceIn(0f, 1f)
        return b[0] + frac * (b[2] - b[0])
    }
    private fun xToRoomHFLevel(x: Float): Float {
        val b = roomHFLevelInnerBounds()
        val span = (b[2] - b[0]).coerceAtLeast(1f)
        val frac = ((x - b[0]) / span).coerceIn(0f, 1f)
        return roomHFLevelMinDb + frac * (roomHFLevelMaxDb - roomHFLevelMinDb)
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
        REVDELAY_CIRCLE, HF_DAMPING_CIRCLE, HF_LEVEL_CIRCLE,
    }
    /** False when the device's reverb ignores pre-delay / early reflections / reverb delay: their handles stop grabbing. */
    var extraParamsEnabled: Boolean = true
        set(v) { field = v; invalidate() }

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

    // Top control line: equal-width zones starting AFTER the Direct Sound capsule
    // (not plotL) so the capsule doesn't overlap the pre-delay range. Each circle
    // is clamped to its zone with a linear param min→max mapping; ticks mark zone edges.
    private val preDelayMinMs = 0f
    private val preDelayMaxMs = 300f
    private val earlyMinMs = 0f
    private val earlyMaxMs = 1000f
    private val revDelayMinMs = 0f
    private val revDelayMaxMs = 100f
    private val decayMinMs = 100f
    // LVREV_MAX_T60 = 7000 ms is the reverb algorithm's hard decay ceiling;
    // the doc's 20000 ms is rejected. Cap the dot at 7 s to match the engine.
    private val decayMaxMs = 7000f
    private val zoneCount = 4

    // Direct Sound capsule layout — shared by drawBars and zonesLeft(). edgeInset
    // keeps the outline off the card edges; the Pre-delay zone starts 2 dp past the
    // capsule so the collapsed Early-Reflections dotted line isn't hidden behind it.
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
    // "Early Reflections" box spans zones 0-1; its 2-D dot maps X → reflectionsDelay
    // (preDelayMin..Max range) and Y → reflectionsLevel, mirroring the Reverb Tail box.
    private fun earlyBoxDelayToX(ms: Float): Float {
        val frac = ((ms - preDelayMinMs) / (preDelayMaxMs - preDelayMinMs)).coerceIn(0f, 1f)
        return zoneStart(0) + frac * (zoneEnd(1) - zoneStart(0))
    }
    private fun xEarlyBoxToDelay(x: Float): Float {
        val frac = ((x - zoneStart(0)) / (zoneEnd(1) - zoneStart(0))).coerceIn(0f, 1f)
        return preDelayMinMs + frac * (preDelayMaxMs - preDelayMinMs)
    }
    /** X of the early-reflections dot = reflectionsDelay mapped across the
     *  merged box (zones 0-1), clamped a dot-margin inside the box. */
    private fun earlyDotX(): Float {
        val dotMargin = 5.5f * density + 2f
        return earlyBoxDelayToX(reflectionsDelayMs)
            .coerceIn(zoneStart(0) + dotMargin, zoneEnd(1) - dotMargin)
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

    // Legacy linear-time helpers — only cache the unused curve-anchor handle positions.
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

        // Control circles sit on a horizontal track-line in the top band with zone
        // labels above; bars + envelope fill the rest, flush left/bottom, right edge
        // inset so all zones share the same narrower right edge.
        val sideMargin = 0f
        val bottomMargin = 0f
        val rightMargin = 4f * density
        // 68 dp bands keep the control boxes tight and give the graph more room.
        val mainBandH = 68f * density
        val hfSubBandH = 68f * density
        val topBandH = mainBandH + hfSubBandH
        plotL = sideMargin
        plotR = w - rightMargin
        plotT = topBandH
        plotB = h - bottomMargin
        controlBandTop = 2f * density
        controlBandBottom = mainBandH - 2f * density  // main band only — HF sub-band sits below
        hfSubBandTop = mainBandH + 2f * density
        hfSubBandBottom = topBandH - 2f * density

        // Bars use a fixed layout; circles slide on a stable log-ms axis. xMaxMs
        // only feeds legacy handle-anchor caching, not bar layout.
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
        drawHfLevelSubBox(canvas)
        drawTimeAxisLine(canvas)
        drawControlCircles(canvas)
        drawHandles(canvas)
    }

    /** HF Damping sub-box below the Decay zone: dot rides the anti-diagonal
     *  (bottom-left = min ratio, top-right = max, centre = 1.0); the curve always
     *  passes through the dot at t = 0.5, so one gesture drives both. */
    private val hfCurvePath = Path()
    /** Decay-zone overlays: LF stream = linear baseline at the `decayTime` rate;
     *  HF stream bends with `decayHfRatio` (below LF for ratio < 1, above for > 1)
     *  — the two streams the API actually tracks. Bars below = perceived combined tail. */
    private fun drawTailStreamOverlays(
        c: Canvas,
        tailStartX: Float,
        tailEndX: Float,
        tailSpan: Float,
        ampStart: Float,
        tailLevel: Float,
        hfExp: Float,
    ) {
        val nSamples = 32

        // Linear decay — white dotted reference line for the un-damped envelope.
        streamOverlayPath.reset()
        for (i in 0..nSamples) {
            val zoneFrac = i.toFloat() / nSamples
            val x = tailStartX + zoneFrac * tailSpan
            val amp = (ampStart * (1f - zoneFrac) * tailLevel).coerceIn(0f, 1f)
            val y = amp01ToY(amp)
            if (i == 0) streamOverlayPath.moveTo(x, y) else streamOverlayPath.lineTo(x, y)
        }
        c.drawPath(streamOverlayPath, lfStreamPaint)

        // HF stream — bends with HF damping.
        streamOverlayPath.reset()
        for (i in 0..nSamples) {
            val zoneFrac = i.toFloat() / nSamples
            val x = tailStartX + zoneFrac * tailSpan
            val amp = (ampStart * (1f - zoneFrac).pow(hfExp) * tailLevel).coerceIn(0f, 1f)
            val y = amp01ToY(amp)
            if (i == 0) streamOverlayPath.moveTo(x, y) else streamOverlayPath.lineTo(x, y)
        }
        c.drawPath(streamOverlayPath, hfStreamPaint)
    }

    /** HF Level — zone 2's column of the HF sub-band. No outline box, just a
     *  trackline spanning the zone tick-to-tick with the dot clamped inside.
     *  Left = −90 dB (HF cut), right = 0 dB (no cut). */
    private fun drawHfLevelSubBox(c: Canvas) {
        val left = zoneStart(2)
        val right = zoneEnd(2)
        val top = hfSubBandTop
        val bottom = hfSubBandBottom

        // "HF Level" label, top-centred above the trackline.
        val labelBaselineY = top + 4f * density - controlLabelPaint.ascent()
        c.drawText(context.getString(R.string.rv_hf_level), (left + right) / 2f, labelBaselineY, controlLabelPaint)

        // Trackline tick-to-tick (full zone width, like Reverb Delay).
        val cy = (top + bottom) / 2f
        c.drawLine(left, cy, right, cy, regionDividerPaint)
        val tickHalf = 5f * density
        c.drawLine(left, cy - tickHalf, left, cy + tickHalf, regionDividerPaint)
        c.drawLine(right, cy - tickHalf, right, cy + tickHalf, regionDividerPaint)

        // Dot clamped a dot-margin inside so its outline never pokes
        // past the tick marks.
        val r = 5.5f * density
        val dotMargin = r + 2f
        val dotX = roomHFLevelToX().coerceIn(left + dotMargin, right - dotMargin)
        handlePos[Handle.HF_LEVEL_CIRCLE] = HandlePos(dotX, cy)
        drawRippleHaloFor(c, Handle.HF_LEVEL_CIRCLE, dotX, cy, r)
        c.drawCircle(dotX, cy, r, handleFillPaint)
        c.drawCircle(dotX, cy, r, handleStrokePaint)
    }

    private fun drawHfDampingSubBox(c: Canvas) {
        val cornerR = 6f * density
        val left = zoneStart(3)
        val right = zoneEnd(3)
        val top = hfSubBandTop
        val bottom = hfSubBandBottom
        c.drawRoundRect(left, top, right, bottom, cornerR, cornerR, zoneCardPaint)

        // Label "HF Damping" inside the sub-box, top-centred.
        val labelBaselineY = top + 4f * density - controlLabelPaint.ascent()
        c.drawText(context.getString(R.string.rv_hf_damping), (left + right) / 2f, labelBaselineY, controlLabelPaint)

        val r = 5.5f * density
        val b = hfDotInnerBounds()
        val innerLeft = b[0]; val innerTop = b[1]
        val innerRight = b[2]; val innerBottom = b[3]
        val centerX = (innerLeft + innerRight) / 2f
        val centerY = (innerTop + innerBottom) / 2f
        val (dotX, dotY) = decayHfRatioToDotPos()

        // Anti-diagonal rail — same [regionDividerPaint] grey as every other trackline.
        c.drawLine(innerLeft, innerBottom, innerRight, innerTop, regionDividerPaint)

        // Quadratic Bezier with control point C = 2·dot − centre so B(0.5) = dot;
        // the dot's [0.25, 0.75] range guarantees C (and the curve) stay in the box.
        val cx = 2f * dotX - centerX
        val cy = 2f * dotY - centerY
        hfCurvePath.reset()
        hfCurvePath.moveTo(innerLeft, innerTop)
        hfCurvePath.quadTo(cx, cy, innerRight, innerBottom)
        c.drawPath(hfCurvePath, hfCurvePaint)

        handlePos[Handle.HF_DAMPING_CIRCLE] = HandlePos(dotX, dotY)
        drawRippleHaloFor(c, Handle.HF_DAMPING_CIRCLE, dotX, dotY, r)
        c.drawCircle(dotX, dotY, r, handleFillPaint)
        c.drawCircle(dotX, dotY, r, handleStrokePaint)
    }

    /** Vertical dotted centre divider — marks where the Reverb Delay drag range begins. */
    private fun drawCenterLine(c: Canvas) {
        val centerX = zoneStart(2)
        c.drawLine(centerX, plotT, centerX, plotB, centerLinePaint)
    }

    /** Shaded silence trapezoids: width tracks the Pre-delay / Reverb Delay
     *  sliders, top follows the envelope line (not a flat rectangle). */
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

    /** Card outlines around Early Reflections and Decay; both boxes share the same
     *  width because plotR is inset globally by 1 dp. */
    private fun drawZoneCards(c: Canvas) {
        val cornerR = 8f * density
        val topPad = 2f * density
        val bottomPad = 2f * density
        val top = controlBandTop + topPad
        val bottom = controlBandBottom - bottomPad
        // Early Reflections spans zones 0-1 (onset × level); Reverb Tail is zone 3
        // (decay time × level).
        c.drawRoundRect(zoneStart(0), top, zoneEnd(1), bottom, cornerR, cornerR, zoneCardPaint)
        c.drawRoundRect(zoneStart(3), top, zoneEnd(3), bottom, cornerR, cornerR, zoneCardPaint)
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
        // Track-line starts at the Direct Sound capsule's right edge (zonesLeft())
        // so it doesn't run behind the capsule; zone labels live above the line.
        val lineY = trackLineY()
        val lineLeft = zonesLeft()
        c.drawLine(lineLeft, lineY, plotR, lineY, regionDividerPaint)
        val tickHalf = 5f * density
        for (i in 0..zoneCount) {
            // Skip i == 1: it's the centre of the merged Early Reflections box —
            // no stray tick down its middle.
            if (i == 1) continue
            val x = lineLeft + (plotR - lineLeft) * (i.toFloat() / zoneCount)
            c.drawLine(x, lineY - tickHalf, x, lineY + tickHalf, regionDividerPaint)
        }

        // Fixed zone labels (not attached to dragged circles), centred between each
        // zone's ticks, pinned to the band top. Early Reflections = zones 0-1;
        // Reverb Delay = zone 2; Reverb Tail = zone 3.
        val labelY = controlBandTop + 4f * density - controlLabelPaint.ascent()
        c.drawText(context.getString(R.string.rv_early_reflections), (zoneStart(0) + zoneEnd(1)) / 2f, labelY, controlLabelPaint)
        c.drawText(context.getString(R.string.rv_reverb_delay), (zoneStart(2) + zoneEnd(2)) / 2f, labelY, controlLabelPaint)
        c.drawText(context.getString(R.string.rv_reverb_tail), (zoneStart(3) + zoneEnd(3)) / 2f, labelY, controlLabelPaint)
    }

    private fun trackLineY(): Float {
        // Centred vertically in the band: equal room for labels above and bars below.
        return (controlBandTop + controlBandBottom) / 2f
    }

    private fun drawBackground(c: Canvas) {
        c.drawRect(plotL, plotT, plotR, plotB, bgPaint)
    }

    private fun drawGhostEnvelope(c: Canvas, decayDurationMs: Float) {
        // Envelope is the exact plot-rect diagonal; bars sample the same line via
        // [envelopeAtX] so they never overshoot. The capsule's bg fill masks the
        // line inside it, so it reads as attaching to the capsule's top.
        ghostPath.reset()
        ghostPath.moveTo(plotL, plotT)
        ghostPath.lineTo(plotR, plotB)
        c.drawPath(ghostPath, ghostEnvelopePaint)
    }

    /** Linear amplitude 0..1 at x (1.0 left edge → 0.0 right edge) — the same line
     *  drawn by [drawGhostEnvelope], so it's a guaranteed upper bound for bars. */
    private fun envelopeAtX(x: Float): Float {
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        return (1f - (x - plotL) / plotW).coerceIn(0f, 1f)
    }

    private fun drawBars(c: Canvas, earlyDurationMs: Float, decayDurationMs: Float) {
        // Bars anchor to the control-circle Xs (Source at plotL; early refl from
        // Pre-delay X; body+tail from Early Refl X to Decay X) so dragging slides
        // regions. Heights follow envelopeAtX; every other bar shrunk to mimic
        // reflection interference in a real reverb tail.
        val altShrink = 0.65f

        val preDelayX = preDelayToX(reflectionsDelayMs)

        // 1. Source signal — hollow capsule at bottom-left, inset so its outline
        //    clears the card's rounded corners; envelope anchors at the capsule's
        //    top, interior bg-filled to mask the line passing inside.
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
            c.drawText(context.getString(R.string.rv_direct_sound), cx, cy + baselineOffset, directLabelPaint)
            c.restore()
        }

        // 2. Early reflections — cluster anchored at Pre-delay's X (preDelayX).
        run {
            val refLevel = dbToAmp01(reflectionsLevelDb)
            val earlyStartX = preDelayX
            // Cluster length fixed at 1/8 of graph width; slides right with onset,
            // clamped so it never overruns the plot.
            val earlyClusterLength = (plotR - plotL) / 8f
            val earlyEndX = (earlyStartX + earlyClusterLength)
                .coerceAtMost(plotR)
                .coerceAtLeast(earlyStartX + 1f)
            val paint = earlyBarPaint
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

        // 3. Reverb body + tail — Reverb Delay circle X → Decay circle X. At
        //    reverbDelay=0 the tail extends back into zone 2. Body = first 40 %,
        //    tail = 60 %. Tail width ∝ decay over the space to plotR; at decay=min
        //    it collapses to a single line at tailStartX (no snap-open).
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
        // Bars decay from startAmp to ~0 at tailEndX, shaped by HF damping:
        // ratio 1 → linear; <1 → concave-up (HF dies fast); >1 → concave-down (sustains).
        val tailSpan = (tailEndX - tailStartX).coerceAtLeast(1f)
        val ampAtTailStart = envelopeAtX(tailStartX)
        val hfDampingExp = (1f / decayHfRatio.coerceIn(0.1f, 2f)).coerceIn(0.5f, 2f)
        // HF greys scale with roomHFLevelDb: 0xBB at 0 dB (merges with bodyBarColor)
        // down to 0x40 at −90 dB (still readable on the 0x1A bg). All HF paints
        // share the value so overlay, base region and excess move together.
        val hfLevel01 = ((roomHFLevelDb + 90f) / 90f).coerceIn(0f, 1f)
        val hfGrey = (0x40 + (0xBB - 0x40) * hfLevel01).toInt().coerceIn(0, 255)
        val hfColor = (0xFF shl 24) or (hfGrey shl 16) or (hfGrey shl 8) or hfGrey
        hfBarPaint.color = hfColor
        hfExcessPaint.color = hfColor
        hfStreamPaint.color = hfColor
        hfCurvePaint.color = hfColor
        // Bar segments: 0→min(lin,HF) uses hfBarPaint (HF energy present); min→max
        // uses bodyBarPaint when lin > HF (bulk decay) or hfExcessPaint when HF > lin.
        // linAmp/hfAmp skip the alt-shrink so bar tops trace the dashed overlays
        // exactly — the colour boundary lands on the HF dotted line.
        for (i in 0 until nTail) {
            val fracT = (i + 0.5f) / nTail
            val x = tailStartX + fracT * (tailEndX - tailStartX)
            if (x > plotR) continue
            val zoneFrac = ((x - tailStartX) / tailSpan).coerceIn(0f, 1f)
            val linAmp = ampAtTailStart * (1f - zoneFrac) * tailLevel
            val hfAmp = ampAtTailStart * (1f - zoneFrac).pow(hfDampingExp) * tailLevel

            if (tailCollapsed) {
                if (linAmp >= 0.01f) {
                    c.drawLine(x, plotB, x, amp01ToY(linAmp), bodyBarDashedPaint)
                }
                continue
            }

            val minAmp = min(linAmp, hfAmp)
            val maxAmp = max(linAmp, hfAmp)

            if (minAmp >= 0.01f) {
                c.drawLine(x, plotB, x, amp01ToY(minAmp), hfBarPaint)
            }
            if (maxAmp - minAmp > 0.01f) {
                val topPaint = if (linAmp > hfAmp) bodyBarPaint else hfExcessPaint
                c.drawLine(x, amp01ToY(minAmp), x, amp01ToY(maxAmp), topPaint)
            }
        }

        // Two-stream overlays show the API's model: LF + HF decay tracked separately.
        if (!tailCollapsed) {
            drawTailStreamOverlays(c, tailStartX, tailEndX, tailSpan,
                ampAtTailStart, tailLevel, hfDampingExp)
        }

        // Curve-anchor handles aren't drawn; positions cached on the legacy
        // linear-time axis for any future re-enable.
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
        // No baseline — the card outline provides the bottom edge; a drawn
        // baseline added an unwanted white-ish stripe under the graph.
    }

    private fun drawRegionLabels(c: Canvas) {
        val labelY = plotB + 12f * density
        val preCenter = (plotL + preEnd) / 2f
        val earlyCenter = (preEnd + earlyEnd) / 2f
        val decayCenter = (earlyEnd + plotR) / 2f
        c.drawText(context.getString(R.string.rv_pre_delay), preCenter, labelY, labelPaint)
        c.drawText(context.getString(R.string.rv_early_reflections), earlyCenter, labelY, labelPaint)
        c.drawText(context.getString(R.string.rv_decay), decayCenter, labelY, labelPaint)
    }

    private fun drawAmplitudeLabel(c: Canvas) {
        c.save()
        val cx = plotL - 12f * density
        val cy = (plotT + plotB) / 2f
        c.rotate(-90f, cx, cy)
        amplitudeLabelPaint.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.rv_amplitude), cx, cy + 4f * density, amplitudeLabelPaint)
        c.restore()
    }

    private fun drawTimeAxisLabel(c: Canvas) {
        // X-axis label, right-aligned just below the region labels —
        // mirrors the "amplitude" Y-axis label on the left.
        val savedAlign = amplitudeLabelPaint.textAlign
        amplitudeLabelPaint.textAlign = Paint.Align.RIGHT
        c.drawText(context.getString(R.string.rv_time_ms), plotR, plotB + 24f * density, amplitudeLabelPaint)
        amplitudeLabelPaint.textAlign = savedAlign
    }

    private fun drawHandles(c: Canvas) {
        // Curve-anchor handles intentionally not drawn; [drawControlCircles]
        // renders the user-facing dots.
    }

    private fun drawControlCircles(c: Canvas) {
        // Top-row thumbs, one per quarter zone with linear param mapping:
        // Pre-delay 0..300 ms, Early Refl 0..1000 ms, Reverb Delay 0..100 ms,
        // Decay 100..20 000 ms. Styled like the density/diffusion X/Y dot.
        // Each circle is clamped inside its zone (margin = r + 2 px) so it never
        // pokes past its tick; the parameter still spans full min..max.
        val cy = trackLineY()
        val r = 5.5f * density
        val dotMargin = r + 2f

        fun clampInZone(zone: Int, x: Float) =
            x.coerceIn(zoneStart(zone) + dotMargin, zoneEnd(zone) - dotMargin)

        val earlyX = earlyDotX()
        val revDelayX = clampInZone(2, revDelayToX(reverbDelayMs))
        val decayX = clampInZone(3, decayToX(decayTimeMs))

        // Early & Decay dots are 2-D (Y leaves the trackline); their Y derives from
        // the dB params so dot and matching dB slider stay in sync automatically.
        val earlyY = reflectionsLevelDbToY()
        val decayY = reverbLevelDbToY()

        handlePos[Handle.EARLY_CIRCLE] = HandlePos(earlyX, earlyY)
        handlePos[Handle.REVDELAY_CIRCLE] = HandlePos(revDelayX, cy)
        handlePos[Handle.DECAY_CIRCLE] = HandlePos(decayX, decayY)
        handlePos.remove(Handle.SOURCE_CIRCLE)
        handlePos.remove(Handle.PREDELAY_CIRCLE)

        for ((h, pos) in listOf(
            Handle.EARLY_CIRCLE to (earlyX to earlyY),
            Handle.REVDELAY_CIRCLE to (revDelayX to cy),
            Handle.DECAY_CIRCLE to (decayX to decayY),
        )) {
            val (x, y) = pos
            drawRippleHaloFor(c, h, x, y, r)
            c.drawCircle(x, y, r, handleFillPaint)
            c.drawCircle(x, y, r, handleStrokePaint)
        }
    }

    /**
     * Halo around the grabbed (or just-released) dot; alpha interpolated from
     * [rippleStateChangeMs] so it fades rather than snapping. Only
     * [lastGrabbedHandle] gets a halo. Self-drives animation frames via
     * [postInvalidateOnAnimation] while the alpha is in motion.
     */
    private fun drawRippleHaloFor(
        c: Canvas,
        h: Handle,
        x: Float,
        y: Float,
        dotR: Float,
    ) {
        if (h != lastGrabbedHandle) return
        val elapsed = SystemClock.elapsedRealtime() - rippleStateChangeMs
        val alpha: Float
        val animating: Boolean
        if (grabbed == h) {
            alpha = (elapsed.toFloat() / rippleFadeInMs).coerceIn(0f, 1f)
            animating = alpha < 1f
        } else {
            alpha = (1f - elapsed.toFloat() / rippleFadeOutMs).coerceIn(0f, 1f)
            animating = alpha > 0f
        }
        if (alpha > 0.01f) {
            // Halo slightly larger than the dot; smaller than ripples used elsewhere.
            val haloR = dotR + 6f * density
            // Peak alpha 0.32 keeps the halo visible without clobbering
            // the dot or the trackline behind it.
            rippleHaloPaint.alpha = (alpha * 0.32f * 255f).toInt()
            c.drawCircle(x, y, haloR, rippleHaloPaint)
        }
        if (animating) postInvalidateOnAnimation()
    }

    // ---- Touch ---------------------------------------------------------

    // Bumped from 22 dp → 30 dp for snappier touch acquisition.
    private val hitRadiusPx: Float get() = 30f * density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = nearestHandle(event.x, event.y) ?: return false
                grabbed = target
                lastGrabbedHandle = target
                rippleStateChangeMs = SystemClock.elapsedRealtime()
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
                rippleStateChangeMs = SystemClock.elapsedRealtime()
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestHandle(x: Float, y: Float): Handle? {
        // Only the top-row circles are touchable; leftover curve-anchor positions are ignored.
        val touchable = if (extraParamsEnabled) setOf(
            Handle.EARLY_CIRCLE,
            Handle.REVDELAY_CIRCLE, Handle.DECAY_CIRCLE,
            Handle.HF_DAMPING_CIRCLE, Handle.HF_LEVEL_CIRCLE,
        ) else setOf(
            Handle.DECAY_CIRCLE, Handle.HF_DAMPING_CIRCLE, Handle.HF_LEVEL_CIRCLE,
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
                    .coerceIn(decayMinMs, decayMaxMs)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
            }
            Handle.SOURCE_CIRCLE -> {
                // Vertical drag adjusts Room Level; Y is read against the plot's
                // range, not the control band.
                val newDb = amp01ToDb(yToAmp01(y)).coerceIn(-90f, 0f)
                roomLevelDb = newDb
                cb?.invoke(Param.ROOM_LEVEL, newDb)
            }
            Handle.PREDELAY_CIRCLE -> {
                // X maps linearly onto reflectionsDelayMs in [0, 300] ms.
                val newDelay = xToPreDelay(x)
                reflectionsDelayMs = newDelay
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
            }
            Handle.EARLY_CIRCLE -> {
                // 2-D: X → reflectionsDelay (onset, 0..300 ms), Y →
                // reflectionsLevelDb [-90, +10] dB (mirrors Reverb Tail box).
                val newDelay = xEarlyBoxToDelay(x).coerceIn(0f, 300f)
                val newDb = yToReflectionsLevelDb(y)
                reflectionsDelayMs = newDelay
                reflectionsLevelDb = newDb
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
                cb?.invoke(Param.REFLECTIONS_LEVEL, newDb)
            }
            Handle.REVDELAY_CIRCLE -> {
                // X maps linearly onto reverbDelayMs in [0, 100] ms.
                val newDelay = xToRevDelay(x)
                reverbDelayMs = newDelay
                cb?.invoke(Param.REVERB_DELAY, newDelay)
            }
            Handle.DECAY_CIRCLE -> {
                // X -> decayTimeMs [100, 7000] ms; Y -> reverbLevelDb [-90, +20] dB; the slider tracks the dot live.
                val newDecay = xToDecay(x)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
                val newDb = yToReverbLevelDb(y)
                reverbLevelDb = newDb
                cb?.invoke(Param.REVERB_LEVEL, newDb)
            }
            Handle.HF_DAMPING_CIRCLE -> {
                // Touch projected onto the anti-diagonal → decayHfRatio [0.1, 2.0]:
                // bottom-left = min (HF dies), top-right = max (HF sustains), centre = 1.0.
                val newRatio = pointToDecayHfRatio(x, y)
                decayHfRatio = newRatio
                cb?.invoke(Param.DECAY_HF, newRatio)
            }
            Handle.HF_LEVEL_CIRCLE -> {
                // Horizontal slide; X → roomHFLevelDb [-90, 0] dB.
                val newDb = xToRoomHFLevel(x)
                roomHFLevelDb = newDb
                cb?.invoke(Param.ROOM_HF_LEVEL, newDb)
            }
        }
    }
}
