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
    private val dbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val freqLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }

    private val barCornerRadius = 6f * density
    private val barGap = 4f * density
    private val labelTopHeight = 18f * density   // space for dB label above bars
    private val labelBottomHeight = 18f * density // space for freq label below bars
    private val barRect = RectF()

    private var activeBand = -1
    private var lastTapTime = 0L
    private var lastTapBand = -1

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

    private fun getBandWidth(): Float {
        return (width.toFloat() - barGap * (BAND_COUNT + 1)) / BAND_COUNT
    }

    private fun getBandLeft(index: Int): Float {
        val bw = getBandWidth()
        return barGap + index * (bw + barGap)
    }

    private fun getBandCenter(index: Int): Float {
        return getBandLeft(index) + getBandWidth() / 2f
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
        val bw = getBandWidth()
        for (i in 0 until BAND_COUNT) {
            val left = getBandLeft(i)
            if (x >= left - barGap / 2 && x <= left + bw + barGap / 2) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bw = getBandWidth()
        val centerY = getCenterY()

        for (i in 0 until BAND_COUNT) {
            val left = getBandLeft(i)
            val right = left + bw

            // Bar background (full height slot)
            barRect.set(left, getBarTop(), right, getBarBottom())
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barBgPaint)

            // Active bar (from center to gain level)
            val gain = gains[i]
            if (gain != 0f) {
                val gainY = gainToY(gain)
                if (gain > 0f) {
                    barRect.set(left, gainY, right, centerY)
                } else {
                    barRect.set(left, centerY, right, gainY)
                }
                canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint)
            }

            // dB label above bar
            val dbText = String.format("%.1f", gain)
            canvas.drawText(dbText, left + bw / 2f, labelTopHeight - 4f * density, dbLabelPaint)

            // Frequency label below bar
            canvas.drawText(LABELS[i], left + bw / 2f, height.toFloat() - 2f * density, freqLabelPaint)
        }

        // Center line (0 dB)
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val band = bandAtX(event.x)
                if (band >= 0) {
                    activeBand = band
                    // Double-tap detection
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300 && band == lastTapBand) {
                        gains[band] = 0f
                        onGainChanged?.invoke(band, 0f)
                        invalidate()
                        lastTapTime = 0
                    } else {
                        val gain = yToGain(event.y)
                        gains[band] = gain
                        onGainChanged?.invoke(band, gain)
                        invalidate()
                        lastTapTime = now
                        lastTapBand = band
                    }
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeBand >= 0) {
                    val gain = yToGain(event.y)
                    gains[activeBand] = gain
                    onGainChanged?.invoke(activeBand, gain)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeBand >= 0) onDragEnd?.invoke()
                activeBand = -1
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}
