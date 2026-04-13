package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

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
    private var preampSlider: Slider? = null
    private var preampText: EditText? = null

    // Undo/redo history
    private val history = mutableListOf<FloatArray>()
    private var historyIndex = -1
    private var undoBtn: MaterialButton? = null
    private var redoBtn: MaterialButton? = null

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
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }

        val btnStyle = com.google.android.material.R.attr.materialButtonOutlinedStyle

        val undo = MaterialButton(activity, null, btnStyle).apply {
            text = "Undo"
            icon = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_undo)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (4 * density).toInt()
            textSize = 11f
            cornerRadius = (12 * density).toInt()
            insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            alpha = 0.3f
            setOnClickListener {
                if (historyIndex > 0) {
                    historyIndex--
                    restoreSnapshot(history[historyIndex])
                    updateUndoRedoState()
                }
            }
        }
        undoBtn = undo

        val redo = MaterialButton(activity, null, btnStyle).apply {
            text = "Redo"
            icon = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_redo)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (4 * density).toInt()
            textSize = 11f
            cornerRadius = (12 * density).toInt()
            insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            alpha = 0.3f
            setOnClickListener {
                if (historyIndex < history.size - 1) {
                    historyIndex++
                    restoreSnapshot(history[historyIndex])
                    updateUndoRedoState()
                }
            }
        }
        redoBtn = redo

        val reset = MaterialButton(activity, null, btnStyle).apply {
            text = "Reset"
            icon = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_reset)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (4 * density).toInt()
            iconSize = (16 * density).toInt()
            textSize = 11f
            cornerRadius = (12 * density).toInt()
            insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val zeros = FloatArray(FREQUENCIES.size) { 0f }
                restoreSnapshot(zeros)
                saveSnapshot()
            }
        }

        controlsRow.addView(undo)
        controlsRow.addView(redo)
        controlsRow.addView(reset)
        controlsCard.addView(controlsRow)
        container.addView(controlsCard)

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
                (280 * density).toInt()  // total height for bars + labels
            )
            // Load gains from EQ
            val gains = FloatArray(SimpleEqBarsView.BAND_COUNT) { i ->
                eq.getBand(i)?.gain?.coerceIn(-12f, 12f) ?: 0f
            }
            setAllGains(gains)

            onGainChanged = { bandIndex, gain ->
                val roundedGain = Math.round(gain * 10f) / 10f // snap to 0.1 dB
                eq.updateBand(bandIndex, FREQUENCIES[bandIndex], roundedGain, BiquadFilter.FilterType.BELL, Q)
                onEqChanged()
            }
            onDragEnd = { saveSnapshot() }
        }
        barsView = bars
        barsCard.addView(bars)
        container.addView(barsCard)

        // Preamp row
        buildPreampRow()
    }

    private fun buildPreampRow() {
        val density = activity.resources.displayMetrics.density
        val preampCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2A2A2A.toInt()))
            strokeWidth = 0
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        val label = TextView(activity).apply {
            text = "Preamp (dB)"
            textSize = 12f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
        row.addView(label)

        val slider = Slider(activity).apply {
            valueFrom = -12f
            valueTo = 12f
            value = state.preampGainDb.coerceIn(-12f, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setLabelFormatter { "" }
        }
        preampSlider = slider

        val textInput = com.google.android.material.textfield.TextInputEditText(activity).apply {
            setText(String.format("%.1f", state.preampGainDb))
            textSize = 12f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams((64 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
            }
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            minimumHeight = 0
        }
        preampText = textInput

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            state.preampGainDb = value
            textInput.setText(String.format("%.1f", value))
            eqPrefs.savePreampGain(value)
            state.pushEqUpdate()
        }

        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val v = textInput.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
                textInput.setText(String.format("%.1f", v))
                slider.value = v
                state.preampGainDb = v
                eqPrefs.savePreampGain(v)
                state.pushEqUpdate()
                textInput.clearFocus()
            }
            true
        }

        row.addView(slider)
        row.addView(textInput)
        preampCard.addView(row)
        container.addView(preampCard)
    }

    fun syncFromEq() {
        val eq = state.parametricEq
        val gains = FloatArray(SimpleEqBarsView.BAND_COUNT) { i ->
            eq.getBand(i)?.gain?.coerceIn(-12f, 12f) ?: 0f
        }
        barsView?.setAllGains(gains)
        preampSlider?.value = state.preampGainDb.coerceIn(-12f, 12f)
        preampText?.setText(String.format("%.1f", state.preampGainDb))
    }

    fun saveGains() {
        val eq = state.parametricEq
        val gains = FloatArray(FREQUENCIES.size) { i ->
            eq.getBand(i)?.gain ?: 0f
        }
        eqPrefs.saveSimpleEqGains(gains)
    }
}
