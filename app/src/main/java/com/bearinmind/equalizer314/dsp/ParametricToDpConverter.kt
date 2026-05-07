package com.bearinmind.equalizer314.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Converts parametric EQ settings into DynamicsProcessing-compatible
 * (cutoff, gain) pairs.
 *
 * Two paths:
 *   • [convertFeatureAware] — for parametric mode. Samples the biquad
 *     composite at every parametric centre + per-filter-type support
 *     points around each, with Wavelet's frequency table as fillers.
 *   • [convertDirect] — for graphic / table / simple modes. Each band's
 *     stored (frequency, gain) pair fed straight to DP, no biquad math.
 *     Same approach Wavelet / Poweramp / RootlessJamesDSP use.
 *
 * The cutoff layout is always Wavelet's 127-band frequency table
 * (decompiled from `a6.z.f608g[]`) — these are the EXACT frequencies
 * AutoEQ's `GraphicEQ.txt` format uses, so a graphic profile import
 * lands every (freq, gain) pair on a real DP cutoff with zero
 * interpolation error.
 */
object ParametricToDpConverter {

    var numBands: Int = 127
        private set
    private const val MIN_FREQ = 10f
    private const val MAX_FREQ = 22000f

    /**
     * Wavelet's 127-band frequency table (decompiled from
     * com.pittvandewitt.wavelet's `a6.z.f608g[]` field). These are the
     * EXACT frequencies AutoEQ's `GraphicEQ.txt` format uses — Wavelet
     * was deliberately built around this layout so a graphic profile
     * import lands every (freq, gain) pair on a real DP cutoff with
     * zero interpolation error.
     */
    private val WAVELET_FREQUENCIES = floatArrayOf(
        20f, 21f, 22f, 23f, 24f, 26f, 27f, 29f, 30f, 32f,
        34f, 36f, 38f, 40f, 43f, 45f, 48f, 50f, 53f, 56f,
        59f, 63f, 66f, 70f, 74f, 78f, 83f, 87f, 92f, 97f,
        103f, 109f, 115f, 121f, 128f, 136f, 143f, 151f, 160f, 169f,
        178f, 188f, 199f, 210f, 222f, 235f, 248f, 262f, 277f, 292f,
        309f, 326f, 345f, 364f, 385f, 406f, 429f, 453f, 479f, 506f,
        534f, 565f, 596f, 630f, 665f, 703f, 743f, 784f, 829f, 875f,
        924f, 977f, 1032f, 1090f, 1151f, 1216f, 1284f, 1357f, 1433f, 1514f,
        1599f, 1689f, 1784f, 1885f, 1991f, 2103f, 2221f, 2347f, 2479f, 2618f,
        2766f, 2921f, 3086f, 3260f, 3443f, 3637f, 3842f, 4058f, 4287f, 4528f,
        4783f, 5052f, 5337f, 5637f, 5955f, 6290f, 6644f, 7018f, 7414f, 7831f,
        8272f, 8738f, 9230f, 9749f, 10298f, 10878f, 11490f, 12137f, 12821f, 13543f,
        14305f, 15110f, 15961f, 16860f, 17809f, 18812f, 19871f
    )

    /** Wavelet's 127-band cutoff frequencies (upper edge of each band). */
    val cutoffFrequencies: FloatArray
        get() = WAVELET_FREQUENCIES.copyOf()

    fun setNumBands(count: Int) {
        // Currently fixed at Wavelet's 127. The setter is kept so
        // DynamicsProcessingManager can ask for "127" explicitly during
        // start() and any future variants can be added without renaming.
        numBands = WAVELET_FREQUENCIES.size
    }

    /** Center frequencies (geometric mean of each band's edges). */
    val centerFrequencies: FloatArray
        get() {
            val cutoffs = cutoffFrequencies
            return FloatArray(numBands) { i ->
                val lower = if (i == 0) MIN_FREQ else cutoffs[i - 1]
                sqrt(lower * cutoffs[i])
            }
        }

    /** Cutoff + gain pair returned by [convertFeatureAware] / [convertDirect].
     *  Both arrays always have length [numBands]; cutoff[i] pairs with gain[i]. */
    data class ConvertedBands(val cutoffs: FloatArray, val gains: FloatArray)

