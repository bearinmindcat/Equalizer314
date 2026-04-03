package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Simple vertical bar meter for limiter visualization.
 * Supports two modes:
 *   - LEVEL: fills from bottom up (green→yellow→red)
 *   - GR: fills from top down (orange/red, gain reduction)
 */
class LimiterMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    enum class Mode { LEVEL, GR }

    var mode: Mode = Mode.LEVEL
        set(value) { field = value; invalidate() }

    /** Current value in dB. For LEVEL: -60 to 0. For GR: 0 to -30. */
    var valueDb: Float = -60f
        set(value) { field = value; invalidate() }

    /** Threshold line position in dB (only shown in LEVEL mode) */
    var thresholdDb: Float = -0.5f
        set(value) { field = value; invalidate() }

    var showThreshold: Boolean = false
        set(value) { field = value; invalidate() }

    // Peak hold
    private var peakDb = -60f
    private var peakHoldFrames = 0
    private val peakHoldMax = 30  // ~1 second at 30fps

    private val bgPaint = Paint().apply { color = 0xFF1E1E1E.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val barPaint = Paint()
    private val peakPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 2f
    }
    private val threshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6666.toInt(); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }

    private val dbMin = -60f
    private val dbMax = 0f
    private val grMin = -30f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val pad = 2f
        val barLeft = pad
        val barRight = w - pad
        val barTop = pad
        val barBottom = h - pad
        val barHeight = barBottom - barTop

        when (mode) {
            Mode.LEVEL -> {
                // Fill from bottom up
                val norm = ((valueDb - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
                val fillTop = barBottom - barHeight * norm

                // Gradient: green at bottom, yellow in middle, red at top
                barPaint.shader = LinearGradient(
                    0f, barBottom, 0f, barTop,
                    intArrayOf(0xFF4CAF50.toInt(), 0xFFFFEB3B.toInt(), 0xFFF44336.toInt()),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(barLeft, fillTop, barRight, barBottom, barPaint)
                barPaint.shader = null

                // Peak hold
                if (valueDb > peakDb) {
                    peakDb = valueDb
                    peakHoldFrames = 0
                } else {
                    peakHoldFrames++
                    if (peakHoldFrames > peakHoldMax) {
                        peakDb = (peakDb - 0.5f).coerceAtLeast(dbMin)
                    }
                }
                val peakNorm = ((peakDb - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
                val peakY = barBottom - barHeight * peakNorm
                if (peakNorm > 0.01f) {
                    canvas.drawLine(barLeft, peakY, barRight, peakY, peakPaint)
                }

                // Threshold line
                if (showThreshold) {
                    val threshNorm = ((thresholdDb - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
                    val threshY = barBottom - barHeight * threshNorm
                    canvas.drawLine(barLeft, threshY, barRight, threshY, threshPaint)
                }
            }
            Mode.GR -> {
                // Fill from top down (GR is negative)
                val norm = ((valueDb - grMin) / (0f - grMin)).coerceIn(0f, 1f)
                val fillBottom = barTop + barHeight * (1f - norm)

                barPaint.color = 0xFFFF8A65.toInt()  // orange
                canvas.drawRect(barLeft, barTop, barRight, fillBottom, barPaint)
            }
        }

        // Border
        canvas.drawRect(pad, pad, w - pad, h - pad, borderPaint)
    }
}
