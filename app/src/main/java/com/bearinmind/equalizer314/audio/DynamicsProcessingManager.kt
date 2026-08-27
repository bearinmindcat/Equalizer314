package com.bearinmind.equalizer314.audio

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter

/**
 * System-wide EQ using Android's DynamicsProcessing API. Configuration
 * follows the patterns reverse-engineered from Wavelet
 * (com.pittvandewitt.wavelet) and Poweramp Equalizer
 * (com.maxmpz.equalizer):
 *
 *   • 127 bands at Wavelet's exact frequency table (matches AutoEQ
 *     GraphicEQ.txt format byte-for-byte).
 *   • `setPreferredFrameDuration(10 ms)` — short FFT window for clean
 *     transient handling.
 *   • Stage order on creation: limiter → MBC dummy → pre-EQ → enable.
 *   • Atomic per-channel `setPreEqByChannelIndex(ch, Eq)` batch update
 *     (2 binder calls per EQ change vs the legacy 256).
 *   • Preamp + per-channel offset routed through DP's input-gain stage
 *     via `setInputGainbyChannel`, leaving band gains as pure EQ shape.
 *   • `dp.hasControl()` guard on every band write.
 *   • MBC stage always allocated with at least 1 dummy band so the
 *     stage exists even when MBC is user-disabled (Wavelet pattern).
 *
 * Requires API 28+.
 */
class DynamicsProcessingManager {

