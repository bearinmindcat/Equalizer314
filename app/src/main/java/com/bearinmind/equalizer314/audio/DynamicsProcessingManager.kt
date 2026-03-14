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
                    true, true, 0,
                    1f, 50f, 10f, -0.5f, 0f
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

        for (i in 0 until ParametricToDpConverter.numBands) {
            val eqBand = DynamicsProcessing.EqBand(true, cutoffs[i], gains[i])
            dp.setPreEqBandByChannelIndex(0, i, eqBand)
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
