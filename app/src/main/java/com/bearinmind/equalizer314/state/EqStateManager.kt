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
        /** Fixed hard ceiling the band machinery is sized for (default-frequency table, slot
         *  indices); the user-facing cap below can be raised up to this. */
        const val ABSOLUTE_MAX_BANDS = 64
        /** User-facing band cap: 16 default, raised up to [ABSOLUTE_MAX_BANDS] by the Experimental
         *  "Max EQ Bands" setting (issue #31). var so it tracks the pref — set in init,
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
        // Experimental band cap (issue #31), bounded [16, ABSOLUTE_MAX_BANDS]; must load before band UI is built
        MAX_BANDS = eqPrefs.getMaxEqBands().coerceIn(16, ABSOLUTE_MAX_BANDS)
    }

    // Device output sample rate so biquad coefficients match the rate DynamicsProcessing
    // actually runs at; 48000 fallback if the property is missing/unparsable.
    private val deviceSampleRate: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val raw = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val parsed = raw?.toIntOrNull()
        android.util.Log.d("EqStateManager", "Device output sample rate: $raw (using ${parsed ?: 48000})")
        parsed ?: 48000
    }

    // Per-channel editing EQs: CSE off → bothEq on both channels; CSE on → leftEq to ch0,
    // rightEq to ch1, activeChannel picks the editing target.
    private val bothEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)
    private val leftEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)
    private val rightEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)
    // CSE shared "Both" layer: its OWN curve (flat until edited) applied on top of BOTH channels —
    // final L = leftEq + sharedEq, final R = rightEq + sharedEq (summed by the DP converter overlay).
    private val sharedEq: ParametricEqualizer = ParametricEqualizer(deviceSampleRate)

    var parametricEq: ParametricEqualizer = bothEq
        private set

    var activeChannel: ActiveChannel = ActiveChannel.BOTH
        private set

    // Per-channel slot layouts: in CSE mode L/R can hold different band counts, so each side needs
    // its own list — a single shared list let a band add on the shorter channel compute an insert
    // position past its end and crash (issue #50). `bandSlots` follows `activeChannel` so call sites work unchanged.
    private val bothBandSlots = mutableListOf<Int>()
    private val leftBandSlots = mutableListOf<Int>()
    private val rightBandSlots = mutableListOf<Int>()
    private val sharedBandSlots = mutableListOf<Int>()
    val bandSlots: MutableList<Int>
        get() = when {
            bothViewActive -> sharedBandSlots
            activeChannel == ActiveChannel.LEFT -> leftBandSlots
            activeChannel == ActiveChannel.RIGHT -> rightBandSlots
            else -> bothBandSlots
        }
    val bandColors = mutableMapOf<Int, Int>() // slot index → color int
    var selectedBandIndex: Int? = null
    var isProcessing = false
    var currentEqUiMode = EqUiMode.PARAMETRIC
    var displayToBandIndex = listOf<Int>()

    // Preamp & auto-gain: with CSE on, each side's preamp (preampLeftDb/preampRightDb) applies
    // to its DP channel; preampGainDb is the single shared preamp when CSE is off.
    var preampGainDb: Float = 0f
    var preampLeftDb: Float = 0f
    var preampRightDb: Float = 0f
    var autoGainEnabled: Boolean = false

    /** CSE "Both" edit view: graph shows the SHARED layer applied on top of both channels
     *  (final L/R = channel EQ + shared). Entered/exited via the Both button next to L/R;
     *  per-band tethering via the band popup is unaffected. */
    var bothViewActive = false
        private set

    /** Reset the shared layer to a flat, editable default (4 bands @ 0 dB). */
    private fun resetSharedEq() {
        sharedEq.clearBands()
        val f = ParametricEqualizer.logSpacedFrequencies(16)
        for (i in 0..3) sharedEq.addBand(f[i], 0f, BiquadFilter.FilterType.BELL)
        sharedEq.isEnabled = true
        rebuildSlots(sharedBandSlots, sharedEq, null)
    }

    fun enterBothView() {
        if (!eqPrefs.getChannelSideEqEnabled()) return
        if (sharedEq.getBandCount() == 0) resetSharedEq()
        bothViewActive = true
        parametricEq = sharedEq
    }

    fun exitBothView() {
        if (!bothViewActive) return
        bothViewActive = false
        parametricEq = if (activeChannel == ActiveChannel.RIGHT) rightEq else leftEq
    }

    /** The preamp value belonging to the current view: shared preamp when
     *  CSE is off, the active side's preamp otherwise. */
    fun getActivePreamp(): Float = when {
        !eqPrefs.getChannelSideEqEnabled() -> preampGainDb
        bothViewActive -> preampLeftDb
        activeChannel == ActiveChannel.RIGHT -> preampRightDb
        else -> preampLeftDb
    }

    /** Write the preamp for the current view. In the Both view the value is
     *  applied to BOTH sides (one slider gesture, both channels). */
    fun setActivePreamp(v: Float) {
        when {
            !eqPrefs.getChannelSideEqEnabled() -> preampGainDb = v
            bothViewActive -> { preampLeftDb = v; preampRightDb = v }
            activeChannel == ActiveChannel.RIGHT -> preampRightDb = v
            else -> preampLeftDb = v
        }
    }

    // Limiter — defaults match Wavelet's a6/z.java:105 baseline
    // (1 ms attack, 60 ms release, 10:1 ratio, −2 dB threshold, 0 dB post-gain).
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
        // With CSE on, restore leftEq/rightEq from their own prefs so L/R divergence survives a
        // restart; missing prefs (first CSE enable / fresh install) fork bothEq into both. Either way, LEFT becomes the editing target.
        if (eqPrefs.getChannelSideEqEnabled()) {
            val lOk = eqPrefs.restoreLeftBands(leftEq)
            val rOk = eqPrefs.restoreRightBands(rightEq)
            if (!lOk) { copyEqState(bothEq, leftEq); tagAllBands(leftEq, ParametricEqualizer.Channel.LEFT) }
            if (!rOk) { copyEqState(bothEq, rightEq); tagAllBands(rightEq, ParametricEqualizer.Channel.RIGHT) }
            if (!eqPrefs.restoreSharedBands(sharedEq)) resetSharedEq()
            else rebuildSlots(sharedBandSlots, sharedEq, eqPrefs.getSavedSharedSlots())
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
        } else {
            activeChannel = ActiveChannel.BOTH
            parametricEq = bothEq
        }
        graphView.setParametricEqualizer(parametricEq)
        graphView.setBandSlotLabels(bandSlots)
        initBandSlots()
        if (eqPrefs.getChannelSideEqEnabled()) sanitizeTethers()
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
        // Rebuild every channel's slot list — a non-active list not matching its own band count
        // desyncs and crashes on a later channel switch + band add.
        rebuildSlots(bothBandSlots, bothEq, eqPrefs.getSavedSlots())
        rebuildSlots(leftBandSlots, leftEq, eqPrefs.getSavedLeftSlots())
        rebuildSlots(rightBandSlots, rightEq, eqPrefs.getSavedRightSlots())
    }

    /** Populate [target] with exactly one slot per band in [eq]: use [saved] when it matches the
     *  band count, else a sequential 0,1,2,… layout (always valid, never out of range). */
    private fun rebuildSlots(target: MutableList<Int>, eq: ParametricEqualizer, saved: List<Int>?) {
        target.clear()
        if (saved != null && saved.size == eq.getBandCount()) {
            target.addAll(saved)
        } else {
            for (i in 0 until eq.getBandCount()) target.add(i)
        }
    }

    /** Trim every channel's EQ to the [MAX_BANDS] cap (issue #31). Called when "Add more EQ bands"
     *  is toggled off — bands beyond the original 16 drop highest-first. Returns true if any removed. */
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

    /** TV Mode hook (issues #35/#55): fired on every pushEqUpdate BEFORE the isProcessing
     *  early-return — a Remote-mode phone must sync to the TV even when its own DP is off. */
    var onEqPushed: (() -> Unit)? = null

    fun pushEqUpdate() {
        // Mirror tethered ("Both"-tagged) band edits between L and R — runs even when not
        // processing so in-memory state is correct for the next save.
        syncBothBands()
        // Shared "Both" layer rides every conversion as an overlay while CSE is on
        // (final channel = channel curve + shared curve).
        ParametricToDpConverter.overlayEq =
            if (eqPrefs.getChannelSideEqEnabled() && sharedEq.getBandCount() > 0) sharedEq else null
        onEqPushed?.invoke()
        if (!isProcessing) return
        val dm = eqService?.dynamicsManager ?: return
        dm.autoGainEnabled = autoGainEnabled
        dm.channelBalancePercent = channelBalancePercent
        if (eqPrefs.getChannelSideEqEnabled()) {
            // Per-side preamps ride the per-channel input-gain stage on top of the Channel Side
            // Options offsets; the shared preamp is zeroed so it can't double-apply.
            dm.preampGainDb = 0f
            dm.leftChannelGainDb = leftChannelGainDb + preampLeftDb
            dm.rightChannelGainDb = rightChannelGainDb + preampRightDb
        } else {
            dm.preampGainDb = preampGainDb
            dm.leftChannelGainDb = leftChannelGainDb
            dm.rightChannelGainDb = rightChannelGainDb
        }
        val (lEq, rEq) = getChannelEqs()
        eqService?.updateEqPerChannel(lEq, rEq)
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updatePending = false
    private val flushUpdate = Runnable {
        updatePending = false
        pushEqUpdate()
    }

    /** Coalesce rapid-fire EQ updates (e.g. graph-dot drag) into ≤1 DP write per frame; the flush
     *  reads the latest in-memory state. Without this a 60+ Hz drag stream blocks the audio thread
     *  with a full DP-band rewrite per touch event. Call [flushEqUpdate] on drag-end (ACTION_UP). */
    fun pushEqUpdateThrottled() {
        if (!isProcessing) return
        // Issue #61: a live drag freezes the DP cutoff layout and the
        // auto-gain offset — every mid-drag write is then a pure gain
        // update instead of a layout reconfigure + stepped whole-mix duck.
        ParametricToDpConverter.layoutFrozen = true
        eqService?.dynamicsManager?.gainHold = true
        if (updatePending) return
        updatePending = true
        updateHandler.postDelayed(flushUpdate, 16L)
    }

    /** Cancel any queued throttled update and push now — used at drag-end so the final value
     *  commits without a frame of latency (with a fresh layout + auto-gain, see #61). */
    fun flushEqUpdate() {
        if (updatePending) {
            updateHandler.removeCallbacks(flushUpdate)
            updatePending = false
        }
        ParametricToDpConverter.layoutFrozen = false
        eqService?.dynamicsManager?.gainHold = false
        pushEqUpdate()
    }

    /** Tag every band in [eq] with [ch]. Forked per-channel copies must be born INDEPENDENT
     *  (L/R-tagged), not BOTH — copyEqState creates BOTH-default bands, and syncBothBands() keeps
     *  BOTH-tagged bands in lockstep, which mirrored L onto R and erased divergence ("enabling
     *  Channel Side EQ applies the L side to both"). Both-tethering is per-band opt-in via the
     *  Both button / band popup (issue #53). */
    private fun tagAllBands(eq: ParametricEqualizer, ch: ParametricEqualizer.Channel) {
        for (i in 0 until eq.getBandCount()) eq.getBand(i)?.channel = ch
    }

    /** Copy one EQ's band state into another — used when forking the shared "both" EQ into the per-channel L/R editors. */
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

    /** Channel Side EQ switch: on enable, fork the current shared EQ into leftEq/rightEq
     *  (identical start) with L as the editing target; on disable, flip back to the "both" EQ. */
    fun setChannelSideEqEnabled(enabled: Boolean) {
        // Persist first — the gear's paint and the L/R tap guards read this pref.
        eqPrefs.saveChannelSideEqEnabled(enabled)
        if (enabled) {
            // Prefer prior L/R divergence when prefs carry it (CSE flipped off + back on); fork
            // from the current active EQ when either pref is absent (first enable / fresh install).
            val lOk = eqPrefs.restoreLeftBands(leftEq)
            val rOk = eqPrefs.restoreRightBands(rightEq)
            if (!lOk || !rOk) {
                val source = parametricEq
                if (!lOk && source !== leftEq) copyEqState(source, leftEq)
                if (!rOk && source !== rightEq) copyEqState(source, rightEq)
            }
            // Forked/legacy-restored channels must be independent, not tethered (see tagAllBands).
            // Post-#53 saves keep their own tags (a genuine tether saves BOTH on both sides — safe).
            if (!lOk) tagAllBands(leftEq, ParametricEqualizer.Channel.LEFT)
            if (!rOk) tagAllBands(rightEq, ParametricEqualizer.Channel.RIGHT)
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
            // Slot layouts: a prefs-restored channel uses its own saved slots; a freshly-forked
            // channel inherits the shared (bothEq) layout so the arrangement carries across.
            rebuildSlots(leftBandSlots, leftEq, if (lOk) eqPrefs.getSavedLeftSlots() else bothBandSlots)
            rebuildSlots(rightBandSlots, rightEq, if (rOk) eqPrefs.getSavedRightSlots() else bothBandSlots)
            // Heal corrupt tether tags from restored data BEFORE any sync (and before persisting, so healed tags stick)
            sanitizeTethers()
            if (!eqPrefs.restoreSharedBands(sharedEq)) resetSharedEq()
            else rebuildSlots(sharedBandSlots, sharedEq, eqPrefs.getSavedSharedSlots())
            // Persist the now-authoritative L/R state (bands + slots) so it survives restart
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
        exitBothView()
        if (channel == activeChannel) return
        // Flush "Both" edits from the departing channel to its twins first, so the switch can't sync the wrong direction (issue #53)
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

    /** Shared "Both" layer for graph rendering — non-null with CSE on, so drawn curves show
     *  channel + shared (what's audible). Graph skips it when the solid curve IS the shared layer (Both view). */
    fun getGraphOverlayEq(): ParametricEqualizer? =
        if (eqPrefs.getChannelSideEqEnabled() && sharedEq.getBandCount() > 0) sharedEq
        else null

    /** Ghost curves: in an L/R view the other channel; in the Both view both channels, so
     *  editing the shared layer shows the resulting L and R outputs move live. */
    fun getGhostEqs(): Pair<ParametricEqualizer?, ParametricEqualizer?> {
        if (!eqPrefs.getChannelSideEqEnabled()) return Pair(null, null)
        if (bothViewActive) return Pair(leftEq, rightEq)
        return when (activeChannel) {
            ActiveChannel.LEFT -> Pair(rightEq, null)
            ActiveChannel.RIGHT -> Pair(leftEq, null)
            else -> Pair(null, null)
        }
    }

    // ---- Per-band channel (L / R / Both) — issue #53 --------------------

    /** Channel tag of the active selected band (BOTH when out of range). */
    fun getBandChannel(index: Int): ParametricEqualizer.Channel =
        parametricEq.getBand(index)?.channel ?: ParametricEqualizer.Channel.BOTH

    /** Set the active band's channel. BOTH mirrors a synced twin into the other channel (same
     *  slot); L/R keep it on one channel only (moving it across if needed). Returns true if the
     *  band left the active channel (caller should refresh selection/UI). */
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

    /** "Untether" a BOTH band: keep it on BOTH channels but make the two sides independent (each
     *  retains its position) instead of dropping it from the other channel (issue #53). */
    fun untetherBand(displayIndex: Int) {
        if (!eqPrefs.getChannelSideEqEnabled() || activeChannel == ActiveChannel.BOTH) return
        val band = parametricEq.getBand(displayIndex) ?: return
        if (band.channel != ParametricEqualizer.Channel.BOTH) return
        val slot = bandSlots.getOrNull(displayIndex) ?: return
        val activeIsLeft = activeChannel == ActiveChannel.LEFT
        val otherEq = if (activeIsLeft) rightEq else leftEq
        val otherSlots = if (activeIsLeft) rightBandSlots else leftBandSlots
        // Ensure the other channel keeps a copy at the current position
        if (otherSlots.indexOf(slot) < 0) mirrorBandTo(otherEq, otherSlots, slot, band)
        // Retag both sides as single-channel so they're now independent
        band.channel = if (activeIsLeft) ParametricEqualizer.Channel.LEFT
            else ParametricEqualizer.Channel.RIGHT
        otherSlots.indexOf(slot).takeIf { it >= 0 }?.let { k ->
            otherEq.getBand(k)?.channel = if (activeIsLeft) ParametricEqualizer.Channel.RIGHT
                else ParametricEqualizer.Channel.LEFT
        }
        persistLeftRightIfCse()
        if (isProcessing) pushEqUpdate()
    }

    /** Create the synced twin in the other channel for a freshly-added BOTH band (issue #53) —
     *  explicit one-time insert (vs. the update-only [syncBothBands]) so new bands default to
     *  "tethered to both". No-op outside CSE or if the band isn't BOTH. */
    fun ensureBothTwin(displayIndex: Int) {
        if (!eqPrefs.getChannelSideEqEnabled() || activeChannel == ActiveChannel.BOTH) return
        val band = parametricEq.getBand(displayIndex) ?: return
        if (band.channel != ParametricEqualizer.Channel.BOTH) return
        val slot = bandSlots.getOrNull(displayIndex) ?: return
        val activeIsLeft = activeChannel == ActiveChannel.LEFT
        val otherEq = if (activeIsLeft) rightEq else leftEq
        val otherSlots = if (activeIsLeft) rightBandSlots else leftBandSlots
        mirrorBandTo(otherEq, otherSlots, slot, band)
    }

    /** Repair tether tags after loading persisted L/R bands (issue #53): a BOTH/BOTH pair with
     *  DIFFERING params is corrupt (earlier builds tagged forked bands BOTH by default) — the next
     *  sync would overwrite one channel with the other ("switching L/R applies L to both"). Demote
     *  any non-identical or one-sided BOTH pair to independent L/R tags. Must run AFTER slots are
     *  rebuilt (pairs are matched by slot). */
    private fun sanitizeTethers() {
        for (i in 0 until leftEq.getBandCount()) {
            val lb = leftEq.getBand(i) ?: continue
            if (lb.channel != ParametricEqualizer.Channel.BOTH) continue
            val slot = leftBandSlots.getOrNull(i)
            val j = if (slot != null) rightBandSlots.indexOf(slot) else -1
            val rb = if (j >= 0) rightEq.getBand(j) else null
            val intact = rb != null &&
                rb.channel == ParametricEqualizer.Channel.BOTH &&
                rb.filterType == lb.filterType &&
                kotlin.math.abs(rb.frequency - lb.frequency) < 0.01f &&
                kotlin.math.abs(rb.gain - lb.gain) < 0.01f &&
                kotlin.math.abs(rb.q - lb.q) < 0.001
            if (!intact) {
                lb.channel = ParametricEqualizer.Channel.LEFT
                if (rb != null && rb.channel == ParametricEqualizer.Channel.BOTH) {
                    rb.channel = ParametricEqualizer.Channel.RIGHT
                }
            }
        }
        // Sweep the right side for one-sided BOTH tags the loop above couldn't reach (no matching left slot)
        for (j in 0 until rightEq.getBandCount()) {
            val rb = rightEq.getBand(j) ?: continue
            if (rb.channel != ParametricEqualizer.Channel.BOTH) continue
            val slot = rightBandSlots.getOrNull(j)
            val i = if (slot != null) leftBandSlots.indexOf(slot) else -1
            val lb = if (i >= 0) leftEq.getBand(i) else null
            if (lb == null || lb.channel != ParametricEqualizer.Channel.BOTH) {
                rb.channel = ParametricEqualizer.Channel.RIGHT
            }
        }
    }

    /** Sync every tethered ("Both") band in the active channel to its slot-matched twin in the
     *  other channel. Called before each persist/DP push. No-op outside CSE. */
    fun syncBothBands() {
        if (!eqPrefs.getChannelSideEqEnabled() || activeChannel == ActiveChannel.BOTH) return
        // In the Both view parametricEq is the SHARED layer, not a channel — L/R tether syncing doesn't apply
        if (bothViewActive) return
        val activeIsLeft = activeChannel == ActiveChannel.LEFT
        val otherEq = if (activeIsLeft) rightEq else leftEq
        val otherSlots = if (activeIsLeft) rightBandSlots else leftBandSlots
        val activeSlots = bandSlots
        for (i in 0 until parametricEq.getBandCount()) {
            val b = parametricEq.getBand(i) ?: continue
            if (b.channel != ParametricEqualizer.Channel.BOTH) continue
            val slot = activeSlots.getOrNull(i) ?: continue
            // UPDATE the existing twin only — never insert. Twin creation is explicit in
            // setBandChannel(BOTH); inserting in this hot sync caused runaway band duplication
            // when slot sets differed between channels (issue #53).
            val j = otherSlots.indexOf(slot)
            val other = if (j >= 0) otherEq.getBand(j) else null
            // A tether is BILATERAL: sync only when the other channel's band at this slot is also
            // BOTH. One-sided BOTH tags from channel-unaware paths (preset loads, imports) must not
            // overwrite the other side — that erased R after an L/R switch post-preset-load.
            // Demote such tags to this channel (self-heals persisted data).
            if (other == null || other.channel != ParametricEqualizer.Channel.BOTH) {
                b.channel = if (activeIsLeft) ParametricEqualizer.Channel.LEFT
                    else ParametricEqualizer.Channel.RIGHT
                continue
            }
            otherEq.updateBand(j, b.frequency, b.gain, b.filterType, b.q)
            other.enabled = b.enabled
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

    /** Replace in-memory EQ state from a parsed preset. cseEnabled=false: [bothBands]→bothEq,
     *  activeChannel=BOTH. cseEnabled=true: [leftBands]→leftEq, [rightBands]→rightEq,
     *  activeChannel=LEFT. The CSE pref is persisted so getChannelSideEqEnabled() matches. */
    fun getSharedEqForPreset(): ParametricEqualizer = sharedEq

    fun applyPresetEqs(
        cseEnabled: Boolean,
        bothBands: List<BandSpec>,
        leftBands: List<BandSpec>,
        rightBands: List<BandSpec>,
        sharedBands: List<BandSpec>? = null,
    ) {
        eqPrefs.saveChannelSideEqEnabled(cseEnabled)
        if (cseEnabled) {
            // A channel with only shared bands still needs one editable band.
            val flat = listOf(BandSpec(1000f, 0f, 0.707, BiquadFilter.FilterType.BELL))
            loadBandsInto(leftEq, leftBands.ifEmpty { flat })
            loadBandsInto(rightEq, rightBands.ifEmpty { flat })
            // Shared "Both" layer from the preset — flat when the preset predates it,
            // so stale layers never leak into a loaded preset (adam's report).
            if (sharedBands != null && sharedBands.isNotEmpty()) {
                loadBandsInto(sharedEq, sharedBands)
            } else {
                resetSharedEq()
            }
            rebuildSlots(sharedBandSlots, sharedEq, null)
            eqPrefs.saveSharedBands(sharedEq)
            // Presets don't store channel tags — loadBandsInto leaves default BOTH tags on both
            // channels, which the tether sync treats as bilateral and mirrors L over R on the first
            // channel switch (issue #53 regression). Preset-loaded channels are independent by definition.
            tagAllBands(leftEq, ParametricEqualizer.Channel.LEFT)
            tagAllBands(rightEq, ParametricEqualizer.Channel.RIGHT)
            activeChannel = ActiveChannel.LEFT
            parametricEq = leftEq
            // Persist the freshly-loaded L/R bands under their own prefs keys so a process restart keeps the divergence
            eqPrefs.saveLeftBands(leftEq)
            eqPrefs.saveRightBands(rightEq)
        } else {
            loadBandsInto(bothEq, bothBands)
            activeChannel = ActiveChannel.BOTH
            parametricEq = bothEq
            // Wipe stale per-channel prefs so re-enabling CSE forks from the newly-loaded bothEq
            // instead of resurrecting old divergence
            eqPrefs.clearLeftRightBands()
        }
        exitBothView()
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
        ParametricToDpConverter.overlayEq =
            if (eqPrefs.getChannelSideEqEnabled() && sharedEq.getBandCount() > 0) sharedEq else null
        dm.autoGainEnabled = autoGainEnabled
        dm.channelBalancePercent = channelBalancePercent
        if (eqPrefs.getChannelSideEqEnabled()) {
            dm.preampGainDb = 0f
            dm.leftChannelGainDb = leftChannelGainDb + preampLeftDb
            dm.rightChannelGainDb = rightChannelGainDb + preampRightDb
        } else {
            dm.preampGainDb = preampGainDb
            dm.leftChannelGainDb = leftChannelGainDb
            dm.rightChannelGainDb = rightChannelGainDb
        }
        dm.limiterEnabled = limiterEnabled
        dm.limiterAttackMs = limiterAttackMs
        dm.limiterReleaseMs = limiterReleaseMs
        dm.limiterRatio = limiterRatio
        dm.limiterThresholdDb = limiterThresholdDb
        dm.limiterPostGainDb = limiterPostGainDb
        // MBC topology must be set BEFORE DP construction so the right number of MBC bands is
        // allocated; per-band params (threshold, ratio, attack…) push AFTER start via applyPersistedMbcConfig.
        dm.mbcEnabled = eqPrefs.getMbcEnabled()
        dm.mbcBandCount = eqPrefs.getMbcBandCount()
        val started = service.startEq(parametricEq)
        isProcessing = started
        if (!started) {
            animatePower(false)
            Toast.makeText(context, "Failed to start DynamicsProcessing", Toast.LENGTH_SHORT).show()
            return
        }
        // Push saved MBC band params + crossovers to the live DP — otherwise MBC reads "on" in the
        // UI but every band sits at DP defaults (ratio=1, no-op compressor) until a slider is
        // touched in MbcActivity (MBC-zombie-state issue).
        service.applyPersistedMbcConfig()
        // With Channel Side EQ on, fan out the distinct L/R responses now that DP is live
        if (eqPrefs.getChannelSideEqEnabled()) {
            val (lEq, rEq) = getChannelEqs()
            if (lEq !== rEq) service.updateEqPerChannel(lEq, rEq)
        }
        // TV Mode (issues #35/#55): isProcessing just flipped ON — the ONLY reliable signal on the
        // power-button path (EQ_STARTED broadcast fires only on the QS-tile path); sync power state to peer.
        onEqPushed?.invoke()
    }

    fun stopProcessing(animatePower: (Boolean) -> Unit) {
        animatePower(false)
        EqService.stop(context, silentToast = true)
        if (serviceBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        eqService = null
        isProcessing = false
        // TV Mode: power OFF — sync to peer (see doStartEq's matching hook).
        onEqPushed?.invoke()
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
        // Keep tethered twins in sync before persisting (issue #53).
        syncBothBands()
        // Don't write Simple-mode bands to the "bands" pref: in Simple mode parametricEq holds the
        // 10 fixed BELL bands, which would overwrite the user's advanced EQ. The advanced EQ is
        // preserved via [eqPrefs.saveAdvancedEqBackup] on entering Simple mode (canonical on next
        // launch); Simple gains persist in their own "simpleEqGains" pref via [SimpleEqController.saveGains].
        if (currentEqUiMode != EqUiMode.SIMPLE) {
            eqPrefs.saveState(parametricEq, bandSlots)
        }
        persistLeftRightIfCse()
        eqPrefs.saveBandColors(bandColors)
        eqPrefs.savePreampGain(preampGainDb)
        eqPrefs.savePreampLeft(preampLeftDb)
        eqPrefs.savePreampRight(preampRightDb)
        eqPrefs.saveAutoGainEnabled(autoGainEnabled)
        eqPrefs.saveLimiterEnabled(limiterEnabled)
        eqPrefs.saveLimiterAttack(limiterAttackMs)
        eqPrefs.saveLimiterRelease(limiterReleaseMs)
        eqPrefs.saveLimiterRatio(limiterRatio)
        eqPrefs.saveLimiterThreshold(limiterThresholdDb)
        eqPrefs.saveLimiterPostGain(limiterPostGainDb)
    }

    /** Mirror the active-EQ save so L/R divergence survives restart; no-op when CSE is off.
     *  Callsites saving via `eqPrefs.saveState(parametricEq, ...)` should also invoke this so the
     *  non-active channel's state isn't lost. */
    fun persistLeftRightIfCse() {
        if (eqPrefs.getChannelSideEqEnabled()) {
            eqPrefs.saveLeftBands(leftEq, leftBandSlots)
            eqPrefs.saveRightBands(rightEq, rightBandSlots)
            eqPrefs.saveSharedBands(sharedEq, sharedBandSlots)
        }
    }
}
