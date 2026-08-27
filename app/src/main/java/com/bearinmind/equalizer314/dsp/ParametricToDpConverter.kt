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
 * [convertFeatureAware] anchors a cutoff at every enabled parametric
 * band's centre, redistributes the remaining cutoffs adaptively along
 * the curve's steep regions, and pre-compensates the engine's
 * staircase + Hann-window rendering (issue #26). The Wavelet 127-band
 * table seeds the layout so AutoEQ-style graphic imports still land on
 * familiar positions.
 */
object ParametricToDpConverter {

    // 128 pre-EQ bands: the HARD ceiling of the public DynamicsProcessing
    // API — Android's AIDL layer clamps preEq bandCount to 128
    // (AidlConversionDp "clampParameter", logcat-verified on Android 16;
    // requesting more throws). Poweramp's 300 "bands" go through its own
    // native bridge, not this API. 127 kept as a paranoid fallback.
    var numBands: Int = 128
        private set
    private const val MIN_FREQ = 10f
    private const val MAX_FREQ = 22000f


    // ---- DP engine geometry (issue #26) -------------------------------
    // AOSP's DPFrequency renders the Pre-EQ as a STAIRCASE: band i's gain
    // is applied flat to the FFT bins from the previous band's binStop+1
    // through binStop = round(cutoffHz * blockSize / sampleRate), linear
    // frequency, no interpolation. Kept in sync by DynamicsProcessingManager
    // (frame duration) and EqService (device sample rate) so gain sampling
    // can be bin-aware — see [bandSpaceDeconvolve].
    var deviceSampleRateHz: Float = 48000f
    /** Optional shared EQ layer (the CSE "Both" curve) whose response is
     *  ADDED to every converted channel EQ: final channel response =
     *  channel curve + overlay curve. Set by EqStateManager while Channel
     *  Side EQ is on; null otherwise. */
    var overlayEq: ParametricEqualizer? = null

    private fun respond(eq: ParametricEqualizer, f: Float): Float {
        val base = eq.getFrequencyResponse(f)
        val ov = overlayEq
        return if (ov != null && ov !== eq) base + ov.getFrequencyResponse(f) else base
    }
    var frameDurationMs: Float = 40f   // mirrors DynamicsProcessingManager.FRAME_DURATION_MS

    /** AOSP DPFrequency block size: frameDuration in samples, clamped to
     *  [8, 16384], rounded UP to a power of two. */
    private fun dpBlockSize(fs: Float): Int {
        val samples = (frameDurationMs / 1000f * fs).toInt().coerceIn(8, 16384)
        var p = 8
        while (p < samples) p = p shl 1
        return p
    }

    // Adaptive-grid / deconvolution tuning (issue #26). Values validated
    // against a full staircase+Hann pipeline simulation of the AOSP engine.
    private const val GRID_SIZE = 768
    private const val ADAPT_ITERS = 256

    /**
     * Slope-adaptive cutoff placement (issue #26). 127 log-spaced bands vs
     * linear-spaced FFT bins means one HF stair spans many bins; a flat
     * stair over a steep stretch was REW-measured as a ~0.8 dB dip at
     * 8.5 kHz. Fix: repeatedly move the cheapest boundary (least variation
     * if merged) into the worst segment (most variation). Anchors (band
     * centres) never removed. Segments < one FFT bin never split (engine
     * can't render the boundary). Two sub-20 Hz seeds give bins 0/1
     * independent stairs to shape the lowest octave.
     */
    private fun responseGrid(eq: ParametricEqualizer): FloatArray {
        val gMin = 10f
        val gMax = 20000f
        val logSpan = ln(gMax / gMin)
        return FloatArray(GRID_SIZE) {
            respond(eq, gMin * kotlin.math.exp(logSpan * it / (GRID_SIZE - 1)))
        }
    }

    private fun adaptiveCutoffs(
        grids: List<FloatArray>,
        anchors: List<Float>,
        total: Int,
        binHz: Float,
    ): FloatArray {
        val gMin = 10f
        val gMax = 20000f
        val logSpan = ln(gMax / gMin)
        fun gi(f: Float): Int {
            val fc = f.coerceIn(gMin, gMax)
            return ((ln(fc / gMin) / logSpan) * (GRID_SIZE - 1) + 0.5f).toInt()
                .coerceIn(0, GRID_SIZE - 1)
        }
        fun variation(lo: Float, hi: Float): Float {
            val i0 = gi(lo); val i1 = gi(hi)
            var worst = 0f
            for (gridDb in grids) {
                val v = if (i1 <= i0) {
                    abs(gridDb[i1] - gridDb[i0])
                } else {
                    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                    for (k in i0..i1) { val g = gridDb[k]; if (g < mn) mn = g; if (g > mx) mx = g }
                    mx - mn
                }
                if (v > worst) worst = v
            }
            return worst
        }

        // Seed: sub-bin splitters + Wavelet table + anchors, sorted, deduped
        // (0.3 % tolerance, anchors win).
        val freqs = ArrayList<Float>(total + 8)
        val isAnchor = ArrayList<Boolean>(total + 8)
        val seed = ArrayList<Pair<Float, Boolean>>()
        // 5 Hz → FFT bin 0 alone, 15 Hz → bin 1 alone (at both 40 and
        // 80 ms frames), so the deconvolution can shape DC and the lowest
        // octave independently. The old 10/14 Hz seeds collapsed into one
        // bin at 80 ms, merging bins 0-1 into a single stair.
        seed.add(5f to false); seed.add(15f to false)
        for (f in WAVELET_FREQUENCIES) seed.add(f to false)
        for (f in anchors) seed.add(f to true)
        seed.sortBy { it.first }
        for ((f, anchor) in seed) {
            val last = freqs.lastOrNull()
            if (last == null || f - last > last * 0.003f) {
                freqs.add(f); isAnchor.add(anchor)
            } else if (anchor && !isAnchor[isAnchor.size - 1]) {
                freqs[freqs.size - 1] = f; isAnchor[isAnchor.size - 1] = true
            }
        }
        // Trim to the band budget: drop the cheapest non-anchor boundaries.
        while (freqs.size > total) {
            var best = -1; var bc = Float.MAX_VALUE
            for (j in 1 until freqs.size - 1) {
                if (isAnchor[j]) continue
                val c = variation(freqs[j - 1], freqs[j + 1])
                if (c < bc) { bc = c; best = j }
            }
            if (best < 0) break
            freqs.removeAt(best); isAnchor.removeAt(best)
        }
        // Pad (paranoia) at the largest log-gap.
        while (freqs.size < total) {
            var bi = 0; var bg = 0f
            for (i in 0 until freqs.size - 1) {
                val g = ln(freqs[i + 1] / freqs[i])
                if (g > bg) { bg = g; bi = i }
            }
            freqs.add(bi + 1, sqrt(freqs[bi] * freqs[bi + 1]))
            isAnchor.add(bi + 1, false)
        }
        // Refinement: move cheap boundaries into high-variation segments.
        for (iter in 0 until ADAPT_ITERS) {
            var worst = -1; var wv = 0f
            for (i in 0 until freqs.size - 1) {
                // Segments narrower than one FFT bin can't render an extra
                // boundary (identity-kernel engine, one gain per bin), so
                // never split below the CURRENT rung's bin width — at 341 ms
                // that's 2.9 Hz, letting steep bass slopes get dense stairs.
                // (An old fixed 23.4 Hz floor here throttled the low end at
                // long frames — REW-measured as +0.5 dB on 70-110 Hz slopes.)
                if (freqs[i + 1] - freqs[i] < binHz) continue
                val v = variation(freqs[i], freqs[i + 1])
                if (v > wv) { wv = v; worst = i }
            }
            if (worst < 0) break
            var best = -1; var bc = Float.MAX_VALUE
            for (j in 1 until freqs.size - 1) {
                if (isAnchor[j] || j == worst || j == worst + 1) continue
                val c = variation(freqs[j - 1], freqs[j + 1])
                if (c < bc) { bc = c; best = j }
            }
            if (best < 0 || bc * 1.7f >= wv) break
            val mid = sqrt(freqs[worst] * freqs[worst + 1])
            freqs.removeAt(best); isAnchor.removeAt(best)
            var at = 0
            while (at < freqs.size && freqs[at] < mid) at++
            freqs.add(at, mid); isAnchor.add(at, false)
        }
        return FloatArray(freqs.size) { freqs[it] }
    }

    /**
     * Bin-aware stair gains (issue #26). Engine renders a per-bin staircase
     * (band i covers bins prevStop+1..binStop, binStop =
     * round(cutoffHz·blockSize/sampleRate)). System-ID on a real device
     * (logcat staircase vs REW sweep pair) found NO inter-bin smearing:
     * fitted kernel [0.04, 0.92, 0.04] ≈ identity, NOT the sqrt-Hann
     * [¼,½,¼] the AOSP source implies. So optimal band gain = analytic
     * target averaged over the stair's bins, no pre-sharpening. (Van
     * Cittert Hann-deconvolution tried, removed — REW-measured ±2 dB
     * ringing on dense LF curves.)
     */
    private fun bandSpaceDeconvolve(
        eq: ParametricEqualizer,
        cutoffs: FloatArray,
        n: Int,
        fs: Float,
    ): FloatArray {
        val half = n / 2 + 1
        val binHz = fs / n
        val tDb = FloatArray(half) { respond(eq, (it * binHz).coerceAtLeast(1f)) }
        val starts = IntArray(cutoffs.size)
        val stops = IntArray(cutoffs.size)
        var prev = -1
        for (i in cutoffs.indices) {
            val stop = (0.5f + cutoffs[i] * n / fs).toInt().coerceAtMost(half - 1)
            starts[i] = prev + 1
            stops[i] = stop
            if (starts[i] <= stop) prev = stop
        }
        val x = FloatArray(cutoffs.size)
        val meanT = FloatArray(cutoffs.size)
        var lastStop = -1
        for (i in cutoffs.indices) {
            if (starts[i] <= stops[i]) {
                var s = 0f
                for (k in starts[i]..stops[i]) s += tDb[k]
                meanT[i] = s / (stops[i] - starts[i] + 1)
                x[i] = meanT[i]
                lastStop = stops[i]
            } else {
                // Collapsed into an already-covered bin — DP renders nothing
                // for this band; keep the plain point sample (harmless).
                x[i] = respond(eq, cutoffs[i])
            }
        }
        return x
    }

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
        // Tiers: 32 (Compat Mode, band-limited HALs), 127 (Wavelet/AutoEQ
        // parity / DP-config fallback), 128 (default). adaptiveCutoffs
        // trims its seed to whichever count, so 32 just yields 32
        // adaptively-placed cutoffs.
        numBands = when {
            count <= 32 -> 32
            count <= 127 -> 127
            else -> 128
        }
    }

    /** Center frequencies (geometric mean of each band's edges). */
    val centerFrequencies: FloatArray
        get() {
            val cutoffs = cutoffFrequencies
            return FloatArray(cutoffs.size) { i ->
                val lower = if (i == 0) MIN_FREQ else cutoffs[i - 1]
                sqrt(lower * cutoffs[i])
            }
        }

    /** Cutoff + gain pair returned by [convertFeatureAware] / [convertDirect].
     *  Both arrays always have length [numBands]; cutoff[i] pairs with gain[i]. */
    data class ConvertedBands(val cutoffs: FloatArray, val gains: FloatArray)

    /**
     * Feature-aware conversion for parametric mode (issue #26). Anchors at
     * every enabled band's centre frequency guarantee narrow peaks are
     * captured; [adaptiveCutoffs] then redistributes the remaining filler
     * cutoffs so no stair spans a steep stretch of the curve, and
     * [bandSpaceDeconvolve] pre-compensates the engine's staircase-plus-
     * Hann-window rendering so the smoothed result lands on the analytic
     * curve. Validated against a pipeline simulation of the AOSP engine:
     * worst-case rendering error ~0.25 dB at a 40 ms frame (~0.1 dB over
     * most of the range), vs ~1 dB before.
     */
    /** Issue #61: while a graph drag is live, FREEZE the cutoff layout and
     *  refit gains only — the adaptive layout otherwise reshuffles every
     *  frame (gain changes move the variation function too), so each DP
     *  write is a full reconfigure some HALs (Pixel) render as a bypass
     *  stutter. Set by EqStateManager on the first throttled push; cleared
     *  at drag-end flush so the final write gets a fresh optimal layout. */
    @Volatile
    var layoutFrozen = false
        set(value) {
            field = value
            if (!value) frozenCutoffs = null
        }
    private var frozenCutoffs: FloatArray? = null

    fun convertFeatureAware(eq: ParametricEqualizer): ConvertedBands {
        val total = numBands
        val fs = deviceSampleRateHz
        val n = dpBlockSize(fs)
        val binHz = fs / n

        val frozen = if (layoutFrozen) frozenCutoffs else null
        val cutoffs = if (frozen != null && frozen.size == total) {
            frozen
        } else {
            adaptiveCutoffs(listOf(responseGrid(eq)), collectAnchors(eq), total, binHz).also {
                if (layoutFrozen) frozenCutoffs = it
            }
        }
        return ConvertedBands(cutoffs, bandSpaceDeconvolve(eq, cutoffs, n, fs))
    }

    data class DualBands(
        val cutoffs: FloatArray,
        val leftGains: FloatArray,
        val rightGains: FloatArray,
    )

    fun convertFeatureAwareDual(
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
    ): DualBands {
        val total = numBands
        val fs = deviceSampleRateHz
        val n = dpBlockSize(fs)
        val binHz = fs / n

        val frozen = if (layoutFrozen) frozenCutoffs else null
        val cutoffs = if (frozen != null && frozen.size == total) {
            frozen
        } else {
            adaptiveCutoffs(
                listOf(responseGrid(leftEq), responseGrid(rightEq)),
                collectAnchors(leftEq) + collectAnchors(rightEq),
                total,
                binHz,
            ).also { if (layoutFrozen) frozenCutoffs = it }
        }
        return DualBands(
            cutoffs,
            bandSpaceDeconvolve(leftEq, cutoffs, n, fs),
            bandSpaceDeconvolve(rightEq, cutoffs, n, fs),
        )
    }

    private fun collectAnchors(eq: ParametricEqualizer): List<Float> {
        val anchors = mutableListOf<Float>()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            if (!band.enabled) continue
            if (band.frequency in MIN_FREQ..MAX_FREQ) anchors.add(band.frequency)
        }
        overlayEq?.takeIf { it !== eq }?.let { ov ->
            for (i in 0 until ov.getBandCount()) {
                val band = ov.getBand(i) ?: continue
                if (!band.enabled) continue
                if (band.frequency in MIN_FREQ..MAX_FREQ) anchors.add(band.frequency)
            }
        }
        return anchors
    }

    // ---- Pre+Post-EQ interleave (256 effective stairs) -----------------

    /** Two independent staircases that the engine cascades (dB adds):
     *  Pre = the normal 128-stair fit, Post = 128 stairs whose boundaries
     *  sit mid-stair between Pre's, carrying the residual ripple. */
    data class InterleavedBands(
        val preCutoffs: FloatArray, val preGains: FloatArray,
        val postCutoffs: FloatArray, val postGains: FloatArray,
    )

    /**
     * Interleaved conversion using BOTH DP EQ stages (each AIDL-capped at
     * 128 bands). SPLIT-HALF: each stage fits the FULL curve on its own
     * grid and renders HALF the dB; Post's boundaries at the ARITHMETIC
     * midpoints of Pre's (bins are linear-Hz, so arithmetic — not geometric
     * — lands mid-stair). Cascaded dB-sum = average of two offset
     * staircases, a 256-breakpoint half-height fit: sim on the 12-band
     * torture curve at 341 ms halves worst-case (0.69→0.36 dB) and mean
     * (0.16→0.08 dB). Same FFT frame, no added latency. (A residual-fit
     * variant — Post = target minus Pre's staircase — sims to NOTHING: the
     * per-stair residual is a ramp, and the offset Post stair averages one
     * ramp-end against the next ramp-start, cancelling to ~0.)
     */
    fun convertInterleaved(eq: ParametricEqualizer): InterleavedBands {
        val fs = deviceSampleRateHz
        val n = dpBlockSize(fs)

        val pre = convertFeatureAware(eq)
        val pc = pre.cutoffs

        // Post boundaries: midpoints of consecutive Pre boundaries, topped
        // off at MAX_FREQ so the last stair covers through Nyquist.
        val postCutoffs = FloatArray(pc.size)
        for (i in 0 until pc.size - 1) postCutoffs[i] = (pc[i] + pc[i + 1]) / 2f
        postCutoffs[pc.size - 1] = MAX_FREQ

        val preGains = FloatArray(pc.size) { pre.gains[it] * 0.5f }
        val postFit = bandSpaceDeconvolve(eq, postCutoffs, n, fs)
        val postGains = FloatArray(postCutoffs.size) { postFit[it] * 0.5f }
        return InterleavedBands(pc, preGains, postCutoffs, postGains)
    }

    data class DualInterleavedBands(
        val preCutoffs: FloatArray,
        val leftPreGains: FloatArray, val rightPreGains: FloatArray,
        val postCutoffs: FloatArray,
        val leftPostGains: FloatArray, val rightPostGains: FloatArray,
    )

    fun convertInterleavedDual(
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
    ): DualInterleavedBands {
        val fs = deviceSampleRateHz
        val n = dpBlockSize(fs)

        val pre = convertFeatureAwareDual(leftEq, rightEq)
        val pc = pre.cutoffs

        val postCutoffs = FloatArray(pc.size)
        for (i in 0 until pc.size - 1) postCutoffs[i] = (pc[i] + pc[i + 1]) / 2f
        postCutoffs[pc.size - 1] = MAX_FREQ

        val leftPreGains = FloatArray(pc.size) { pre.leftGains[it] * 0.5f }
        val rightPreGains = FloatArray(pc.size) { pre.rightGains[it] * 0.5f }
        val leftPostFit = bandSpaceDeconvolve(leftEq, postCutoffs, n, fs)
        val rightPostFit = bandSpaceDeconvolve(rightEq, postCutoffs, n, fs)
        val leftPostGains = FloatArray(postCutoffs.size) { leftPostFit[it] * 0.5f }
        val rightPostGains = FloatArray(postCutoffs.size) { rightPostFit[it] * 0.5f }
        return DualInterleavedBands(
            pc, leftPreGains, rightPreGains,
            postCutoffs, leftPostGains, rightPostGains,
        )
    }

}
