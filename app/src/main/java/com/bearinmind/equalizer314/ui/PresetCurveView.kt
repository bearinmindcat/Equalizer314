package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * EQ-curve preview for a saved preset. Renders the same way as the
 * thumbnail in MainActivity's preset picker (`MainActivity.kt:971`):
 * grey curve `#AAAAAA` over a faint `#6A6A6A` grid (one horizontal
 * mid-line + one vertical left-edge line), log frequency axis from
 * 20 Hz to ~22 kHz, ±15 dB vertical range, 0.5 dp stroke.
 *
 * For Channel-Side-EQ presets the view stacks an L-graph on top and
 * an R-graph below (with "L"/"R" labels and a divider) so two-channel
 * EQs read as two labeled graphs rather than one mashed curve —
 * identical layout to the preset picker.
 */
class PresetCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var presetJson: JSONObject? = null

    private val density = resources.displayMetrics.density
    private val curveColor = 0xFFAAAAAA.toInt()

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = curveColor
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * density
    }
    private val gridPaint = Paint().apply {
        color = 0xFF6A6A6A.toInt()
        strokeWidth = 1f
    }
    private val dividerPaint = Paint().apply {
        color = 0xFF444444.toInt()
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    /** Set the preset to render, or null to clear (renders an empty
     *  grid only — used for `"(none)"` sentinel rows). */
    fun setPreset(json: JSONObject?) {
        presetJson = json
        invalidate()
    }

    /** Convenience setter for callers that only have the bands array
     *  (no CSE bookkeeping). Wraps the array in a synthetic JSONObject. */
    fun setBands(bands: JSONArray?) {
        presetJson = bands?.let { JSONObject().put("bands", it) }
        invalidate()
    }

    private fun buildEq(arr: JSONArray): ParametricEqualizer {
        val eq = ParametricEqualizer()
        eq.clearBands()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val ft = runCatching {
                BiquadFilter.FilterType.valueOf(b.getString("filterType"))
            }.getOrDefault(BiquadFilter.FilterType.BELL)
            eq.addBand(
                b.getDouble("frequency").toFloat(),
                b.getDouble("gain").toFloat(),
                ft,
                b.getDouble("q"),
            )
        }
        return eq
    }

    private fun drawCurve(
        canvas: Canvas,
        eq: ParametricEqualizer,
        x0: Float,
        y0: Float,
        w: Float,
        h: Float,
    ) {
        canvas.drawLine(x0, y0 + h / 2f, x0 + w, y0 + h / 2f, gridPaint)
        canvas.drawLine(x0, y0, x0, y0 + h, gridPaint)
        path.reset()
        val maxDb = 15f
        val steps = 50
        for (s in 0..steps) {
            val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
            val freq = 10f.pow(logF)
            val db = eq.getFrequencyResponse(freq)
            val x = x0 + w * s / steps
            val y = (y0 + h / 2f - (db / maxDb) * (h / 2f)).coerceIn(y0, y0 + h)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val obj = presetJson
        if (obj == null) {
            // Empty grid for "(none)" / missing rows.
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            canvas.drawLine(0f, 0f, 0f, h, gridPaint)
            return
        }

        try {
            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                val labelCol = 9f * density
                val gap = 2f * density
                val halfH = (h - gap) / 2f
                val fm = labelPaint.fontMetrics
                val textCenterOffset = (-fm.ascent - fm.descent) / 2f
                // L row
                canvas.drawText("L", labelCol / 2f, halfH / 2f + textCenterOffset, labelPaint)
                drawCurve(
                    canvas,
                    buildEq(obj.getJSONArray("leftBands")),
                    labelCol, 0f, w - labelCol, halfH,
                )
                // Divider
                canvas.drawLine(0f, halfH + gap / 2f, w, halfH + gap / 2f, dividerPaint)
                // R row
                val rTop = halfH + gap
                canvas.drawText("R", labelCol / 2f, rTop + halfH / 2f + textCenterOffset, labelPaint)
                drawCurve(
                    canvas,
                    buildEq(obj.getJSONArray("rightBands")),
                    labelCol, rTop, w - labelCol, halfH,
                )
            } else {
                drawCurve(canvas, buildEq(obj.getJSONArray("bands")), 0f, 0f, w, h)
            }
        } catch (_: Exception) {
            // Malformed JSON or missing fields — leave the grid blank.
        }
    }
}
