package com.bearinmind.equalizer314.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Converts parametric EQ settings (with Q, filter types, bell curves)
 * into DynamicsProcessing-compatible band gains.
 *
 * Samples the composite frequency response of the parametric EQ at N
 * log-spaced frequencies, producing (cutoffFrequency, gain) pairs that
 * approximate the parametric curves using DynamicsProcessing's FFT engine.
 */
object ParametricToDpConverter {

    var numBands: Int = 128
        private set
    private const val MIN_FREQ = 10f
    private const val MAX_FREQ = 22000f

    private var _cutoffFrequencies: FloatArray? = null

    /** Log-spaced cutoff frequencies (upper edge of each band). Recomputed when numBands changes. */
    val cutoffFrequencies: FloatArray
        get() {
            var cached = _cutoffFrequencies
            if (cached == null || cached.size != numBands) {
                cached = computeCutoffs(numBands)
                _cutoffFrequencies = cached
            }
            return cached
        }

    fun setNumBands(count: Int) {
        numBands = count.coerceIn(32, 128)
        _cutoffFrequencies = null // invalidate cache
    }

    private fun computeCutoffs(bandCount: Int): FloatArray {
        val cutoffs = FloatArray(bandCount)
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)
        for (i in 0 until bandCount) {
            val logFreq = logMin + (i + 1).toFloat() / bandCount * (logMax - logMin)
            cutoffs[i] = 10f.pow(logFreq)
        }
        return cutoffs
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

    /**
     * Sample the parametric EQ's composite frequency response at each band's
     * geometric center, returning the gain in dB for each band.
     */
    fun convert(eq: ParametricEqualizer): FloatArray {
        val centers = centerFrequencies
        return FloatArray(numBands) { i -> eq.getFrequencyResponse(centers[i]) }
    }

    /** Cutoff + gain pair returned by [convertFeatureAware] / [convertDirect].
     *  Both arrays always have length [numBands]; cutoff[i] pairs with gain[i]. */
    data class ConvertedBands(val cutoffs: FloatArray, val gains: FloatArray)

