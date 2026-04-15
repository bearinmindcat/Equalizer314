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
                bottomMargin = (8 * density).toInt()
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

        // Card wrapping the bars
        val bCard = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(0xFF1E1E1E.toInt())
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
            }
            onDragEnd = { saveSnapshot() }
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
                topMargin = (12 * density).toInt()
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

        // List saved presets
        for (name in presetNames) {
            val gains = eqPrefs.getSimpleEqPresetGains(name) ?: continue
            pickerContainer.addView(createPresetRow(name, gains))
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
            eqPrefs.saveSimpleEqPreset(name, getCurrentGains())
            populatePresetPicker()
            android.widget.Toast.makeText(activity, "Saved \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun createPresetRow(name: String, gains: FloatArray): View {
        val density = activity.resources.displayMetrics.density

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

        // Preset name
        val nameText = TextView(activity).apply {
            text = name
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 14f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Mini 10-bar thumbnail
        val thumbW = (48 * density).toInt()
        val thumbH = (24 * density).toInt()
        val thumbnail = object : View(activity) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                if (w <= 0 || h <= 0) return
                val barW = w / (gains.size * 2f)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFAAAAAA.toInt()
                }
                val bgPaint = android.graphics.Paint().apply { color = 0xFF6A6A6A.toInt(); strokeWidth = 1f }
                canvas.drawLine(0f, h / 2f, w, h / 2f, bgPaint)
                for (i in gains.indices) {
                    val cx = w * (i + 0.5f) / gains.size
                    val left = cx - barW / 2f
                    val right = cx + barW / 2f
                    val mid = h / 2f
                    val barH = (gains[i] / 12f) * (h / 2f)
                    if (barH > 0) {
                        canvas.drawRect(left, mid - barH, right, mid, paint)
                    } else if (barH < 0) {
                        canvas.drawRect(left, mid, right, mid - barH, paint)
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        // Export button — APO format
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
            setOnClickListener {
                val sb = StringBuilder()
                sb.append("Preamp: 0.0 dB\n")
                for (i in gains.indices) {
                    val freq = FREQUENCIES[i]
                    val freqInt = freq.toInt()
                    sb.append("Filter ${i + 1}: ON PK Fc $freqInt Hz Gain ${String.format("%.1f", gains[i])} dB Q ${String.format("%.2f", Q)}\n")
                }
                (activity as? com.bearinmind.equalizer314.MainActivity)?.launchPresetExport(sb.toString(), "$name.txt")
            }
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

        row.addView(nameText)
        row.addView(thumbnail)
        row.addView(exportBtn)
        row.addView(deleteBtn)

        // Tap row to load preset
        row.setOnClickListener {
            val presetGains = eqPrefs.getSimpleEqPresetGains(name) ?: return@setOnClickListener
            restoreSnapshot(presetGains)
            saveSnapshot()
            closePresetPicker()
            android.widget.Toast.makeText(activity, "Loaded \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
        }

        return row
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
