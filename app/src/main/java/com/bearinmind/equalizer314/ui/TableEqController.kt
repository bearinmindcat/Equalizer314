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
    companion object {
        val BAND_COLORS = listOf(
            0xFF333333.toInt() to "Default",
            0xFFFFFFFF.toInt() to "White",
            0xFFEF9A9A.toInt() to "Red",
            0xFFA5D6A7.toInt() to "Green",
            0xFF90CAF9.toInt() to "Blue",
            0xFFFFF59D.toInt() to "Yellow",
            0xFFFFCC80.toInt() to "Orange",
            0xFFCE93D8.toInt() to "Purple",
            0xFF9FA8DA.toInt() to "Indigo"
        )
    }

    /** Remove disabled bands that were auto-added for table display */
    fun cleanup() {
        val eq = state.parametricEq
        // Remove bands in reverse to avoid index shifting
        for (i in eq.getBandCount() - 1 downTo 0) {
            if (eq.getBand(i)?.enabled == false) {
                eq.removeBand(i)
                if (i < state.bandSlots.size) state.bandSlots.removeAt(i)
            }
        }
        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)
    }

    fun buildTable() {
        rowContainer.removeAllViews()
        val eq = state.parametricEq

        // Fill all 16 slots — inactive bands are disabled and greyed out
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

        val slotIndex = if (bandIndex < state.bandSlots.size) state.bandSlots[bandIndex] else bandIndex
        val slotLabel = slotIndex + 1
        val hasColor = state.bandColors.containsKey(slotIndex)
        val savedColor = state.bandColors[slotIndex] ?: 0xFF666666.toInt()
        val numBox = TextView(activity).apply {
            text = "$slotLabel"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                marginEnd = (4 * density).toInt()
            }
            textSize = 12f
            setTextColor(when {
                !band.enabled -> 0xFF666666.toInt()
                hasColor -> savedColor
                else -> 0xFFCCCCCC.toInt()
            })
            gravity = android.view.Gravity.CENTER
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF333333.toInt())  // original solid cell fill
                if (hasColor) {
                    setStroke((1.5f * density).toInt(), savedColor)
                }
                cornerRadius = 8 * density
            }
        }
        if (!band.enabled) row.alpha = 0.5f
        row.addView(numBox)

        // Full 12-token APO vocabulary. Parallel lists: label[i] ↔ type[i].
        // BYPASS is the last entry and maps to ALL_PASS (matches the
        // Parametric-mode Bypass↔AP tie).
        val filterTypeNames = listOf(
            "PEAK",
            "LSHELF", "LSHELF 6dB",
            "HSHELF", "HSHELF 6dB",
            "LPF", "LPF 6dB",
            "HPF", "HPF 6dB",
            "BAND PASS", "NOTCH",
            "BYPASS",
        )
        val filterTypeValues = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF, BiquadFilter.FilterType.LOW_SHELF_1,
            BiquadFilter.FilterType.HIGH_SHELF, BiquadFilter.FilterType.HIGH_SHELF_1,
            BiquadFilter.FilterType.LOW_PASS, BiquadFilter.FilterType.LOW_PASS_1,
            BiquadFilter.FilterType.HIGH_PASS, BiquadFilter.FilterType.HIGH_PASS_1,
            BiquadFilter.FilterType.BAND_PASS, BiquadFilter.FilterType.NOTCH,
            BiquadFilter.FilterType.ALL_PASS,
        )
        val currentTypeIdx = when {
            !band.enabled -> filterTypeNames.size - 1  // legacy disabled → BYPASS
            else -> filterTypeValues.indexOf(band.filterType).coerceAtLeast(0)
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
                            // Apply the chosen type directly; BYPASS = ALL_PASS
                            // per the Parametric-mode model. Re-enable the
                            // band regardless so no band stays in the legacy
                            // disabled state.
                            if (!b.enabled) eq.setBandEnabled(bandIndex, true)
                            eq.updateBand(bandIndex, b.frequency, b.gain, filterTypeValues[idx], b.q)
                            row.alpha = 1f
                            numBox.setTextColor(
                                if (isColorLight(state.bandColors[slotIndex] ?: 0xFF333333.toInt()))
                                    0xFF222222.toInt() else 0xFFCCCCCC.toInt()
                            )
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
            val curColor = state.bandColors[slotIndex] ?: 0xFF333333.toInt()
            val light = isColorLight(curColor)
            numBox.setTextColor(if (!nowEnabled) 0xFF666666.toInt() else if (light) 0xFF222222.toInt() else 0xFFCCCCCC.toInt())
            filterBtn.text = when {
                !nowEnabled -> "BYPASS"
                else -> filterTypeNames[filterTypeValues.indexOf(b.filterType).coerceAtLeast(0)]
            }
            graphView.setParametricEqualizer(eq)
            onEqChanged()
        }

        numBox.setOnLongClickListener {
            showColorPicker(slotIndex, numBox, band.enabled)
            true
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

        val gainless = when (band.filterType) {
            BiquadFilter.FilterType.LOW_PASS, BiquadFilter.FilterType.HIGH_PASS,
            BiquadFilter.FilterType.LOW_PASS_1, BiquadFilter.FilterType.HIGH_PASS_1,
            BiquadFilter.FilterType.BAND_PASS, BiquadFilter.FilterType.NOTCH,
            BiquadFilter.FilterType.ALL_PASS -> true
            else -> false
        }
        val dbInput = makeInput(String.format("%.1f", band.gain), signedNumType, 1f, marginEnd) { text ->
            val db = text.toFloatOrNull()?.coerceIn(-12f, 12f) ?: band.gain
            val b = eq.getBand(bandIndex) ?: return@makeInput
            eq.updateBand(bandIndex, b.frequency, db, b.filterType, b.q)
            graphView.updateBandLevels()
            onEqChanged()
        }
        dbInput.setTextColor(if (gainless) 0xFF666666.toInt() else 0xFFCCCCCC.toInt())
        dbInput.isEnabled = !gainless
        dbInput.alpha = if (gainless) 0.4f else 1f
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

    private fun showColorPicker(slotIndex: Int, numBox: TextView, enabled: Boolean) {
        val density = activity.resources.displayMetrics.density
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val sheetLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }

        val title = TextView(activity).apply {
            text = "Band Color"
            textSize = 16f
            setTextColor(0xFFE2E2E2.toInt())
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        sheetLayout.addView(title)

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        for ((color, _) in BAND_COLORS) {
            val isNone = color == 0xFF333333.toInt()
            val size = (32 * density).toInt()
            val swatch = if (isNone) {
                TextView(activity).apply {
                    text = "—"
                    textSize = 16f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF333333.toInt())
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            }
            swatch.setOnClickListener {
                if (isNone) {
                    state.bandColors.remove(slotIndex)
                } else {
                    state.bandColors[slotIndex] = color
                }
                numBox.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF333333.toInt())  // original solid cell fill
                    if (!isNone) {
                        setStroke((1.5f * density).toInt(), color)
                    }
                    cornerRadius = 8 * density
                }
                numBox.setTextColor(when {
                    !enabled -> 0xFF666666.toInt()
                    isNone -> 0xFFCCCCCC.toInt()
                    else -> color
                })
                graphView.setBandColors(state.bandColors)
                state.saveState()
                bottomSheet.dismiss()
            }
            grid.addView(swatch)
        }

        sheetLayout.addView(grid)
        bottomSheet.setContentView(sheetLayout)
        bottomSheet.show()
    }

    private fun isColorLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return luminance > 0.5
    }
}
