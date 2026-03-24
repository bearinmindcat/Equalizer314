package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.*

/**
 * Professional spectrum analyzer renderer — waveform-based approach.
 *
 * Pipeline per frame:
 * 1. WaveformFftProcessor: waveform bytes → Hann window → zero-pad → FFT → dBFS
 * 2. Convert dB → linear magnitude for proper noise-reducing temporal smoothing
 * 3. Asymmetric EMA in linear domain (fast attack, slow release)
 * 4. Convert back to dB after smoothing
 * 5. Log-frequency mapping: interpolate for sub-bin, peak for multi-bin
 * 6. +3 dB/oct tilt compensation (pivot at 1 kHz)
 * 7. Render with cubic Bézier paths + gradient fill
 */
class SpectrumAnalyzerRenderer(
    private val sampleRate: Int = 48000
) {
    private val fftProcessor = WaveformFftProcessor(fftSize = 4096, sampleRate = sampleRate)

    // Temporally smoothed LINEAR magnitudes per bin (not dB)
    // Smoothing in linear domain properly reduces noise floor by √N frames
    @Volatile
    private var smoothedLinear: FloatArray? = null

    // Asymmetric ballistics — applied to linear magnitudes
    private val attackAlpha = 0.6f    // fast rise (~2-3 frames to reach 90%)
    private val releaseAlpha = 0.22f  // ~6 frames to decay

    // Reusable drawing objects — input spectrum
    private val inputFillPath = Path()

    // Reusable drawing objects — output spectrum
    private val outputFillPath = Path()
    private val outputStrokePath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(100, 220, 220, 220)
    }

    /**
     * Called from Visualizer callback with RAW WAVEFORM bytes.
     */
    fun updateWaveformData(waveform: ByteArray) {
        val dbValues = fftProcessor.process(waveform)
        val n = dbValues.size

        // Convert dB → linear magnitude for smoothing
        // Smoothing in linear domain averages noise properly (reduces by √N)
        // instead of dB domain which biases toward peaks
        val currentLinear = FloatArray(n) { i ->
            10f.pow(dbValues[i] / 20f)
        }

        // Asymmetric EMA in linear domain
        var prev = smoothedLinear
        if (prev == null || prev.size != n) {
            prev = FloatArray(n) // zeros = silence
        }
        val result = FloatArray(n)
        for (i in 0 until n) {
            val alpha = if (currentLinear[i] > prev[i]) attackAlpha else releaseAlpha
            result[i] = alpha * currentLinear[i] + (1f - alpha) * prev[i]
        }
        smoothedLinear = result
    }

    /**
     * @param eqResponseDb Optional EQ frequency response in dB per display pixel.
     *                     When provided, draws dual spectrum: dim input + bright output.
     *                     When null, draws single spectrum (input only).
     */
    fun draw(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        dbMin: Float, dbMax: Float,
        eqResponseDb: FloatArray? = null
    ) {
        val linear = smoothedLinear ?: return
        val n = linear.size
        if (n < 2) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidthHz = fftProcessor.binWidthHz

        // Convert smoothed linear back to dB for display
        val db = FloatArray(n) { i ->
            if (linear[i] > 1e-10f) (20f * log10(linear[i])).coerceAtLeast(-96f)
            else -96f
        }

        // Log frequency mapping: 20 Hz – 20 kHz
        val logMin = log10(20f)
        val logMax = log10(20000f)
        val logRange = logMax - logMin

        // Collect display points — one per pixel column
        val displayWidth = graphWidth.toInt().coerceAtLeast(1)
        val xArr = FloatArray(displayWidth)
        val inputY = FloatArray(displayWidth)
        val outputY = if (eqResponseDb != null) FloatArray(displayWidth) else null

        for (x in 0 until displayWidth) {
            val logFreqLow = logMin + logRange * x / displayWidth
            val logFreqHigh = logMin + logRange * (x + 1) / displayWidth
            val freqLow = 10f.pow(logFreqLow)
            val freqHigh = 10f.pow(logFreqHigh)
            val freq = 10f.pow((logFreqLow + logFreqHigh) / 2f)

            val binLow = (freqLow / binWidthHz).toInt().coerceIn(1, n - 1)
            val binHigh = (freqHigh / binWidthHz).toInt().coerceIn(1, n - 1)

            val rawDb: Float
            if (binLow == binHigh) {
                val exactBin = freq / binWidthHz
                val lower = exactBin.toInt().coerceIn(1, n - 2)
                val frac = exactBin - lower
                rawDb = db[lower] * (1f - frac) + db[lower + 1] * frac
            } else {
                var peak = -96f
                for (b in binLow..binHigh.coerceAtMost(n - 1)) {
                    if (db[b] > peak) peak = db[b]
                }
                rawDb = peak
            }

            // +3 dB/octave tilt compensation (pivot at 1 kHz)
            val tiltDb = 3.0f * (log10(freq / 1000f) / 0.30103f)
            val inputDb = rawDb + tiltDb

            val inputNorm = ((inputDb - dbMin) / dbRange).coerceIn(0f, 1f)
            xArr[x] = left + x
            inputY[x] = top + graphHeight * (1f - inputNorm)

            // Output = input + EQ response
            if (outputY != null && eqResponseDb != null && x < eqResponseDb.size) {
                val outputDb = inputDb + eqResponseDb[x]
                val outputNorm = ((outputDb - dbMin) / dbRange).coerceIn(0f, 1f)
                outputY[x] = top + graphHeight * (1f - outputNorm)
            }
        }

        // Bass smoothing disabled — was killing low-end extension
        // val bass150Pixel = ((log10(150f) - logMin) / logRange * displayWidth).toInt()
        //     .coerceIn(0, displayWidth - 1)
        // val bass300Pixel = ((log10(300f) - logMin) / logRange * displayWidth).toInt()
        //     .coerceIn(0, displayWidth - 1)
        // for (pass in 0 until 3) {
        //     val tmp = inputY.copyOf()
        //     val tmpOut = outputY?.copyOf()
        //     for (x in 2 until bass300Pixel - 2) {
        //         val smoothed = (tmp[x - 2] + tmp[x - 1] + tmp[x] + tmp[x + 1] + tmp[x + 2]) / 5f
        //         val blend = if (x < bass150Pixel) 1f
        //                     else 1f - (x - bass150Pixel).toFloat() / (bass300Pixel - bass150Pixel)
        //         inputY[x] = blend * smoothed + (1f - blend) * tmp[x]
        //         if (tmpOut != null && outputY != null) {
        //             val sOut = (tmpOut[x - 2] + tmpOut[x - 1] + tmpOut[x] + tmpOut[x + 1] + tmpOut[x + 2]) / 5f
        //             outputY[x] = blend * sOut + (1f - blend) * tmpOut[x]
        //         }
        //     }
        // }

        val step = 2
        val lastIdx = displayWidth - 1

        // Clip to graph bounds
        canvas.save()
        canvas.clipRect(left, top, right, bottom)

        // ── Draw INPUT spectrum (dim, background layer) ──
        inputFillPath.reset()
        inputFillPath.moveTo(xArr[0], bottom)
        inputFillPath.lineTo(xArr[0], inputY[0])
        var i = step
        while (i < displayWidth) {
            val midX = (xArr[i - step] + xArr[i]) / 2f
            inputFillPath.cubicTo(midX, inputY[i - step], midX, inputY[i], xArr[i], inputY[i])
            i += step
        }
        if (i - step < lastIdx) inputFillPath.lineTo(xArr[lastIdx], inputY[lastIdx])
        inputFillPath.lineTo(xArr[lastIdx], bottom)
        inputFillPath.close()

        if (outputY != null) {
            // When showing dual spectrum, input is dim
            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(35, 150, 150, 150), Color.argb(8, 100, 100, 100)),
                null, Shader.TileMode.CLAMP
            )
        } else {
            // Single spectrum mode — normal brightness
            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(80, 180, 180, 180), Color.argb(15, 100, 100, 100)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(inputFillPath, fillPaint)

        // ── Draw OUTPUT spectrum (bright, foreground layer) ──
        if (outputY != null) {
            outputFillPath.reset()
            outputStrokePath.reset()

            outputFillPath.moveTo(xArr[0], bottom)
            outputFillPath.lineTo(xArr[0], outputY[0])
            outputStrokePath.moveTo(xArr[0], outputY[0])

            i = step
            while (i < displayWidth) {
                val midX = (xArr[i - step] + xArr[i]) / 2f
                outputFillPath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                outputStrokePath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                i += step
            }
            if (i - step < lastIdx) {
                outputFillPath.lineTo(xArr[lastIdx], outputY[lastIdx])
                outputStrokePath.lineTo(xArr[lastIdx], outputY[lastIdx])
            }
            outputFillPath.lineTo(xArr[lastIdx], bottom)
            outputFillPath.close()

            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(65, 200, 200, 200), Color.argb(12, 120, 120, 120)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(outputFillPath, fillPaint)
            canvas.drawPath(outputStrokePath, outputStrokePaint)
        }

        canvas.restore()
    }

    fun release() {
        smoothedLinear = null
    }
}
