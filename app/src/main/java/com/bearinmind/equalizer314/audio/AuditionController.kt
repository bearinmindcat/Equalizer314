package com.bearinmind.equalizer314.audio

import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqStateManager
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Surgical solo audition ("Band Solo").
 *
 * Long-pressing (and holding) any band node replaces the whole live
 * DynamicsProcessing pre-EQ staircase with a narrow band-pass tuned to that
 * band — instantly, with zero DP rebuild, because the converter samples the
 * overridden frequency response and re-writes only the flat band gains. The
 * normal EQ is untouched in memory; release restores it exactly.
 *
 * Restrictions when active:
 *  - normal EQ pushes are suppressed globally (EqStateManager audition
 *    hook) so a second channel can't stomp the solo staircase;
 *  - dragging the held node retargets the audition's center frequency;
 *  - requires an active DynamicsProcessing session — the caller shows a
 *    toast in that case.
 */
class AuditionController(private val stateManager: EqStateManager) {

    companion object {
        /** 2nd-order sections cascaded for the solo skirt (3 × ~12 dB/oct). */
        const val CASCADE_COUNT = 3

        /** Extra thinness for the solo band-pass (resonance = Q of this filter). */
        const val DEFAULT_SOLO_Q = 1.4

        const val MIN_SOLO_FREQ = 20f
        const val MAX_SOLO_FREQ = 20000f
    }

    // ── The synthetic band-pass passed to the DP converter ──────────────
    /**
     * A ParametricEqualizer-shaped object with NO bands whose response is a
     * pure band-pass: [cascadeCount] cascaded 2nd-order skirt-slope sections
     * around [centerFrequencyHz]. It never touches the audio process path;
     * only [getFrequencyResponse] is consumed (sampled by
     * [ParametricToDpConverter] into the DP staircase gains).
     */
    class BandSoloEq : ParametricEqualizer(
        ParametricToDpConverter.deviceSampleRateHz.toInt().coerceAtLeast(8000)
    ) {
        var centerFrequencyHz: Float = 1000f
        var soloQ: Double = DEFAULT_SOLO_Q
        var cascadeCount: Int = CASCADE_COUNT

        override fun getFrequencyResponse(frequency: Float): Float {
            val f0 = centerFrequencyHz
            if (f0 <= 0f) return 0f
            val r = (frequency / f0).toDouble()
            // Normalized 2nd-order band-pass magnitude (0 dB at center)
            val q = max(soloQ, 0.01)
            val denom = sqrt((1.0 - r * r) * (1.0 - r * r) + (r / q) * (r / q))
            val mag0 = if (denom > 1e-9) r / denom else 1.0
            val dbOne = 20.0 * log10(max(mag0, 1e-9))
            val totalDb = dbOne * cascadeCount
            return totalDb.toFloat().coerceAtMost(0f)
        }
    }

    private val solo = BandSoloEq()

    var isActive = false
        private set
    var soloedBandIndex = -1
        private set

    private var savedConverterOverlay: ParametricEqualizer? = null

    /** Suppressor hook referenced by EqStateManager.pushEqUpdate. */
    fun shouldSuppressNormalPush(): Boolean = isActive

    private fun dm(): DynamicsProcessingManager? = stateManager.eqService?.dynamicsManager

    /**
     * Begin audition of [bandIndex]. No-ops (returns false) when the DP
     * session is not active — the caller shows a toast for that.
     */
    fun startAudition(bandIndex: Int): Boolean {
        if (isActive) return true
        val dm = dm()
        if (!stateManager.isProcessing || dm == null || !dm.isActive) return false
        val service = stateManager.eqService ?: return false

        val targetFreq = pickBandFrequency(bandIndex) ?: return false

        // Commit whatever throttled update is in flight so the DP matches the graph exactly
        stateManager.flushEqUpdate()

        // The solo staircase must be pure: no CSE overlay mixed into it
        savedConverterOverlay = ParametricToDpConverter.overlayEq
        ParametricToDpConverter.overlayEq = null

        isActive = true
        soloedBandIndex = bandIndex
        setSoloCenter(targetFreq)
        // Freeze auto-gain while auditioning so the duck doesn't re-trigger
        dm.gainHold = true
        service.updateEqPerChannel(solo, solo)
        return true
    }

    /** Retarget the active audition (dragging the held node). */
    fun retargetAudition(bandIndex: Int, frequency: Float) {
        if (!isActive) return
        soloedBandIndex = bandIndex
        setSoloCenter(frequency)
        stateManager.eqService?.updateEqPerChannel(solo, solo)
    }

    /** Stop the audition and restore the real EQ exactly as before. */
    fun stopAudition() {
        if (!isActive) return
        isActive = false
        soloedBandIndex = -1

        // Band edits made during the solo live in the in-memory EQs (pushes
        // were suppressed by design), so pushing the live state restores the
        // staircase with any mid-solo tweaks included.
        ParametricToDpConverter.overlayEq = savedConverterOverlay
        savedConverterOverlay = null
        val dm = dm()
        if (dm != null) dm.gainHold = false
        stateManager.pushEqUpdate()
    }

    private fun setSoloCenter(freq: Float) {
        solo.centerFrequencyHz = freq.coerceIn(MIN_SOLO_FREQ, MAX_SOLO_FREQ)
    }

    /** Frequency of the long-pressed band in the channel view being edited. */
    private fun pickBandFrequency(bandIndex: Int): Float? =
        stateManager.parametricEq.getBand(bandIndex)?.frequency
}
