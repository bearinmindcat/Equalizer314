package com.bearinmind.equalizer314.state

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.ui.EqGraphView
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.R

class EqStateManager(
    private val context: Context,
    val eqPrefs: EqPreferencesManager
) {
    companion object {
        const val MAX_BANDS = 16
        const val MIN_BANDS = 1
        val COLOR_PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(),
            0xFF00BCD4.toInt(), 0xFF2196F3.toInt(), 0xFF7C4DFF.toInt(), 0xFFE91E63.toInt()
        )
    }

    var parametricEq: ParametricEqualizer = ParametricEqualizer()
        private set
    val bandSlots = mutableListOf<Int>()
    val bandColors = mutableMapOf<Int, Int>() // slot index → color int
    var selectedBandIndex: Int? = null
    var isProcessing = false
    var currentEqUiMode = EqUiMode.PARAMETRIC
    var displayToBandIndex = listOf<Int>()

    // Service binding
    var eqService: EqService? = null
    var serviceBound = false
    var pendingStartEq = false

    // Callbacks
    var onProcessingChanged: ((Boolean) -> Unit)? = null
    var onServiceConnected: (() -> Unit)? = null

    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as EqService.EqBinder).service
            eqService = service
            serviceBound = true
            if (pendingStartEq) {
                pendingStartEq = false
                onServiceConnected?.invoke()
            } else {
                isProcessing = service.dynamicsManager.isActive
                onProcessingChanged?.invoke(isProcessing)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            serviceBound = false
            isProcessing = false
            onProcessingChanged?.invoke(false)
        }
    }

    val allDefaultFrequencies: FloatArray by lazy {
        ParametricEqualizer.logSpacedFrequencies(MAX_BANDS)
    }

    fun initEq(graphView: EqGraphView) {
        parametricEq.isEnabled = true
        eqPrefs.restoreState(parametricEq)
        graphView.setParametricEqualizer(parametricEq)
        graphView.setBandSlotLabels(bandSlots)
        initBandSlots()
        bandColors.clear()
        bandColors.putAll(eqPrefs.getBandColors())
        graphView.setBandColors(bandColors)
    }

    fun initBandSlots() {
        bandSlots.clear()
        val eq = parametricEq
        val savedSlots = eqPrefs.getSavedSlots()
        if (savedSlots != null && savedSlots.size == eq.getBandCount()) {
            bandSlots.addAll(savedSlots)
            return
        }
        val defaults = allDefaultFrequencies
        val usedSlots = mutableSetOf<Int>()
        for (i in 0 until eq.getBandCount()) {
            val freq = eq.getBand(i)?.frequency ?: 0f
            val slot = defaults.indices
                .filter { it !in usedSlots }
                .minByOrNull { kotlin.math.abs(defaults[it] - freq) }
                ?: i
            bandSlots.add(slot)
            usedSlots.add(slot)
        }
    }

    fun pushEqUpdate() {
        if (!isProcessing) return
        eqService?.updateEq(parametricEq)
    }

    fun updateDpBandVisualization(graphView: EqGraphView) {
        val centers = ParametricToDpConverter.centerFrequencies
        val gains = ParametricToDpConverter.convert(parametricEq)
        graphView.updateDpBandData(centers, gains)
    }

    fun loadPreset(name: String, graphView: EqGraphView) {
        parametricEq.loadPreset(name)
        graphView.updateBandLevels()
        eqPrefs.savePresetName(name)
        updateDpBandVisualization(graphView)
        pushEqUpdate()
    }

    fun startProcessing(doStartEq: () -> Unit, animatePower: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(context, "DynamicsProcessing requires Android 9+", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // Caller handles permission request
                return
            }
        }

        animatePower(true)
        EqService.start(context)

        if (serviceBound) {
            doStartEq()
        } else {
            pendingStartEq = true
            val intent = Intent(context, EqService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun doStartEq(animatePower: (Boolean) -> Unit) {
        val service = eqService ?: return
        val started = service.startEq(parametricEq)
        isProcessing = started
        if (!started) {
            animatePower(false)
            Toast.makeText(context, "Failed to start DynamicsProcessing", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopProcessing(animatePower: (Boolean) -> Unit) {
        animatePower(false)
        EqService.stop(context)
        if (serviceBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        eqService = null
        isProcessing = false
    }

    fun getFilterIconRes(filterType: BiquadFilter.FilterType): Int {
        return when (filterType) {
            BiquadFilter.FilterType.BELL -> R.drawable.ic_filter_bell
            BiquadFilter.FilterType.LOW_SHELF -> R.drawable.ic_filter_low_shelf
            BiquadFilter.FilterType.HIGH_SHELF -> R.drawable.ic_filter_high_shelf
            BiquadFilter.FilterType.LOW_PASS -> R.drawable.ic_filter_low_pass
            BiquadFilter.FilterType.HIGH_PASS -> R.drawable.ic_filter_high_pass
        }
    }

    fun getFilterIconForBand(index: Int): Int? {
        val filterType = parametricEq.getBand(index)?.filterType ?: return null
        return getFilterIconRes(filterType)
    }

    fun saveState() {
        eqPrefs.saveState(parametricEq, bandSlots)
        eqPrefs.saveBandColors(bandColors)
    }
}
