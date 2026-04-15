package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton

class SimpleEqController(
    private val activity: Activity,
    private val container: LinearLayout,
    private val state: EqStateManager,
    private val eqPrefs: EqPreferencesManager,
    private val onEqChanged: () -> Unit
) {
    companion object {
        val FREQUENCIES = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val Q = 1.414 // ~2 octave bandwidth, standard for 10-band graphic EQ
    }

    private var barsView: SimpleEqBarsView? = null
    private var miniGraph: EqGraphView? = null

    // Undo/redo history
    private val history = mutableListOf<FloatArray>()
    private var historyIndex = -1
    private var undoBtn: android.view.View? = null
    private var redoBtn: android.view.View? = null

    private fun saveSnapshot() {
        val eq = state.parametricEq
        val snap = FloatArray(FREQUENCIES.size) { i -> eq.getBand(i)?.gain ?: 0f }
        // Trim future states
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history.add(snap)
        historyIndex = history.size - 1
        updateUndoRedoState()
    }

    private fun restoreSnapshot(snap: FloatArray) {
        val eq = state.parametricEq
        for (i in snap.indices) {
            if (i < FREQUENCIES.size) {
                eq.updateBand(i, FREQUENCIES[i], snap[i], BiquadFilter.FilterType.BELL, Q)
            }
        }
        barsView?.setAllGains(snap)
        miniGraph?.invalidate()
        onEqChanged()
    }

    private fun updateUndoRedoState() {
        undoBtn?.alpha = if (historyIndex > 0) 1f else 0.3f
        redoBtn?.alpha = if (historyIndex < history.size - 1) 1f else 0.3f
    }

    fun configureParametricEq() {
        val eq = state.parametricEq
        val savedGains = eqPrefs.getSimpleEqGains() ?: FloatArray(10) { 0f }

        eq.clearBands()
        for (i in FREQUENCIES.indices) {
            val gain = if (i < savedGains.size) savedGains[i] else 0f
            eq.addBand(FREQUENCIES[i], gain, BiquadFilter.FilterType.BELL, Q)
        }
        eq.isEnabled = true

        state.bandSlots.clear()
        for (i in 0 until 10) state.bandSlots.add(i)
        state.displayToBandIndex = (0 until 10).toList()
        state.pushEqUpdate()
    }

    fun buildSliders() {
        container.removeAllViews()

        val density = activity.resources.displayMetrics.density
        val eq = state.parametricEq

        // Header — matches MBC / Limiter top bar exactly:
        // paddingStart=16dp, paddingEnd=16dp, paddingTop=8dp, paddingBottom=8dp
        // (same values as activity_mbc.xml lines 17-20)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val headerText = TextView(activity).apply {
            text = "Simple EQ Mode"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerText)
        container.addView(headerRow)

        // Mini EQ graph preview — scaled-down version of the main EQ graph card
        // (same styling, half the height: 246dp → 123dp). Shows a live frequency
        // response curve as the user adjusts the bars.
        val graphCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurfaceContainer, 0xFF1E1E1E.toInt()))
            setStrokeColor(android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    activity, com.google.android.material.R.attr.colorOutlineVariant, 0xFF444444.toInt())))
            strokeWidth = (1 * density).toInt()
        }
        val graph = EqGraphView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (123 * density).toInt()  // half of 246dp
            )
            setParametricEqualizer(state.parametricEq)
            showBandPoints = true       // dots at each band frequency on the curve
            readOnlyPoints = true       // small dots, no labels, no dragging
            showSaturationCurve = false
            minGain = -12f              // match simple EQ range: ±12dB
            maxGain = 12f               // grid lines at +6, +12, -6, -12
            showDpBands = false
            showEqCurve = true
            showCurveFill = true   // fill between curve and 0dB line
            verticalPadding = 40f  // scaled down from 80f for half-height graph
        }
        miniGraph = graph
        graphCard.addView(graph)
        container.addView(graphCard)

        // Undo / Redo / Reset card (topMargin matches the gap between header and
        // graph card on MBC / Limiter screens for visual consistency)
        val controlsCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2A2A2A.toInt()))
            strokeWidth = 0
        }

        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        // Nothing-style layout: RESET (red, left) | spacer | Undo (icon) | Redo (icon)

        val btnStyle = com.google.android.material.R.attr.materialButtonOutlinedStyle

        // Reset button — red text, left side, taller like Nothing's RESET
        val reset = MaterialButton(activity, null, btnStyle).apply {
            text = "RESET"
            textSize = 12f
            setTextColor(0xFFEF9A9A.toInt())
            cornerRadius = (12 * density).toInt()
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            val btnH = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, btnH).apply {
                marginEnd = (8 * density).toInt()
            }
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            setOnClickListener {
                val zeros = FloatArray(FREQUENCIES.size) { 0f }
                restoreSnapshot(zeros)
                saveSnapshot()
            }
        }

        // Spacer to push undo/redo to the right
        val spacer = android.view.View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        // Undo — icon only, rounded rectangle outline (matches band toggle style)
        val iconBtnSize = (48 * density).toInt()
        val undo = android.widget.ImageButton(activity).apply {
            setImageResource(R.drawable.ic_undo)
            setColorFilter(0xFFBBBBBB.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(iconBtnSize, iconBtnSize).apply {
                marginEnd = (8 * density).toInt()
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val iconPad = (12 * density).toInt()
            setPadding(iconPad, iconPad, iconPad, iconPad)
            alpha = 0.3f
            setOnClickListener {
                if (historyIndex > 0) {
                    historyIndex--
                    restoreSnapshot(history[historyIndex])
                    updateUndoRedoState()
                }
            }
        }

        // Redo — icon only, rounded rectangle outline
        val redo = android.widget.ImageButton(activity).apply {
            setImageResource(R.drawable.ic_redo)
            setColorFilter(0xFFBBBBBB.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(iconBtnSize, iconBtnSize)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val iconPad = (12 * density).toInt()
            setPadding(iconPad, iconPad, iconPad, iconPad)
            alpha = 0.3f
            setOnClickListener {
                if (historyIndex < history.size - 1) {
                    historyIndex++
                    restoreSnapshot(history[historyIndex])
                    updateUndoRedoState()
                }
            }
        }

        // Store refs for alpha updates in updateUndoRedoState
        undoBtn = undo
        redoBtn = redo

        controlsRow.addView(reset)
        controlsRow.addView(spacer)
        controlsRow.addView(undo)
        controlsRow.addView(redo)
        controlsCard.addView(controlsRow)

        // Save initial snapshot for undo history
        saveSnapshot()

        // Card wrapping the bars
        val barsCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(0xFF1E1E1E.toInt())
            strokeWidth = 0
        }

        // Custom bars view
        val bars = SimpleEqBarsView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()  // total height for bars + labels
            )
            // Load gains from EQ
            val gains = FloatArray(SimpleEqBarsView.BAND_COUNT) { i ->
                eq.getBand(i)?.gain?.coerceIn(-12f, 12f) ?: 0f
            }
            setAllGains(gains)

            onGainChanged = { bandIndex, gain ->
                val roundedGain = Math.round(gain * 10f) / 10f // snap to 0.1 dB
                eq.updateBand(bandIndex, FREQUENCIES[bandIndex], roundedGain, BiquadFilter.FilterType.BELL, Q)
                miniGraph?.invalidate() // live preview of curve changes
                onEqChanged()
            }
            onDragEnd = { saveSnapshot() }
        }
        barsView = bars
        barsCard.addView(bars)
        container.addView(barsCard)

        // Preamp card is reparented from eqControlsContainer by switchEqUiMode —
        // it gets inserted here (index 2, after bars card) so it matches the
        // existing preamp card used in parametric/graphic/table modes.

        // Undo / Redo / Reset card goes AFTER the preamp
        container.addView(controlsCard)
    }

    fun syncFromEq() {
        val eq = state.parametricEq
        val gains = FloatArray(SimpleEqBarsView.BAND_COUNT) { i ->
            eq.getBand(i)?.gain?.coerceIn(-12f, 12f) ?: 0f
        }
        barsView?.setAllGains(gains)
    }

    fun saveGains() {
        val eq = state.parametricEq
        val gains = FloatArray(FREQUENCIES.size) { i ->
            eq.getBand(i)?.gain ?: 0f
        }
        eqPrefs.saveSimpleEqGains(gains)
    }
}
