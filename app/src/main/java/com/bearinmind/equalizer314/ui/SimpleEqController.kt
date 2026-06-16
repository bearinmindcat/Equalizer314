package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton
import kotlin.math.pow

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
    private var barsCard: View? = null
    private var presetPickerScroll: android.widget.ScrollView? = null
    private var presetPickerContainer: LinearLayout? = null
    private var presetPickerOpen = false
    private var saveBtn: android.widget.ImageButton? = null

    // Undo/redo history
    private val history = mutableListOf<FloatArray>()
    private var historyIndex = -1
    private var undoBtn: View? = null
    private var redoBtn: View? = null

    // Debounced persist of Simple-EQ gains. Each ACTION_MOVE on a bar
    // schedules a flush 250 ms in the future, cancelling any prior
    // pending flush. Drag-end and lifecycle saves call saveGains()
    // directly. Without this, edits only landed on disk when the
    // activity reached onPause — abrupt process death (Bluetooth A2DP
    // teardown, force-stop, system memory pressure) lost user edits.
    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistRunnable = Runnable { saveGains() }
    private fun scheduleGainPersist() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, 250L)
    }

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

    private fun getCurrentGains(): FloatArray {
        val eq = state.parametricEq
        return FloatArray(FREQUENCIES.size) { i -> eq.getBand(i)?.gain ?: 0f }
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
        presetPickerOpen = false

        val density = activity.resources.displayMetrics.density
        val eq = state.parametricEq

        // No "Simple EQ Mode" header — the mode selector card (now
        // visible in Simple mode and highlighting the Simple tab)
        // already signals which mode is active, so the title would be
        // redundant.

        // Mini EQ graph preview — scaled-down version of the main EQ graph card
        // (same styling, half the height: 246dp → 123dp). Shows a live frequency
        // response curve as the user adjusts the bars.
        val graphCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Consistent 12dp inter-card gap (matches bars / preamp /
                // controls below) so Simple mode spacing is homogeneous.
                bottomMargin = (12 * density).toInt()
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
            showEqCurve = true
            showCurveFill = true   // fill between curve and 0dB line
            verticalPadding = 40f  // scaled down from 80f for half-height graph
        }
        miniGraph = graph
        graphCard.addView(graph)
        container.addView(graphCard)

        // Card wrapping the bars
        val bCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurfaceVariant, 0xFF1E1E1E.toInt()))
            strokeWidth = 0
        }

        // Custom bars view
        val bars = SimpleEqBarsView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (256 * density).toInt()  // total height for bars + labels
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
                scheduleGainPersist()   // debounced SharedPrefs flush
            }
            onDragEnd = {
                saveSnapshot()
                // Force an immediate flush at drag-end so the user's
                // last value survives an abrupt process kill before
                // the 250 ms debounce window expires.
                persistHandler.removeCallbacks(persistRunnable)
                saveGains()
            }
        }
        barsView = bars
        bCard.addView(bars)
        barsCard = bCard
        container.addView(bCard)

        // Preset picker (initially GONE, toggled by save button)
        // Styled to match the advanced EQ preset picker: plain ScrollView,
        // colorSurface background, no card wrapper.
        val pickerScroll = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurface, 0xFF121212.toInt()))
        }
        val pickerInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        pickerScroll.addView(pickerInner)
        presetPickerScroll = pickerScroll
        presetPickerContainer = pickerInner
        container.addView(pickerScroll)

        // Preamp card is reparented from eqControlsContainer by switchEqUiMode —
        // it gets inserted here (index 4, after barsCard=2, presetPicker=3) so it
        // matches the existing preamp card used in parametric/graphic/table modes.

        // Undo / Redo / Reset / Save card
        val controlsCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // No top margin — the preamp card above carries the 12dp
                // gap, so we don't double it up here.
                topMargin = 0
                bottomMargin = (12 * density).toInt()
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

        // Nothing-style layout: RESET (red, left) | spacer | Save (icon) | Undo (icon) | Redo (icon)

        // Reset button — red text, left side
        val reset = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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

        // Spacer to push buttons to the right
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val iconBtnSize = (48 * density).toInt()

        // Save / Presets button — icon only, rounded rectangle outline
        val save = android.widget.ImageButton(activity).apply {
            setImageResource(R.drawable.ic_save)
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
            setOnClickListener { togglePresetPicker() }
        }
        saveBtn = save

        // Undo — icon only, rounded rectangle outline
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
        controlsRow.addView(save)
        controlsRow.addView(undo)
        controlsRow.addView(redo)
        controlsCard.addView(controlsRow)

        // Save initial snapshot for undo history
        saveSnapshot()

        // Controls card goes above the mini graph (index 1, after header)
        container.addView(controlsCard, 1)
    }

    private fun togglePresetPicker() {
        val density = activity.resources.displayMetrics.density
        val decel = android.view.animation.DecelerateInterpolator()
        val preampCard = activity.findViewById<View>(R.id.preampCardBar)
        presetPickerOpen = !presetPickerOpen
        if (presetPickerOpen) {
            populatePresetPicker()
            // Hide bars + preamp instantly, fade picker in
            barsCard?.visibility = View.GONE
            preampCard?.visibility = View.GONE
            presetPickerScroll?.visibility = View.VISIBLE
            presetPickerScroll?.alpha = 0f
            presetPickerScroll?.animate()?.alpha(1f)?.setDuration(200)?.setInterpolator(decel)?.start()
            saveBtn?.apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(0xFF555555.toInt())
                    setStroke((1 * density).toInt(), 0xFF888888.toInt())
                }
                setColorFilter(0xFFDDDDDD.toInt())
            }
        } else {
            closePresetPicker()
        }
    }

    private fun closePresetPicker() {
        val density = activity.resources.displayMetrics.density
        val decel = android.view.animation.DecelerateInterpolator()
        val preampCard = activity.findViewById<View>(R.id.preampCardBar)
        presetPickerOpen = false
        // Hide picker instantly, fade bars + preamp in
        presetPickerScroll?.visibility = View.GONE
        barsCard?.visibility = View.VISIBLE
        barsCard?.alpha = 0f
        barsCard?.animate()?.alpha(1f)?.setDuration(200)?.setInterpolator(decel)?.start()
        preampCard?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(200).setInterpolator(decel).start()
        }
        saveBtn?.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            setColorFilter(0xFFBBBBBB.toInt())
        }
    }

    private fun populatePresetPicker() {
        val pickerContainer = presetPickerContainer ?: return
        pickerContainer.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val presetNames = eqPrefs.getSimpleEqPresetNames()

        // "+" button at top — save current EQ as new preset
        val saveCurrentBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (4 * density).toInt())
            }
            cornerRadius = (12 * density).toInt()
            textSize = 11f
            val vertPad = (6 * density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            gravity = Gravity.CENTER
            setBackgroundColor(0x00000000)
            setTextColor(0xFF888888.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setOnClickListener { showSaveDialog() }
        }
        pickerContainer.addView(saveCurrentBtn)

        // List saved presets — styled identically to the advanced
        // (Parametric/Graphic/Table) picker rows.
        for (name in presetNames) {
            pickerContainer.addView(createPresetRow(name))
        }
    }

    private fun showSaveDialog() {
        val density = activity.resources.displayMetrics.density
        val presetNames = eqPrefs.getSimpleEqPresetNames()

        // Find next Custom # number
        var nextNum = 1
        for (n in presetNames) {
            val match = Regex("Custom #(\\d+)").find(n)
            if (match != null) nextNum = maxOf(nextNum, match.groupValues[1].toInt() + 1)
        }

        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        val title = TextView(activity).apply {
            text = "Save Simple EQ Preset"
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val inputBox = android.widget.FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF555555.toInt())
                cornerRadius = 12 * density
            }
        }
        val defaultName = "Custom #$nextNum"
        val input = android.widget.EditText(activity).apply {
            hint = defaultName
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = null
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isSingleLine = true
        }
        inputBox.addView(input)
        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val cancelBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            setTextColor(0xFFEF9A9A.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
            insetTop = 0; insetBottom = 0
        }
        val okBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "OK"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            insetTop = 0; insetBottom = 0
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(okBtn)
        dialogView.addView(title)
        dialogView.addView(inputBox)
        dialogView.addView(divider)
        dialogView.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(activity, R.style.Theme_Equalizer314_Dialog)
            .setView(dialogView)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        okBtn.setOnClickListener {
            val name = input.text.toString().trim().ifEmpty { defaultName }
            eqPrefs.saveSimpleEqPreset(name, getCurrentGains(), state.preampGainDb)
            populatePresetPicker()
            android.widget.Toast.makeText(activity, "Saved \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun createPresetRow(name: String): View {
        val density = activity.resources.displayMetrics.density
        val presetJson = eqPrefs.getCustomPresetJson(name)
        val bandCount = try {
            org.json.JSONObject(presetJson ?: "{}").getJSONArray("bands").length()
        } catch (_: Exception) { 0 }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (4 * density).toInt())
            }
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
                cornerRadius = 12 * density
            }
            val hPad = (12 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }

        // Left side: preset name stacked over a small preamp subtitle
        // (matches the advanced picker rows).
        val isLightUi = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val nameText = TextView(activity).apply {
            text = name
            // Dark in light mode — preset rows sit on the light surface.
            setTextColor(if (isLightUi) 0xFF202020.toInt() else 0xFFE2E2E2.toInt())
            textSize = 14f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val presetPreamp: Double? = try {
            org.json.JSONObject(presetJson ?: "{}").optDouble("preamp", 0.0)
        } catch (_: Exception) { null }
        val preampSubtitle = TextView(activity).apply {
            text = presetPreamp?.let { PresetDropdownAdapter.formatPreamp(it) } ?: ""
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            isSingleLine = true
            visibility = if (presetPreamp == null) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val nameCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            addView(nameText)
            addView(preampSubtitle)
        }

        // Right side: EQ curve thumbnail + "N filters" text, same as the
        // advanced picker.
        val thumbW = (48 * density).toInt()
        val thumbH = (24 * density).toInt()
        val thumbnail = object : View(activity) {
            private fun buildEq(arr: org.json.JSONArray): com.bearinmind.equalizer314.dsp.ParametricEqualizer {
                val eq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
                eq.clearBands()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val ft = try { BiquadFilter.FilterType.valueOf(b.getString("filterType")) }
                             catch (_: Exception) { BiquadFilter.FilterType.BELL }
                    eq.addBand(b.getDouble("frequency").toFloat(), b.getDouble("gain").toFloat(), ft, b.getDouble("q"))
                }
                return eq
            }

            private fun drawCurve(
                canvas: android.graphics.Canvas,
                eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer,
                x0: Float, y0: Float, w: Float, h: Float, color: Int,
            ) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; strokeWidth = 0.5f * density; style = android.graphics.Paint.Style.STROKE
                }
                val gridPaint = android.graphics.Paint().apply { this.color = 0xFF6A6A6A.toInt(); strokeWidth = 1f }
                canvas.drawLine(x0, y0 + h / 2f, x0 + w, y0 + h / 2f, gridPaint)
                canvas.drawLine(x0, y0, x0, y0 + h, gridPaint)
                val path = android.graphics.Path()
                val maxDb = 15f; val steps = 50
                for (s in 0..steps) {
                    val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
                    val freq = 10f.pow(logF)
                    val db = eq.getFrequencyResponse(freq)
                    val x = x0 + w * s / steps
                    val y = (y0 + h / 2f - (db / maxDb) * (h / 2f)).coerceIn(y0, y0 + h)
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                if (w <= 0 || h <= 0 || presetJson == null) return
                try {
                    val obj = org.json.JSONObject(presetJson)
                    val cseOn = obj.optBoolean("channelSideEqEnabled", false)
                    val curveColor = 0xFFAAAAAA.toInt()
                    if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                        val labelCol = 9f * density
                        val gap = 2f * density
                        val halfH = (h - gap) / 2f
                        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0xFF888888.toInt()
                            textSize = 10f * activity.resources.displayMetrics.scaledDensity
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val fm = labelPaint.fontMetrics
                        val textCenterOffset = (-fm.ascent - fm.descent) / 2f
                        canvas.drawText("L", labelCol / 2f, halfH / 2f + textCenterOffset, labelPaint)
                        drawCurve(canvas, buildEq(obj.getJSONArray("leftBands")), labelCol, 0f, w - labelCol, halfH, curveColor)
                        val dividerPaint = android.graphics.Paint().apply { color = 0xFF444444.toInt(); strokeWidth = 1f }
                        canvas.drawLine(0f, halfH + gap / 2f, w, halfH + gap / 2f, dividerPaint)
                        val rTop = halfH + gap
                        canvas.drawText("R", labelCol / 2f, rTop + halfH / 2f + textCenterOffset, labelPaint)
                        drawCurve(canvas, buildEq(obj.getJSONArray("rightBands")), labelCol, rTop, w - labelCol, halfH, curveColor)
                    } else {
                        drawCurve(canvas, buildEq(obj.getJSONArray("bands")), 0f, 0f, w, h, curveColor)
                    }
                } catch (_: Exception) {}
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
        }

        val filtersText = TextView(activity).apply {
            text = "$bandCount filters"
            setTextColor(0xFF888888.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
        }
        val rightCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            addView(thumbnail)
            addView(filtersText)
        }

        // Export button — full APO mapping, same as the advanced picker.
        val exportBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(), (36 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            setPadding(0, 0, 0, 0)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setBackgroundColor(0x00000000)
            icon = activity.resources.getDrawable(R.drawable.ic_export, activity.theme)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            iconSize = (18 * density).toInt()
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setOnClickListener { exportPresetApo(name) }
        }

        // Delete button
        val deleteBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "×"
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(), (36 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            textSize = 16f
            setPadding(0, 0, 0, 0)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            gravity = Gravity.CENTER
            setBackgroundColor(0x00000000)
            setTextColor(0xFFEF9A9A.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setOnClickListener { showDeleteDialog(name) }
        }

        row.addView(nameCol)
        row.addView(rightCol)
        row.addView(exportBtn)
        row.addView(deleteBtn)

        // Tap row to load preset (sampled down to the 10 Simple bars)
        row.setOnClickListener {
            val presetGains = eqPrefs.getSimpleEqPresetGains(name) ?: return@setOnClickListener
            restoreSnapshot(presetGains)
            saveSnapshot()
            closePresetPicker()
            android.widget.Toast.makeText(activity, "Loaded \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
        }

        return row
    }

    /** Build an APO config from the shared-pool preset JSON and hand it to
     *  MainActivity's export launcher — identical mapping to the advanced
     *  picker so exports match regardless of which mode saved the preset. */
    private fun exportPresetApo(name: String) {
        val presetJson = eqPrefs.getCustomPresetJson(name) ?: return
        val obj = org.json.JSONObject(presetJson)
        val sb = StringBuilder()
        sb.append("Preamp: ${String.format("%.1f", obj.optDouble("preamp", 0.0))} dB\n")

        fun appendFilters(bands: org.json.JSONArray, indexOffset: Int = 0) {
            for (i in 0 until bands.length()) {
                val b = bands.getJSONObject(i)
                val apoType: String; val hasGain: Boolean; val hasQ: Boolean
                when (b.getString("filterType")) {
                    "BELL"         -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                    "LOW_SHELF"    -> { apoType = "LSC"; hasGain = true;  hasQ = true  }
                    "HIGH_SHELF"   -> { apoType = "HSC"; hasGain = true;  hasQ = true  }
                    "LOW_PASS"     -> { apoType = "LPQ"; hasGain = false; hasQ = true  }
                    "HIGH_PASS"    -> { apoType = "HPQ"; hasGain = false; hasQ = true  }
                    "LOW_SHELF_1"  -> { apoType = "LS";  hasGain = true;  hasQ = false }
                    "HIGH_SHELF_1" -> { apoType = "HS";  hasGain = true;  hasQ = false }
                    "LOW_PASS_1"   -> { apoType = "LP";  hasGain = false; hasQ = false }
                    "HIGH_PASS_1"  -> { apoType = "HP";  hasGain = false; hasQ = false }
                    "BAND_PASS"    -> { apoType = "BP";  hasGain = false; hasQ = true  }
                    "NOTCH"        -> { apoType = "NO";  hasGain = false; hasQ = true  }
                    "ALL_PASS"     -> { apoType = "AP";  hasGain = false; hasQ = true  }
                    else           -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                }
                val fc = b.getDouble("frequency").toInt()
                val line = StringBuilder("Filter ${i + 1 + indexOffset}: ON $apoType Fc $fc Hz")
                if (hasGain) line.append(" Gain ${String.format("%.1f", b.getDouble("gain"))} dB")
                if (hasQ) line.append(" Q ${String.format("%.2f", b.getDouble("q"))}")
                sb.append(line).append('\n')
            }
        }

        val cseOn = obj.optBoolean("channelSideEqEnabled", false)
        if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
            val leftArr = obj.getJSONArray("leftBands")
            sb.append("Channel: L\n"); appendFilters(leftArr)
            sb.append("Channel: R\n"); appendFilters(obj.getJSONArray("rightBands"), indexOffset = leftArr.length())
        } else {
            appendFilters(obj.getJSONArray("bands"))
        }
        (activity as? com.bearinmind.equalizer314.MainActivity)?.launchPresetExport(sb.toString(), "$name.txt")
    }

    private fun showDeleteDialog(name: String) {
        val density = activity.resources.displayMetrics.density
        val dlgView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        val dlgTitle = TextView(activity).apply {
            text = "Delete"
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val dlgMsg = TextView(activity).apply {
            text = "Delete preset \"$name\"?"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val dlgDiv = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        }
        val dlgBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val dlgDeleteBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            setTextColor(0xFFEF9A9A.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
            insetTop = 0; insetBottom = 0
        }
        val dlgCancelBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt()
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            insetTop = 0; insetBottom = 0
        }
        dlgBtnRow.addView(dlgDeleteBtn)
        dlgBtnRow.addView(dlgCancelBtn)
        dlgView.addView(dlgTitle)
        dlgView.addView(dlgMsg)
        dlgView.addView(dlgDiv)
        dlgView.addView(dlgBtnRow)
        val dlg = android.app.AlertDialog.Builder(activity, R.style.Theme_Equalizer314_Dialog)
            .setView(dlgView).create()
        dlgCancelBtn.setOnClickListener { dlg.dismiss() }
        dlgDeleteBtn.setOnClickListener {
            eqPrefs.deleteSimpleEqPreset(name)
            populatePresetPicker()
            dlg.dismiss()
        }
        dlg.show()
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