    companion object {
        private const val TAG = "DynamicsProcessingMgr"
        // FFT frame duration — mirrored into ParametricToDpConverter so its
        // deconvolution matches the engine's real bin layout. Default 80 ms
        // (~12 Hz bins): same frame class as Wavelet's default long-frame
        // mode (4096 samples ≈ 85 ms) and Poweramp (85-341 ms), and gets the
        // rendered curve to ~0.1 dB worst-case (issue #26). 40 ms (~23 Hz
        // bins, experimental "Low latency mode") trades sub-100 Hz accuracy
        // (~0.25 dB) for half the latency. Static so EqService /
        // ExperimentalActivity can set it from prefs before start().
        const val FRAME_DURATION_DEFAULT_MS = 80f
        const val FRAME_DURATION_LOW_LATENCY_MS = 40f
        // "Maximum bass precision": 160 ms → ~5.9 Hz bins. REW-measured: at
        // 80 ms, features only 2-3 bins wide (e.g. Q=2 bell at 60 Hz) render
        // with ~0.9 dB smoothing; 160 ms halves the bin width. Poweramp runs
        // up to 341 ms frames, so the latency is precedented.
        const val FRAME_DURATION_MAX_PRECISION_MS = 160f
        @Volatile
        var frameDurationMs = FRAME_DURATION_DEFAULT_MS
        // Experimental Pre+Post-EQ interleave (issue #26 follow-up): both DP
        // EQ stages (128 bands each), Post's cutoffs offset half a stair from
        // Pre's — 256 effective stairs, roughly halving per-stair ripple.
        // Baked in at DP creation (power cycle to change). Static for
        // prefs-driven setup before start().
        @Volatile
        var interleaveEnabled = false
        // Compatibility Mode: mirrors Wavelet's "aidl_mode" profile for the
        // AIDL-HAL generation (Pixel / Android 15+): 32 Pre-EQ bands AND a
        // 10 ms frame, Post-EQ never allocated. The latency window and
        // interleave settings stay saved but are inert while it's on.
        const val COMPAT_BAND_COUNT = 32
        const val COMPAT_FRAME_MS = 10f
        @Volatile
        var compatMode = false
        // Creation-time effective values — compat overrides the user settings.
        val effectiveFrameMs: Float get() = if (compatMode) COMPAT_FRAME_MS else frameDurationMs
        val effectiveInterleave: Boolean get() = !compatMode && interleaveEnabled
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentBandCount = 0
    // Whether the LIVE DP config allocated the Post-EQ stage. Band writes
    // must match the live config, not the static flag — the toggle can flip
    // while DP runs and only applies on the next power cycle.
    private var currentInterleave = false
    private var lastEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    // Optional right-channel EQ for per-channel mode. When null, lastEq is
    // applied to both channels (original shared behavior).
    private var lastRightEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    private var lastReclaimTime = 0L
    private val reclaimCooldownMs = 2000L  // Don't reclaim more than once every 2 seconds
    // @Volatile: read off the main thread by EqService's watchdog and by
    // EqService.isDpRunning mirrors, written from start()/stop().
    @Volatile
    var isActive = false
        private set

    // Preamp
    var preampGainDb: Float = 0f

    // Auto-gain
    var autoGainEnabled: Boolean = false
    var lastAutoGainOffset: Float = 0f
        private set
    // Issue #61: hold the auto-gain offset steady during a drag. Recomputing
    // per write ducks the WHOLE mix in 20-60 steps/s while boosting — an
    // audible pumping/"choke". Held offset may transiently under-protect a
    // growing boost; the limiter covers that until drag-end recomputes.
    @Volatile
    var gainHold = false

    // MBC
    var mbcEnabled: Boolean = false
    var mbcBandCount: Int = 3
    // Volume compensation: dB shift (≤0) added to MBC thresholds/gates so
    // compression tracks the pre-volume signal. Set by EqService.
    @Volatile var mbcThresholdOffsetDb: Float = 0f

    // Limiter defaults = Wavelet's `a6/z.java:105` baseline (1 ms attack,
    // 60 ms release, 10:1 ratio, −2 dB threshold, 0 dB post-gain).
    // EqStateManager overwrites from user prefs before start(); these are
    // the fallback for the very-first call before sync.
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 60f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -2f
    var limiterPostGainDb: Float = 0f

    // Channel Side Options — balance + per-channel preamp.
    // Routed through DP's input-gain stage, NOT baked into band gains.
    var channelBalancePercent: Int = 0     // -100..100, 0 = center
    var leftChannelGainDb: Float = 0f      // -12..12
    var rightChannelGainDb: Float = 0f     // -12..12

    // Worker thread for binder calls: one setPreEqByChannelIndex transaction
    // per channel per update — on the UI thread these block rendering and
    // (under contention) the audio path during a drag.
    private val workerThread = android.os.HandlerThread("EqDpWorker").apply { start() }
    private val workerHandler = android.os.Handler(workerThread.looper)
    @Volatile private var pendingApply: Runnable? = null
    @Volatile private var pendingLimiter: Runnable? = null

    // Issue #61 (Pixel 8a stutter/dropout during drags): space DP band
    // writes ≥50 ms apart. A 16 ms rewrite storm — with the adaptive cutoff
    // layout reshuffling per frame on HF drags — forces a per-write effect
    // reconfigure that some HALs (Pixel/AIDL era) render as an audible
    // bypass stutter. 20 writes/s still feels live.
    private val minWriteSpacingMs = 50L
    @Volatile private var lastWriteAtMs = 0L
    // Retry for writes skipped on transient control loss: if control comes
    // back AFTER the final drag write was skipped, nothing else re-applies
    // the bands (watchdog sees a healthy DP) and audio stays unprocessed.
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var controlRetryPending = false
    @Volatile private var controlRetryCount = 0

    fun start(eq: ParametricEqualizer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }

        stop() // Clean up any existing instance

        // A recreate mid-drag must not carry stale drag-freeze state (#61).
        ParametricToDpConverter.layoutFrozen = false
        gainHold = false
        // Keep the converter's model of DP's FFT geometry in sync so its
        // deconvolution matches the engine (issue #26).
        ParametricToDpConverter.frameDurationMs = effectiveFrameMs
        // Compat Mode → 32 bands to match band-limited HALs; else 128 (the
        // AIDL ceiling; requesting more throws) with 127 paranoid fallback.
        val bandLadder = if (compatMode) intArrayOf(COMPAT_BAND_COUNT) else intArrayOf(128, 127)
        for (tryBands in bandLadder) {
            ParametricToDpConverter.setNumBands(tryBands)
            if (startWithBandCount(eq, ParametricToDpConverter.numBands)) return
            Log.w(TAG, "DP creation failed with ${ParametricToDpConverter.numBands} bands")
        }
        // Paranoid fallback: some OEM could reject a config with the Post-EQ
        // stage allocated. Retry the whole ladder single-stage.
        if (effectiveInterleave) {
            Log.w(TAG, "Retrying without Pre+Post interleave")
            interleaveEnabled = false
            for (tryBands in bandLadder) {
                ParametricToDpConverter.setNumBands(tryBands)
                if (startWithBandCount(eq, ParametricToDpConverter.numBands)) return
                Log.w(TAG, "DP creation failed with ${ParametricToDpConverter.numBands} bands")
            }
        }
        Log.e(TAG, "DynamicsProcessing could not be started with any band count")
    }

