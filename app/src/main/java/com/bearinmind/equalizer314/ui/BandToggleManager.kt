package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton

class BandToggleManager(
    private val activity: Activity,
    private val toggleGroup: LinearLayout,
    private val toggleGroup2: LinearLayout,
    private val triangleIndicator: View,
    private val graphView: EqGraphView,
    private val state: EqStateManager,
    private val onEqChanged: () -> Unit,
    private val onBandCountChanged: () -> Unit,
    private val onBandSelected: (Int?) -> Unit
) {
    private val ROW_SIZE = 8
    private var isAnimating = false

    private fun getRowForDisplay(displayPos: Int): LinearLayout {
        return if (displayPos < ROW_SIZE) toggleGroup else toggleGroup2
    }

    private fun getRowIndex(displayPos: Int): Int {
        return if (displayPos < ROW_SIZE) displayPos else displayPos - ROW_SIZE
    }

    private fun updateRowVisibility() {
        val eq = state.parametricEq
        val bandCount = eq.getBandCount()
        // Show both rows when there are enough bands (all modes including graphic)
        toggleGroup.visibility = View.VISIBLE
        toggleGroup2.visibility = if (bandCount > ROW_SIZE || (bandCount == ROW_SIZE && bandCount < EqStateManager.MAX_BANDS)) View.VISIBLE else View.GONE
    }

    fun setupToggles() {
        toggleGroup.removeAllViews()
        toggleGroup2.removeAllViews()

        val eq = state.parametricEq
        val bandCount = eq.getBandCount()

        // Graphic-mode band toggles follow the slot label order (1, 2, 3,
        // …) so the row stays in numeric order regardless of where the
        // user has dragged each dot on the graph above. Other modes keep
        // their natural index order.
        val orderedIndices = if (state.currentEqUiMode == EqUiMode.GRAPHIC) {
            val slots = state.bandSlots
            (0 until bandCount).sortedBy { slots.getOrNull(it) ?: it }
        } else {
            (0 until bandCount).toList()
        }
        state.displayToBandIndex = orderedIndices

        for ((displayPos, bandIdx) in orderedIndices.withIndex()) {
            val row = getRowForDisplay(displayPos)
            row.addView(createToggleButton(bandIdx))
        }
        if (bandCount < EqStateManager.MAX_BANDS) {
            val addRow = getRowForDisplay(bandCount)
            addRow.addView(createAddButton())
        }
        updateRowVisibility()
    }

    fun updateSelection(bandIndex: Int?) {
        state.selectedBandIndex = bandIndex
        updateRowVisibility()
        for (displayPos in state.displayToBandIndex.indices) {
            val row = getRowForDisplay(displayPos)
            val rowIdx = getRowIndex(displayPos)
            val btn = row.getChildAt(rowIdx) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            val bandIdx = state.displayToBandIndex[displayPos]
            val enabled = state.parametricEq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled, selected = (bandIdx == bandIndex), bandIdx = bandIdx)
        }
        updateTriangleIndicator(bandIndex)
    }

    fun updateIcons() {
        val eq = state.parametricEq
        val iconSize = (22 * activity.resources.displayMetrics.density).toInt()
        for (displayPos in state.displayToBandIndex.indices) {
            val bandIdx = state.displayToBandIndex[displayPos]
            val row = getRowForDisplay(displayPos)
            val rowIdx = getRowIndex(displayPos)
            val btn = row.getChildAt(rowIdx) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            val filterIcon = state.getFilterIconForBand(bandIdx)?.let {
                ContextCompat.getDrawable(activity, it)?.mutate()
            }
            filterIcon?.setBounds(0, 0, iconSize, iconSize)
            btn.setCompoundDrawablesRelative(null, null, null, filterIcon)
            val enabled = eq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled, selected = (bandIdx == state.selectedBandIndex), bandIdx = bandIdx)
        }
    }

    fun addNewBand() {
        val eq = state.parametricEq
        if (eq.getBandCount() >= EqStateManager.MAX_BANDS) return

        val oldBandCount = eq.getBandCount()
        val usedSlots = state.bandSlots.toSet()
        val newSlot = (0 until EqStateManager.MAX_BANDS).firstOrNull { it !in usedSlots } ?: return
        val newFreq = state.allDefaultFrequencies[newSlot]
        val insertPos = state.bandSlots.indexOfFirst { it > newSlot }.let { if (it < 0) state.bandSlots.size else it }

        eq.insertBand(insertPos, newFreq, 0f, BiquadFilter.FilterType.BELL)
        state.bandSlots.add(insertPos, newSlot)
        state.displayToBandIndex = (0 until eq.getBandCount()).toList()

        state.selectedBandIndex?.let { sel ->
            if (insertPos <= sel) state.selectedBandIndex = sel + 1
        }

        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)
        graphView.setBandColors(state.bandColors)

        // If another animation is in progress, skip animated transition
        if (isAnimating) {
            setupToggles()
            updateSelection(state.selectedBandIndex)
            state.saveState()
            onBandCountChanged()
            return
        }

        // Check if new band replaces "+" at a row boundary (row count stays same = no resize)
        val replacesAdd = insertPos == oldBandCount &&
                (getRowForDisplay(oldBandCount) != getRowForDisplay(oldBandCount + 1) ||
                 oldBandCount + 1 == EqStateManager.MAX_BANDS)

        if (replacesAdd) {
            // Dual animation: "+" shrinks out while new band grows in
            val addRow = getRowForDisplay(oldBandCount)
            val addRowIdx = getRowIndex(oldBandCount)
            val oldAddBtn = addRow.getChildAt(addRowIdx)

            if (oldAddBtn != null && oldAddBtn.width > 0) {
                // Switch "+" to explicit width
                val oldLp = oldAddBtn.layoutParams as LinearLayout.LayoutParams
                val startWidth = oldAddBtn.width
                oldLp.height = oldAddBtn.height
                oldLp.weight = 0f; oldLp.width = startWidth; oldAddBtn.layoutParams = oldLp

                // Create new band button at width 0, insert before "+"
                val newBtn = createToggleButton(insertPos)
                val newLp = newBtn.layoutParams as LinearLayout.LayoutParams
                newLp.height = oldAddBtn.height
                newLp.weight = 0f; newLp.width = 0
                newBtn.alpha = 0f
                addRow.addView(newBtn, addRowIdx)

                isAnimating = true
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        val f = it.animatedFraction
                        val growWidth = (startWidth * f).toInt()
                        newLp.width = growWidth
                        newBtn.alpha = f
                        newBtn.requestLayout()
                        oldLp.width = startWidth - growWidth
                        oldAddBtn.alpha = 1f - f
                        oldAddBtn.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            isAnimating = false
                            addRow.removeView(oldAddBtn)
                            newLp.weight = 1f; newLp.width = 0
                            newLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                            newBtn.alpha = 1f
                            newBtn.requestLayout()
                            if (eq.getBandCount() < EqStateManager.MAX_BANDS) {
                                val newAddRow = getRowForDisplay(eq.getBandCount())
                                newAddRow.addView(createAddButton())
                            }
                            updateRowVisibility()
                        }
                    })
                    start()
                }
            } else {
                setupToggles()
            }
            updateSelection(state.selectedBandIndex)
        } else {
            // Check if inserting into row1 would require moving items between rows
            val needsRowReflow = insertPos < ROW_SIZE && oldBandCount >= ROW_SIZE - 1

            if (needsRowReflow) {
                // Single-phase: grow new in row1, shrink overflow in row1,
                // and grow matching button in row2 — all simultaneously
                val capturedHeight = (0 until toggleGroup.childCount)
                    .mapNotNull { toggleGroup.getChildAt(it)?.height?.takeIf { h -> h > 0 } }
                    .firstOrNull() ?: 0

                // Lock all row1 buttons to explicit widths
                for (i in 0 until toggleGroup.childCount) {
                    val child = toggleGroup.getChildAt(i) ?: continue
                    val clp = child.layoutParams as LinearLayout.LayoutParams
                    if (capturedHeight > 0) clp.height = capturedHeight
                    clp.weight = 0f
                    clp.width = child.width
                    child.layoutParams = clp
                }

                // Create new button at insertion position, width 0
                val newBtn = createToggleButton(insertPos)
                val newLp = newBtn.layoutParams as LinearLayout.LayoutParams
                if (capturedHeight > 0) newLp.height = capturedHeight
                newLp.weight = 0f; newLp.width = 0
                newBtn.alpha = 0f
                val rowIdx = getRowIndex(insertPos)
                toggleGroup.addView(newBtn, rowIdx)

                // The last child in row1 is the overflow button (pushed to row2)
                val overflowIdx = toggleGroup.childCount - 1
                val overflowBtn = toggleGroup.getChildAt(overflowIdx)
                val overflowLp = overflowBtn.layoutParams as LinearLayout.LayoutParams
                val overflowStartWidth = overflowLp.width // actual captured width after locking
                val isOverflowAdd = (overflowBtn as? MaterialButton)?.text == "+"

                // For band-button overflow: create matching button in row2 (grows simultaneously)
                val row2NewBtn: View? = if (!isOverflowAdd && state.displayToBandIndex.size > ROW_SIZE) {
                    val overflowBandIdx = state.displayToBandIndex[ROW_SIZE]
                    val r2btn = createToggleButton(overflowBandIdx)
                    val r2lp = r2btn.layoutParams as LinearLayout.LayoutParams
                    r2lp.weight = 0f; r2lp.width = 0
                    r2btn.alpha = 0f
                    toggleGroup2.addView(r2btn, 0)
                    updateRowVisibility()
                    r2btn
                } else null
                val row2NewLp = row2NewBtn?.layoutParams as? LinearLayout.LayoutParams

                // If hitting MAX_BANDS, find "+" in row2 to shrink it during animation
                val hittingMax = eq.getBandCount() >= EqStateManager.MAX_BANDS
                var row2AddBtn: View? = null
                var row2AddLp: LinearLayout.LayoutParams? = null
                var row2AddWidth = 0
                if (hittingMax) {
                    for (i in toggleGroup2.childCount - 1 downTo 0) {
                        val child = toggleGroup2.getChildAt(i)
                        if ((child as? MaterialButton)?.text == "+") {
                            row2AddBtn = child
                            row2AddLp = child.layoutParams as LinearLayout.LayoutParams
                            row2AddWidth = child.width
                            break
                        }
                    }
                }

                // Lock all row2 children to explicit widths for smooth animation
                if (row2NewBtn != null || row2AddBtn != null) {
                    for (i in 0 until toggleGroup2.childCount) {
                        val child = toggleGroup2.getChildAt(i) ?: continue
                        if (child == row2NewBtn) continue // already at width 0
                        val clp = child.layoutParams as LinearLayout.LayoutParams
                        clp.weight = 0f
                        clp.width = child.width
                        clp.height = child.height
                        child.layoutParams = clp
                    }
                }

                // Capture remaining row2 children for smooth resize during animation
                val row2Remaining = mutableListOf<Triple<View, LinearLayout.LayoutParams, Int>>()
                var row2ContentWidth = 0
                for (i in 0 until toggleGroup2.childCount) {
                    val child = toggleGroup2.getChildAt(i) ?: continue
                    if (child == row2NewBtn || child == row2AddBtn) continue
                    val clp = child.layoutParams as LinearLayout.LayoutParams
                    row2ContentWidth += clp.width
                    row2Remaining.add(Triple(child, clp, clp.width))
                }
                // Add width of shrinking "+" to available space
                if (row2AddBtn != null) row2ContentWidth += row2AddWidth
                val row2FinalCount = row2Remaining.size + 1 // remaining + new button
                val row2TargetWidth = if (row2FinalCount > 0) row2ContentWidth / row2FinalCount else 0

                isAnimating = true
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { anim ->
                        val f = anim.animatedFraction
                        // Row1: use overflow's actual width for complementary animation
                        val growWidth = (overflowStartWidth * f).toInt()
                        newLp.width = growWidth
                        newBtn.alpha = f
                        newBtn.requestLayout()
                        overflowLp.width = overflowStartWidth - growWidth
                        overflowBtn.alpha = 1f - f
                        overflowBtn.requestLayout()
                        // Row2: grow matching button
                        if (row2NewBtn != null && row2NewLp != null) {
                            row2NewLp.width = (row2TargetWidth * f).toInt()
                            row2NewBtn.alpha = f
                            row2NewBtn.requestLayout()
                        }
                        // Row2: shrink "+" if hitting max
                        if (row2AddBtn != null && row2AddLp != null) {
                            val row2ShrinkWidth = (row2AddWidth * f).toInt()
                            row2AddLp.width = row2AddWidth - row2ShrinkWidth
                            row2AddBtn.alpha = 1f - f
                            row2AddBtn.requestLayout()
                        }
                        // Row2: resize remaining children smoothly
                        for ((rChild, rClp, rStartW) in row2Remaining) {
                            rClp.width = rStartW + ((row2TargetWidth - rStartW) * f).toInt()
                            rChild.requestLayout()
                        }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            isAnimating = false
                            toggleGroup.removeView(overflowBtn)
                            // Remove "+" if it was shrunk
                            if (row2AddBtn != null) {
                                (row2AddBtn.parent as? LinearLayout)?.removeView(row2AddBtn)
                            }
                            // Reset row1 buttons to weight layout
                            for (i in 0 until toggleGroup.childCount) {
                                val child = toggleGroup.getChildAt(i) ?: continue
                                val clp = child.layoutParams as LinearLayout.LayoutParams
                                clp.weight = 1f; clp.width = 0
                                clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                child.alpha = 1f
                                child.requestLayout()
                            }
                            // Reset row2 buttons to weight layout
                            for (i in 0 until toggleGroup2.childCount) {
                                val child = toggleGroup2.getChildAt(i) ?: continue
                                val clp = child.layoutParams as LinearLayout.LayoutParams
                                clp.weight = 1f; clp.width = 0
                                clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                child.alpha = 1f
                                child.requestLayout()
                            }
                            // For "+" overflow: add to row2 with grow animation
                            if (isOverflowAdd && eq.getBandCount() < EqStateManager.MAX_BANDS) {
                                val addBtn = createAddButton()
                                toggleGroup2.addView(addBtn, 0)
                                updateRowVisibility()
                                animateButtonIn(addBtn, toggleGroup2)
                            }
                            updateRowVisibility()
                            updateSelection(state.selectedBandIndex)
                            updateClickListeners()
                        }
                    })
                    start()
                }
            } else {
                // Insert new button directly without full rebuild
                val newDisplayPos = state.displayToBandIndex.indexOf(insertPos)
                if (newDisplayPos >= 0) {
                    val row = getRowForDisplay(newDisplayPos)
                    val rowIdx = getRowIndex(newDisplayPos)
                    val newBtn = createToggleButton(insertPos)

                    // Find "+" to animate out if we hit MAX_BANDS
                    var addBtnToRemove: View? = null
                    if (eq.getBandCount() >= EqStateManager.MAX_BANDS) {
                        val addBtnRow = getRowForDisplay(oldBandCount)
                        for (i in addBtnRow.childCount - 1 downTo 0) {
                            if ((addBtnRow.getChildAt(i) as? MaterialButton)?.text == "+") {
                                addBtnToRemove = addBtnRow.getChildAt(i)
                                break
                            }
                        }
                    }

                    if (addBtnToRemove != null) {
                        // Dual animation: new grows, "+" shrinks
                        val addLp = addBtnToRemove.layoutParams as LinearLayout.LayoutParams
                        val addWidth = addBtnToRemove.width
                        val capturedHeight = addBtnToRemove.height

                        // Lock all buttons in the row to explicit widths
                        for (i in 0 until row.childCount) {
                            val child = row.getChildAt(i) ?: continue
                            val clp = child.layoutParams as LinearLayout.LayoutParams
                            clp.weight = 0f
                            clp.width = child.width
                            clp.height = child.height
                            child.layoutParams = clp
                        }

                        addLp.weight = 0f; addLp.width = addWidth

                        val newLp = newBtn.layoutParams as LinearLayout.LayoutParams
                        newLp.weight = 0f; newLp.width = 0
                        newLp.height = capturedHeight
                        newBtn.alpha = 0f
                        row.addView(newBtn, rowIdx)

                        updateRowVisibility()
                        updateSelection(state.selectedBandIndex)
                        updateClickListeners()

                        isAnimating = true
                        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 200
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener { anim ->
                                val f = anim.animatedFraction
                                val growWidth = (addWidth * f).toInt()
                                newLp.width = growWidth
                                newBtn.alpha = f
                                newBtn.requestLayout()
                                addLp.width = addWidth - growWidth
                                addBtnToRemove.alpha = 1f - f
                                addBtnToRemove.requestLayout()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    isAnimating = false
                                    row.removeView(addBtnToRemove)
                                    for (i in 0 until row.childCount) {
                                        val child = row.getChildAt(i) ?: continue
                                        val clp = child.layoutParams as LinearLayout.LayoutParams
                                        clp.weight = 1f; clp.width = 0
                                        clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                        child.alpha = 1f
                                        child.requestLayout()
                                    }
                                }
                            })
                            start()
                        }
                    } else {
                        row.addView(newBtn, rowIdx)
                        updateRowVisibility()
                        updateSelection(state.selectedBandIndex)
                        updateClickListeners()
                        animateButtonIn(newBtn, row)
                    }
                } else {
                    setupToggles()
                    updateSelection(state.selectedBandIndex)
                }
            }
        }

        state.saveState()
        onBandCountChanged()
    }

    fun removeBandAt(index: Int) {
        val eq = state.parametricEq
        if (eq.getBandCount() <= EqStateManager.MIN_BANDS) return

        // If another animation is in progress, skip animated transition
        if (isAnimating) {
            performRemoveBand(index)
            return
        }

        val bandCount = eq.getBandCount()
        val displayPos = state.displayToBandIndex.indexOf(index)

        if (displayPos >= 0) {
            val isLastBand = displayPos == bandCount - 1
            val bandRow = getRowForDisplay(displayPos)
            val addBtnRow = if (bandCount < EqStateManager.MAX_BANDS) getRowForDisplay(bandCount) else null
            // Crossfade if "+" is in a different row (or didn't exist) — row count stays same
            val crossfade = isLastBand && (addBtnRow != bandRow || bandCount == EqStateManager.MAX_BANDS)

            val row = bandRow
            val rowIdx = getRowIndex(displayPos)
            val btn = row.getChildAt(rowIdx)

            if (btn != null) {
                if (crossfade) {
                    // Dual animation: band shrinks out while "+" grows in
                    val capturedBandCount = bandCount

                    // Remove old "+" from its previous row (if it existed elsewhere)
                    if (capturedBandCount < EqStateManager.MAX_BANDS) {
                        val oldAddRow = getRowForDisplay(capturedBandCount)
                        val oldAddRowIdx = getRowIndex(capturedBandCount)
                        if (oldAddRowIdx < oldAddRow.childCount) {
                            oldAddRow.removeViewAt(oldAddRowIdx)
                        }
                    }

                    // Switch band button to explicit width
                    val lp = btn.layoutParams as LinearLayout.LayoutParams
                    val startWidth = btn.width
                    lp.height = btn.height
                    lp.weight = 0f; lp.width = startWidth; btn.layoutParams = lp

                    // Create "+" at width 0, insert after band button
                    val addBtn = createAddButton()
                    val addLp = addBtn.layoutParams as LinearLayout.LayoutParams
                    addLp.height = btn.height
                    addLp.weight = 0f; addLp.width = 0
                    addBtn.alpha = 0f
                    row.addView(addBtn, rowIdx + 1)

                    isAnimating = true
                    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            val f = it.animatedFraction
                            val growWidth = (startWidth * f).toInt()
                            addLp.width = growWidth
                            addBtn.alpha = f
                            addBtn.requestLayout()
                            lp.width = startWidth - growWidth
                            btn.alpha = 1f - f
                            btn.requestLayout()
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                isAnimating = false
                                row.removeView(btn)
                                addLp.weight = 1f; addLp.width = 0
                                addLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                addBtn.alpha = 1f
                                addBtn.requestLayout()
                                performRemoveBand(index, skipViewRebuild = true)
                            }
                        })
                        start()
                    }
                } else {
                    // Width animation for non-boundary cases
                    val needsRowReflow = displayPos < ROW_SIZE && bandCount >= ROW_SIZE

                    if (needsRowReflow) {
                        // Single-phase: shrink removed in row1 + grow moved button from row2,
                        // while row2's first child shrinks out — all simultaneously
                        val itemWidth = btn.width
                        val capturedHeight = btn.height
                        val capturedBandCount = bandCount
                        val needsAddBtn = capturedBandCount == EqStateManager.MAX_BANDS

                        // Lock all row1 buttons to explicit widths
                        for (i in 0 until toggleGroup.childCount) {
                            val child = toggleGroup.getChildAt(i) ?: continue
                            val clp = child.layoutParams as LinearLayout.LayoutParams
                            clp.height = capturedHeight
                            clp.weight = 0f
                            clp.width = child.width
                            child.layoutParams = clp
                        }

                        // Lock ALL row2 children to explicit widths
                        for (i in 0 until toggleGroup2.childCount) {
                            val child = toggleGroup2.getChildAt(i) ?: continue
                            val clp = child.layoutParams as LinearLayout.LayoutParams
                            clp.weight = 0f
                            clp.width = child.width
                            clp.height = child.height
                            child.layoutParams = clp
                        }

                        val row2First = toggleGroup2.getChildAt(0)
                        val row2FirstLp = row2First.layoutParams as LinearLayout.LayoutParams
                        val row2FirstWidth = row2First.width

                        // Create a fresh matching button at end of row1 at width 0
                        val isRow2FirstAdd = (row2First as? MaterialButton)?.text == "+"
                        val newBtn: View = if (isRow2FirstAdd) {
                            createAddButton()
                        } else {
                            createToggleButton(state.displayToBandIndex[ROW_SIZE])
                        }
                        val newLp = newBtn.layoutParams as LinearLayout.LayoutParams
                        newLp.height = capturedHeight
                        newLp.weight = 0f; newLp.width = 0
                        newBtn.alpha = 0f
                        toggleGroup.addView(newBtn)

                        // If at max bands, create "+" in row2 at width 0 to animate in
                        val addBtn: View? = if (needsAddBtn) {
                            val ab = createAddButton()
                            val abLp = ab.layoutParams as LinearLayout.LayoutParams
                            abLp.weight = 0f; abLp.width = 0
                            abLp.height = capturedHeight
                            ab.alpha = 0f
                            toggleGroup2.addView(ab)
                            ab
                        } else null
                        val addBtnLp = addBtn?.layoutParams as? LinearLayout.LayoutParams
                        val addBtnTargetWidth = row2FirstWidth

                        // Capture remaining row2 children for smooth resize during animation
                        val row2Remaining = mutableListOf<Triple<View, LinearLayout.LayoutParams, Int>>()
                        var row2ContentWidth = 0
                        for (i in 0 until toggleGroup2.childCount) {
                            val child = toggleGroup2.getChildAt(i) ?: continue
                            if (child == row2First || child == addBtn) continue
                            val clp = child.layoutParams as LinearLayout.LayoutParams
                            row2ContentWidth += clp.width
                            row2Remaining.add(Triple(child, clp, clp.width))
                        }
                        // row2First's width becomes available space for remaining children
                        row2ContentWidth += row2FirstWidth
                        val row2FinalChildCount = row2Remaining.size + (if (addBtn != null) 1 else 0)
                        val row2ChildTargetWidth = if (row2FinalChildCount > 0) row2ContentWidth / row2FinalChildCount else 0

                        val removedLp = btn.layoutParams as LinearLayout.LayoutParams

                        isAnimating = true
                        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 200
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener { anim ->
                                val f = anim.animatedFraction
                                // Row1: complementary widths to prevent rounding shift
                                val row1GrowWidth = (itemWidth * f).toInt()
                                newLp.width = row1GrowWidth
                                newBtn.alpha = f
                                newBtn.requestLayout()
                                removedLp.width = itemWidth - row1GrowWidth
                                btn.alpha = 1f - f
                                btn.requestLayout()
                                // Row2: shrink first child + grow "+" complementarily
                                val row2ShrinkAmount = (row2FirstWidth * f).toInt()
                                row2FirstLp.width = row2FirstWidth - row2ShrinkAmount
                                row2First.alpha = 1f - f
                                row2First.requestLayout()
                                if (addBtn != null && addBtnLp != null) {
                                    addBtnLp.width = (addBtnTargetWidth * f).toInt()
                                    addBtn.alpha = f
                                    addBtn.requestLayout()
                                }
                                // Row2: resize remaining children smoothly
                                for ((rChild, rClp, rStartW) in row2Remaining) {
                                    rClp.width = rStartW + ((row2ChildTargetWidth - rStartW) * f).toInt()
                                    rChild.requestLayout()
                                }
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    isAnimating = false
                                    toggleGroup.removeView(btn)
                                    toggleGroup2.removeView(row2First)
                                    for (i in 0 until toggleGroup.childCount) {
                                        val child = toggleGroup.getChildAt(i) ?: continue
                                        val clp = child.layoutParams as LinearLayout.LayoutParams
                                        clp.weight = 1f; clp.width = 0
                                        clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                        child.alpha = 1f
                                        child.requestLayout()
                                    }
                                    for (i in 0 until toggleGroup2.childCount) {
                                        val child = toggleGroup2.getChildAt(i) ?: continue
                                        val clp = child.layoutParams as LinearLayout.LayoutParams
                                        clp.weight = 1f; clp.width = 0
                                        clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                        child.alpha = 1f
                                        child.requestLayout()
                                    }
                                    performRemoveBand(index, skipViewRebuild = true)
                                    updateClickListeners()
                                }
                            })
                            start()
                        }
                    } else {
                        val lp = btn.layoutParams as LinearLayout.LayoutParams
                        val startWidth = btn.width
                        lp.height = btn.height
                        lp.weight = 0f; lp.width = startWidth; btn.layoutParams = lp

                        val wasFull = bandCount == EqStateManager.MAX_BANDS

                        // If at max bands, lock row and create "+" to grow simultaneously
                        val addBtn: View?
                        val addBtnLp: LinearLayout.LayoutParams?
                        if (wasFull) {
                            for (i in 0 until row.childCount) {
                                val child = row.getChildAt(i) ?: continue
                                if (child == btn) continue
                                val clp = child.layoutParams as LinearLayout.LayoutParams
                                clp.weight = 0f
                                clp.width = child.width
                                clp.height = child.height
                                child.layoutParams = clp
                            }
                            val ab = createAddButton()
                            val abLp = ab.layoutParams as LinearLayout.LayoutParams
                            abLp.weight = 0f; abLp.width = 0
                            abLp.height = btn.height
                            ab.alpha = 0f
                            row.addView(ab)
                            addBtn = ab
                            addBtnLp = abLp
                        } else {
                            addBtn = null
                            addBtnLp = null
                        }

                        isAnimating = true
                        android.animation.ValueAnimator.ofInt(startWidth, 0).apply {
                            duration = 200
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener {
                                val w = it.animatedValue as Int
                                lp.width = w; btn.requestLayout()
                                btn.alpha = (w.toFloat() / startWidth).coerceIn(0f, 1f)
                                if (addBtn != null && addBtnLp != null) {
                                    addBtnLp.width = startWidth - w
                                    addBtn.alpha = 1f - (w.toFloat() / startWidth).coerceIn(0f, 1f)
                                    addBtn.requestLayout()
                                }
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    isAnimating = false
                                    row.removeView(btn)
                                    if (wasFull) {
                                        for (i in 0 until row.childCount) {
                                            val child = row.getChildAt(i) ?: continue
                                            val clp = child.layoutParams as LinearLayout.LayoutParams
                                            clp.weight = 1f; clp.width = 0
                                            clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                            child.alpha = 1f
                                            child.requestLayout()
                                        }
                                    }
                                    performRemoveBand(index, skipViewRebuild = true)
                                    updateClickListeners()
                                }
                            })
                            start()
                        }
                    }
                }
                return
            }
        }
        performRemoveBand(index)
    }

    private fun performRemoveBand(index: Int, skipViewRebuild: Boolean = false) {
        val eq = state.parametricEq
        if (eq.getBandCount() <= EqStateManager.MIN_BANDS) return

        if (state.selectedBandIndex == index) {
            val newSelection = if (index > 0) index - 1 else if (eq.getBandCount() > 1) 0 else null
            state.selectedBandIndex = newSelection
            if (newSelection != null) {
                graphView.setActiveBand(newSelection)
                onBandSelected(newSelection)
            } else {
                graphView.clearActiveBand()
            }
        } else if (state.selectedBandIndex != null && state.selectedBandIndex!! > index) {
            state.selectedBandIndex = state.selectedBandIndex!! - 1
        }

        if (index < state.bandSlots.size) state.bandSlots.removeAt(index)
        eq.removeBand(index)
        state.displayToBandIndex = (0 until eq.getBandCount()).toList()

        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)
        graphView.setBandColors(state.bandColors)

        if (!skipViewRebuild) {
            setupToggles()
        }
        updateRowVisibility()
        updateSelection(state.selectedBandIndex)
        state.saveState()
        onBandCountChanged()
    }

    private fun updateClickListeners() {
        val eq = state.parametricEq
        for (displayPos in state.displayToBandIndex.indices) {
            val row = getRowForDisplay(displayPos)
            val rowIdx = getRowIndex(displayPos)
            val btn = row.getChildAt(rowIdx) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            val bandIdx = state.displayToBandIndex[displayPos]
            btn.setOnClickListener {
                graphView.setActiveBand(bandIdx)
                updateSelection(bandIdx)
                onBandSelected(bandIdx)
            }
            btn.setOnLongClickListener {
                if (eq.getBandCount() > EqStateManager.MIN_BANDS) {
                    removeBandAt(bandIdx)
                }
                true
            }
        }
    }

    fun updateTriangleIndicator(bandIndex: Int?) {
        if (bandIndex == null || bandIndex !in state.displayToBandIndex) {
            triangleIndicator.visibility = View.INVISIBLE
            return
        }
        val displayPos = state.displayToBandIndex.indexOf(bandIndex)
        val row = getRowForDisplay(displayPos)
        val rowIdx = getRowIndex(displayPos)
        val btn = row.getChildAt(rowIdx) ?: run {
            triangleIndicator.visibility = View.INVISIBLE
            return
        }
        triangleIndicator.visibility = View.VISIBLE
        val btnLoc = IntArray(2)
        btn.getLocationInWindow(btnLoc)
        val btnCenterX = btnLoc[0] + btn.width / 2f
        val containerLoc = IntArray(2)
        (triangleIndicator.parent as View).getLocationInWindow(containerLoc)
        val targetX = btnCenterX - containerLoc[0] - triangleIndicator.width / 2f
        triangleIndicator.animate().translationX(targetX).setDuration(150).start()
    }

    private fun createToggleButton(bandIdx: Int): MaterialButton {
        val eq = state.parametricEq
        val iconSize = (22 * activity.resources.displayMetrics.density).toInt()
        val slotLabel = if (bandIdx < state.bandSlots.size) state.bandSlots[bandIdx] + 1 else bandIdx + 1
        return MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "$slotLabel"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            cornerRadius = (12 * activity.resources.displayMetrics.density).toInt()
            textSize = 11f
            val vertPad = (6 * activity.resources.displayMetrics.density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            gravity = android.view.Gravity.CENTER
            rippleColor = android.content.res.ColorStateList.valueOf(0x33AAAAAA.toInt())
            compoundDrawablePadding = (1 * activity.resources.displayMetrics.density).toInt()

            val filterIcon = state.getFilterIconForBand(bandIdx)?.let {
                ContextCompat.getDrawable(activity, it)?.mutate()
            }
            filterIcon?.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawablesRelative(null, null, null, filterIcon)

            val enabled = eq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(this, enabled, bandIdx = bandIdx)

            setOnClickListener {
                graphView.setActiveBand(bandIdx)
                updateSelection(bandIdx)
                onBandSelected(bandIdx)
            }

            setOnLongClickListener {
                if (eq.getBandCount() > EqStateManager.MIN_BANDS) {
                    removeBandAt(bandIdx)
                }
                true
            }
        }
    }

    private fun createAddButton(): MaterialButton {
        return MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            cornerRadius = (12 * activity.resources.displayMetrics.density).toInt()
            textSize = 11f
            val vertPad = (6 * activity.resources.displayMetrics.density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0x00000000)
            setTextColor(0xFF888888.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener { addNewBand() }
        }
    }

    private fun animateButtonIn(btn: View, row: LinearLayout) {
        val lp = btn.layoutParams as LinearLayout.LayoutParams
        val siblingHeight = (0 until row.childCount)
            .mapNotNull { row.getChildAt(it)?.takeIf { v -> v != btn } }
            .firstOrNull()?.height ?: btn.height
        lp.height = siblingHeight
        lp.weight = 0f; lp.width = 0; btn.layoutParams = lp
        btn.alpha = 0f
        // Use a sibling's actual width as target (avoids margin-inclusive division)
        val siblingWidth = (0 until row.childCount)
            .mapNotNull { row.getChildAt(it)?.takeIf { v -> v != btn && v.width > 0 }?.width }
            .firstOrNull()
        val parentWidth = if (row.width > 0) row.width else toggleGroup.width
        val targetWidth = siblingWidth ?: (if (row.childCount > 0) parentWidth / row.childCount else parentWidth)
        android.animation.ValueAnimator.ofInt(0, targetWidth).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                lp.width = w; btn.requestLayout()
                btn.alpha = (w.toFloat() / targetWidth).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.weight = 1f; lp.width = 0
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    btn.alpha = 1f
                    btn.requestLayout()
                }
            })
            start()
        }
    }

    private fun updateToggleStyle(btn: MaterialButton, enabled: Boolean, selected: Boolean = false, bandIdx: Int = -1) {
        val density = activity.resources.displayMetrics.density
        val slotIdx = if (bandIdx >= 0 && bandIdx < state.bandSlots.size) state.bandSlots[bandIdx] else -1
        val bandColor = if (slotIdx >= 0) state.bandColors[slotIdx] else null

        if (selected && enabled) {
            btn.setBackgroundColor(bandColor ?: 0xFF777777.toInt())
            val isLight = bandColor != null && isColorLight(bandColor)
            btn.setTextColor(if (isLight) 0xFF222222.toInt() else 0xFFFFFFFF.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(bandColor ?: 0xFFBBBBBB.toInt())
            btn.strokeWidth = (2 * density).toInt()
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(if (isLight) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()) }
        } else if (enabled) {
            btn.setBackgroundColor(0xFF555555.toInt())
            btn.setTextColor(0xFFDDDDDD.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(bandColor ?: 0xFF888888.toInt())
            btn.strokeWidth = (if (bandColor != null) 2 else 1).let { (it * density).toInt() }
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(0xFFDDDDDD.toInt()) }
        } else {
            btn.setBackgroundColor(0x00000000)
            btn.setTextColor(0xFF555555.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(bandColor ?: 0xFF444444.toInt())
            btn.strokeWidth = (1 * density).toInt()
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(0xFF555555.toInt()) }
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.5
    }
}
