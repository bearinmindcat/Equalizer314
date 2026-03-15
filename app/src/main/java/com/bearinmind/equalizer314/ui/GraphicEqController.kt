package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class GraphicEqController(
    private val activity: Activity,
    private val container: LinearLayout,
    private val graphView: EqGraphView,
    private val state: EqStateManager,
    private val onEqChanged: () -> Unit,
    private val onBandCountChanged: () -> Unit
) {
    private val sliderRefs = mutableListOf<Slider>()
    private var sortedIndices = listOf<Int>()
    private var allSortedIndices = listOf<Int>()
    private var isUpdating = false
    private var currentPage = 0
    private val PAGE_SIZE = 8
    var targetCardHeight = 0

    /** Returns true if the page changed and sliders need rebuilding */
    fun updatePageForBand(bandIndex: Int?): Boolean {
        val eq = state.parametricEq
        val allSorted = (0 until eq.getBandCount()).sortedBy { eq.getBand(it)?.frequency ?: 0f }
        val displayPos = if (bandIndex != null) {
            val idx = allSorted.indexOf(bandIndex)
            if (idx >= 0) idx else 0
        } else 0
        val newPage = displayPos / PAGE_SIZE
        if (newPage != currentPage) {
            currentPage = newPage
            return true
        }
        return false
    }

    fun buildSliders(targetHeight: Int = 0) {
        container.removeAllViews()
        sliderRefs.clear()
        val eq = state.parametricEq
        allSortedIndices = (0 until eq.getBandCount()).sortedBy { eq.getBand(it)?.frequency ?: 0f }

        // Determine page from selected band
        val selectedBand = state.selectedBandIndex
        val selectedDisplayPos = if (selectedBand != null) {
            val idx = allSortedIndices.indexOf(selectedBand)
            if (idx >= 0) idx else 0
        } else 0
        currentPage = selectedDisplayPos / PAGE_SIZE

        // Only build slider cards for the current page
        val pageStart = currentPage * PAGE_SIZE
        val pageEnd = minOf(pageStart + PAGE_SIZE, allSortedIndices.size)
        val pageBands = allSortedIndices.subList(pageStart, pageEnd)
        sortedIndices = pageBands.toList()

        for (i in sortedIndices) {
            container.addView(createSliderCard(i, targetHeight))
        }
    }

    fun updateSliderValues() {
        val eq = state.parametricEq
        isUpdating = true
        for (pos in sliderRefs.indices) {
            val bandIndex = sortedIndices.getOrNull(pos) ?: continue
            val gain = eq.getBand(bandIndex)?.gain?.coerceIn(-12f, 12f) ?: 0f
            sliderRefs[pos].value = gain
        }
        isUpdating = false
    }

    fun insertCard(insertPos: Int) {
        val density = activity.resources.displayMetrics.density
        val eq = state.parametricEq
        val totalBands = eq.getBandCount()
        val btnMargin = 2
        val bandsOnPage = sortedIndices.size
        val hasAddButton = if (totalBands >= EqStateManager.MAX_BANDS) false
            else if (currentPage == 0 && totalBands <= PAGE_SIZE) true
            else if (currentPage > 0) true
            else false
        val itemsInRow = bandsOnPage + if (hasAddButton) 1 else 0
        val availableWidth = activity.resources.displayMetrics.widthPixels - (32 * density).toInt()
        val totalMargins = itemsInRow * (btnMargin * 2)
        val cardWidth = (availableWidth - totalMargins) / itemsInRow
        val newCard = createSliderCard(insertPos, targetCardHeight)
        val cardLp = newCard.layoutParams as LinearLayout.LayoutParams
        cardLp.width = 0
        newCard.alpha = 0f
        container.addView(newCard, insertPos)
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                (newCard.layoutParams as LinearLayout.LayoutParams).width = (cardWidth * v).toInt()
                newCard.alpha = v
                newCard.requestLayout()
            }
            start()
        }
    }

    fun removeCard(index: Int) {
        val cardToRemove = if (index < container.childCount) container.getChildAt(index) else null
        if (index < sliderRefs.size) sliderRefs.removeAt(index)
        if (cardToRemove != null) {
            val startWidth = cardToRemove.width
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 250
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    (cardToRemove.layoutParams as LinearLayout.LayoutParams).width = (startWidth * v).toInt()
                    cardToRemove.alpha = v
                    cardToRemove.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        container.removeView(cardToRemove)
                    }
                })
                start()
            }
        }
    }

    private fun createSliderCard(bandIndex: Int, targetHeight: Int = 0): com.google.android.material.card.MaterialCardView {
        val eq = state.parametricEq
        val band = eq.getBand(bandIndex)!!
        val isLpHp = band.filterType == BiquadFilter.FilterType.LOW_PASS || band.filterType == BiquadFilter.FilterType.HIGH_PASS
        val density = activity.resources.displayMetrics.density
        val sliderVisualHeight = (130 * density).toInt()
        val btnMargin = 2

        val filterTypes = listOf(
            R.drawable.ic_filter_bell to BiquadFilter.FilterType.BELL,
            R.drawable.ic_filter_low_shelf to BiquadFilter.FilterType.LOW_SHELF,
            R.drawable.ic_filter_high_shelf to BiquadFilter.FilterType.HIGH_SHELF,
            R.drawable.ic_filter_low_pass to BiquadFilter.FilterType.LOW_PASS,
            R.drawable.ic_filter_high_pass to BiquadFilter.FilterType.HIGH_PASS
        )

        // Match width of toggle buttons for the current page's row
        val totalBands = eq.getBandCount()
        val bandsOnPage = sortedIndices.size
        val hasAddButton = if (totalBands >= EqStateManager.MAX_BANDS) false
            else if (currentPage == 0 && totalBands <= PAGE_SIZE) true
            else if (currentPage > 0) true
            else false
        val itemsInRow = bandsOnPage + if (hasAddButton) 1 else 0
        val availableWidth = activity.resources.displayMetrics.widthPixels - (32 * density).toInt()
        val totalMargins = itemsInRow * (btnMargin * 2)
        val cardWidth = (availableWidth - totalMargins) / itemsInRow
        val cardHeight = if (targetHeight > 0) targetHeight else LinearLayout.LayoutParams.WRAP_CONTENT
        val card = com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                setMargins(btnMargin, 0, btnMargin, 0)
            }
            radius = 12 * density
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2A2A2A.toInt()))
            strokeWidth = 0
            clipChildren = false
            clipToPadding = false
            clipToOutline = false
        }

        val inset = (6 * density).toInt()
        val cardContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(inset, inset, inset, inset)
            clipChildren = false
            clipToPadding = false
        }

        val bandCount = eq.getBandCount()
        val hzDbSize = when { bandCount <= 5 -> 9f; bandCount <= 6 -> 8f; else -> 7f }
        val qSize = when { bandCount <= 5 -> 8f; bandCount <= 6 -> 7f; else -> 6f }
        val bubblePadH = when { bandCount <= 5 -> 6; bandCount <= 6 -> 4; else -> 2 }
        val bubblePad = (bubblePadH * density).toInt()
        val paramBubble = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = 8 * density
            }
            setPadding(bubblePad, (4 * density).toInt(), bubblePad, (4 * density).toInt())
        }
        val hzText = TextView(activity).apply {
            text = formatHz(band.frequency)
            textSize = hzDbSize
            setTextColor(0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            maxLines = 1
        }
        val dbText = TextView(activity).apply {
            text = "${String.format("%.1f", band.gain)} dB"
            textSize = hzDbSize
            setTextColor(if (isLpHp) 0xFF555555.toInt() else 0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            maxLines = 1
        }
        val qText = TextView(activity).apply {
            text = "${String.format("%.2f", band.q)} Q"
            textSize = qSize
            setTextColor(0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            maxLines = 1
        }
        paramBubble.addView(hzText)
        paramBubble.addView(dbText)
        paramBubble.addView(qText)
        paramBubble.setOnClickListener {
            showBandEditDialog(bandIndex, hzText, dbText, qText)
        }
        cardContent.addView(paramBubble)

        val sliderFrame = FrameLayout(activity).apply {
            layoutParams = if (targetHeight > 0) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, sliderVisualHeight)
            }
            clipChildren = false
            clipToPadding = false
        }

        val slider = Slider(activity).apply {
            if (isLpHp) {
                valueFrom = 0.1f; valueTo = 12f
                value = band.q.toFloat().coerceIn(0.1f, 12f)
            } else {
                valueFrom = -12f; valueTo = 12f
                value = band.gain.coerceIn(-12f, 12f)
            }
            rotation = 270f
            layoutParams = FrameLayout.LayoutParams(
                sliderVisualHeight, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE

            addOnChangeListener { _, value, fromUser ->
                if (!fromUser || isUpdating) return@addOnChangeListener
                val b = eq.getBand(bandIndex) ?: return@addOnChangeListener
                if (isLpHp) {
                    eq.updateBand(bandIndex, b.frequency, b.gain, b.filterType, value.toDouble())
                    hzText.text = formatHz(b.frequency)
                    qText.text = "${String.format("%.2f", value)} Q"
                } else {
                    eq.updateBand(bandIndex, b.frequency, value, b.filterType, b.q)
                    hzText.text = formatHz(b.frequency)
                    dbText.text = "${String.format("%.1f", value)} dB"
                }
                graphView.setParametricEqualizer(eq)
                onEqChanged()
            }
        }

        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { v, event ->
            if (consumeUntilUp) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    consumeUntilUp = false
                }
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    val b = eq.getBand(bandIndex) ?: return@setOnTouchListener false
                    if (isLpHp) {
                        slider.value = 0.71f
                        eq.updateBand(bandIndex, b.frequency, b.gain, b.filterType, 0.707)
                        qText.text = "0.71 Q"
                    } else {
                        slider.value = 0f
                        eq.updateBand(bandIndex, b.frequency, 0f, b.filterType, b.q)
                        dbText.text = "0.0 dB"
                    }
                    graphView.setParametricEqualizer(eq)
                    onEqChanged()
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        sliderRefs.add(slider)
        sliderFrame.addView(slider)
        cardContent.addView(sliderFrame)

        val filterTypeLabels = listOf("PEAK", "LSHELF", "HSHELF", "LPF", "HPF")
        val currentFilterIdx = filterTypes.indexOfFirst { it.second == band.filterType }.coerceAtLeast(0)
        val filterLabelSize = when { bandCount <= 5 -> 8f; bandCount <= 6 -> 7f; else -> 6f }

        val filterBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = null
            text = if (band.enabled) filterTypeLabels[currentFilterIdx] else "BYPASS"
            textSize = filterLabelSize
            setTextColor(0xFFAAAAAA.toInt())
            cornerRadius = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (32 * density).toInt()
            ).apply {
                setMargins((2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt(), 0)
            }
            setPadding(0, 0, 0, 0)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setBackgroundColor(0x00000000)
            iconTint = android.content.res.ColorStateList.valueOf(0xFFAAAAAA.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()

            setOnClickListener {
                val filterBtn = this
                val allLabels = filterTypeLabels + "BYPASS"
                val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
                val sheetLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
                }
                allLabels.forEachIndexed { idx, name ->
                    val item = android.widget.TextView(activity).apply {
                        text = name
                        textSize = 16f
                        setTextColor(0xFFE2E2E2.toInt())
                        setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                        setOnClickListener {
                            val b = eq.getBand(bandIndex) ?: return@setOnClickListener
                            if (idx >= filterTypes.size) {
                                eq.setBandEnabled(bandIndex, false)
                                filterBtn.text = "BYPASS"
                            } else {
                                if (!b.enabled) eq.setBandEnabled(bandIndex, true)
                                eq.updateBand(bandIndex, b.frequency, b.gain, filterTypes[idx].second, b.q)
                                filterBtn.text = filterTypeLabels[idx]
                            }
                            graphView.updateBandLevels()
                            onBandCountChanged()
                            bottomSheet.dismiss()
                        }
                    }
                    sheetLayout.addView(item)
                }
                bottomSheet.setContentView(sheetLayout)
                bottomSheet.show()
            }
        }
        cardContent.addView(filterBtn)
        card.addView(cardContent)
        return card
    }

    private fun showBandEditDialog(bandIndex: Int, hzLabel: TextView, dbLabel: TextView, qLabel: TextView) {
        val eq = state.parametricEq
        val band = eq.getBand(bandIndex) ?: return
        val isLpHp = band.filterType == BiquadFilter.FilterType.LOW_PASS || band.filterType == BiquadFilter.FilterType.HIGH_PASS
        val density = activity.resources.displayMetrics.density
        val slotLabel = if (bandIndex < state.bandSlots.size) state.bandSlots[bandIndex] + 1 else bandIndex + 1

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }

        fun makeRow(label: String, initialValue: String, inputType: Int, imeAction: Int, enabled: Boolean = true): Pair<android.widget.EditText, LinearLayout> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val input = android.widget.EditText(activity).apply {
                setText(initialValue)
                textSize = 22f
                setTextColor(if (enabled) 0xFFE2E2E2.toInt() else 0xFF555555.toInt())
                this.inputType = inputType
                isSingleLine = true
                background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                imeOptions = imeAction
                isEnabled = enabled
                isFocusable = enabled
            }
            row.addView(input)
            row.addView(TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(if (enabled) 0xFF999999.toInt() else 0xFF555555.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * density).toInt()
                }
            })
            return input to row
        }

        fun makeDivider(): View {
            return View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                    topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt()
                }
                setBackgroundColor(0xFF444444.toInt())
            }
        }

        val hzType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        val dbType = hzType or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        val (hzInput, hzRow) = makeRow("Hz", String.format("%.0f", band.frequency), hzType, android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
        val (dbInput, dbRow) = makeRow("dB", String.format("%.1f", band.gain), dbType, android.view.inputmethod.EditorInfo.IME_ACTION_DONE, enabled = !isLpHp)
        val (qInput, qRow) = makeRow("Q", String.format("%.2f", band.q), hzType, android.view.inputmethod.EditorInfo.IME_ACTION_DONE)

        container.addView(hzRow)
        container.addView(makeDivider())
        container.addView(dbRow)
        container.addView(makeDivider())
        container.addView(qRow)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Band $slotLabel")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val hz = hzInput.text.toString().toFloatOrNull()?.coerceIn(10f, 20000f) ?: band.frequency
                val db = if (isLpHp) band.gain else dbInput.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: band.gain
                val q = qInput.text.toString().toDoubleOrNull()?.coerceIn(0.1, 12.0) ?: band.q
                val b = eq.getBand(bandIndex) ?: return@setPositiveButton
                eq.updateBand(bandIndex, hz, db, b.filterType, q)
                hzLabel.text = formatHz(hz)
                dbLabel.text = "${String.format("%.1f", db)} dB"
                qLabel.text = "Q ${String.format("%.2f", q)}"
                val sliderPos = sortedIndices.indexOf(bandIndex)
                if (sliderPos in sliderRefs.indices) {
                    isUpdating = true
                    sliderRefs[sliderPos].value = if (isLpHp) q.toFloat().coerceIn(0.1f, 12f) else db
                    isUpdating = false
                }
                graphView.setParametricEqualizer(eq)
                onEqChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun formatHz(hz: Float): String {
        return when {
            hz >= 10000 -> "${(hz / 1000).toInt()}k"
            hz >= 1000 -> String.format("%.1fk", hz / 1000)
            else -> "${hz.toInt()}"
        }
    }
}