    private fun startWithBandCount(eq: ParametricEqualizer, bandCount: Int): Boolean {
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        val useInterleave = effectiveInterleave
        Log.d(TAG, "DP variant=FREQUENCY bands=$bandCount frame=${effectiveFrameMs}ms interleave=$useInterleave compat=$compatMode")

        // MBC stage: always allocate ≥1 band (dummy disabled passthrough when
        // MBC off). Wavelet does this regardless of MBC state — the stage
        // existing seems to be the expected DP usage pattern.
        val mbcStageBandCount = if (mbcEnabled) mbcBandCount else 1
        val configBuilder = DynamicsProcessing.Config.Builder(
            variant,
            2,                  // channel count (stereo)
            true,               // pre-EQ stage enabled
            bandCount,          // pre-EQ band count
            true,               // MBC stage allocated
            mbcStageBandCount,
            useInterleave,      // post-EQ stage: interleave's second staircase
            if (useInterleave) bandCount else 0,
            true                // limiter stage enabled
        )
        // FFT window length sets the EQ's frequency resolution: bins are
        // spaced ~1/frameDuration LINEARLY in Hz. The original 10 ms frame
        // (~94 Hz bins) crushed any feature narrower than ~2 bins — a Q=3
        // bell at 300 Hz rendered +1.4 dB instead of +4 (REW-verified,
        // issue #26). See the companion constants for the 80/40 ms choice.
        configBuilder.setPreferredFrameDuration(effectiveFrameMs)
        val config = configBuilder.build()

        try {
            lastEq = eq
            // Must be set BEFORE applyParametricResponse below — the initial
            // band write reads currentInterleave to pick the conversion path,
            // and a stale value from the previous config would write half
            // gains with no Post stage (REW-measured: whole curve at half
            // depth after an ON→OFF power cycle).
            currentInterleave = useInterleave
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                // Stage population order matches Wavelet's a6/b0.smali:
                // limiter → MBC → pre-EQ → setEnabled. Enabled last so DP
                // never processes audio with default bands.

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Dummy passthrough MBC band when MBC off — audio unchanged,
                // stage still reports its allocated band slot.
                if (!mbcEnabled) {
                    val dummyMbc = DynamicsProcessing.MbcBand(
                        false,        // enabled = false (passthrough)
                        20000f,       // cutoff well above audible
                        1f, 100f, 1f, 0f, 0f, -120f, 1f, 0f, 0f
                    )
                    setMbcBandByChannelIndex(0, 0, dummyMbc)
                    setMbcBandByChannelIndex(1, 0, dummyMbc)
                }

                // Apply response, then enable — drainPendingApply blocks
                // until the band write lands (no default-band audio).
                applyParametricResponse(this, eq)
                drainPendingApply()
                enabled = true

                // Detect when another app disables/overrides our DP and re-attach
                setEnableStatusListener(android.media.audiofx.AudioEffect.OnEnableStatusChangeListener { _, enabled ->
                    if (!enabled && isActive) {
                        reclaimSession()
                    }
                })

                // Detect control status changes (another app taking over session 0)
                setControlStatusListener(android.media.audiofx.AudioEffect.OnControlStatusChangeListener { _, controlGranted ->
                    if (!controlGranted && isActive) {
                        reclaimSession()
                    }
                })
            }
            currentBandCount = bandCount
            isActive = true
            Log.d(TAG, "DynamicsProcessing started with $bandCount bands (interleave=$useInterleave)")
            // Diagnostic readback (issue #26): engine-accepted vs requested —
            // catches OEMs silently clamping frame duration / band count.
            // Read via: adb logcat -s DynamicsProcessingMgr
            try {
                val actual = dynamicsProcessing?.config
                Log.i(TAG, "DP config readback: variant=${actual?.variant} " +
                    "frameDuration=${actual?.preferredFrameDuration}ms " +
                    "(requested ${effectiveFrameMs}ms) " +
                    "preEqBands=${actual?.preEqBandCount} (requested $bandCount) " +
                    "postEqBands=${actual?.postEqBandCount} (interleave=$useInterleave) " +
                    "converter: fs=${ParametricToDpConverter.deviceSampleRateHz}Hz")
            } catch (e: Exception) {
                Log.w(TAG, "DP config readback failed: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicsProcessing ($bandCount bands)", e)
            dynamicsProcessing = null
            isActive = false
            return false
        }
    }

