package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.log10

/**
 * Limiter scrolling level display — Pro-L 2 style.
 * High temporal resolution: 1 column ≈ 1-2 pixels.
 * Staging queue for smooth constant-rate scrolling.
 * Filled Path contour (not individual bars).
 */
class LimiterWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Buffer sized to pixel width — one column per ~2px
    private val bufferSize = 600
    private var writeHead = 0
    private val inputLevels = FloatArray(bufferSize) { -96f }
    private val grLevels = FloatArray(bufferSize)

    // Staging queue: Visualizer pushes many sub-block peaks, drain timer releases steadily
    private val stagingQueue = ConcurrentLinkedQueue<FloatArray>()

    var ceilingDb: Float = -0.5f

    // Peak tracker state (near-instant attack, smooth release)
    private var trackedLevel = -96f
    private val peakAttack = 0.97f
    private val peakRelease = 0.15f

    // dB scale: 0 at top, -24 at bottom
    private val dbMax = 0f
    private val dbMin = -24f
    private val dbRange = dbMax - dbMin

    // Drain: 1 column per 33ms frame — same scroll speed as MBC
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val redrawRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // Drain 1 column per frame for smooth, slow scrolling
            run {
                val pair = stagingQueue.poll() ?: return@run
                // Apply peak tracking per column
                val rawDb = pair[0]
                trackedLevel = if (rawDb > trackedLevel) {
                    peakAttack * rawDb + (1f - peakAttack) * trackedLevel
                } else {
                    peakRelease * rawDb + (1f - peakRelease) * trackedLevel
                }
                val ceiling = ceilingDb
                val gr = if (trackedLevel > ceiling) -(trackedLevel - ceiling) else 0f

                val pos = writeHead % bufferSize
                inputLevels[pos] = trackedLevel.coerceIn(-96f, 10f)
                grLevels[pos] = gr.coerceIn(-30f, 0f)
                writeHead++
            }
            invalidate()
            handler.postDelayed(this, 33) // 30fps, same as MBC
        }
    }

    // Reusable paths
    private val inputFillPath = Path()
    private val inputStrokePath = Path()
    private val outputFillPath = Path()
    private val outputStrokePath = Path()
    private val grLinePath = Path()
    private val grFillPath = Path()

    private val bgPaint = Paint().apply { color = 0xFF1A1A1A.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt(); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 24f
    }
    private val ceilingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF6666.toInt(); strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }
    private val levelColor = 0xFFBBBBBB.toInt()
    private val inputFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.FILL; alpha = 15
    }
    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 35
    }
    private val outputFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.FILL; alpha = 40
    }
    private val outputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = levelColor; style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 70
    }
    private val grTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE57373.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val grFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE57373.toInt(); style = Paint.Style.FILL; alpha = 25
    }

    /**
     * Push raw waveform from Visualizer callback.
     * Splits into 32 sub-blocks, computes peak dB per block, queues them.
     */
    fun pushWaveformData(waveform: ByteArray) {
        val blocksPerCapture = 32
        val blockSize = waveform.size / blocksPerCapture

        for (block in 0 until blocksPerCapture) {
            var maxSample = 0f
            val start = block * blockSize
            val end = start + blockSize
            for (j in start until end) {
                val sample = ((waveform[j].toInt() and 0xFF) - 128) / 128f
                val absSample = abs(sample)
                if (absSample > maxSample) maxSample = absSample
            }
            val peakDb = if (maxSample > 0.0001f) (20f * log10(maxSample)).coerceAtLeast(-96f) else -96f
            stagingQueue.offer(floatArrayOf(peakDb))
        }
    }

    /** Also accept direct dB value from getMeasurementPeakRms */
    fun pushFrame(inputDb: Float, grDb: Float) {
        stagingQueue.offer(floatArrayOf(inputDb))
    }

    private fun dbToY(db: Float, h: Float): Float {
        val clamped = db.coerceIn(dbMin, dbMax)
        return h * (dbMax - clamped) / dbRange
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        for (db in listOf(0f, -3f, -6f, -9f, -12f, -18f)) {
            val y = dbToY(db, h)
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = if (db == 0f) "0" else "${db.toInt()}"
            canvas.drawText(label, 10f, y + 8f, labelPaint)
        }

        val head = writeHead
        val pxPerCol = w / bufferSize

        // ── INPUT LEVEL (light grey filled contour from bottom) ──
        inputFillPath.reset(); inputStrokePath.reset()
        inputFillPath.moveTo(0f, h)
        for (s in 0 until bufferSize) {
            val idx = ((head - bufferSize + s) % bufferSize + bufferSize) % bufferSize
            val x = s * pxPerCol
            val y = dbToY(inputLevels[idx], h)
            inputFillPath.lineTo(x, y)
            if (s == 0) inputStrokePath.moveTo(x, y) else inputStrokePath.lineTo(x, y)
        }
        inputFillPath.lineTo(w, h); inputFillPath.close()
        canvas.drawPath(inputFillPath, inputFillPaint)
        canvas.drawPath(inputStrokePath, inputStrokePaint)

        // ── OUTPUT LEVEL (brighter, capped at ceiling) ──
        outputFillPath.reset(); outputStrokePath.reset()
        outputFillPath.moveTo(0f, h)
        for (s in 0 until bufferSize) {
            val idx = ((head - bufferSize + s) % bufferSize + bufferSize) % bufferSize
            val outDb = (inputLevels[idx] + grLevels[idx]).coerceAtMost(ceilingDb)
            val x = s * pxPerCol
            val y = dbToY(outDb, h)
            outputFillPath.lineTo(x, y)
            if (s == 0) outputStrokePath.moveTo(x, y) else outputStrokePath.lineTo(x, y)
        }
        outputFillPath.lineTo(w, h); outputFillPath.close()
        canvas.drawPath(outputFillPath, outputFillPaint)
        canvas.drawPath(outputStrokePath, outputStrokePaint)

        // ── GR TRACE (red from top) ──
        grLinePath.reset(); grFillPath.reset()
        grFillPath.moveTo(0f, 0f)
        for (s in 0 until bufferSize) {
            val idx = ((head - bufferSize + s) % bufferSize + bufferSize) % bufferSize
            val x = s * pxPerCol
            val y = dbToY(grLevels[idx], h)
            if (s == 0) { grLinePath.moveTo(x, y); grFillPath.lineTo(x, y) }
            else { grLinePath.lineTo(x, y); grFillPath.lineTo(x, y) }
        }
        grFillPath.lineTo(w, 0f); grFillPath.close()
        canvas.drawPath(grFillPath, grFillPaint)
        canvas.drawPath(grLinePath, grTracePaint)

        // Ceiling line
        canvas.drawLine(0f, dbToY(ceilingDb, h), w, dbToY(ceilingDb, h), ceilingLinePaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isRunning = true
        handler.post(redrawRunnable)
    }

    override fun onDetachedFromWindow() {
        isRunning = false
        handler.removeCallbacks(redrawRunnable)
        stagingQueue.clear()
        super.onDetachedFromWindow()
    }
}
