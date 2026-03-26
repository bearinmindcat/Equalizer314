package com.bearinmind.equalizer314.audio

/**
 * Asymmetric exponential moving average smoother.
 * Fast attack (peaks rise quickly), slow release (peaks fall slowly).
 * This gives the FabFilter Pro-Q / EQ Eight "liquid" look.
 */
class SpectrumSmoother(private val binCount: Int) {

    private val smoothed = FloatArray(binCount) { -100f }

    // Asymmetric smoothing coefficients
    private val attackAlpha = 0.4f    // fast rise: 0.3–0.5
    private val releaseAlpha = 0.05f  // slow fall: 0.03–0.1 (lower = smoother decay)

    fun smooth(newValues: FloatArray): FloatArray {
        val count = minOf(newValues.size, smoothed.size)
        for (i in 0 until count) {
            val alpha = if (newValues[i] > smoothed[i]) attackAlpha else releaseAlpha
            smoothed[i] = alpha * newValues[i] + (1f - alpha) * smoothed[i]
        }
        return smoothed.copyOf()
    }

    fun reset() {
        smoothed.fill(-100f)
    }
}