    /**
     * Block until any pending [applyParametricResponse] worker job runs.
     * Used in [start] so the band feed lands BEFORE `enabled = true`
     * (Wavelet ordering). No-op when nothing is queued.
     */
    private fun drainPendingApply() {
        val job = pendingApply ?: return
        // Dequeue and run synchronously — binder calls are thread-agnostic;
        // only ordering matters.
        workerHandler.removeCallbacks(job)
        try { job.run() } catch (_: Exception) {}
    }

    private fun reclaimSession() {
        val now = System.currentTimeMillis()
        if (now - lastReclaimTime < reclaimCooldownMs) return  // Cooldown — don't fight endlessly
        lastReclaimTime = now
        Log.w(TAG, "DynamicsProcessing overridden by another app — reclaiming")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isActive && lastEq != null) {
                Log.d(TAG, "Reclaiming DynamicsProcessing")
                start(lastEq!!)
            }
        }, 100)
    }

    /** Clean recreate of the live DP on the current output with the
     *  last-applied EQ. Used on route changes (BT ↔ speaker etc.) to dodge
     *  OEM output-effect conflicts that leave the new route muted until DP
     *  is re-toggled — e.g. Pixel Adaptive Sound on Android 14. Power
     *  off/on equivalent, bands preserved. Returns false when DP inactive
     *  or no remembered EQ. Caller re-applies MBC params / bypass after,
     *  same as any start(). */
    fun reattachActive(): Boolean {
        if (!isActive) return false
        val eq = lastEq ?: return false
        stop()
        start(eq)
        return isActive
    }

    /** True when the EQ should be live but the session-0 effect silently
     *  lost control / got disabled by another app or OEM audio policy —
     *  the "EQ goes flat after switching apps" case the OnControl/OnEnable
     *  listeners miss on aggressive ROMs. Any-thread safe: false when DP
     *  inactive; a native read on a torn-down handle is treated as "lost"
     *  so the caller reattaches cleanly. */
    fun hasLostControl(): Boolean {
        if (!isActive) return false
        val dp = dynamicsProcessing ?: return false
        return try {
            !dp.hasControl() || !dp.enabled
        } catch (e: Throwable) {
            Log.w(TAG, "hasLostControl read threw — treating as lost", e)
            true
        }
    }

    /** Cooldown gate shared with [reclaimSession] (same lastReclaimTime /
     *  reclaimCooldownMs) so the watchdog and the listener path can't both
     *  fire a recreate inside the 2s window. Consumes the window when it
     *  returns true. */
    fun reclaimCooldownElapsed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReclaimTime < reclaimCooldownMs) return false
        lastReclaimTime = now
        return true
    }

    fun updateFromEqualizer(eq: ParametricEqualizer) {
        updateFromEqualizers(eq, eq)
    }

    /** Apply potentially-different EQs to the two channels. Pass the same
     *  instance for both in shared/BOTH mode. */
    fun updateFromEqualizers(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer) {
        val dp = dynamicsProcessing ?: return

        // If band count changed, must recreate the DP instance
        if (ParametricToDpConverter.numBands != currentBandCount) {
            Log.d(TAG, "Band count changed ($currentBandCount -> ${ParametricToDpConverter.numBands}), recreating DP")
            lastRightEq = if (leftEq !== rightEq) rightEq else null
            start(leftEq)
            return
        }

        try {
            lastEq = leftEq
            lastRightEq = if (leftEq !== rightEq) rightEq else null
            applyParametricResponse(dp, leftEq, rightEq)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update DynamicsProcessing", e)
        }
    }

    private fun applyParametricResponse(dp: DynamicsProcessing, eq: ParametricEqualizer) {
        applyParametricResponse(dp, eq, eq)
    }

    private fun applyParametricResponse(
        dp: DynamicsProcessing,
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
    ) {
        // Response sampling (cheap) stays on the caller's thread — it touches
        // the UI-owned ParametricEqualizer; the binder transactions into
        // AudioFlinger go to the worker thread.
        //
        // Single conversion path for every UI mode: feature-aware sampling
        // anchors cutoffs at each filter's centre frequency + per-filter-type
        // support points, fills remaining slots from Wavelet's 127-band
        // table. Gain at each cutoff = eq.getFrequencyResponse(f) — the same
        // biquad-summed value the graph draws, so audio always agrees with
        // the graph in parametric / graphic / table / simple mode.
        val useInterleave = currentInterleave
        val cutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        // Interleave (issue #26 follow-up): Post-EQ carries a second
        // staircase offset half a stair from Pre's, holding the residual
        // ripple. Null when the live config has no Post-EQ stage.
        val postCutoffs: FloatArray?
        val leftPostGains: FloatArray?
        val rightPostGains: FloatArray?
        if (useInterleave) {
            if (leftEq === rightEq) {
                val li = ParametricToDpConverter.convertInterleaved(leftEq)
                cutoffs = li.preCutoffs
                leftGains = li.preGains
                postCutoffs = li.postCutoffs
                leftPostGains = li.postGains
                rightGains = leftGains.copyOf()
                rightPostGains = leftPostGains.copyOf()
            } else {
                val di = ParametricToDpConverter.convertInterleavedDual(leftEq, rightEq)
                cutoffs = di.preCutoffs
                leftGains = di.leftPreGains
                rightGains = di.rightPreGains
                postCutoffs = di.postCutoffs
                leftPostGains = di.leftPostGains
                rightPostGains = di.rightPostGains
            }
        } else {
            if (leftEq === rightEq) {
                val l = ParametricToDpConverter.convertFeatureAware(leftEq)
                cutoffs = l.cutoffs
                leftGains = l.gains
                rightGains = leftGains.copyOf()
            } else {
                val d = ParametricToDpConverter.convertFeatureAwareDual(leftEq, rightEq)
                cutoffs = d.cutoffs
                leftGains = d.leftGains
                rightGains = d.rightGains
            }
            postCutoffs = null
            leftPostGains = null
            rightPostGains = null
        }

        // Auto-gain: bring the loudest band to ≤ 0 dB. Applied as a flat
        // shift to all bands so it preserves EQ shape.
        if (autoGainEnabled) {
            if (!gainHold) {
                var peak = Float.NEGATIVE_INFINITY
                if (useInterleave && leftPostGains != null && rightPostGains != null) {
                    // Split-half: each stage carries HALF the curve, so the
                    // true response peak is ~2× a single stage's gain.
                    for (g in leftGains) if (2f * g > peak) peak = 2f * g
                    for (g in rightGains) if (2f * g > peak) peak = 2f * g
                    for (g in leftPostGains) if (2f * g > peak) peak = 2f * g
                    for (g in rightPostGains) if (2f * g > peak) peak = 2f * g
                } else {
                    for (g in leftGains) if (g > peak) peak = g
                    for (g in rightGains) if (g > peak) peak = g
                }
                lastAutoGainOffset = if (peak > 0f) -peak else 0f
            }
            if (lastAutoGainOffset != 0f) {
                // Flat shift on the Pre stage only — the per-bin total moves
                // by exactly the offset either way, and Post stays pure shape.
                for (i in leftGains.indices) leftGains[i] += lastAutoGainOffset
                for (i in rightGains.indices) rightGains[i] += lastAutoGainOffset
            }
        } else {
            lastAutoGainOffset = 0f
        }

        // Channel offsets + preamp are flat shifts on DP's input-gain stage,
        // NOT baked into band gains (Wavelet a6/b0.smali:
        // setInputGainbyChannel(0, leftSum) / (1, rightSum)) — band gains
        // stay pure EQ shape so DP headroom logic doesn't fight balance.
        val (leftOffsetDb, rightOffsetDb) = computeChannelOffsets()

        Log.d(TAG, "[DUMP] preamp=${"%.2f".format(preampGainDb)} dB, " +
            "autoGain=$autoGainEnabled (offset=${"%.2f".format(lastAutoGainOffset)} dB), " +
            "channelOffsets L=${"%.2f".format(leftOffsetDb)} R=${"%.2f".format(rightOffsetDb)} dB, " +
            "bands=${cutoffs.size}")
        run {
            val sb = StringBuilder("[DUMP] (cutoff Hz, L gain dB, R gain dB) per band:\n")
            for (i in cutoffs.indices) {
                sb.append("  [%3d] cutoff=%-9.1f L=%+6.2f R=%+6.2f\n"
                    .format(i, cutoffs[i], leftGains[i], rightGains[i]))
            }
            sb.toString().split('\n').forEach { line ->
                if (line.isNotEmpty()) Log.d(TAG, line)
            }
            Log.d(TAG, "[DUMP] Parametric source bands (left EQ):")
            for (i in 0 until leftEq.getBandCount()) {
                val b = leftEq.getBand(i) ?: continue
                Log.d(TAG, "  src[%2d] type=%-12s freq=%-8.1f Hz gain=%+6.2f dB Q=%.3f enabled=%s"
                    .format(i, b.filterType.name, b.frequency, b.gain, b.q, b.enabled))
            }
            if (useInterleave && postCutoffs != null && leftPostGains != null && rightPostGains != null) {
                val psb = StringBuilder("[DUMP] interleave POST stage (cutoff Hz, L gain dB, R gain dB):\n")
                for (i in postCutoffs.indices) {
                    psb.append("  [%3d] cutoff=%-9.1f L=%+6.2f R=%+6.2f\n"
                        .format(i, postCutoffs[i], leftPostGains[i], rightPostGains[i]))
                }
                psb.toString().split('\n').forEach { line ->
                    if (line.isNotEmpty()) Log.d(TAG, line)
                }
            }
        }

        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        // Input gain = preamp + per-channel offset. Auto-gain is already
        // baked into band gains above — don't double-add.
        val leftInputGainDb = preampGainDb + leftOffsetDb
        val rightInputGainDb = preampGainDb + rightOffsetDb
        val job = Runnable {
            try {
                // Wavelet calls dp.hasControl() before applying settings
                // (a6/n0.smali) — without control all setters silently no-op.
                // Skip; reclaimSession() recreates when control returns.
                if (!dp.hasControl()) {
                    Log.w(TAG, "DP lost control — band write skipped, scheduling retry")
                    scheduleControlRetry()
                    return@Runnable
                }
                controlRetryCount = 0
                // Preamp + offset via input-gain stage; Wavelet uses
                // per-channel values (a6/b0.smali:343,379).
                try {
                    dp.setInputGainbyChannel(0, leftInputGainDb)
                    dp.setInputGainbyChannel(1, rightInputGainDb)
                } catch (e: Throwable) {
                    Log.w(TAG, "setInputGainbyChannel failed", e)
                }
                // Atomic per-channel EQ swap: one Eq object → one binder
                // transaction per channel; engine never sees partial state.
                val leftEqObj = DynamicsProcessing.Eq(true, true, n)
                val rightEqObj = DynamicsProcessing.Eq(true, true, n)
                for (i in 0 until n) {
                    leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i]))
                    rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i]))
                }
                dp.setPreEqByChannelIndex(0, leftEqObj)
                dp.setPreEqByChannelIndex(1, rightEqObj)
                // Interleave: second staircase on the Post-EQ stage. Only
                // when the live config allocated it (currentInterleave).
                if (postCutoffs != null && leftPostGains != null && rightPostGains != null) {
                    val leftPostObj = DynamicsProcessing.Eq(true, true, n)
                    val rightPostObj = DynamicsProcessing.Eq(true, true, n)
                    for (i in 0 until n) {
                        leftPostObj.setBand(i, DynamicsProcessing.EqBand(true, postCutoffs[i], leftPostGains[i]))
                        rightPostObj.setBand(i, DynamicsProcessing.EqBand(true, postCutoffs[i], rightPostGains[i]))
                    }
                    dp.setPostEqByChannelIndex(0, leftPostObj)
                    dp.setPostEqByChannelIndex(1, rightPostObj)
                }
                lastWriteAtMs = android.os.SystemClock.uptimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "DP band write failed", e)
            } finally {
                pendingApply = null
            }
        }
        pendingApply?.let { workerHandler.removeCallbacks(it) }
        pendingApply = job
        val delay = (lastWriteAtMs + minWriteSpacingMs - android.os.SystemClock.uptimeMillis())
            .coerceIn(0L, minWriteSpacingMs)
        workerHandler.postDelayed(job, delay)
    }

    /** Re-apply the latest EQ state once transient session-0 control loss
     *  passes (issue #61 — the skipped final drag write left DP with stale
     *  bands). Retries on the main thread (the live ParametricEqualizer is
     *  main-thread-owned); after 4 misses falls back to a full reclaim. */
    private fun scheduleControlRetry() {
        if (controlRetryPending) return
        controlRetryPending = true
        retryHandler.postDelayed({
            controlRetryPending = false
            val dp = dynamicsProcessing ?: return@postDelayed
            val eq = lastEq ?: return@postDelayed
            if (!isActive) return@postDelayed
            val hasControl = try { dp.hasControl() } catch (_: Throwable) { false }
            if (hasControl) {
                Log.i(TAG, "control restored — re-applying skipped band write")
                try { applyParametricResponse(dp, eq, lastRightEq ?: eq) } catch (_: Exception) {}
            } else if (++controlRetryCount >= 4) {
                controlRetryCount = 0
                reclaimSession()
            } else {
                scheduleControlRetry()
            }
        }, 300L)
    }

    /**
     * Per-channel flat dB offset for the input-gain stage: per-channel
     * preamp + balance attenuation. Balance: the side panned TOWARD stays
     * at 0 dB relative to preamp, the other side is attenuated; pan wins
     * over preamp (full-left pan mutes right regardless of right preamp).
     */
    private fun computeChannelOffsets(): Pair<Float, Float> {
        val pct = channelBalancePercent.coerceIn(-100, 100)
        val leftBalanceDb = if (pct > 0) {
            val ratio = ((100 - pct) / 100f).coerceAtLeast(1e-4f)
            20f * kotlin.math.log10(ratio)
        } else 0f
        val rightBalanceDb = if (pct < 0) {
            val ratio = ((100 + pct) / 100f).coerceAtLeast(1e-4f)
            20f * kotlin.math.log10(ratio)
        } else 0f
        // Cap floor at -60 dB (≈ silent) to avoid feeding an extreme number to
        // DynamicsProcessing; cap ceiling at +24 dB.
        val left = (leftChannelGainDb + leftBalanceDb).coerceIn(-60f, 24f)
        val right = (rightChannelGainDb + rightBalanceDb).coerceIn(-60f, 24f)
        return Pair(left, right)
    }

    /** Re-apply the current EQ with fresh channel settings (balance, preamp). */
    fun updateChannelSettings() {
        val dp = dynamicsProcessing ?: return
        val eq = lastEq ?: return
        try {
            applyParametricResponse(dp, eq, lastRightEq ?: eq)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update channel settings", e)
        }
    }

    fun updateLimiter() {
        val dp = dynamicsProcessing ?: return
        try {
            val limiter = DynamicsProcessing.Limiter(
                limiterEnabled, limiterEnabled, 0,
                limiterAttackMs, limiterReleaseMs, limiterRatio,
                limiterThresholdDb, limiterPostGainDb
            )
            dp.setLimiterByChannelIndex(0, limiter)
            dp.setLimiterByChannelIndex(1, limiter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update limiter", e)
        }
    }

    /**
     * Apply MBC band settings from MbcActivity's band data.
     * @param bands List of band parameters: cutoff, attack, release, ratio, threshold, knee, noiseGate, expander, preGain, postGain
     * @param crossovers Crossover frequencies (bands.size - 1)
     */
    fun applyMbcBands(
        bands: List<MbcBandParams>,
        crossovers: FloatArray
    ) {
        val dp = dynamicsProcessing ?: return
        if (!mbcEnabled) return

        try {
            val off = mbcThresholdOffsetDb
            for (i in bands.indices) {
                val b = bands[i]
                val cutoff = if (i < crossovers.size) crossovers[i] else 20000f
                val mbcBand = DynamicsProcessing.MbcBand(
                    b.enabled,
                    cutoff,
                    b.attackMs,
                    b.releaseMs,
                    b.ratio,
                    (b.thresholdDb + off).coerceIn(-125f, 0f),
                    b.kneeDb,
                    (b.noiseGateDb + off).coerceIn(-125f, 0f),
                    b.expanderRatio,
                    b.preGainDb,
                    b.postGainDb
                )
                dp.setMbcBandByChannelIndex(0, i, mbcBand)
                dp.setMbcBandByChannelIndex(1, i, mbcBand)
                Log.d(TAG, "MBC band $i: preGain=${b.preGainDb} postGain=${b.postGainDb} threshold=${b.thresholdDb} ratio=${b.ratio} cutoff=$cutoff")
            }

            // Readback
            val readback = dp.getMbcBandByChannelIndex(0, 0)
            Log.d(TAG, "MBC readback band 0: preGain=${readback.preGain} postGain=${readback.postGain} threshold=${readback.threshold}")
            Log.d(TAG, "DP enabled=${dp.enabled}, MBC stage enabled=${dp.getMbcByChannelIndex(0).isEnabled}, bandCount=${dp.getMbcByChannelIndex(0).bandCount}")
            Log.d(TAG, "Applied ${bands.size} MBC bands")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply MBC bands", e)
        }
    }

    /** Simple data class for MBC band parameters passed to applyMbcBands */
    data class MbcBandParams(
        val enabled: Boolean = true,
        val attackMs: Float = 1f,
        val releaseMs: Float = 100f,
        val ratio: Float = 2f,
        val thresholdDb: Float = 0f,
        val kneeDb: Float = 8f,
        val noiseGateDb: Float = -60f,
        val expanderRatio: Float = 1f,
        val preGainDb: Float = 0f,
        val postGainDb: Float = 0f
    )

    fun setEnabled(enabled: Boolean) {
        dynamicsProcessing?.enabled = enabled
    }

    /** Apply current limiter fields to the live DP without rebuild.
     *  Worker-thread dispatched (slider drags don't stall UI on binder) and
     *  coalesced so back-to-back ticks collapse to one write. Silent no-op
     *  when DP isn't running. */
    fun pushLimiterUpdate() {
        val dp = dynamicsProcessing ?: return
        val limiter = DynamicsProcessing.Limiter(
            limiterEnabled, limiterEnabled, 0,
            limiterAttackMs, limiterReleaseMs, limiterRatio,
            limiterThresholdDb, limiterPostGainDb
        )
        val job = Runnable {
            try {
                dp.setLimiterByChannelIndex(0, limiter)
                dp.setLimiterByChannelIndex(1, limiter)
            } catch (e: Exception) {
                Log.e(TAG, "Limiter live-update failed", e)
            } finally {
                pendingLimiter = null
            }
        }
        pendingLimiter?.let { workerHandler.removeCallbacks(it) }
        pendingLimiter = job
        workerHandler.post(job)
    }

    fun stop() {
        // Drain any queued band-write before tearing down the DP instance —
        // the runnable would otherwise run against a released native handle.
        pendingApply?.let { workerHandler.removeCallbacks(it) }
        pendingApply = null
        pendingLimiter?.let { workerHandler.removeCallbacks(it) }
        pendingLimiter = null
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing DynamicsProcessing", e)
        }
        dynamicsProcessing = null
        currentBandCount = 0
        isActive = false
        Log.d(TAG, "DynamicsProcessing stopped")
    }
}
