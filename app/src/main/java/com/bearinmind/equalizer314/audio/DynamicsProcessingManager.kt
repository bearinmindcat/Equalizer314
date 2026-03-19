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
    var isActive = false
        private set

    // Preamp
    var preampGainDb: Float = 0f

    // Auto-gain
    var autoGainEnabled: Boolean = false
    var lastAutoGainOffset: Float = 0f
        private set

    // Limiter
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 50f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -0.5f
    var limiterPostGainDb: Float = 0f

    fun start(eq: ParametricEqualizer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }

        stop() // Clean up any existing instance

        val bandCount = ParametricToDpConverter.numBands

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            1,          // channel count
            true,       // pre-EQ enabled
            bandCount,  // pre-EQ band count
            false,      // MBC disabled
            0,
            false,      // post-EQ disabled
            0,
            true        // limiter enabled
        ).build()

        try {
            dynamicsProcessing = DynamicsProcessing(0, 0, config).apply {
                enabled = true

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)

                // Apply parametric response sampled at N frequencies
                applyParametricResponse(this, eq)
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

        // Apply preamp offset
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

        for (i in 0 until ParametricToDpConverter.numBands) {
            val eqBand = DynamicsProcessing.EqBand(true, cutoffs[i], gains[i])
            dp.setPreEqBandByChannelIndex(0, i, eqBand)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update limiter", e)
        }
    }

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
