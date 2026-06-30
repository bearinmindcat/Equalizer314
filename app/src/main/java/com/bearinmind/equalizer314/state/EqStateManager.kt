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
        /** Hard ceiling the band machinery is sized for (default-frequency
         *  table, slot indices). Stays fixed; the *user-facing* cap below can
         *  be raised up to this via the Experimental "Max EQ Bands" setting. */
        const val ABSOLUTE_MAX_BANDS = 64
        /** Current user-facing band cap. 16 by default; raised (up to
         *  [ABSOLUTE_MAX_BANDS]) by the experimental setting (issue #31). A var,
         *  not const, so it tracks the pref — set in [EqStateManager]'s init and
         *  live-updated by ExperimentalActivity. */
        var MAX_BANDS = 16
        const val MIN_BANDS = 1
        val COLOR_PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(),
            0xFF00BCD4.toInt(), 0xFF2196F3.toInt(), 0xFF7C4DFF.toInt(), 0xFFE91E63.toInt()
        )
    }

    enum class ActiveChannel { BOTH, LEFT, RIGHT }

    init {
        // Pick up the experimental band cap (issue #31) before any band UI is
        // built. Bounded to [16, ABSOLUTE_MAX_BANDS]; 16 for everyone who
        // hasn't opted in.
        MAX_BANDS = eqPrefs.getMaxEqBands().coerceIn(16, ABSOLUTE_MAX_BANDS)
    }

    // Query the device's actual audio output sample rate so the biquad
    // coefficients we compute match the rate DynamicsProcessing actually
    // runs at. Falling back to 48000 keeps things sensible if the
    // property is missing or unparsable.
    private val deviceSampleRate: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val raw = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val parsed = raw?.toIntOrNull()
        android.util.Log.d("EqStateManager", "Device output sample rate: $raw (using ${parsed ?: 48000})")
        parsed ?: 48000
    }

    // The three EQ instances backing per-channel editing. When Channel Side EQ
    // is off, only bothEq is used (applied to both channels). When Channel
    // Side EQ is on, leftEq goes to ch0 and rightEq goes to ch1, with
    // activeChannel deciding which one is the current editing target.
    private val bothEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)
    private val leftEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)
    private val rightEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)

    var parametricEq: ParametricEqualizer = bothEq
        private set

    var activeChannel: ActiveChannel = ActiveChannel.BOTH
        private set

    // Per-channel slot layouts. In Channel-Side-EQ mode left and right are
    // independent EQs that can hold different band counts, so each side needs
    // its own slot list — a single shared list let a band add on the shorter
    // channel compute an insert position past its end and crash (issue #50).
    // `bandSlots` follows `activeChannel` so all existing call sites keep
    // working unchanged; switching channels swaps which backing list they see.
    private val bothBandSlots = mutableListOf<Int>()
    private val leftBandSlots = mutableListOf<Int>()
    private val rightBandSlots = mutableListOf<Int>()
    val bandSlots: MutableList<Int>
        get() = when (activeChannel) {
            ActiveChannel.LEFT -> leftBandSlots
            ActiveChannel.RIGHT -> rightBandSlots
            ActiveChannel.BOTH -> bothBandSlots
        }
    val bandColors = mutableMapOf<Int, Int>() // slot index → color int
    var selectedBandIndex: Int? = null
    var isProcessing = false
    var currentEqUiMode = EqUiMode.PARAMETRIC
    var displayToBandIndex = listOf<Int>()

    // Preamp & auto-gain
    var preampGainDb: Float = 0f
    var autoGainEnabled: Boolean = false

    // Limiter — defaults match Wavelet's a6/z.java:105 baseline
    // (1 ms attack, 60 ms release, 10:1 ratio, −2 dB threshold, 0 dB
    // post-gain).
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 60f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -2f
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
        ParametricEqualizer.logSpacedFrequencies(ABSOLUTE_MAX_BANDS)
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
        // Rebuild every channel's slot list, not just the active one — the
        // non-active channel's list must always match its own band count or a
        // later channel switch + band add would desync and crash.
        rebuildSlots(bothBandSlots, bothEq, eqPrefs.getSavedSlots())
        rebuildSlots(leftBandSlots, leftEq, eqPrefs.getSavedLeftSlots())
        rebuildSlots(rightBandSlots, rightEq, eqPrefs.getSavedRightSlots())
    }

    /** Populate [target] so it has exactly one slot per band in [eq]. Uses
     *  [saved] when it matches the band count, otherwise falls back to a
     *  sequential 0,1,2,… layout (always valid, never out of range). */
    private fun rebuildSlots(target: MutableList<Int>, eq: ParametricEqualizer, saved: List<Int>?) {
        target.clear()
        if (saved != null && saved.size == eq.getBandCount()) {
            target.addAll(saved)
        } else {
            for (i in 0 until eq.getBandCount()) target.add(i)
        }
    }

    /** Trim every channel's EQ down to the current [MAX_BANDS] cap (issue #31).
     *  Called when the "Add more EQ bands" toggle is turned off — the bands
     *  added beyond the original 16 are dropped (highest indices first).
     *  Returns true if anything was removed. */
    fun enforceBandCap(): Boolean {
        var changed = false
        for (eq in listOf(bothEq, leftEq, rightEq)) {
            while (eq.getBandCount() > MAX_BANDS) {
                eq.removeBand(eq.getBandCount() - 1)
                changed = true
            }
        }
        if (changed) {
            val count = parametricEq.getBandCount()
            selectedBandIndex = selectedBandIndex?.coerceIn(0, (count - 1).coerceAtLeast(0))
            initBandSlots()
            saveState()
            if (isProcessing) pushEqUpdate()
        }
        return changed
    }

    fun pushEqUpdate() {
        // Mirror any "Both" band edits to the other channel before pushing, so
        // both channels stay in lockstep (issue #53). Runs even when not
        // processing so the in-memory twins are correct for the next save.
        syncBothBands()
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
            // Build each side's slot layout: a channel restored from prefs uses
            // its own saved slots; a freshly-forked channel inherits the shared
            // (bothEq) layout so the arrangement carries across the fork.
            rebuildSlots(leftBandSlots, leftEq, if (lOk) eqPrefs.getSavedLeftSlots() else bothBandSlots)
            rebuildSlots(rightBandSlots, rightEq, if (rOk) eqPrefs.getSavedRightSlots() else bothBandSlots)
            // Persist the now-authoritative L/R state (bands + slots) so it
            // survives restart.
            eqPrefs.saveLeftBands(leftEq, leftBandSlots)
            eqPrefs.saveRightBands(rightEq, rightBandSlots)
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
        // Flush "Both" edits from the channel we're leaving to its twins first,
        // so the switch can't sync the wrong direction (issue #53).
        syncBothBands()
        activeChannel = channel
        parametricEq = if (channel == ActiveChannel.LEFT) leftEq else rightEq
    }

    /** Returns the ParametricEqualizer to apply to ch0 (left) and ch1 (right)
     *  respectively. In BOTH mode both channels share the same EQ. */
    fun getChannelEqs(): Pair<ParametricEqualizer, ParametricEqualizer> =
        if (eqPrefs.getChannelSideEqEnabled()) Pair(leftEq, rightEq)
        else Pair(bothEq, bothEq)

    /** The EQ of the channel NOT currently being edited, for the dotted ghost
     *  curve (issue #53). Null when Channel Side EQ is off. */
    fun getInactiveChannelEq(): ParametricEqualizer? {
        if (!eqPrefs.getChannelSideEqEnabled()) return null
        return when (activeChannel) {
            ActiveChannel.LEFT -> rightEq
            ActiveChannel.RIGHT -> leftEq
            else -> null
        }
    }

    // ---- Per-band channel (L / R / Both) — issue #53 --------------------

    /** Channel tag of the active selected band (BOTH when out of range). */
    fun getBandChannel(index: Int): ParametricEqualizer.Channel =
        parametricEq.getBand(index)?.channel ?: ParametricEqualizer.Channel.BOTH

    /** Set the active band's channel. In CSE mode a BOTH band is mirrored as a
     *  synced twin in the other channel (same slot); L/R keep it on one channel
     *  only (moving it across if needed). Returns true if the band left the
     *  active channel (caller should refresh selection/UI). */
    fun setBandChannel(index: Int, channel: ParametricEqualizer.Channel): Boolean {
        if (!eqPrefs.getChannelSideEqEnabled()) return false
        if (activeChannel == ActiveChannel.BOTH) return false
        val band = parametricEq.getBand(index) ?: return false
        val slot = bandSlots.getOrNull(index) ?: return false
        val activeIsLeft = activeChannel == ActiveChannel.LEFT
        val otherEq = if (activeIsLeft) rightEq else leftEq
        val otherSlots = if (activeIsLeft) rightBandSlots else leftBandSlots
        var leftActive = false
        when (channel) {
            ParametricEqualizer.Channel.BOTH -> {
                band.channel = ParametricEqualizer.Channel.BOTH
                mirrorBandTo(otherEq, otherSlots, slot, band)
            }
            else -> {
                val belongsToActive =
                    (channel == ParametricEqualizer.Channel.LEFT && activeIsLeft) ||
                    (channel == ParametricEqualizer.Channel.RIGHT && !activeIsLeft)
                if (belongsToActive) {
                    band.channel = channel
                    removeBandAtSlot(otherEq, otherSlots, slot)   // drop the twin
                } else {
                    // Band belongs to the other channel only → move it there.
                    mirrorBandTo(otherEq, otherSlots, slot, band)
                    val j = otherSlots.indexOf(slot)
                    if (j >= 0) otherEq.getBand(j)?.channel = channel
                    parametricEq.removeBand(index)
                    if (index < bandSlots.size) bandSlots.removeAt(index)
                    leftActive = true
                }
            }
        }
        persistLeftRightIfCse()
        if (isProcessing) pushEqUpdate()
        return leftActive
    }

    /** Copy [src]'s params into [targetEq] at [slot] (creating the band there if
     *  absent), tagged BOTH — the synced twin of a "Both" band. */
    private fun mirrorBandTo(
        targetEq: ParametricEqualizer,
        targetSlots: MutableList<Int>,
        slot: Int,
        src: ParametricEqualizer.EqualizerBand,
    ) {
        val existing = targetSlots.indexOf(slot)
        if (existing >= 0) {
            targetEq.updateBand(existing, src.frequency, src.gain, src.filterType, src.q)
            targetEq.getBand(existing)?.let {
                it.enabled = src.enabled
                it.channel = ParametricEqualizer.Channel.BOTH
            }
        } else {
            val pos = targetSlots.indexOfFirst { it > slot }
                .let { if (it < 0) targetSlots.size else it }
                .coerceIn(0, targetEq.getBandCount())
            targetEq.insertBand(pos, src.frequency, src.gain, src.filterType, src.q)
            targetEq.getBand(pos)?.let {
                it.enabled = src.enabled
                it.channel = ParametricEqualizer.Channel.BOTH
            }
            targetSlots.add(pos, slot)
        }
    }

    private fun removeBandAtSlot(
        targetEq: ParametricEqualizer,
        targetSlots: MutableList<Int>,
        slot: Int,
    ) {
        val pos = targetSlots.indexOf(slot)
        if (pos >= 0) {
            targetEq.removeBand(pos)
            targetSlots.removeAt(pos)
        }
    }

    /** Keep every "Both" band in the active channel synced to its twin in the
     *  other channel (matched by slot), creating the twin if missing. Called
     *  before each persist/DP push so edits to a Both band mirror over. No-op
     *  outside CSE. */
    fun syncBothBands() {
        if (!eqPrefs.getChannelSideEqEnabled() || activeChannel == ActiveChannel.BOTH) return
        val activeIsLeft = activeChannel == ActiveChannel.LEFT
        val otherEq = if (activeIsLeft) rightEq else leftEq
        val otherSlots = if (activeIsLeft) rightBandSlots else leftBandSlots
        val activeSlots = bandSlots
        for (i in 0 until parametricEq.getBandCount()) {
            val b = parametricEq.getBand(i) ?: continue
            if (b.channel != ParametricEqualizer.Channel.BOTH) continue
            val slot = activeSlots.getOrNull(i) ?: continue
            mirrorBandTo(otherEq, otherSlots, slot, b)
        }
    }

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


    fun loadPreset(name: String, graphView: EqGraphView) {
        parametricEq.loadPreset(name)
        graphView.updateBandLevels()
        eqPrefs.savePresetName(name)
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
        // MBC topology has to be set BEFORE DP is constructed so the
        // right number of MBC bands gets allocated. The per-band
        // params (threshold, ratio, attack…) are pushed AFTER start
        // via applyPersistedMbcConfig — see the comment there.
        dm.mbcEnabled = eqPrefs.getMbcEnabled()
        dm.mbcBandCount = eqPrefs.getMbcBandCount()
        val started = service.startEq(parametricEq)
        isProcessing = started
        if (!started) {
            animatePower(false)
            Toast.makeText(context, "Failed to start DynamicsProcessing", Toast.LENGTH_SHORT).show()
            return
        }
        // Push the saved MBC band params + crossovers to the live DP.
        // Without this, MBC would say "on" in the UI but every band
        // would be at DynamicsProcessing's default (ratio=1, etc.) —
        // a no-op compressor that only "wakes up" when the user
        // touched a slider in MbcActivity. Fixes the report in the
        // MBC-zombie-state issue.
        service.applyPersistedMbcConfig()
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
        // Keep "Both" band twins in sync before persisting (issue #53).
        syncBothBands()
        // Don't pollute the "bands" pref with Simple-mode band data. In
        // Simple mode `parametricEq` holds the 10 fixed BELL bands —
        // writing them to "bands" would overwrite the user's advanced
        // EQ. The advanced EQ is preserved separately via
        // [eqPrefs.saveAdvancedEqBackup] when the user enters Simple
        // mode; that's the canonical source on next launch. Simple
        // gains have their own pref ("simpleEqGains") written by
        // [SimpleEqController.saveGains].
        if (currentEqUiMode != EqUiMode.SIMPLE) {
            eqPrefs.saveState(parametricEq, bandSlots)
        }
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
            eqPrefs.saveLeftBands(leftEq, leftBandSlots)
            eqPrefs.saveRightBands(rightEq, rightBandSlots)
        }
    }
}
