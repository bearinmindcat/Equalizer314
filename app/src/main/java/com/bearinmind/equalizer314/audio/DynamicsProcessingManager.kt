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

    // Channel Side Options — balance, per-channel preamp, L/R swap.
    // All applied as flat dB offsets baked into the PreEq band gains per channel.
    var channelBalancePercent: Int = 0     // -100..100, 0 = center
    var leftChannelGainDb: Float = 0f      // -12..12
    var rightChannelGainDb: Float = 0f     // -12..12
    var channelSwapEnabled: Boolean = false

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
        val dp = dynamicsProcessing ?: return

        // If band count changed, must recreate the DP instance
        if (ParametricToDpConverter.numBands != currentBandCount) {
            Log.d(TAG, "Band count changed ($currentBandCount -> ${ParametricToDpConverter.numBands}), recreating DP")
            start(eq)
            return
        }

        try {
            applyParametricResponse(dp, eq)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update DynamicsProcessing", e)
        }
    }

    private fun applyParametricResponse(dp: DynamicsProcessing, eq: ParametricEqualizer) {
        val cutoffs = ParametricToDpConverter.cutoffFrequencies
        val gains = ParametricToDpConverter.convert(eq)

        // Apply global preamp offset
        if (preampGainDb != 0f) {
            for (i in gains.indices) gains[i] += preampGainDb
        }

        // Auto-gain: subtract peak boost to prevent clipping
        if (autoGainEnabled) {
            val peakGain = gains.max()
            lastAutoGainOffset = if (peakGain > 0f) -peakGain else 0f
            if (lastAutoGainOffset != 0f) {
                for (i in gains.indices) gains[i] += lastAutoGainOffset
            }
        } else {
            lastAutoGainOffset = 0f
        }

        // Per-channel offsets: balance + preamp (L/R), optional swap.
        val (leftOffsetDb, rightOffsetDb) = computeChannelOffsets()

        // When swap is on, left's gains go onto channel 1 and right's onto channel 0.
        val (ch0OffsetDb, ch1OffsetDb) =
            if (channelSwapEnabled) Pair(rightOffsetDb, leftOffsetDb)
            else Pair(leftOffsetDb, rightOffsetDb)

        for (i in 0 until ParametricToDpConverter.numBands) {
            dp.setPreEqBandByChannelIndex(
                0, i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i] + ch0OffsetDb)
            )
            dp.setPreEqBandByChannelIndex(
                1, i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i] + ch1OffsetDb)
            )
        }
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

    /** Re-apply the current EQ with fresh channel settings (balance, preamp, swap). */
    fun updateChannelSettings() {
        val dp = dynamicsProcessing ?: return
        val eq = lastEq ?: return
        try {
            applyParametricResponse(dp, eq)
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

    fun stop() {
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
