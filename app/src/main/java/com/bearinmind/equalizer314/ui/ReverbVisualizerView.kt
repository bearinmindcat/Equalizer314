package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Reverb amplitude-envelope display modeled on the standard "Visualizing
 * Reverb" diagram (Splice / Producer Hive style):
 *
 *     | Pre-delay | Early Reflections |          Decay              |
 *     -------------|-----X-X-XXX-XXX---|wwwwww~~~~~~~~~~~~~--------- ← amplitude
 *
 * Single-sided continuous amplitude curve, gradient fill underneath,
 * three labeled regions at the bottom, "Amplitude" label on the left,
 * "Total Decay Time" indicator on top.
 *
 * Interactive handles drag the three regional levels and the time
 * extents to update parameters live (host wires [onParameterChanged] to
 * sliders + prefs).
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

    var onParameterChanged: ((Param, Float) -> Unit)? = null

    var decayTimeMs: Float = 1490f; set(v) { field = v; invalidate() }
    var decayHfRatio: Float = 0.83f; set(v) { field = v; invalidate() }
    var reverbLevelDb: Float = -26f; set(v) { field = v; invalidate() }
    var roomLevelDb: Float = -10f; set(v) { field = v; invalidate() }
    var reflectionsDelayMs: Float = 7f; set(v) { field = v; invalidate() }
    var reflectionsLevelDb: Float = -10f; set(v) { field = v; invalidate() }
    var diffusionPct: Float = 100f; set(v) { field = v; invalidate() }
    var densityPct: Float = 100f; set(v) { field = v; invalidate() }

    private val density = context.resources.displayMetrics.density

    // ---- Paints --------------------------------------------------------

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
    private val curveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8A50.toInt()
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val curveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private val curvePath = Path()
    private val curveFillPath = Path()

    // ---- Handles -------------------------------------------------------

    private enum class Handle { ROOM, REFLECTIONS, REVERB, DECAY_HF, DECAY_END }
    private data class HandlePos(val x: Float, val y: Float)
    private val handlePos = HashMap<Handle, HandlePos>()
    private var grabbed: Handle? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    // Cached layout (recomputed each onDraw).
    private var plotL = 0f; private var plotT = 0f
    private var plotR = 0f; private var plotB = 0f
    private var xMaxMs = 0f
    private var preEnd = 0f
    private var earlyEnd = 0f
    private var decayEnd = 0f

    private fun timeToX(ms: Float) = plotL + (ms / xMaxMs) * (plotR - plotL)
    private fun xToTime(x: Float) = ((x - plotL) / (plotR - plotL)) * xMaxMs

    /** Linear amplitude (0..1) for a dB value mapped against the plot height.
     *  -90 dB ≈ 0, 0 dB = full plot height. Linear-in-dB feels "right" here
     *  because the user's level sliders are dB-denominated. */
    private fun dbToAmp01(db: Float) = ((db + 90f) / 90f).coerceIn(0f, 1f)
    private fun amp01ToY(a: Float) = plotB - a.coerceIn(0f, 1f) * (plotB - plotT)
    private fun yToAmp01(y: Float) = ((plotB - y) / (plotB - plotT)).coerceIn(0f, 1f)
    private fun amp01ToDb(a: Float) = a.coerceIn(0f, 1f) * 90f - 90f

    // ---- Drawing -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Layout: leave room for "Amplitude" label on the left, region
        // labels on the bottom, and the "Total Decay Time" arrow on top.
        val leftLabelW = 20f * density
        val bottomLabelH = 16f * density
        val topArrowH = 12f * density
        plotL = leftLabelW
        plotT = topArrowH
        plotR = w - 6f * density
        plotB = h - bottomLabelH

        // Time scale: divide chart into pre-delay | early-reflections |
        // decay so widths visibly track the parameter values.
        val earlyDurationMs = max(decayTimeMs * 0.18f, 80f)
        val decayDurationMs = decayTimeMs * decayHfRatio.coerceIn(0.1f, 2f)
        xMaxMs = max(reflectionsDelayMs + earlyDurationMs + decayDurationMs + 200f, 1500f)
        preEnd = timeToX(reflectionsDelayMs)
        earlyEnd = timeToX(reflectionsDelayMs + earlyDurationMs)
        decayEnd = timeToX((reflectionsDelayMs + earlyDurationMs + decayDurationMs).coerceAtMost(xMaxMs))

        drawFrame(canvas)
        drawCurve(canvas)
        drawRegionLabels(canvas, h)
        drawAmplitudeLabel(canvas)
        drawHandles(canvas)
    }

    private fun drawFrame(c: Canvas) {
        // Bottom baseline + left edge form the "L" of the chart.
        c.drawLine(plotL, plotB, plotR, plotB, frameLinePaint)
        c.drawLine(plotL, plotT, plotL, plotB, frameLinePaint)
        // Right tick.
        c.drawLine(plotR, plotT, plotR, plotB, frameLinePaint)

        // Region dividers.
        c.drawLine(preEnd, plotT, preEnd, plotB, regionDividerPaint)
        c.drawLine(earlyEnd, plotT, earlyEnd, plotB, regionDividerPaint)

        // "Total Decay Time" arrow across the top.
        val topY = plotT - 4f * density
        c.drawLine(plotL + 6f * density, topY, plotR - 6f * density, topY, arrowPaint)
        // Arrow heads (left and right).
        val ah = 4f * density
        c.drawLine(plotL + 6f * density, topY, plotL + 6f * density + ah, topY - ah * 0.7f, arrowPaint)
        c.drawLine(plotL + 6f * density, topY, plotL + 6f * density + ah, topY + ah * 0.7f, arrowPaint)
        c.drawLine(plotR - 6f * density, topY, plotR - 6f * density - ah, topY - ah * 0.7f, arrowPaint)
        c.drawLine(plotR - 6f * density, topY, plotR - 6f * density - ah, topY + ah * 0.7f, arrowPaint)
    }

    private fun drawCurve(c: Canvas) {
        // Sample N amplitude points across the chart and stitch them into
        // a single Path. Higher sample count = smoother curve.
        val samples = 220
        val refl = reflectionsDelayMs
        val refLevel = dbToAmp01(reflectionsLevelDb)
        val roomLevel = dbToAmp01(roomLevelDb)
        val revLevel = dbToAmp01(reverbLevelDb)
        val tailStartMs = refl + max(decayTimeMs * 0.18f, 80f)
        val decayDurationMs = decayTimeMs * decayHfRatio.coerceIn(0.1f, 2f)
        val curveExp = (2f - decayHfRatio).coerceIn(0.4f, 2.0f)

        // Reflection cluster — generate a stable set of impulse positions
        // weighted by Density (count) and Diffusion (spread).
        val baseN = 6
        val maxN = 22
        val nRefl = (baseN + (densityPct / 100f) * (maxN - baseN)).toInt()
        val spread = 0.25f + 0.75f * (diffusionPct / 100f)
        val reflTimes = FloatArray(nRefl)
        val reflWeights = FloatArray(nRefl)
        val rng = java.util.Random(42)
        val earlyDur = max(decayTimeMs * 0.18f, 80f)
        for (i in 0 until nRefl) {
            val r = rng.nextFloat()
            val fracT = (1f - spread) * (i.toFloat() / nRefl) + spread * r
            reflTimes[i] = refl + earlyDur * fracT
            reflWeights[i] = (0.55f + rng.nextFloat() * 0.45f) * (1f - fracT * 0.6f)
        }

        curvePath.reset()
        curveFillPath.reset()
        var firstPoint = true
        for (s in 0..samples) {
            val tFrac = s.toFloat() / samples
            val tMs = tFrac * xMaxMs

            var amp = 0f

            // Direct sound — narrow gaussian spike at t=0.
            amp += roomLevel * exp(-(tMs * tMs) / 8f)

            // Pre-delay window stays near zero (just the direct decaying).
            if (tMs >= refl) {
                // Early reflections — sum of narrow gaussians at the
                // pre-computed reflection times.
                val sigma = 4f + 6f * (1f - diffusionPct / 100f)
                for (i in 0 until nRefl) {
                    val dt = tMs - reflTimes[i]
                    if (dt < -3f * sigma || dt > 3f * sigma) continue
                    amp += refLevel * reflWeights[i] * exp(-(dt * dt) / (2f * sigma * sigma))
                }

                // Late reverb tail — exponential envelope (in dB-space)
                // multiplied by a smooth wavy modulation. Modulation
                // depth scales with Density so a denser room has more
                // texture, and decreases over time so the tail smooths.
                if (tMs >= tailStartMs) {
                    val tailDur = decayDurationMs.coerceAtLeast(50f)
                    val tailFrac = ((tMs - tailStartMs) / tailDur).coerceIn(0f, 1f)
                    val curveFrac = tailFrac.toDouble().pow(curveExp.toDouble()).toFloat()
                    // dB envelope: drops 60 dB across tailDur, scaled to amp01.
                    val envDb = reverbLevelDb - 60f * curveFrac
                    val envAmp = dbToAmp01(envDb)
                    // Wavy modulation — sum of three sines with diminishing
                    // amplitude over time; phase locked so it's deterministic.
                    val k = (densityPct / 100f) * 0.55f * (1f - tailFrac).pow(0.7f)
                    val mod = 1f + k * (
                        sin(tMs * 0.012f + 0.7f) +
                        sin(tMs * 0.027f + 1.9f) * 0.6f +
                        sin(tMs * 0.061f + 3.5f) * 0.35f
                    ) / 1.95f
                    amp += envAmp * mod.coerceAtLeast(0f) * 0.95f
                }
            }

            val x = timeToX(tMs)
            val y = amp01ToY(amp)
            if (firstPoint) {
                curvePath.moveTo(x, y)
                curveFillPath.moveTo(x, plotB)
                curveFillPath.lineTo(x, y)
                firstPoint = false
            } else {
                curvePath.lineTo(x, y)
                curveFillPath.lineTo(x, y)
            }
        }
        curveFillPath.lineTo(plotR, plotB)
        curveFillPath.close()

        // Orange→pink vertical gradient under the curve, fading slightly
        // toward the right so the tail recedes visually.
        curveFillPaint.shader = LinearGradient(
            plotL, plotT, plotR, plotB,
            intArrayOf(0xFFFFB070.toInt(), 0xFFFF6E91.toInt(), 0x33FF6E91.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(curveFillPath, curveFillPaint)
        c.drawPath(curvePath, curveStrokePaint)

        // Cache handle anchor positions on the curve.
        handlePos[Handle.ROOM] = HandlePos(timeToX(0f), amp01ToY(roomLevel))
        handlePos[Handle.REFLECTIONS] = HandlePos(timeToX(refl), amp01ToY(refLevel))
        handlePos[Handle.REVERB] = HandlePos(timeToX(tailStartMs), amp01ToY(revLevel))
        // Curve handle at the tail's midpoint.
        run {
            val midFrac = 0.5f
            val midT = tailStartMs + decayDurationMs * midFrac
            val midDb = reverbLevelDb - 60f * midFrac.toDouble().pow(curveExp.toDouble()).toFloat()
            handlePos[Handle.DECAY_HF] = HandlePos(timeToX(midT), amp01ToY(dbToAmp01(midDb)))
        }
        handlePos[Handle.DECAY_END] = HandlePos(decayEnd, plotB)
    }

    private fun drawRegionLabels(c: Canvas, viewH: Float) {
        val y = viewH - 4f * density
        val labelY = plotB + 12f * density
        // Region centers.
        val preCenter = (plotL + preEnd) / 2f
        val earlyCenter = (preEnd + earlyEnd) / 2f
        val decayCenter = (earlyEnd + plotR) / 2f
        c.drawText("Pre-delay", preCenter, labelY, labelPaint)
        c.drawText("Early Reflections", earlyCenter, labelY, labelPaint)
        c.drawText("Decay", decayCenter, labelY, labelPaint)
    }

    private fun drawAmplitudeLabel(c: Canvas) {
        // Rotated 90° so it reads bottom-to-top alongside the Y axis.
        c.save()
        val cx = plotL - 12f * density
        val cy = (plotT + plotB) / 2f
        c.rotate(-90f, cx, cy)
        amplitudeLabelPaint.textAlign = Paint.Align.CENTER
        c.drawText("amplitude", cx, cy + 4f * density, amplitudeLabelPaint)
        c.restore()
    }

    private fun drawHandles(c: Canvas) {
        for ((h, p) in handlePos) {
            val active = h == grabbed
            val fill = if (active) handleFillActivePaint else handleFillPaint
            c.drawCircle(p.x, p.y, 5f * density, fill)
            c.drawCircle(p.x, p.y, 5f * density, handleStrokePaint)
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
                // Mid-tail amplitude → solve for power exponent → decayHfRatio.
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
        }
    }
}
