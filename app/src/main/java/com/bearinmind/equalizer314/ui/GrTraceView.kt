package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.log10
import kotlin.math.pow

/**
 * Frequency-domain MBC display with per-band scrolling traces.
 * ONE unified dB scale (0 to -60 dB) for everything:
 * - Input level waveform (grey fill from bottom)
 * - GR trace (colored line from top, dips when compressing)
 * - Threshold line (draggable, per-band)
 * - Crossover dividers (draggable)
 *
 * The GR trace NEVER goes past the threshold line.
 * GR only appears when input level exceeds the threshold.
 * Like FabFilter Pro-C 3.
 */
class GrTraceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxBands = 6
    var numBands = 3
    var selectedBand = 0

    // Per-band thresholds (draggable)
    private val thresholds = FloatArray(maxBands) { 0f }
    private var draggingThresholdBand = -1
    var onThresholdChanged: ((bandIndex: Int, thresholdDb: Float) -> Unit)? = null

    // Crossover dragging
    private var draggingCrossover = -1
    var onCrossoverChanged: ((crossoverIndex: Int, frequency: Float) -> Unit)? = null

    // Per-band ring buffers
    private val bufferSize = 300
    private var writeIdx = 0
    private val grHistory = Array(maxBands) { FloatArray(bufferSize) }  // GR in dB (always <= 0)
    private val levelHistory = Array(maxBands) { FloatArray(bufferSize) { -60f } }  // input level in dB
    private val gateGrHistory = Array(maxBands) { FloatArray(bufferSize) }  // gate/expander GR (always <= 0)

    var crossoverFreqs: FloatArray? = null

    private val bandColors = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(),
        0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt()
    )

    // Unified dB scale: +20 at top, -60 at bottom
    private val dbMax = 20f
    private val dbMin = -60f
    private val dbRange = dbMax - dbMin

    // Frequency axis (matches EqGraphView: 10 Hz to 20000 Hz)
    private val freqMin = 10f
    private val freqMax = 20000f
    private val logMin = log10(freqMin)
    private val logMax = log10(freqMax)
    private val logRange = logMax - logMin

    // Vertical padding (matches EqGraphView vPad = 80f)
    private val vPad = 80f

    // Paints
    private val bgPaint = Paint().apply { color = 0xFF1A1A1A.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt(); strokeWidth = 1f
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt(); textSize = 16f
    }
    private val crossoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAABBBBBB.toInt(); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val threshLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    fun pushFrame(bandGrValues: FloatArray, bandLevels: FloatArray, bandGateGr: FloatArray = FloatArray(0)) {
        val idx = writeIdx % bufferSize
        for (b in 0 until numBands.coerceAtMost(maxBands)) {
            grHistory[b][idx] = bandGrValues.getOrElse(b) { 0f }.coerceIn(-30f, 0f)
            levelHistory[b][idx] = bandLevels.getOrElse(b) { -60f }.coerceIn(-60f, 20f)
            gateGrHistory[b][idx] = bandGateGr.getOrElse(b) { 0f }.coerceIn(-30f, 0f)
        }
        writeIdx++
    }

    fun setThreshold(bandIndex: Int, thresholdDb: Float) {
        if (bandIndex in thresholds.indices) thresholds[bandIndex] = thresholdDb.coerceIn(dbMin, dbMax)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Horizontal grid
        for (db in listOf(10f, 0f, -10f, -20f, -30f, -40f, -50f)) {
            val y = dbToY(db, h)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Hz grid (matching EqGraphView: color 0xFF888888, textSize 24f, centered, 30px below graph)
        val gBottom = h - vPad
        val hzTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt(); textSize = 24f
        }
        for (freq in listOf(100f, 1000f, 10000f)) {
            val x = freqToX(freq, w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
            val label = if (freq >= 1000) "${(freq / 1000).toInt()}k" else "${freq.toInt()}"
            val labelWidth = hzTextPaint.measureText(label)
            canvas.drawText(label, x - labelWidth / 2f, gBottom + 30f, hzTextPaint)
        }

        val xovers = crossoverFreqs ?: return

        // Draw per-band traces within crossover sections
        for (b in 0 until numBands.coerceAtMost(xovers.size + 1)) {
            val leftFreq = if (b == 0) freqMin else xovers[b - 1]
            val rightFreq = if (b >= xovers.size) freqMax else xovers[b]
            val leftX = freqToX(leftFreq, w)
            val rightX = freqToX(rightFreq, w)
            val sectionWidth = rightX - leftX
            if (sectionWidth < 2f) continue

            val color = bandColors[b % bandColors.size]
            val isSelected = b == selectedBand
            val samplesVisible = bufferSize
            val pxPerSample = sectionWidth / samplesVisible

            val graphBottom = h - vPad
            canvas.save()
            canvas.clipRect(leftX, 0f, rightX, h)

            // ── INPUT LEVEL (raw input, fill from bottom) ──
            val levelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.FILL
                alpha = if (isSelected) 20 else 10
            }
            val levelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE
                strokeWidth = 1f; alpha = if (isSelected) 50 else 25
            }
            val levelFillPath = Path()
            val levelStrokePath = Path()
            var started = false
            for (s in 0 until samplesVisible) {
                val bufIdx = ringIdx(s, samplesVisible)
                val level = levelHistory[b][bufIdx]
                val y = dbToY(level, h)
                val x = leftX + s * pxPerSample
                if (!started) {
                    levelFillPath.moveTo(x, h); levelFillPath.lineTo(x, y)
                    levelStrokePath.moveTo(x, y)
                    started = true
                } else {
                    levelFillPath.lineTo(x, y); levelStrokePath.lineTo(x, y)
                }
            }
            if (started) {
                levelFillPath.lineTo(rightX, h); levelFillPath.close()
                canvas.drawPath(levelFillPath, levelFillPaint)
                canvas.drawPath(levelStrokePath, levelStrokePaint)
            }

            // ── GATED LEVEL (input + gate GR, darker outline, fill from bottom) ──
            val gateFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.FILL
                alpha = if (isSelected) 12 else 6
            }
            val gateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE
                strokeWidth = 1f; alpha = if (isSelected) 30 else 15
            }
            val gateFillPath = Path()
            val gateStrokePath = Path()
            var gateStarted = false
            var hasGateActivity = false
            for (s in 0 until samplesVisible) {
                val bufIdx = ringIdx(s, samplesVisible)
                val gateGr = gateGrHistory[b][bufIdx]
                if (gateGr < -0.5f) hasGateActivity = true
                val gatedLevel = (levelHistory[b][bufIdx] + gateGr).coerceAtLeast(-60f)
                val y = dbToY(gatedLevel, h)
                val x = leftX + s * pxPerSample
                if (!gateStarted) {
                    gateFillPath.moveTo(x, h); gateFillPath.lineTo(x, y)
                    gateStrokePath.moveTo(x, y)
                    gateStarted = true
                } else {
                    gateFillPath.lineTo(x, y); gateStrokePath.lineTo(x, y)
                }
            }
            if (gateStarted && hasGateActivity) {
                gateFillPath.lineTo(rightX, h); gateFillPath.close()
                canvas.drawPath(gateFillPath, gateFillPaint)
                canvas.drawPath(gateStrokePath, gateStrokePaint)
            }

            // ── GR TRACE (from top, on unified dB scale) ──
            // GR shows as: the trace sits at 0 dB (top) and dips by |GR| amount
            // But it NEVER goes below the threshold line
            // When input < threshold: GR = 0, trace stays at top
            // When input > threshold: GR is negative, trace dips from top by |GR| dB
            val grTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = if (isSelected) 3f else 2f
                style = Paint.Style.STROKE
                alpha = if (isSelected) 230 else 150
            }
            val grFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.FILL
                alpha = if (isSelected) 35 else 15
            }
            val grLinePath = Path()
            val grFillPath = Path()
            var grStarted = false
            for (s in 0 until samplesVisible) {
                val bufIdx = ringIdx(s, samplesVisible)
                val gr = grHistory[b][bufIdx]  // always <= 0
                // GR trace Y: starts at 0 dB (top), dips by |gr| dB
                // On the unified scale: y = dbToY(gr, h)
                // But gr is 0 to -60, and dbToY(0) = top, dbToY(-60) = bottom
                // So the GR trace naturally maps: 0 GR = top, -20 GR = 1/3 down
                val y = dbToY(gr, h)
                val x = leftX + s * pxPerSample
                if (!grStarted) {
                    grLinePath.moveTo(x, y)
                    grFillPath.moveTo(x, 0f); grFillPath.lineTo(x, y)
                    grStarted = true
                } else {
                    grLinePath.lineTo(x, y); grFillPath.lineTo(x, y)
                }
            }
            if (grStarted) {
                grFillPath.lineTo(rightX, 0f); grFillPath.close()
                canvas.drawPath(grFillPath, grFillPaint)
                canvas.drawPath(grLinePath, grTracePaint)
            }

            canvas.restore()
        }

        // Threshold lines with centered dB label (line breaks around text)
        val threshTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
        }
        for (b in 0 until numBands.coerceAtMost(xovers.size + 1)) {
            val leftFreq = if (b == 0) freqMin else xovers[b - 1]
            val rightFreq = if (b >= xovers.size) freqMax else xovers[b]
            val leftX = freqToX(leftFreq, w)
            val rightX = freqToX(rightFreq, w)
            val threshY = dbToY(thresholds[b], h)
            val centerX = (leftX + rightX) / 2f

            threshLinePaint.color = bandColors[b % bandColors.size]
            threshLinePaint.alpha = if (b == selectedBand || b == draggingThresholdBand) 180 else 80

            // Measure text to create gap in the line
            val text = String.format("%.1f", thresholds[b])
            val textWidth = threshTextPaint.measureText(text)
            val gapPad = 8f
            val gapLeft = centerX - textWidth / 2f - gapPad
            val gapRight = centerX + textWidth / 2f + gapPad

            // Draw line in two segments (left of text, right of text)
            if (gapLeft > leftX) canvas.drawLine(leftX, threshY, gapLeft, threshY, threshLinePaint)
            if (gapRight < rightX) canvas.drawLine(gapRight, threshY, rightX, threshY, threshLinePaint)

            // Draw text centered on the line
            threshTextPaint.color = 0xFFAAAAAA.toInt()
            threshTextPaint.alpha = if (b == selectedBand || b == draggingThresholdBand) 255 else 120
            canvas.drawText(text, centerX, threshY + 7f, threshTextPaint)
        }

        // Crossover dividers + drag glow
        val graphBottom = h - vPad
        for (i in xovers.indices) {
            val x = freqToX(xovers[i], w)
            canvas.drawLine(x, 0f, x, h, crossoverPaint)

            if (i == draggingCrossover) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFBBBBBB.toInt(); alpha = 40; style = Paint.Style.FILL
                }
                val glowPad = 24f
                val cornerR = 10f
                val glowRect = RectF(x - glowPad, vPad + 4f, x + glowPad, graphBottom - 4f)
                canvas.drawRoundRect(glowRect, cornerR, cornerR, glowPaint)
            }
        }

        // dB labels (matching EqGraphView: left side, 0xFF888888, 24f, at x=10, y+8)
        val dbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt(); textSize = 24f
        }
        for (db in listOf(10f, 0f, -10f, -20f, -30f, -40f, -50f)) {
            val y = dbToY(db, h)
            val label = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            canvas.drawText(label, 10f, y + 8f, dbTextPaint)
        }

        // Dragging: highlight the threshold line with a glowing rounded rect around it
        if (draggingThresholdBand >= 0) {
            val b = draggingThresholdBand
            val leftFreq = if (b == 0) freqMin else xovers[b - 1]
            val rightFreq = if (b >= xovers.size) freqMax else xovers[b]
            val leftX = freqToX(leftFreq, w)
            val rightX = freqToX(rightFreq, w)
            val threshY = dbToY(thresholds[b], h)

            // Rounded rect glow around the line (no outline)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bandColors[b % bandColors.size]; alpha = 40; style = Paint.Style.FILL
            }
            val glowPad = 24f
            val cornerR = 10f
            val glowRect = RectF(leftX + 4f, threshY - glowPad, rightX - 4f, threshY + glowPad)
            canvas.drawRoundRect(glowRect, cornerR, cornerR, glowPaint)
        }
    }

    private fun dbToY(db: Float, h: Float): Float {
        // Map dB to FULL view height — traces can extend edge to edge
        return h * (dbMax - db) / dbRange
    }

    private fun freqToX(freq: Float, w: Float): Float {
        return w * (log10(freq.coerceIn(freqMin, freqMax)) - logMin) / logRange
    }

    private fun xToFreq(x: Float, w: Float): Float {
        return 10f.pow(logMin + (x / w) * logRange)
    }

    private fun ringIdx(sampleOffset: Int, samplesVisible: Int): Int {
        return ((writeIdx - samplesVisible + sampleOffset) % bufferSize + bufferSize) % bufferSize
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val xovers = crossoverFreqs ?: return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = event.x
                val touchY = event.y
                val hitRadius = 40f

                // Check crossover lines
                for (i in xovers.indices) {
                    if (Math.abs(touchX - freqToX(xovers[i], w)) < hitRadius) {
                        draggingCrossover = i
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                // Check threshold lines
                for (b in 0 until numBands.coerceAtMost(xovers.size + 1)) {
                    val leftX = freqToX(if (b == 0) freqMin else xovers[b - 1], w)
                    val rightX = freqToX(if (b >= xovers.size) freqMax else xovers[b], w)
                    if (touchX < leftX || touchX > rightX) continue
                    if (Math.abs(touchY - dbToY(thresholds[b], h)) < hitRadius) {
                        draggingThresholdBand = b
                        selectedBand = b
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCrossover >= 0) {
                    val freq = xToFreq(event.x, w)
                    val min = if (draggingCrossover > 0) xovers[draggingCrossover - 1] + 1f else 20f
                    val max = if (draggingCrossover < xovers.size - 1) xovers[draggingCrossover + 1] - 1f else 20000f
                    xovers[draggingCrossover] = freq.coerceIn(min, max)
                    onCrossoverChanged?.invoke(draggingCrossover, xovers[draggingCrossover])
                    invalidate()
                    return true
                }
                if (draggingThresholdBand >= 0) {
                    val newDb = (dbMax - (event.y / h) * dbRange).coerceIn(dbMin, dbMax)
                    thresholds[draggingThresholdBand] = Math.round(newDb * 2f) / 2f
                    onThresholdChanged?.invoke(draggingThresholdBand, thresholds[draggingThresholdBand])
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (draggingCrossover >= 0) { draggingCrossover = -1; invalidate(); return true }
                if (draggingThresholdBand >= 0) { draggingThresholdBand = -1; invalidate(); return true }
            }
        }
        return super.onTouchEvent(event)
    }

    fun updateNumBands(n: Int) { numBands = n.coerceIn(1, maxBands) }
    fun release() {
        for (buf in grHistory) buf.fill(0f)
        for (buf in levelHistory) buf.fill(-60f)
        for (buf in gateGrHistory) buf.fill(0f)
        writeIdx = 0
    }
    fun setBandGR(bandIndex: Int, gr: Float, totalGain: Float) {}

    // Unused compat
    var spectrumDb: FloatArray? = null
    var spectrumBinWidth: Float = 11.72f
}
