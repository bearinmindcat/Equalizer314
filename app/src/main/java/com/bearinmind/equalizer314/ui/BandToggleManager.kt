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
    private val triangleIndicator: View,
    private val graphView: EqGraphView,
    private val state: EqStateManager,
    private val onEqChanged: () -> Unit,
    private val onBandCountChanged: () -> Unit,
    private val onBandSelected: (Int?) -> Unit
) {

    fun setupToggles() {
        val savedTransition = toggleGroup.layoutTransition
        toggleGroup.layoutTransition = null
        toggleGroup.removeAllViews()

        val eq = state.parametricEq
        val bandCount = eq.getBandCount()

        val orderedIndices = if (state.currentEqUiMode == EqUiMode.GRAPHIC) {
            (0 until bandCount).sortedBy { eq.getBand(it)?.frequency ?: 0f }
        } else {
            (0 until bandCount).toList()
        }
        state.displayToBandIndex = orderedIndices

        for (bandIdx in orderedIndices) {
            toggleGroup.addView(createToggleButton(bandIdx))
        }
        if (bandCount < EqStateManager.MAX_BANDS) {
            toggleGroup.addView(createAddButton())
        }
        toggleGroup.layoutTransition = savedTransition
    }

    fun updateSelection(bandIndex: Int?) {
        state.selectedBandIndex = bandIndex
        for (displayPos in state.displayToBandIndex.indices) {
            val btn = toggleGroup.getChildAt(displayPos) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            val bandIdx = state.displayToBandIndex[displayPos]
            val enabled = state.parametricEq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled, selected = (bandIdx == bandIndex))
        }
        updateTriangleIndicator(bandIndex)
    }

    fun updateIcons() {
        val eq = state.parametricEq
        val iconSize = (22 * activity.resources.displayMetrics.density).toInt()
        for (bandIdx in 0 until eq.getBandCount()) {
            val btn = toggleGroup.getChildAt(bandIdx) as? MaterialButton ?: continue
            val filterIcon = state.getFilterIconForBand(bandIdx)?.let {
                ContextCompat.getDrawable(activity, it)?.mutate()
            }
            filterIcon?.setBounds(0, 0, iconSize, iconSize)
            btn.setCompoundDrawablesRelative(null, null, null, filterIcon)
            val enabled = eq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled)
        }
    }

    fun addNewBand() {
        val eq = state.parametricEq
        if (eq.getBandCount() >= EqStateManager.MAX_BANDS) return

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

        removeAddButton()
        rebuildLabels(skipBandIndex = insertPos)

        val btn = createToggleButton(insertPos)
        val lp = btn.layoutParams as LinearLayout.LayoutParams
        lp.weight = 0f
        btn.alpha = 0f
        toggleGroup.addView(btn, insertPos)

        if (eq.getBandCount() < EqStateManager.MAX_BANDS) {
            toggleGroup.addView(createAddButton())
        }

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                (btn.layoutParams as LinearLayout.LayoutParams).weight = v
                btn.alpha = v
                btn.requestLayout()
                updateTriangleIndicator(state.selectedBandIndex)
            }
            start()
        }

        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)
        updateTriangleIndicator(state.selectedBandIndex)
        state.saveState()
        onBandCountChanged()
    }

    fun removeBandAt(index: Int) {
        val eq = state.parametricEq
        if (eq.getBandCount() <= EqStateManager.MIN_BANDS) return

        if (state.selectedBandIndex == index) {
            state.selectedBandIndex = null
            graphView.clearActiveBand()
        } else if (state.selectedBandIndex != null && state.selectedBandIndex!! > index) {
            state.selectedBandIndex = state.selectedBandIndex!! - 1
        }

        val displayPos = state.displayToBandIndex.indexOf(index)
        val btnToRemove = if (displayPos >= 0 && displayPos < toggleGroup.childCount)
            toggleGroup.getChildAt(displayPos) else null

        removeAddButton()
        if (index < state.bandSlots.size) state.bandSlots.removeAt(index)
        eq.removeBand(index)
        state.displayToBandIndex = (0 until eq.getBandCount()).toList()
        rebuildLabelsSkipping(btnToRemove)
        if (eq.getBandCount() < EqStateManager.MAX_BANDS) {
            toggleGroup.addView(createAddButton())
        }
        graphView.setParametricEqualizer(eq)
        graphView.setBandSlotLabels(state.bandSlots)
        updateTriangleIndicator(state.selectedBandIndex)
        state.saveState()
        onBandCountChanged()

        if (btnToRemove != null) {
            (btnToRemove as? MaterialButton)?.let {
                it.text = ""
                it.setCompoundDrawablesRelative(null, null, null, null)
                it.minimumWidth = 0
                it.setOnClickListener(null)
                it.setOnLongClickListener(null)
            }
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 250
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    val lp = btnToRemove.layoutParams as LinearLayout.LayoutParams
                    lp.weight = v
                    lp.setMargins((2 * v).toInt(), 0, (2 * v).toInt(), 0)
                    btnToRemove.layoutParams = lp
                    btnToRemove.alpha = v
                    updateTriangleIndicator(state.selectedBandIndex)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        toggleGroup.removeView(btnToRemove)
                    }
                })
                start()
            }
        }
    }

    fun updateTriangleIndicator(bandIndex: Int?) {
        if (bandIndex == null || bandIndex !in state.displayToBandIndex) {
            triangleIndicator.visibility = View.INVISIBLE
            return
        }
        val displayPos = state.displayToBandIndex.indexOf(bandIndex)
        val btn = toggleGroup.getChildAt(displayPos) ?: run {
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
            updateToggleStyle(this, enabled)

            setOnClickListener {
                val nowEnabled = eq.getBand(bandIdx)?.enabled != false
                val newState = !nowEnabled
                eq.setBandEnabled(bandIdx, newState)

                graphView.setActiveBand(bandIdx)
                updateSelection(bandIdx)
                onBandSelected(bandIdx)

                updateToggleStyle(this, newState, selected = true)
                graphView.updateBandLevels()
                graphView.invalidate()
                onEqChanged()
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

    private fun removeAddButton() {
        val last = toggleGroup.getChildAt(toggleGroup.childCount - 1)
        if (last is MaterialButton && last.text == "+") {
            toggleGroup.removeView(last)
        }
    }

    private fun rebuildLabels(skipBandIndex: Int = -1) {
        val eq = state.parametricEq
        val iconSize = (22 * activity.resources.displayMetrics.density).toInt()
        var bandIdx = 0
        for (viewPos in 0 until toggleGroup.childCount) {
            val btn = toggleGroup.getChildAt(viewPos) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            if (bandIdx == skipBandIndex) bandIdx++
            if (bandIdx >= eq.getBandCount()) break

            val slotLabel = if (bandIdx < state.bandSlots.size) state.bandSlots[bandIdx] + 1 else bandIdx + 1
            btn.text = "$slotLabel"
            val filterIcon = state.getFilterIconForBand(bandIdx)?.let {
                ContextCompat.getDrawable(activity, it)?.mutate()
            }
            filterIcon?.setBounds(0, 0, iconSize, iconSize)
            btn.setCompoundDrawablesRelative(null, null, null, filterIcon)
            val enabled = eq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled, selected = (bandIdx == state.selectedBandIndex))

            val capturedIdx = bandIdx
            btn.setOnClickListener {
                val nowEnabled = eq.getBand(capturedIdx)?.enabled != false
                val newState = !nowEnabled
                eq.setBandEnabled(capturedIdx, newState)
                graphView.setActiveBand(capturedIdx)
                updateSelection(capturedIdx)
                onBandSelected(capturedIdx)
                updateToggleStyle(btn, newState, selected = true)
                graphView.updateBandLevels()
                graphView.invalidate()
                onEqChanged()
            }
            btn.setOnLongClickListener {
                if (eq.getBandCount() > EqStateManager.MIN_BANDS) removeBandAt(capturedIdx)
                true
            }
            bandIdx++
        }
    }

    private fun rebuildLabelsSkipping(deadButton: View?) {
        val eq = state.parametricEq
        val iconSize = (22 * activity.resources.displayMetrics.density).toInt()
        var bandIdx = 0
        for (viewPos in 0 until toggleGroup.childCount) {
            val child = toggleGroup.getChildAt(viewPos)
            if (child === deadButton) continue
            val btn = child as? MaterialButton ?: continue
            if (btn.text == "+") continue
            if (bandIdx >= eq.getBandCount()) break

            val slotLabel = if (bandIdx < state.bandSlots.size) state.bandSlots[bandIdx] + 1 else bandIdx + 1
            btn.text = "$slotLabel"
            val filterIcon = state.getFilterIconForBand(bandIdx)?.let {
                ContextCompat.getDrawable(activity, it)?.mutate()
            }
            filterIcon?.setBounds(0, 0, iconSize, iconSize)
            btn.setCompoundDrawablesRelative(null, null, null, filterIcon)
            val enabled = eq.getBand(bandIdx)?.enabled != false
            updateToggleStyle(btn, enabled, selected = (bandIdx == state.selectedBandIndex))

            val capturedIdx = bandIdx
            btn.setOnClickListener {
                val nowEnabled = eq.getBand(capturedIdx)?.enabled != false
                val newState = !nowEnabled
                eq.setBandEnabled(capturedIdx, newState)
                graphView.setActiveBand(capturedIdx)
                updateSelection(capturedIdx)
                onBandSelected(capturedIdx)
                updateToggleStyle(btn, newState, selected = true)
                graphView.updateBandLevels()
                graphView.invalidate()
                onEqChanged()
            }
            btn.setOnLongClickListener {
                if (eq.getBandCount() > EqStateManager.MIN_BANDS) removeBandAt(capturedIdx)
                true
            }
            bandIdx++
        }
    }

    private fun updateToggleStyle(btn: MaterialButton, enabled: Boolean, selected: Boolean = false) {
        val density = activity.resources.displayMetrics.density
        if (selected && enabled) {
            btn.setBackgroundColor(0xFF777777.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFFBBBBBB.toInt())
            btn.strokeWidth = (2 * density).toInt()
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(0xFFFFFFFF.toInt()) }
        } else if (enabled) {
            btn.setBackgroundColor(0xFF555555.toInt())
            btn.setTextColor(0xFFDDDDDD.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            btn.strokeWidth = (1 * density).toInt()
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(0xFFDDDDDD.toInt()) }
        } else {
            btn.setBackgroundColor(0x00000000)
            btn.setTextColor(0xFF555555.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            btn.strokeWidth = (1 * density).toInt()
            btn.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(0xFF555555.toInt()) }
        }
    }
}
