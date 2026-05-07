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
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentBandCount = 0
    private var lastEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    // Optional right-channel EQ for per-channel mode. When null, lastEq is
    // applied to both channels (original shared behavior).
    private var lastRightEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    private var lastReclaimTime = 0L
    private val reclaimCooldownMs = 2000L  // Don't reclaim more than once every 2 seconds
    var isActive = false
        private set

    // Preamp
    var preampGainDb: Float = 0f

    // Auto-gain
    var autoGainEnabled: Boolean = false
    var lastAutoGainOffset: Float = 0f
        private set

    // MBC
    var mbcEnabled: Boolean = false
    var mbcBandCount: Int = 3

    // Limiter — defaults match Wavelet's `a6/z.java:105` baseline
    // (1 ms attack, 60 ms release, 10:1 ratio, −2 dB threshold, 0 dB
    // post-gain). EqStateManager will overwrite these from user prefs
    // before start(); these values are the in-class fallback for the
    // very-first call before sync.
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

    /**
     * Whether the active UI mode is "graphic-style" (graphic / table /
     * simple) vs parametric. Set by EqStateManager based on the user's
     * current UI mode. Controls which conversion path runs:
     *   • true  → [ParametricToDpConverter.convertDirect]: each band's
     *     stored (frequency, gain) pair fed straight to DP, no biquad
     *     math (matches Wavelet's graphic / Poweramp behaviour).
     *   • false → [ParametricToDpConverter.convertFeatureAware]: biquad
     *     composite sampled with anchors at every parametric centre +
     *     per-filter-type support points.
     */
    var useDirectGraphicPath: Boolean = false

    // Background thread for the binder calls. Each EQ update issues one
    // setPreEqByChannelIndex transaction per channel; running them on the
    // UI thread blocks both rendering and (under contention) the audio
    // path during a drag.
    private val workerThread = android.os.HandlerThread("EqDpWorker").apply { start() }
    private val workerHandler = android.os.Handler(workerThread.looper)
    @Volatile private var pendingApply: Runnable? = null
    @Volatile private var pendingLimiter: Runnable? = null

    fun start(eq: ParametricEqualizer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }

        stop() // Clean up any existing instance

        // 127 bands at Wavelet's exact frequency table (a6.z.f608g[]).
        // Matches AutoEQ GraphicEQ.txt's 127 fixed positions, so a
        // graphic profile loads with zero interpolation error.
        ParametricToDpConverter.setNumBands(127)
        val bandCount = ParametricToDpConverter.numBands
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        Log.d(TAG, "DP variant=FREQUENCY bands=$bandCount")

        // MBC stage: always allocate the stage with at least 1 band
        // (dummy disabled passthrough when MBC is user-disabled). Wavelet
        // does this regardless of MBC state — the stage existing seems
        // to be the expected DP usage pattern.
        val mbcStageBandCount = if (mbcEnabled) mbcBandCount else 1
        val configBuilder = DynamicsProcessing.Config.Builder(
            variant,
            2,                  // channel count (stereo)
            true,               // pre-EQ stage enabled
            bandCount,          // pre-EQ band count
            true,               // MBC stage allocated
            mbcStageBandCount,
            false,              // post-EQ disabled
            0,
            true                // limiter stage enabled
        )
        // Explicitly set FFT window length. DP's silent default is
        // typically ~32 ms, which smears bass periods and adds pre/post-
        // echo on transients. 10 ms = ~480-sample FFT @ 48 kHz, the
        // transient-friendly value Wavelet uses for short-frame mode.
        configBuilder.setPreferredFrameDuration(10f)
        val config = configBuilder.build()

        try {
            lastEq = eq
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                // Stage population order matches Wavelet's a6/b0.smali:
                // limiter → MBC → pre-EQ → setEnabled. Setting enabled
                // last avoids DP processing audio with default bands
                // before our real values arrive.

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Dummy MBC band when user has MBC off — passthrough so
                // the audio is unchanged but the stage reports the
                // band slot DP allocated.
                if (!mbcEnabled) {
                    val dummyMbc = DynamicsProcessing.MbcBand(
                        false,        // enabled = false (passthrough)
                        20000f,       // cutoff well above audible
                        1f, 100f, 1f, 0f, 0f, -120f, 1f, 0f, 0f
                    )
                    setMbcBandByChannelIndex(0, 0, dummyMbc)
                    setMbcBandByChannelIndex(1, 0, dummyMbc)
                }

                // Apply parametric response, then enable. drainPendingApply
                // blocks until the band write lands so DP doesn't briefly
                // run with default bands.
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
            Log.d(TAG, "DynamicsProcessing started with $bandCount bands")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicsProcessing", e)
            dynamicsProcessing = null
            isActive = false
        }
    }

    /**
     * Block the calling thread until any pending [applyParametricResponse]
     * worker job has executed. Used in [start] so the band feed lands
     * BEFORE we toggle `enabled = true` — the Wavelet ordering. No-op
     * when no job is queued.
     */
    private fun drainPendingApply() {
        val job = pendingApply ?: return
        // Remove from queue, run synchronously on the caller's thread.
        // Safe because the binder calls inside are thread-agnostic;
        // only ordering matters, not which thread issues them.
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
        // Cheap math (response sampling) on the caller's thread since it
        // touches the live ParametricEqualizer owned by the UI thread.
        // The expensive part — binder transactions into AudioFlinger —
        // is dispatched to the worker thread.
        //
        // Path selection by UI mode:
        //   • graphic / table / simple → convertDirect: user
        //     (freq, gain) pairs verbatim, no biquad math (Wavelet /
        //     Poweramp pattern).
        //   • parametric → convertFeatureAware: biquad composite
        //     sampled at every parametric centre + per-filter-type
        //     support points around each.
        val cutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        val pathTag: String
        if (useDirectGraphicPath) {
            val l = ParametricToDpConverter.convertDirect(leftEq)
            cutoffs = l.cutoffs
            leftGains = l.gains
            rightGains = if (leftEq === rightEq) leftGains.copyOf()
                else ParametricToDpConverter.convertDirect(rightEq).gains
            pathTag = "direct"
        } else {
            val l = ParametricToDpConverter.convertFeatureAware(leftEq)
            cutoffs = l.cutoffs
            leftGains = l.gains
            rightGains = if (leftEq === rightEq) leftGains.copyOf()
                else ParametricToDpConverter.convertFeatureAware(rightEq).gains
            pathTag = "feature-aware"
        }

        // Auto-gain: bring the loudest band to ≤ 0 dB. Applied as a flat
        // shift to all bands so it preserves EQ shape.
        if (autoGainEnabled) {
            var peak = Float.NEGATIVE_INFINITY
            for (g in leftGains) if (g > peak) peak = g
            for (g in rightGains) if (g > peak) peak = g
            lastAutoGainOffset = if (peak > 0f) -peak else 0f
            if (lastAutoGainOffset != 0f) {
                for (i in leftGains.indices) leftGains[i] += lastAutoGainOffset
                for (i in rightGains.indices) rightGains[i] += lastAutoGainOffset
            }
        } else {
            lastAutoGainOffset = 0f
        }

        // Channel offsets and preamp are per-channel flat shifts —
        // they belong on DP's input-gain stage, NOT baked into band
        // gains. Wavelet's a6/b0.smali pattern: setInputGainbyChannel
        // (0, leftSum) and (1, rightSum). Keeps band gains as pure EQ
        // shape so DP's headroom logic doesn't compete with balance.
        val (leftOffsetDb, rightOffsetDb) = computeChannelOffsets()

        Log.d(TAG, "[DUMP] preamp=${"%.2f".format(preampGainDb)} dB, " +
            "autoGain=$autoGainEnabled (offset=${"%.2f".format(lastAutoGainOffset)} dB), " +
            "channelOffsets L=${"%.2f".format(leftOffsetDb)} R=${"%.2f".format(rightOffsetDb)} dB, " +
            "bands=${cutoffs.size}, path=$pathTag")

        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        // Input gain composition: preamp + per-channel offset.
        // Auto-gain is already baked into band gains above (it's a
        // shape-preserving shift), so don't double-add it here.
        val leftInputGainDb = preampGainDb + leftOffsetDb
        val rightInputGainDb = preampGainDb + rightOffsetDb
        val job = Runnable {
            try {
                // Wavelet calls dp.hasControl() before applying any
                // settings (a6/n0.smali). If another app stole control
                // of session 0, all setters silently no-op without it.
                // Skip the apply — reclaimSession() will recreate when
                // control is regained.
                if (!dp.hasControl()) {
                    Log.w(TAG, "DP lost control — skipping band write")
                    return@Runnable
                }
                // Push preamp + per-channel offset via DP's input-gain
                // stage. Wavelet uses different values per channel
                // (a6/b0.smali:343,379).
                try {
                    dp.setInputGainbyChannel(0, leftInputGainDb)
                    dp.setInputGainbyChannel(1, rightInputGainDb)
                } catch (e: Throwable) {
                    Log.w(TAG, "setInputGainbyChannel failed", e)
                }
                // Atomic per-channel EQ swap. One Eq object per channel
                // → one binder transaction per channel. Audio engine
                // never observes partial state during the update.
                val leftEqObj = DynamicsProcessing.Eq(true, true, n)
                val rightEqObj = DynamicsProcessing.Eq(true, true, n)
                for (i in 0 until n) {
                    leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i]))
                    rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i]))
                }
                dp.setPreEqByChannelIndex(0, leftEqObj)
                dp.setPreEqByChannelIndex(1, rightEqObj)
            } catch (e: Exception) {
                Log.e(TAG, "DP band write failed", e)
            } finally {
                pendingApply = null
            }
        }
        pendingApply?.let { workerHandler.removeCallbacks(it) }
        pendingApply = job
        workerHandler.post(job)
    }

    /**
     * Compute the flat dB offset to apply to each channel via the
     * input-gain stage, combining per-channel preamp gain with balance
     * attenuation.
     *
     * Balance semantics: the side being panned TOWARD stays at 0 dB
     * relative to preamp; the opposite side is attenuated. Pan wins
     * over preamp, so a fully-left pan mutes the right channel
     * regardless of right preamp.
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
            for (i in bands.indices) {
                val b = bands[i]
                val cutoff = if (i < crossovers.size) crossovers[i] else 20000f
                val mbcBand = DynamicsProcessing.MbcBand(
                    b.enabled,
                    cutoff,
                    b.attackMs,
                    b.releaseMs,
                    b.ratio,
                    b.thresholdDb,
                    b.kneeDb,
                    b.noiseGateDb,
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

    /** Apply the current limiter fields to the live DP instance without
     *  rebuilding it. Dispatched to the worker thread so a slider drag
     *  doesn't stall the UI thread on a binder transaction. Coalesced with
     *  the band-write job so back-to-back slider ticks collapse to one
     *  write. Falls back silently when DP isn't running. */
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