    /**
     * Direct path for graphic / table / simple modes — no biquad math.
     *
     * Each enabled band's `(frequency, gain)` pair is fed straight to
     * DP using Wavelet's 127-band frequency table as cutoffs. For
     * frequencies between user points, gains are linearly interpolated
     * in (log f, dB) space. Same data flow Wavelet / Poweramp /
     * RootlessJamesDSP use.
     *
     * For an AutoEQ `GraphicEQ.txt` import (whose freqs are already at
     * Wavelet's 127 positions), every user value lands exactly on a DP
     * cutoff with zero interpolation error.
     */
    fun convertDirect(eq: ParametricEqualizer): ConvertedBands {
        // 1. Collect enabled (freq, gain) pairs in freq-sorted order.
        data class Pt(val f: Float, val g: Float)
        val pts = mutableListOf<Pt>()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            if (!band.enabled) continue
            val f = band.frequency
            if (f !in MIN_FREQ..MAX_FREQ) continue
            pts.add(Pt(f, band.gain))
        }
        pts.sortBy { it.f }

        // 2. Use Wavelet's 127 freqs verbatim as cutoffs and interpolate
        //    gains from user points in (log f, dB) space.
        val cutoffs = WAVELET_FREQUENCIES.copyOf()
        if (pts.isEmpty()) return ConvertedBands(cutoffs, FloatArray(cutoffs.size) { 0f })
        val freqs = FloatArray(pts.size) { pts[it].f }
        val gains = FloatArray(pts.size) { pts[it].g }
        val outGains = FloatArray(cutoffs.size) { i ->
            val f = cutoffs[i]
            if (f <= freqs[0]) gains[0]
            else if (f >= freqs[freqs.size - 1]) gains[freqs.size - 1]
            else {
                var lo = 0; var hi = freqs.size - 1
                while (lo + 1 < hi) {
                    val mid = (lo + hi) ushr 1
                    if (freqs[mid] <= f) lo = mid else hi = mid
                }
                val t = (log10(f) - log10(freqs[lo])) /
                        (log10(freqs[hi]) - log10(freqs[lo]))
                gains[lo] + t * (gains[hi] - gains[lo])
            }
        }
        return ConvertedBands(cutoffs, outGains)
    }

    /**
     * Feature-aware sampling for parametric mode: places anchor points
     * at every enabled parametric band's centre frequency plus per-
     * filter-type support points along its characteristic magnitude
     * shape, then fills remaining slots from Wavelet's 127-band table.
     *
     * Without this, a Q=10 BELL at 7878 Hz +7 dB would be sampled at
     * the nearest log-spaced cutoffs (7681 / 8157 Hz), missing the
     * narrow peak entirely — DP would render ~+2 dB instead of +7 dB.
     * With the anchor at exactly 7878 Hz, the peak is captured.
     *
     * Returns a [ConvertedBands] with both cutoffs and gains so the
     * caller can hand them to DP together.
     */
    fun convertFeatureAware(eq: ParametricEqualizer): ConvertedBands {
        val total = WAVELET_FREQUENCIES.size

        // 1. For each enabled source filter, place an anchor at fc and
        //    several support points along its magnitude shape.
        val anchorPeaks = mutableListOf<Float>()
        val supportPoints = mutableListOf<Float>()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            if (!band.enabled) continue
            val f = band.frequency
            if (f !in MIN_FREQ..MAX_FREQ) continue
            anchorPeaks.add(f)
            for (s in supportsForBand(band)) {
                if (s in MIN_FREQ..MAX_FREQ) supportPoints.add(s)
            }
        }

        // Cap the feature budget: anchors get unlimited slots, supports
        // share whatever's left up to 60 % of total.
        val featureBudget = (total * 6 / 10).coerceAtLeast(anchorPeaks.size)
        val supportsKept = supportPoints
            .sorted()
            .distinct()
            .take((featureBudget - anchorPeaks.size).coerceAtLeast(0))
        val effectivePeaksAll = (anchorPeaks + supportsKept).sorted()

        // 2. Fill the rest from Wavelet's 127-band table — broad-curve
        //    sampling matches AutoEQ's expected positions exactly.
        val baseCount = (total - effectivePeaksAll.size).coerceAtLeast(0)
        val basePoints = mutableListOf<Float>()
        if (baseCount > 0) {
            val step = (WAVELET_FREQUENCIES.size.toFloat() / baseCount).coerceAtLeast(1f)
            var idx = 0f
            repeat(baseCount) {
                val ix = idx.toInt().coerceIn(0, WAVELET_FREQUENCIES.size - 1)
                basePoints.add(WAVELET_FREQUENCIES[ix])
                idx += step
            }
        }

        // 3. Merge, sort, dedupe within 0.5 % tolerance — anchors win
        //    against any nearby filler / support.
        // Priority: anchor (2) > support (1) > Wavelet-table filler (0).
        data class Candidate(val freq: Float, val priority: Int)
        val merged = mutableListOf<Candidate>()
        for (f in basePoints) merged.add(Candidate(f, 0))
        for (f in supportPoints) merged.add(Candidate(f, 1))
        for (f in anchorPeaks) merged.add(Candidate(f, 2))
        merged.sortBy { it.freq }
        val deduped = mutableListOf<Candidate>()
        for (c in merged) {
            val last = deduped.lastOrNull()
            if (last == null || (c.freq - last.freq) > last.freq * 0.005f) {
                deduped.add(c)
            } else if (c.priority > last.priority) {
                deduped[deduped.size - 1] = c
            }
        }

        // 4. Pad up to exactly [total] points by inserting at the
        //    largest log-gap. DP needs all band slots filled.
        while (deduped.size < total) {
            var bestIdx = 0
            var bestGap = 0f
            for (i in 0 until deduped.size - 1) {
                val gap = ln(deduped[i + 1].freq / deduped[i].freq)
                if (gap > bestGap) { bestGap = gap; bestIdx = i }
            }
            val insertF = sqrt(deduped[bestIdx].freq * deduped[bestIdx + 1].freq)
            deduped.add(bestIdx + 1, Candidate(insertF, 0))
        }

        val capped = deduped.take(total)
        val cutoffs = FloatArray(total) { capped[it].freq }
        val gains = FloatArray(total) { i -> eq.getFrequencyResponse(cutoffs[i]) }
        return ConvertedBands(cutoffs, gains)
    }

    /**
     * Per-filter-type support points for [convertFeatureAware]. See the
     * earlier git history for the full per-type rules. Returns
     * frequencies (Hz) that bracket the filter's characteristic
     * magnitude shape so DP's piecewise-linear reconstruction stays
     * faithful to the parametric curve. The anchor at fc is added by
     * the caller — this function returns only the *additional* shaping
     * points.
     */
    private fun supportsForBand(band: ParametricEqualizer.EqualizerBand): List<Float> {
        val f = band.frequency
        val q = band.q.toFloat().coerceAtLeast(0.1f)
        val absGain = abs(band.gain)
        val bw = f / q

        return when (band.filterType) {
            BiquadFilter.FilterType.BELL -> when {
                absGain < 0.5f -> emptyList()
                q > 7f -> listOf(
                    f - bw / 2f, f - bw / 4f, f - bw / 8f, f - bw / 16f,
                    f + bw / 16f, f + bw / 8f, f + bw / 4f, f + bw / 2f
                )
                q > 3f -> listOf(
                    f - bw / 4f, f - bw / 8f, f + bw / 8f, f + bw / 4f
                )
                else -> listOf(f * 0.5f, f * 0.75f, f * 1.333f, f * 2.0f)
            }
            BiquadFilter.FilterType.BAND_PASS -> when {
                q > 7f -> listOf(
                    f - bw / 2f, f - bw / 4f, f - bw / 8f,
                    f + bw / 8f, f + bw / 4f, f + bw / 2f
                )
                q > 3f -> listOf(
                    f - bw / 4f, f - bw / 8f, f + bw / 8f, f + bw / 4f
                )
                else -> listOf(f * 0.5f, f * 0.75f, f * 1.333f, f * 2.0f)
            }
            BiquadFilter.FilterType.NOTCH -> when {
                q > 7f -> listOf(
                    f - bw / 4f, f - bw / 8f, f - bw / 16f,
                    f + bw / 16f, f + bw / 8f, f + bw / 4f
                )
                q > 3f -> listOf(
                    f - bw / 4f, f - bw / 16f, f + bw / 16f, f + bw / 4f
                )
                else -> listOf(f * 0.5f, f * 0.85f, f * 1.176f, f * 2.0f)
            }
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF -> when {
                absGain < 0.5f -> emptyList()
                q > 1.5f -> listOf(
                    f * 0.25f, f * 0.5f, f * 0.71f,
                    f * 1.41f, f * 2.0f, f * 4.0f
                )
                else -> listOf(f * 0.25f, f * 0.5f, f * 2.0f, f * 4.0f)
            }
            BiquadFilter.FilterType.LOW_SHELF_1,
            BiquadFilter.FilterType.HIGH_SHELF_1 ->
                if (absGain < 0.5f) emptyList()
                else listOf(f * 0.25f, f * 0.5f, f * 2.0f, f * 4.0f)
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS -> when {
                q > 1.5f -> listOf(
                    f * 0.5f, f * 0.71f,
                    f - bw / 4f, f + bw / 4f,
                    f * 1.41f, f * 2.0f
                )
                else -> listOf(f * 0.5f, f * 0.71f, f * 1.41f, f * 2.0f)
            }
            BiquadFilter.FilterType.LOW_PASS_1,
            BiquadFilter.FilterType.HIGH_PASS_1 ->
                listOf(f * 0.5f, f * 2.0f)
            BiquadFilter.FilterType.ALL_PASS -> emptyList()
        }
    }
}