    /**
     * Direct path for graphic / table / simple modes — no biquad math.
     *
     * Wavelet, Poweramp, RootlessJamesDSP all hand DP `(freq, gain)` pairs
     * verbatim from the user's input. 314Eq's legacy + feature-aware paths
     * route every UI mode through [ParametricEqualizer]'s bell-filter chain
     * first, which means even in graphic mode each user point becomes a
     * BELL, all the BELLs interact, and DP sees the smoothed sum — not the
     * raw values. That's a 1–2 dB-per-band wander relative to the user's
     * actual graph.
     *
     * This path skips the biquad model entirely:
     *   - Each enabled band's `frequency` becomes a DP cutoff.
     *   - Each enabled band's `gain` (dB) becomes the DP gain at that cutoff.
     *   - No Q, no filter type, no composite — exactly what JamesDSP /
     *     Wavelet / Poweramp do. Filter types are ignored (graphic mode
     *     only has BELLs anyway; if the user has a real shelf or pass
     *     filter they should be in parametric mode where biquad math is
     *     correct).
     *
     * Remaining DP slots (when the user has fewer than [numBands] points)
     * are filled with log-spaced cutoffs whose gains are linearly
     * interpolated between the user's points in (log f, dB) space — same
     * behaviour DP would do internally between cutoffs, so explicit fill
     * doesn't change the audible curve, it just gives a complete band feed.
     */
    fun convertDirect(eq: ParametricEqualizer): ConvertedBands {
        val total = numBands

        // 1. Collect enabled (freq, gain) pairs in freq-sorted order. Drop
        //    out-of-range and disabled bands.
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

        // 2. Empty EQ → flat 0 dB at the cached log-spaced cutoffs.
        if (pts.isEmpty()) {
            return ConvertedBands(cutoffFrequencies.copyOf(), FloatArray(total) { 0f })
        }

        // 3. Linear interpolation in (log f, dB). Endpoints clamp to the
        //    nearest user point (no extrapolation past the user's range).
        val freqs = FloatArray(pts.size) { pts[it].f }
        val gains = FloatArray(pts.size) { pts[it].g }
        fun gainAt(f: Float): Float {
            if (f <= freqs[0]) return gains[0]
            val n = freqs.size
            if (f >= freqs[n - 1]) return gains[n - 1]
            var lo = 0
            var hi = n - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) ushr 1
                if (freqs[mid] <= f) lo = mid else hi = mid
            }
            val t = (log10(f) - log10(freqs[lo])) /
                    (log10(freqs[hi]) - log10(freqs[lo]))
            return gains[lo] + t * (gains[hi] - gains[lo])
        }

        // 4. Build the cutoff list: every user freq is an anchor (must
        //    survive dedup), the rest is log-spaced filler so DP has all
        //    [total] slots filled. Anchors win the same priority dedup as
        //    [convertFeatureAware] so a user point at e.g. 5500 Hz never
        //    gets snapped to a nearby log-spaced 5477.
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)
        val baseCount = (total - pts.size).coerceAtLeast(0)
        data class Cand(val f: Float, val isAnchor: Boolean)
        val merged = mutableListOf<Cand>()
        for (p in pts) merged.add(Cand(p.f, true))
        if (baseCount > 0) {
            for (i in 0 until baseCount) {
                val logF = logMin + (i + 0.5f) / baseCount * (logMax - logMin)
                merged.add(Cand(10f.pow(logF), false))
            }
        }
        merged.sortBy { it.f }

        // 5. Dedup within 0.5 % relative tolerance — anchors always win.
        val deduped = mutableListOf<Cand>()
        for (c in merged) {
            val last = deduped.lastOrNull()
            if (last == null || (c.f - last.f) > last.f * 0.005f) {
                deduped.add(c)
            } else if (c.isAnchor && !last.isAnchor) {
                deduped[deduped.size - 1] = c
            }
        }

        // 6. Pad up to [total] at the largest log-gap if dedup left holes.
        while (deduped.size < total) {
            var bestIdx = 0
            var bestGap = 0f
            for (i in 0 until deduped.size - 1) {
                val gap = ln(deduped[i + 1].f / deduped[i].f)
                if (gap > bestGap) { bestGap = gap; bestIdx = i }
            }
            val insertF = sqrt(deduped[bestIdx].f * deduped[bestIdx + 1].f)
            deduped.add(bestIdx + 1, Cand(insertF, false))
        }

        val capped = deduped.take(total)
        val cutoffs = FloatArray(total) { capped[it].f }
        val outGains = FloatArray(total) { gainAt(cutoffs[it]) }
        return ConvertedBands(cutoffs, outGains)
    }

    /**
     * Feature-aware sampling: instead of pure log-spaced cutoffs, this
     * variant injects each enabled parametric band's *centre frequency*
     * into the cutoff list before log-fill. That guarantees every
     * narrow peak/dip in the parametric profile (e.g. Q=10 corrections
     * around 7–8 kHz) lands on an actual sample point, instead of
     * being attenuated because the peak fell between two log-spaced
     * centres.
     *
     * Diagnostic comparison (Samson SR850 AutoEQ profile, log-spaced):
     *   src[7] BELL 7878 Hz +7.00 dB Q=10 → was sampled at +2.21 dB
     *   (4.8 dB error) because the nearest log-spaced centres at
     *   7681 and 8157 Hz both miss the narrow peak. With feature-aware
     *   sampling we add 7878 Hz directly to the cutoffs and the +7 dB
     *   is captured exactly.
     *
     * Returns a [ConvertedBands] with both cutoffs and gains so the
     * caller can hand them to DP together (DP needs the cutoffs to
     * change too — they're no longer the static [cutoffFrequencies]).
     */
    fun convertFeatureAware(eq: ParametricEqualizer): ConvertedBands {
        val total = numBands
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)

        // 1. For each enabled source filter, place an anchor point at
        //    the filter's centre frequency and several support points
        //    along its characteristic magnitude shape. Support count
        //    and spacing are scaled by:
        //      • filter type   (resonance vs slope vs flat)
        //      • Q             (narrower → tighter, denser inner points)
        //      • |gain| dB     (negligible bells/shelves don't burn slots)
        //      • frequency Hz  (out-of-range supports clipped to 10–22 kHz)
        //    anchorPeaks are "must keep" in dedup; supports beat
        //    log-spaced fillers but lose to anchors. See
        //    [supportsForBand] for the per-type rules.
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
        // share whatever's left up to 60 % of total. Reserves at least
        // 40 % for log-spaced fillers so the broad curve isn't ignored.
        val featureBudget = (total * 6 / 10).coerceAtLeast(anchorPeaks.size)
        val supportsKept = supportPoints
            .sorted()
            .distinct()
            .take((featureBudget - anchorPeaks.size).coerceAtLeast(0))
        val effectivePeaksAll = (anchorPeaks + supportsKept).sorted()

        // 2. Fill the rest with log-spaced sample points (centred log).
        val baseCount = (total - effectivePeaksAll.size).coerceAtLeast(0)
        val basePoints = mutableListOf<Float>()
        for (i in 0 until baseCount) {
            val logFreq = logMin + (i + 0.5f) / baseCount * (logMax - logMin)
            basePoints.add(10f.pow(logFreq))
        }

        // 3. Merge, sort, dedupe within 0.5 % relative tolerance — but
        //    when a parametric anchor and a log-spaced point are both
        //    within tolerance, the anchor wins (gets kept, log-spaced
        //    point is dropped). This is what last build's "5500 deduped
        //    to 5477" bug was: the parametric peak lost the tie.
        // Merge: tag each candidate so we can prefer anchors / support.
        // Priority: anchor (2) > support (1) > log-spaced (0).
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
                // Replace lower-priority neighbour with this higher-priority one.
                deduped[deduped.size - 1] = c
            }
        }

        // 4. Pad back up to exactly [total] points by inserting at the
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
     * Per-filter-type support points for [convertFeatureAware].
     *
     * Returns frequencies (Hz) that bracket the filter's characteristic
     * magnitude shape so DP's piecewise-linear reconstruction stays
     * faithful to the parametric curve. The anchor at fc is added by
     * the caller — this function returns only the *additional* shaping
     * points.
     *
     * Rules:
     * - **BELL** — gain-dependent + Q-dependent. <0.5 dB skipped (negligible
     *   composite contribution). Q ≤ 3: octave bracket f×{0.5, 0.75, 1.33, 2}.
     *   Q ∈ (3, 7]: bandwidth-fraction f ± bw/{8, 4}. Q > 7: tighter
     *   f ± bw/{16, 8, 4, 2} (8 supports) — needed for Q=10 corrections.
     * - **BAND_PASS** — peak gain = Q (RBJ constant skirt) so density is
     *   purely Q-driven; gain param is ignored.
     * - **NOTCH** — full-depth dip at fc regardless of gain; tighter inner
     *   points (bw/16) capture the steep drop.
     * - **LOW_SHELF / HIGH_SHELF (2nd-order)** — Q > 1.5 introduces a
     *   resonance ripple at fc, so we add f × 0.71 / 1.41 to the broad
     *   octave bracket. <0.5 dB skipped.
     * - **LOW_SHELF_1 / HIGH_SHELF_1 (1st-order)** — gentle 6 dB/oct slope,
     *   broad octave bracket only. <0.5 dB skipped.
     * - **LOW_PASS / HIGH_PASS (2nd-order)** — Q > 1.5 has a resonance peak
     *   AT fc; bandwidth-fraction supports added on top of f × {0.5, 0.71,
     *   1.41, 2}. Gain is irrelevant (always passband 0 dB / stopband -∞).
     * - **LOW_PASS_1 / HIGH_PASS_1 (1st-order)** — gentle slope, no
     *   resonance; just f × {0.5, 2}.
     * - **ALL_PASS** — flat magnitude (only phase changes); no shape to
     *   sample, return empty. The anchor at fc still gets added so the
     *   filter's existence is logged in the (cutoff, gain) dump.
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
