package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqStateManager

class TableEqController(
    private val activity: Activity,
    private val rowContainer: LinearLayout,
    private val graphView: EqGraphView,
    private val state: EqStateManager,
    private val onEqChanged: () -> Unit
) {

    fun buildTable() {
        rowContainer.removeAllViews()
        val eq = state.parametricEq

        while (eq.getBandCount() < EqStateManager.MAX_BANDS) {
            val usedSlots = state.bandSlots.toSet()
            val newSlot = (0 until EqStateManager.MAX_BANDS).firstOrNull { it !in usedSlots } ?: break
            val newFreq = state.allDefaultFrequencies[newSlot]
            val insertPos = state.bandSlots.indexOfFirst { it > newSlot }.let { if (it < 0) state.bandSlots.size else it }
            eq.insertBand(insertPos, newFreq, 0f, BiquadFilter.FilterType.BELL)
            eq.setBandEnabled(insertPos, false)
            state.bandSlots.add(insertPos, newSlot)
        }
        state.displayToBandIndex = (0 until eq.getBandCount()).toList()
        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)

        for (i in 0 until eq.getBandCount()) {
            rowContainer.addView(createRow(i))
        }
    }

    private fun createRow(bandIndex: Int): View {
        val eq = state.parametricEq
        val band = eq.getBand(bandIndex) ?: return View(activity)
        val density = activity.resources.displayMetrics.density

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (2 * density).toInt()
            }
        }

        val slotLabel = if (bandIndex < state.bandSlots.size) state.bandSlots[bandIndex] + 1 else bandIndex + 1
        val numBox = TextView(activity).apply {
            text = "$slotLabel"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                marginEnd = (4 * density).toInt()
            }
            textSize = 12f
            setTextColor(if (band.enabled) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = 8 * density
            }
        }
        if (!band.enabled) row.alpha = 0.5f
        row.addView(numBox)

        val filterTypeNames = listOf("PEAK", "LSHELF", "HSHELF", "LPF", "HPF", "BYPASS")
        val filterTypeValues = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS
        )
        val currentTypeIdx = if (!band.enabled) {
            filterTypeNames.size - 1
        } else {
            filterTypeValues.indexOf(band.filterType).coerceAtLeast(0)
        }
        val filterBtn = TextView(activity).apply {
            text = filterTypeNames[currentTypeIdx]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = 8 * density
            }
            setTextColor(0xFFCCCCCC.toInt())

            setOnClickListener {
                val filterBtn = this
                val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
                val sheetLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
                }
                filterTypeNames.forEachIndexed { idx, name ->
                    val item = TextView(activity).apply {
                        text = name
                        textSize = 16f
                        setTextColor(0xFFE2E2E2.toInt())
                        setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                        setOnClickListener {
                            val b = eq.getBand(bandIndex) ?: return@setOnClickListener
                            if (idx == filterTypeNames.size - 1) {
                                eq.setBandEnabled(bandIndex, false)
                                row.alpha = 0.5f
                                numBox.setTextColor(0xFF666666.toInt())
                            } else {
                                if (!b.enabled) eq.setBandEnabled(bandIndex, true)
                                eq.updateBand(bandIndex, b.frequency, b.gain, filterTypeValues[idx], b.q)
                                row.alpha = 1f
                                numBox.setTextColor(0xFFCCCCCC.toInt())
                            }
                            filterBtn.text = filterTypeNames[idx]
                            graphView.setParametricEqualizer(eq)
                            onEqChanged()
                            bottomSheet.dismiss()
                        }
                    }
                    sheetLayout.addView(item)
                }
                bottomSheet.setContentView(sheetLayout)
                bottomSheet.show()
            }
        }
        row.addView(filterBtn)

        numBox.setOnClickListener {
            val b = eq.getBand(bandIndex) ?: return@setOnClickListener
            val nowEnabled = !b.enabled
            eq.setBandEnabled(bandIndex, nowEnabled)
            row.alpha = if (nowEnabled) 1f else 0.5f
            numBox.setTextColor(if (nowEnabled) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
            filterBtn.text = if (nowEnabled) {
                val idx = filterTypeValues.indexOf(b.filterType).coerceAtLeast(0)
                filterTypeNames[idx]
            } else {
                "BYPASS"
            }
            graphView.setParametricEqualizer(eq)
            onEqChanged()
        }

        fun makeInput(initialValue: String, inputType: Int, weight: Float, marginEnd: Int, onDone: (String) -> Unit): EditText {
            return EditText(activity).apply {
                setText(initialValue)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    this.marginEnd = marginEnd
                }
                textSize = 12f
                setTextColor(0xFFCCCCCC.toInt())
                this.inputType = inputType
                gravity = android.view.Gravity.CENTER
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF333333.toInt())
                    cornerRadius = 8 * density
                }
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        onDone(text.toString())
                        clearFocus()
                    }
                    true
                }
            }
        }

        val marginEnd = (4 * density).toInt()
        val numType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        val signedNumType = numType or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED

        val hzInput = makeInput(String.format("%.0f", band.frequency), numType, 1f, marginEnd) { text ->
            val hz = text.toFloatOrNull()?.coerceIn(10f, 20000f) ?: band.frequency
            val b = eq.getBand(bandIndex) ?: return@makeInput
            eq.updateBand(bandIndex, hz, b.gain, b.filterType, b.q)
            graphView.updateBandLevels()
            onEqChanged()
        }
        row.addView(hzInput)

        val isLpHp = band.filterType == BiquadFilter.FilterType.LOW_PASS || band.filterType == BiquadFilter.FilterType.HIGH_PASS
        val dbInput = makeInput(String.format("%.1f", band.gain), signedNumType, 1f, marginEnd) { text ->
            val db = text.toFloatOrNull()?.coerceIn(-12f, 12f) ?: band.gain
            val b = eq.getBand(bandIndex) ?: return@makeInput
            eq.updateBand(bandIndex, b.frequency, db, b.filterType, b.q)
            graphView.updateBandLevels()
            onEqChanged()
        }
        dbInput.setTextColor(if (isLpHp) 0xFF666666.toInt() else 0xFFCCCCCC.toInt())
        dbInput.isEnabled = !isLpHp
        dbInput.alpha = if (isLpHp) 0.4f else 1f
        row.addView(dbInput)

        val qInput = makeInput(String.format("%.2f", band.q), numType, 1f, 0) { text ->
            val q = text.toDoubleOrNull()?.coerceIn(0.1, 12.0) ?: band.q
            val b = eq.getBand(bandIndex) ?: return@makeInput
            eq.updateBand(bandIndex, b.frequency, b.gain, b.filterType, q)
            graphView.updateBandLevels()
            onEqChanged()
        }
        row.addView(qInput)

        return row
    }
}
