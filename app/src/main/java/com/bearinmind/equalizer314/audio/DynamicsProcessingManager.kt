package com.bearinmind.equalizer314.audio

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter

/**
 * Lightweight system-wide EQ using Android's built-in DynamicsProcessing API.
 * Samples the parametric EQ response at N log-spaced frequencies via ParametricToDpConverter,
 * feeding the result to DynamicsProcessing's FFT engine for smooth interpolation.
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

    // Limiter
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 0.01f
    var limiterReleaseMs: Float = 1f
    var limiterRatio: Float = 2f
    var limiterThresholdDb: Float = 0f
    var limiterPostGainDb: Float = 0f

    // Channel Side Options — balance + per-channel preamp.
    // All applied as flat dB offsets baked into the PreEq band gains per channel.
    var channelBalancePercent: Int = 0     // -100..100, 0 = center
    var leftChannelGainDb: Float = 0f      // -12..12
    var rightChannelGainDb: Float = 0f     // -12..12

    // Background thread for the per-band binder calls. Each EQ update issues
    // 2 × numBands setPreEqBandByChannelIndex() transactions; running them
    // on the UI thread blocks both rendering and (under contention) the
    // audio path during a drag.
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

        val bandCount = ParametricToDpConverter.numBands

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,          // channel count (stereo)
            true,       // pre-EQ enabled
            bandCount,  // pre-EQ band count
            mbcEnabled, // MBC enabled/disabled
            if (mbcEnabled) mbcBandCount else 0,
            false,      // post-EQ disabled
            0,
            true        // limiter enabled
        ).build()

        try {
            lastEq = eq
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                enabled = true

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Apply parametric response sampled at N frequencies
                applyParametricResponse(this, eq)

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
            Log.d(TAG, "DynamicsProcessing started with $bandCount bands (parametric approx)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicsProcessing", e)
            dynamicsProcessing = null
            isActive = false
        }
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
        // Cheap math (response sampling, gain offsets) on the caller's thread,
        // since it touches the live ParametricEqualizer which is owned by the
        // UI thread. The expensive part — 2 × numBands binder transactions
        // into AudioFlinger — is dispatched to the worker thread.
        val cutoffs = ParametricToDpConverter.cutoffFrequencies
        val leftGains = ParametricToDpConverter.convert(leftEq)
        val rightGains = if (leftEq === rightEq) leftGains.copyOf()
            else ParametricToDpConverter.convert(rightEq)

        if (preampGainDb != 0f) {
            for (i in leftGains.indices) leftGains[i] += preampGainDb
            for (i in rightGains.indices) rightGains[i] += preampGainDb
        }

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

        val (leftOffsetDb, rightOffsetDb) = computeChannelOffsets()
        for (i in leftGains.indices) leftGains[i] += leftOffsetDb
        for (i in rightGains.indices) rightGains[i] += rightOffsetDb

        // Coalesce: drop any in-flight job in favour of the latest gains. The
        // Volatile read is a stale-but-correct check; the only consequence of
        // a race is one extra binder loop, which is harmless.
        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        val job = Runnable {
            try {
                for (i in 0 until n) {
                    dp.setPreEqBandByChannelIndex(
                        0, i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i])
                    )
                    dp.setPreEqBandByChannelIndex(
                        1, i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i])
                    )
                }
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
     * Compute the flat dB offset to apply to each channel's pre-EQ bands,
     * combining per-channel preamp gain with balance attenuation.
     *
     * Balance semantics: the side being panned TOWARD stays at 0 dB relative
     * to preamp; the opposite side is attenuated. Pan wins over preamp, so a
     * fully-left pan mutes the right channel regardless of right preamp.
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
