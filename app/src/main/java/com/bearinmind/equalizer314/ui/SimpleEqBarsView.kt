package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SimpleEqBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        val FREQUENCIES = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val LABELS = arrayOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        const val BAND_COUNT = 10
        const val MIN_DB = -12f
        const val MAX_DB = 12f
    }

    private val gains = FloatArray(BAND_COUNT) { 0f }

    var onGainChanged: ((bandIndex: Int, gain: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    // Bar styling
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        style = Paint.Style.FILL
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt()
        style = Paint.Style.FILL
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt()
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }

    private val barCornerRadius = 6f * density
    // Bar sizing as % of available width — scales with screen size.
    // barWidthPct + barGapPct should sum to ~10% (one slot = 10% of width for 10 bands).
    private val barWidthPct = 0.07f  // each bar = 7% of view width
    private val barGapPct = 0.02f    // gap between bars = 2% of view width
    private val labelTopHeight = 28f * density   // space for dB label above bars
    private val labelBottomHeight = 28f * density // space for freq label below bars
    private val barRect = RectF()

    private var activeBand = -1
    private var lastTapTime = 0L
    private var lastTapBand = -1
    private var doubleTapReset = false // skip ACTION_MOVE after double-tap reset

    // Drag-from-original-position model: ACTION_DOWN doesn't snap the
    // bar to the touch's Y position. Instead it records where the
    // finger started (touchDownY) and what the bar's gain was at that
    // moment (touchDownGain). ACTION_MOVE applies the finger's *delta*
    // since DOWN to that starting gain. So a tap leaves the bar
    // unchanged; only a swipe up/down moves it.
    private var touchDownY = 0f
    private var touchDownGain = 0f

    // Pop-out animation: the active bar scales wider when being dragged
    private val popScales = FloatArray(BAND_COUNT) { 1f }
    private var popAnimator: android.animation.ValueAnimator? = null
    private val POP_SCALE = 1.1f // active bar is 10% bigger

    private fun animatePop(bandIndex: Int, popIn: Boolean) {
        popAnimator?.cancel()
        val from = popScales[bandIndex]
        val to = if (popIn) POP_SCALE else 1f
        popAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 150
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                popScales[bandIndex] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setGain(index: Int, gain: Float) {
        if (index in 0 until BAND_COUNT) {
            gains[index] = gain.coerceIn(MIN_DB, MAX_DB)
            invalidate()
        }
    }

    fun getGain(index: Int): Float = if (index in 0 until BAND_COUNT) gains[index] else 0f

    fun setAllGains(newGains: FloatArray) {
        for (i in 0 until minOf(newGains.size, BAND_COUNT)) {
            gains[i] = newGains[i].coerceIn(MIN_DB, MAX_DB)
        }
        invalidate()
    }

    private fun getBarWidth(): Float = width * barWidthPct
    private fun getBarGap(): Float = width * barGapPct

    private fun getTotalGroupWidth(): Float {
        return BAND_COUNT * getBarWidth() + (BAND_COUNT - 1) * getBarGap()
    }

    private fun getGroupStartX(): Float {
        return (width.toFloat() - getTotalGroupWidth()) / 2f
    }

    private fun getBandCenter(index: Int): Float {
        return getGroupStartX() + index * (getBarWidth() + getBarGap()) + getBarWidth() / 2f
    }

    private fun getBarTop(): Float = labelTopHeight
    private fun getBarBottom(): Float = height.toFloat() - labelBottomHeight
    private fun getBarHeight(): Float = getBarBottom() - getBarTop()
    private fun getCenterY(): Float = getBarTop() + getBarHeight() / 2f

    private fun gainToY(gain: Float): Float {
        val fraction = (gain - MIN_DB) / (MAX_DB - MIN_DB) // 0 at -12, 1 at +12
        return getBarBottom() - fraction * getBarHeight()
    }

    private fun yToGain(y: Float): Float {
        val fraction = (getBarBottom() - y) / getBarHeight()
        return (MIN_DB + fraction * (MAX_DB - MIN_DB)).coerceIn(MIN_DB, MAX_DB)
    }

    private fun bandAtX(x: Float): Int {
        val slotW = getBarWidth() + getBarGap()
        for (i in 0 until BAND_COUNT) {
            val cx = getBandCenter(i)
            if (x >= cx - slotW / 2f && x <= cx + slotW / 2f) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = getCenterY()

        for (i in 0 until BAND_COUNT) {
            val scale = popScales[i]
            val cx = getBandCenter(i)
            val halfW = getBarWidth() / 2f * scale
            val left = cx - halfW
            val right = cx + halfW

            // Vertical pop: bar grows taller equally on top and bottom when active
            val extraV = (getBarHeight() * (scale - 1f) * 0.5f)
            val barTop = getBarTop() - extraV
            val barBottom = getBarBottom() + extraV

            // Bar background (full height slot)
            barRect.set(left, barTop, right, barBottom)
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barBgPaint)

            // Active bar — always fills from bottom up to the gain level.
            val gain = gains[i]
            val scaledBarH = barBottom - barTop
            val fraction = (gain - MIN_DB) / (MAX_DB - MIN_DB)
            val gainY = barBottom - fraction * scaledBarH
            if (gainY < barBottom) {
                barRect.set(left, gainY, right, barBottom)
                canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint)
            }

            // dB label above bar — moves up with the pop animation
            val dbText = String.format("%.1f", gain)
            canvas.drawText(dbText, cx, barTop - 4f * density, labelPaint)

            // Frequency label below bar — moves down with the pop animation
            val freqText = if (FREQUENCIES[i] >= 1000f) {
                "${(FREQUENCIES[i] / 1000f).toInt()} kHz"
            } else {
                "${FREQUENCIES[i].toInt()} Hz"
            }
            canvas.drawText(freqText, cx, barBottom + labelPaint.textSize + 2f * density, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val band = bandAtX(event.x)
                if (band >= 0) {
                    activeBand = band
                    doubleTapReset = false
                    animatePop(band, true) // pop out the active bar
                    // Double-tap detection — second tap on the same band
                    // within 300 ms still resets that bar to 0 dB. Single
                    // tap (no swipe) leaves the bar where it is.
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300 && band == lastTapBand) {
                        gains[band] = 0f
                        onGainChanged?.invoke(band, 0f)
                        invalidate()
                        lastTapTime = 0
                        doubleTapReset = true // ignore ACTION_MOVE until finger lifts
                    } else {
                        // Don't change the bar's gain on touch-down.
                        // Record the starting finger Y and the bar's
                        // current gain so ACTION_MOVE can apply a delta.
                        touchDownY = event.y
                        touchDownGain = gains[band]
                        lastTapTime = now
                        lastTapBand = band
                    }
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeBand >= 0 && !doubleTapReset) {
                    // Convert finger movement to a gain delta. Moving
                    // the finger up (smaller Y) should INCREASE the
                    // gain, hence (touchDownY - event.y).
                    val pxPerDb = getBarHeight() / (MAX_DB - MIN_DB)
                    val deltaDb = (touchDownY - event.y) / pxPerDb
                    val newGain = (touchDownGain + deltaDb).coerceIn(MIN_DB, MAX_DB)
                    gains[activeBand] = newGain
                    onGainChanged?.invoke(activeBand, newGain)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeBand >= 0) {
                    animatePop(activeBand, false) // shrink back to normal
                    onDragEnd?.invoke()
                }
                activeBand = -1
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}
