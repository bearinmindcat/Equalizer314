package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Interactive reverb impulse-response display. Draws the IR mirrored
 * around a horizontal baseline (waveform-style) and exposes five drag
 * handles that map directly to the underlying parameters:
 *   • Direct       — top of the t=0 impulse, vertical drag → Room Level.
 *   • Reflections  — corner where early reflections begin, X drag →
 *                    Reflections Delay, Y drag → Reflections Level.
 *   • Tail Start   — beginning of the late reverb envelope, Y drag →
 *                    Reverb Level.
 *   • Tail Curve   — middle of the tail, Y drag → Decay HF Ratio
 *                    (steeper / shallower decay envelope).
 *   • Tail End     — right end of the envelope on the baseline, X drag
 *                    → Decay Time.
 *
 * The view never writes to prefs directly; it only reports parameter
 * changes through [onParameterChanged]. The hosting activity keeps the
 * sliders, prefs, and visualizer in lockstep.
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

    /** Fired while a handle is being dragged. The host should mirror the
     *  value into the matching slider, save it to prefs, and update the
     *  view's own field via the corresponding setter. */
    var onParameterChanged: ((Param, Float) -> Unit)? = null

    var decayTimeMs: Float = 1490f
        set(v) { field = v; invalidate() }
    var decayHfRatio: Float = 0.83f
        set(v) { field = v; invalidate() }
    var reverbLevelDb: Float = -26f
        set(v) { field = v; invalidate() }
    var roomLevelDb: Float = -10f
        set(v) { field = v; invalidate() }
    var reflectionsDelayMs: Float = 7f
        set(v) { field = v; invalidate() }
    var reflectionsLevelDb: Float = -10f
        set(v) { field = v; invalidate() }
    var diffusionPct: Float = 100f
        set(v) { field = v; invalidate() }
    var densityPct: Float = 100f
        set(v) { field = v; invalidate() }

    private val density = context.resources.displayMetrics.density

    // ---- Paints --------------------------------------------------------

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        strokeWidth = 1f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33888888.toInt()
        strokeWidth = 1f
    }
    private val directPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E2E2.toInt()
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val reflectPaintTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val reflectPaintBot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7F7F7F.toInt()
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tailStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val tailFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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

    private val tailPathTop = Path()
    private val tailPathBot = Path()
    private val tailFillPath = Path()

    // ---- Handles -------------------------------------------------------

    private enum class Handle { DIRECT, REFLECTIONS, TAIL_START, TAIL_CURVE, TAIL_END }

    private data class HandlePos(val x: Float, val y: Float)

    private val handlePos = HashMap<Handle, HandlePos>()
    private var grabbed: Handle? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    // ---- Geometry helpers ---------------------------------------------

    private var plotL = 0f
    private var plotT = 0f
    private var plotR = 0f
    private var plotB = 0f
    private var centerY = 0f
    private var halfH = 0f
    private var xMaxMs = 0f

    private fun timeToX(ms: Float) = plotL + (ms / xMaxMs) * (plotR - plotL)
    private fun xToTime(x: Float) = ((x - plotL) / (plotR - plotL)) * xMaxMs

    /** dB → distance from baseline (positive going up). 0 dB = full halfH. */
    private fun dbToHalf(db: Float): Float {
        val norm = ((db + 90f) / 90f).coerceIn(0f, 1f)
        return norm * halfH
    }

    /** Convert |distance from baseline| back into dB (clamped 0 dB). */
    private fun halfToDb(half: Float): Float {
        val norm = (half / halfH).coerceIn(0f, 1f)
        return norm * 90f - 90f
    }

    // ---- Drawing -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padX = 8f * density
        val padY = 8f * density
        plotL = padX
        plotT = padY
        plotR = w - padX
        plotB = h - padY
        centerY = (plotT + plotB) * 0.5f
        halfH = (plotB - plotT) * 0.5f

        xMaxMs = max(decayTimeMs + reflectionsDelayMs + 200f, 1500f)

        // Subtle grid: vertical guides at quarter-time, horizontal centre.
        for (i in 1..3) {
            val gx = plotL + (plotR - plotL) * i / 4f
            canvas.drawLine(gx, plotT, gx, plotB, gridPaint)
        }
        canvas.drawLine(plotL, centerY, plotR, centerY, baselinePaint)

        // 1. Direct sound — mirrored impulse at t=0.
        val x0 = timeToX(0f)
        val directH = dbToHalf(roomLevelDb)
        canvas.drawLine(x0, centerY - directH, x0, centerY + directH, directPaint)
        handlePos[Handle.DIRECT] = HandlePos(x0, centerY - directH)

        // 2. Early reflections — alternating up/down spikes (waveform feel).
        val reflectStart = reflectionsDelayMs
        val reflectEnd = (reflectStart + decayTimeMs * 0.35f).coerceAtMost(xMaxMs)
        val baseCount = 8
        val maxCount = 24
        val n = (baseCount + (densityPct / 100f) * (maxCount - baseCount)).toInt()
        val spread = 0.25f + 0.75f * (diffusionPct / 100f)
        val rng = Random(42)
        val refH0 = dbToHalf(reflectionsLevelDb)
        for (i in 0 until n) {
            val r = rng.nextFloat()
            val frac = (1f - spread) * (i.toFloat() / n) + spread * r
            val t = reflectStart + (reflectEnd - reflectStart) * frac
            val tFrac = ((t - reflectStart) / (reflectEnd - reflectStart)).coerceIn(0f, 1f)
            val attenDb = reflectionsLevelDb - 60f * tFrac
            val sH = dbToHalf(attenDb)
            val tx = timeToX(t)
            val pol = if (i % 2 == 0) reflectPaintTop else reflectPaintBot
            // Alternate top / bottom polarity for the waveform mirror look.
            if (i % 2 == 0) canvas.drawLine(tx, centerY, tx, centerY - sH, pol)
            else canvas.drawLine(tx, centerY, tx, centerY + sH, pol)
        }
        handlePos[Handle.REFLECTIONS] = HandlePos(timeToX(reflectStart), centerY - refH0)

        // 3. Late reverb tail — exponential envelope, mirrored. Decay HF
        //    Ratio shapes the curve: low ratio → steep (concave) drop,
        //    high ratio → shallow (convex) drop.
        val effectiveDecay = decayTimeMs * decayHfRatio.coerceIn(0.1f, 2f)
        val tailStartMs = reflectStart + 30f
        val tailEndMs = (tailStartMs + effectiveDecay).coerceAtMost(xMaxMs)
        val steps = 80
        val curveExp = (2f - decayHfRatio).coerceIn(0.4f, 2.0f)  // 0.4 (shallow) .. 2.0 (steep)

        tailPathTop.reset()
        tailPathBot.reset()
        tailFillPath.reset()
        for (i in 0..steps) {
            val frac = i.toFloat() / steps
            val t = tailStartMs + (tailEndMs - tailStartMs) * frac
            // Power-curve interpolation in dB-space gives us a visibly
            // adjustable convex/concave shape via decayHfRatio.
            val curveFrac = Math.pow(frac.toDouble(), curveExp.toDouble()).toFloat()
            val envelopeDb = reverbLevelDb - 60f * curveFrac
            val tx = timeToX(t)
            val eH = dbToHalf(envelopeDb)
            val yTop = centerY - eH
            val yBot = centerY + eH
            if (i == 0) {
                tailPathTop.moveTo(tx, yTop)
                tailPathBot.moveTo(tx, yBot)
                tailFillPath.moveTo(tx, yTop)
            } else {
                tailPathTop.lineTo(tx, yTop)
                tailPathBot.lineTo(tx, yBot)
                tailFillPath.lineTo(tx, yTop)
            }
        }
        // Close the fill across the bottom envelope going right→left so
        // the gradient under the tail is bounded by both halves.
        for (i in steps downTo 0) {
            val frac = i.toFloat() / steps
            val t = tailStartMs + (tailEndMs - tailStartMs) * frac
            val curveFrac = Math.pow(frac.toDouble(), curveExp.toDouble()).toFloat()
            val envelopeDb = reverbLevelDb - 60f * curveFrac
            val tx = timeToX(t)
            val eH = dbToHalf(envelopeDb)
            tailFillPath.lineTo(tx, centerY + eH)
        }
        tailFillPath.close()

        tailFillPaint.shader = LinearGradient(
            timeToX(tailStartMs), 0f, timeToX(tailEndMs), 0f,
            0x66E2E2E2.toInt(), 0x00E2E2E2.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(tailFillPath, tailFillPaint)
        canvas.drawPath(tailPathTop, tailStrokePaint)
        canvas.drawPath(tailPathBot, tailStrokePaint)

        // Compute & cache handle positions on top of the tail.
        val tailStartH = dbToHalf(reverbLevelDb)
        handlePos[Handle.TAIL_START] = HandlePos(timeToX(tailStartMs), centerY - tailStartH)

        // Mid-tail handle for HF curve drag — sits at the 50% time point.
        val midFrac = 0.5f
        val midT = tailStartMs + (tailEndMs - tailStartMs) * midFrac
        val midCurveFrac = Math.pow(midFrac.toDouble(), curveExp.toDouble()).toFloat()
        val midDb = reverbLevelDb - 60f * midCurveFrac
        handlePos[Handle.TAIL_CURVE] = HandlePos(timeToX(midT), centerY - dbToHalf(midDb))

        handlePos[Handle.TAIL_END] = HandlePos(timeToX(tailEndMs), centerY)

        // Render handles last so they sit on top of the curves.
        for ((h, p) in handlePos) {
            val active = h == grabbed
            val fill = if (active) handleFillActivePaint else handleFillPaint
            canvas.drawCircle(p.x, p.y, 5f * density, fill)
            canvas.drawCircle(p.x, p.y, 5f * density, handleStrokePaint)
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
        var best: Handle? = null
        var bestDist = hitRadiusPx
        for ((h, p) in handlePos) {
            val d = hypot((p.x - x).toDouble(), (p.y - y).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                best = h
            }
        }
        return best
    }

    private fun applyHandleDrag(h: Handle, x: Float, y: Float) {
        val cb = onParameterChanged
        when (h) {
            Handle.DIRECT -> {
                val newDb = halfToDb(centerY - y).coerceIn(-90f, 0f)
                roomLevelDb = newDb
                cb?.invoke(Param.ROOM_LEVEL, newDb)
            }
            Handle.REFLECTIONS -> {
                val newDelay = xToTime(x).coerceIn(0f, 300f)
                val newDb = halfToDb(centerY - y).coerceIn(-90f, 10f)
                reflectionsDelayMs = newDelay
                reflectionsLevelDb = newDb
                cb?.invoke(Param.REFLECTIONS_DELAY, newDelay)
                cb?.invoke(Param.REFLECTIONS_LEVEL, newDb)
            }
            Handle.TAIL_START -> {
                val newDb = halfToDb(centerY - y).coerceIn(-90f, 20f)
                reverbLevelDb = newDb
                cb?.invoke(Param.REVERB_LEVEL, newDb)
            }
            Handle.TAIL_CURVE -> {
                // Convert handle Y → effective dB at midpoint → solve for
                // the power-curve exponent → invert to decayHfRatio.
                // dB(mid) = reverbLevel - 60 * 0.5^curveExp
                // → curveExp = log2(60 / (reverbLevel - dBmid))
                // Then decayHfRatio = 2 - curveExp (clamped).
                val midDb = halfToDb(centerY - y)
                val drop = (reverbLevelDb - midDb).coerceIn(0.5f, 60f)
                val curveExp = (Math.log(60.0 / drop) / Math.log(2.0)).toFloat()
                val ratio = (2f - curveExp).coerceIn(0.1f, 2.0f)
                decayHfRatio = ratio
                cb?.invoke(Param.DECAY_HF, ratio)
            }
            Handle.TAIL_END -> {
                val tailEndMs = xToTime(x)
                val tailStartMs = reflectionsDelayMs + 30f
                val effective = (tailEndMs - tailStartMs).coerceAtLeast(50f)
                val newDecay = (effective / decayHfRatio.coerceAtLeast(0.1f))
                    .coerceIn(100f, 20000f)
                decayTimeMs = newDecay
                cb?.invoke(Param.DECAY_TIME, newDecay)
            }
        }
    }
}
