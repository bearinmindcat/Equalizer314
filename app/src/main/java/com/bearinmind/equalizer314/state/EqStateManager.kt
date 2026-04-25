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

    enum class ActiveChannel { BOTH, LEFT, RIGHT }

    // The three EQ instances backing per-channel editing. When Channel Side EQ
    // is off, only bothEq is used (applied to both channels). When Channel
    // Side EQ is on, leftEq goes to ch0 and rightEq goes to ch1, with
    // activeChannel deciding which one is the current editing target.
    private val bothEq: ParametricEqualizer = ParametricEqualizer()
    private val leftEq: ParametricEqualizer = ParametricEqualizer()
    private val rightEq: ParametricEqualizer = ParametricEqualizer()

    var parametricEq: ParametricEqualizer = bothEq
        private set

    var activeChannel: ActiveChannel = ActiveChannel.BOTH
        private set

    val bandSlots = mutableListOf<Int>()
    val bandColors = mutableMapOf<Int, Int>() // slot index → color int
    var selectedBandIndex: Int? = null
    var isProcessing = false
    var currentEqUiMode = EqUiMode.PARAMETRIC
    var displayToBandIndex = listOf<Int>()

    // Preamp & auto-gain
    var preampGainDb: Float = 0f
    var autoGainEnabled: Boolean = false

    // Limiter
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 50f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -0.5f
    var limiterPostGainDb: Float = 0f

    // Channel Side Options
    var channelBalancePercent: Int = 0
    var leftChannelGainDb: Float = 0f
    var rightChannelGainDb: Float = 0f

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
            android.util.Log.d("EqStateManager", "onServiceConnected: pendingStartEq=$pendingStartEq isActive=${service.dynamicsManager.isActive}")
            if (pendingStartEq) {
                pendingStartEq = false
                android.util.Log.d("EqStateManager", "Calling doStartEq via onServiceConnected callback!")
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
        bothEq.isEnabled = true
        eqPrefs.restoreState(bothEq)
        // If the user left Channel Side EQ on, try to restore `leftEq` and
        // `rightEq` from their own prefs so a session's L/R divergence
        // survives a process restart. When those prefs don't exist (first
        // time CSE has been enabled, or fresh install) fall back to forking
        // `bothEq` into both. Either way, activate LEFT as the editing target.
        if (eqPrefs.getChannelSideEqEnabled()) {
            val lOk = eqPrefs.restoreLeftBands(leftEq)
            val rOk = eqPrefs.restoreRightBands(rightEq)
            if (!lOk) copyEqState(bothEq, leftEq)
            if (!rOk) copyEqState(bothEq, rightEq)
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
        } else {
            activeChannel = ActiveChannel.BOTH
            parametricEq = bothEq
        }
        graphView.setParametricEqualizer(parametricEq)
        graphView.setBandSlotLabels(bandSlots)
        initBandSlots()
        bandColors.clear()
        bandColors.putAll(eqPrefs.getBandColors())
        graphView.setBandColors(bandColors)

        // Restore preamp & auto-gain
        preampGainDb = eqPrefs.getPreampGain()
        autoGainEnabled = eqPrefs.getAutoGainEnabled()

        // Restore channel side options
        channelBalancePercent = eqPrefs.getChannelBalancePercent()
        leftChannelGainDb = eqPrefs.getLeftChannelGainDb()
        rightChannelGainDb = eqPrefs.getRightChannelGainDb()

        // Restore limiter
        limiterEnabled = eqPrefs.getLimiterEnabled()
        limiterAttackMs = eqPrefs.getLimiterAttack()
        limiterReleaseMs = eqPrefs.getLimiterRelease()
        limiterRatio = eqPrefs.getLimiterRatio()
        limiterThresholdDb = eqPrefs.getLimiterThreshold()
        limiterPostGainDb = eqPrefs.getLimiterPostGain()
    }

    fun initBandSlots() {
        bandSlots.clear()
        val eq = parametricEq
        val savedSlots = eqPrefs.getSavedSlots()
        if (savedSlots != null && savedSlots.size == eq.getBandCount()) {
            bandSlots.addAll(savedSlots)
            return
        }
        // Default: sequential slots 0, 1, 2, ...
        for (i in 0 until eq.getBandCount()) {
            bandSlots.add(i)
        }
    }

    fun pushEqUpdate() {
        if (!isProcessing) return
        val dm = eqService?.dynamicsManager ?: return
        dm.preampGainDb = preampGainDb
        dm.autoGainEnabled = autoGainEnabled
        dm.channelBalancePercent = channelBalancePercent
        dm.leftChannelGainDb = leftChannelGainDb
        dm.rightChannelGainDb = rightChannelGainDb
        val (lEq, rEq) = getChannelEqs()
        eqService?.updateEqPerChannel(lEq, rEq)
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updatePending = false
    private val flushUpdate = Runnable {
        updatePending = false
        pushEqUpdate()
    }

    /** Coalesce rapid-fire EQ updates (e.g. graph-dot drag) into at most one
     *  DP write per frame. Each ACTION_MOVE only schedules a flush if one
     *  isn't already queued; the flush reads the latest in-memory EQ state.
     *  Without this, a 60+ Hz drag stream blocks the audio thread with one
     *  full DP-band rewrite per touch event. Call [flushEqUpdate] on the
     *  drag-end (ACTION_UP) so the final committed state lands immediately. */
    fun pushEqUpdateThrottled() {
        if (!isProcessing) return
        if (updatePending) return
        updatePending = true
        updateHandler.postDelayed(flushUpdate, 16L)
    }

    /** Cancel any queued throttled update and push the current state now.
     *  Used at drag-end so the final value is committed without a frame of
     *  latency. */
    fun flushEqUpdate() {
        if (updatePending) {
            updateHandler.removeCallbacks(flushUpdate)
            updatePending = false
        }
        pushEqUpdate()
    }

    /** Copy one EQ's band state into another. Used when forking the shared
     *  "both" EQ into the per-channel L/R editors. */
    private fun copyEqState(from: ParametricEqualizer, to: ParametricEqualizer) {
        to.clearBands()
        val count = from.getBandCount()
        for (i in 0 until count) {
            val b = from.getBand(i) ?: continue
            to.addBand(b.frequency, b.gain, b.filterType, b.q)
            to.setBandEnabled(i, b.enabled)
        }
        to.isEnabled = from.isEnabled
    }

    /** Called when the Channel Side EQ switch flips. On enable we fork the
     *  current shared EQ into leftEq / rightEq (so they start identical to
     *  what the user had) and activate L as the default editing target.
     *  On disable we flip back to the shared "both" EQ. */
    fun setChannelSideEqEnabled(enabled: Boolean) {
        if (enabled) {
            // Prefer prior L/R divergence when the prefs carry it (e.g. user
            // flipped CSE off + back on without loading a fresh preset). Fall
            // back to forking from the current active EQ when either pref is
            // absent, which is the first-time-enabled / fresh-install path.
            val lOk = eqPrefs.restoreLeftBands(leftEq)
            val rOk = eqPrefs.restoreRightBands(rightEq)
            if (!lOk || !rOk) {
                val source = parametricEq
                if (!lOk && source !== leftEq) copyEqState(source, leftEq)
                if (!rOk && source !== rightEq) copyEqState(source, rightEq)
            }
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
            // Persist the now-authoritative L/R state so it survives restart.
            eqPrefs.saveLeftBands(leftEq)
            eqPrefs.saveRightBands(rightEq)
        } else {
            activeChannel = ActiveChannel.BOTH
            parametricEq = bothEq
        }
    }

    /** Switch the active editing channel while Channel Side EQ is on.
     *  No-op when CSE is off or the channel is already active. */
    fun setActiveChannel(channel: ActiveChannel) {
        if (!eqPrefs.getChannelSideEqEnabled()) return
        if (channel == ActiveChannel.BOTH) return   // BOTH is only reachable via CSE off
        if (channel == activeChannel) return
        activeChannel = channel
        parametricEq = if (channel == ActiveChannel.LEFT) leftEq else rightEq
    }

    /** Returns the ParametricEqualizer to apply to ch0 (left) and ch1 (right)
     *  respectively. In BOTH mode both channels share the same EQ. */
    fun getChannelEqs(): Pair<ParametricEqualizer, ParametricEqualizer> =
        if (eqPrefs.getChannelSideEqEnabled()) Pair(leftEq, rightEq)
        else Pair(bothEq, bothEq)

    /** Minimal structure for a preset's band list, shared between the
     *  preset-save / preset-load / APO-round-trip paths. */
    data class BandSpec(
        val frequency: Float,
        val gain: Float,
        val q: Double,
        val filterType: BiquadFilter.FilterType,
        val enabled: Boolean = true,
    )

    /** Replace the in-memory EQ state from a parsed preset.
     *  - `cseEnabled == false`: load [bothBands] into `bothEq`, point
     *    `parametricEq` at it, activeChannel = BOTH.
     *  - `cseEnabled == true`: load [leftBands] into `leftEq` and
     *    [rightBands] into `rightEq`, activeChannel = LEFT, point
     *    `parametricEq` at `leftEq`. The CSE pref is also persisted so a
     *    subsequent `getChannelSideEqEnabled()` matches what was loaded. */
    fun applyPresetEqs(
        cseEnabled: Boolean,
        bothBands: List<BandSpec>,
        leftBands: List<BandSpec>,
        rightBands: List<BandSpec>,
    ) {
        eqPrefs.saveChannelSideEqEnabled(cseEnabled)
        if (cseEnabled) {
            loadBandsInto(leftEq, leftBands)
            loadBandsInto(rightEq, rightBands)
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
            // Persist the freshly-loaded L / R bands under their own prefs
            // keys so a subsequent process restart keeps the divergence.
            eqPrefs.saveLeftBands(leftEq)
            eqPrefs.saveRightBands(rightEq)
        } else {
            loadBandsInto(bothEq, bothBands)
            activeChannel = ActiveChannel.BOTH
            parametricEq = bothEq
            // Wipe any stale per-channel prefs so re-enabling CSE forks from
            // the newly-loaded bothEq instead of resurrecting old divergence.
            eqPrefs.clearLeftRightBands()
        }
    }

    private fun loadBandsInto(eq: ParametricEqualizer, bands: List<BandSpec>) {
        eq.clearBands()
        for ((i, b) in bands.withIndex()) {
            eq.addBand(b.frequency, b.gain, b.filterType, b.q)
            eq.setBandEnabled(i, b.enabled)
        }
        eq.isEnabled = true
    }

    /** Apply only channel-side-options changes (balance / per-channel preamp)
     *  without recomputing the EQ curve. Cheap enough to call on every slider step. */
    fun pushChannelSettingsUpdate() {
        if (!isProcessing) return
        val dm = eqService?.dynamicsManager ?: return
        dm.channelBalancePercent = channelBalancePercent
        dm.leftChannelGainDb = leftChannelGainDb
        dm.rightChannelGainDb = rightChannelGainDb
        dm.updateChannelSettings()
    }

    fun pushLimiterUpdate() {
        if (!isProcessing) return
        val dm = eqService?.dynamicsManager ?: return
        dm.limiterEnabled = limiterEnabled
        dm.limiterAttackMs = limiterAttackMs
        dm.limiterReleaseMs = limiterReleaseMs
        dm.limiterRatio = limiterRatio
        dm.limiterThresholdDb = limiterThresholdDb
        dm.limiterPostGainDb = limiterPostGainDb
        dm.updateLimiter()
    }

    fun getAutoGainOffset(): Float {
        return eqService?.dynamicsManager?.lastAutoGainOffset ?: 0f
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
        // Sync all DSP params before starting
        val dm = service.dynamicsManager
        dm.preampGainDb = preampGainDb
        dm.autoGainEnabled = autoGainEnabled
        dm.channelBalancePercent = channelBalancePercent
        dm.leftChannelGainDb = leftChannelGainDb
        dm.rightChannelGainDb = rightChannelGainDb
        dm.limiterEnabled = limiterEnabled
        dm.limiterAttackMs = limiterAttackMs
        dm.limiterReleaseMs = limiterReleaseMs
        dm.limiterRatio = limiterRatio
        dm.limiterThresholdDb = limiterThresholdDb
        dm.limiterPostGainDb = limiterPostGainDb
        val started = service.startEq(parametricEq)
        isProcessing = started
        if (!started) {
            animatePower(false)
            Toast.makeText(context, "Failed to start DynamicsProcessing", Toast.LENGTH_SHORT).show()
            return
        }
        // If Channel Side EQ is on, fan out the distinct L/R responses now
        // that DP is live.
        if (eqPrefs.getChannelSideEqEnabled()) {
            val (lEq, rEq) = getChannelEqs()
            if (lEq !== rEq) service.updateEqPerChannel(lEq, rEq)
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
            BiquadFilter.FilterType.LOW_SHELF_1 -> R.drawable.ic_filter_low_shelf_6
            BiquadFilter.FilterType.HIGH_SHELF -> R.drawable.ic_filter_high_shelf
            BiquadFilter.FilterType.HIGH_SHELF_1 -> R.drawable.ic_filter_high_shelf_6
            BiquadFilter.FilterType.LOW_PASS -> R.drawable.ic_filter_low_pass
            BiquadFilter.FilterType.LOW_PASS_1 -> R.drawable.ic_filter_low_pass_6
            BiquadFilter.FilterType.HIGH_PASS -> R.drawable.ic_filter_high_pass
            BiquadFilter.FilterType.HIGH_PASS_1 -> R.drawable.ic_filter_high_pass_6
            BiquadFilter.FilterType.BAND_PASS -> R.drawable.ic_filter_band_pass
            BiquadFilter.FilterType.NOTCH -> R.drawable.ic_filter_notch
            BiquadFilter.FilterType.ALL_PASS -> R.drawable.ic_filter_bypass
        }
    }

    fun getFilterIconForBand(index: Int): Int? {
        val filterType = parametricEq.getBand(index)?.filterType ?: return null
        return getFilterIconRes(filterType)
    }

    fun saveState() {
        eqPrefs.saveState(parametricEq, bandSlots)
        persistLeftRightIfCse()
        eqPrefs.saveBandColors(bandColors)
        eqPrefs.savePreampGain(preampGainDb)
        eqPrefs.saveAutoGainEnabled(autoGainEnabled)
        eqPrefs.saveLimiterEnabled(limiterEnabled)
        eqPrefs.saveLimiterAttack(limiterAttackMs)
        eqPrefs.saveLimiterRelease(limiterReleaseMs)
        eqPrefs.saveLimiterRatio(limiterRatio)
        eqPrefs.saveLimiterThreshold(limiterThresholdDb)
        eqPrefs.saveLimiterPostGain(limiterPostGainDb)
    }

    /** Mirror the active-EQ save so L/R divergence survives an app restart.
     *  Safe to call even when CSE is off — becomes a no-op. Callsites that
     *  save the active EQ directly via `eqPrefs.saveState(parametricEq, ...)`
     *  should also invoke this so the non-active channel's state isn't lost. */
    fun persistLeftRightIfCse() {
        if (eqPrefs.getChannelSideEqEnabled()) {
            eqPrefs.saveLeftBands(leftEq)
            eqPrefs.saveRightBands(rightEq)
        }
    }
}
