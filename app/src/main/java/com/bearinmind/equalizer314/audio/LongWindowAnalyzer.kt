package com.bearinmind.equalizer314.audio

import kotlin.math.*

/**
 * Long-window real-time FFT for the surgical spectrum analyzer.
 *
 * Session-0 Visualizer waveform captures arrive as contiguous blocks of the
 * output mix (samplingRate = the output sample rate). A single 1024-sample
 * block resolves only ~47 Hz, so this engine ACCUMULATES successive blocks
 * into a ring buffer and analyzes the last N samples as one continuous
 * window — true resolution of sampleRate/N, not interpolated zero-padding:
 *
 *   2048  → 23.4 Hz  (fast, classic RTA feel)
 *   4096  → 11.7 Hz  (default)
 *   8192  → 5.9 Hz   (surgical low-end)
 *   16384 → 2.9 Hz   (room-mode hunting)
 *   32768 → 1.5 Hz
 *   65536 → 0.73 Hz  (max, ~1.4 s of history)
 *
 * All FFT work happens on a dedicated worker thread; `appendWaveform` on the
 * Visualizer callback thread only converts + copies. Every buffer is
 * allocated once per size change — no steady-state allocation, so analysis
 * never pressures GC while the audio path is running. Frame rate scales
 * with window cost (~30 fps small, ~8 fps for 65536 points).
 *
 * Smoothing runs in the LINEAR magnitude domain with asymmetric ballistics
 * (fast attack / configurable release — the same signal the compressor math
 * reads via [getSmoothedLinear]) and optional PPO frequency smoothing.
 */
class LongWindowAnalyzer(targetSize: Int = 4096, sampleRate: Int = 48000) {

    companion object {
        const val MIN_SIZE = 2048
        const val MAX_SIZE = 65536
        const val RING_SIZE = MAX_SIZE

        /** Configurable analysis windows, in samples. */
        val SIZES = intArrayOf(2048, 4096, 8192, 16384, 32768, 65536)

        fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

        fun nextPowerOfTwo(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return p
        }
    }

    @Volatile
    var sampleRate: Int = sampleRate
        set(value) {
            if (field != value) {
                field = value
                windowNeedsRebuild = true   // adaptive sizes depend on the rate too
                scheduleCompute(true)
            }
        }

    // ── Window configuration ────────────────────────────────────────────
    // Target analysis window in samples (power of 2, clamped). Until the
    // ring holds targetSize samples the effective window ramps up from what
    // is available, so the graph sharpens progressively instead of freezing.
    @Volatile
    private var targetSize: Int = clampSize(targetSize)

    /** Adaptive mode: active zoom span in Hz. Null = manual size only. */
    @Volatile
    private var zoomSpanHz: Float? = null

    /** Bins aimed across a zoom span when adaptive. */
    private val zoomBins = 64

    @Volatile
    var effectiveSize: Int = 1024
        private set

    @Volatile
    private var windowNeedsRebuild = true

    fun setTargetSize(size: Int) {
        val clamped = clampSize(size)
        if (clamped != targetSize) {
            targetSize = clamped
            zoomSpanHz = null
            windowNeedsRebuild = true
            scheduleCompute(true)
        }
    }

    fun getTargetSize(): Int = targetSize

    /**
     * Pick the window automatically for a zoom span: enough bins (see
     * [zoomBins]) across the visible span, clamped between MIN_SIZE and
     * MAX_SIZE.
     */
    fun setZoomSpan(spanHz: Float?) {
        if (spanHz == null || spanHz <= 0f) {
            zoomSpanHz = null
            return
        }
        zoomSpanHz = spanHz
        val wanted = nextPowerOfTwo((sampleRate * zoomBins / spanHz).toInt().coerceAtLeast(MIN_SIZE))
        val size = wanted.coerceIn(MIN_SIZE, MAX_SIZE)
        if (size != targetSize) {
            targetSize = size
            windowNeedsRebuild = true
            scheduleCompute(true)
        }
    }

    /** Turn zoom adaptivity off (a manual FFT size is configured in Spectrum Control). */
    fun clearZoomSpan() {
        zoomSpanHz = null
    }

    private fun clampSize(size: Int): Int =
        nextPowerOfTwo(size).coerceIn(MIN_SIZE, MAX_SIZE)

    // ── Ring buffer ─────────────────────────────────────────────────────
    private val ring = FloatArray(RING_SIZE)
    private var ringWrite = 0     // index the next sample lands on
    private var samplesHeld = 0   // valid samples in the ring (≤ RING_SIZE)
    @Volatile
    private var newSamplesSinceCompute = 0

    // ── Worker ──────────────────────────────────────────────────────────
    private val workerThread = android.os.HandlerThread("EqLongWindowFft").apply { start() }
    private val worker = android.os.Handler(workerThread.looper)
    private val computing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val postQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var lastComputeNanos = 0L

    /** Rate limit scales with window cost so big windows don't burn a core. */
    private fun maxRateHz(): Float = when {
        effectiveSize >= 32768 -> 8f
        effectiveSize >= 16384 -> 12f
        effectiveSize >= 8192 -> 20f
        else -> 30f
    }

    private fun scheduleCompute(asap: Boolean) {
        if (!postQueued.compareAndSet(false, true)) return
        val minIntervalNanos = (1_000_000_000L / maxRateHz()).toLong()
        val waitNanos = if (asap) 0L else (lastComputeNanos + minIntervalNanos - System.nanoTime()).coerceAtLeast(0L)
        if (waitNanos <= 0L) worker.post { runComputeIfQueued() }
        else worker.postDelayed({ runComputeIfQueued() }, waitNanos / 1_000_000L)
    }

    private fun runComputeIfQueued() {
        if (!postQueued.compareAndSet(true, false)) return
        lastComputeNanos = System.nanoTime()
        if (!computing.compareAndSet(false, true)) return
        try {
            doCompute()
        } finally {
            computing.set(false)
            // New samples arrived while we worked → schedule the next pass.
            if (newSamplesSinceCompute > 256) scheduleCompute(false)
        }
    }

    // ── Per-window buffers (allocated on size change only) ──────────────
    private var bufReal = FloatArray(0)
    private var bufImag = FloatArray(0)
    private var hannTable = FloatArray(0)
    private var stateLinear = FloatArray(0)   // worker-owned EMA state
    private var publishScratch = FloatArray(0)
    private var ppoScratch = FloatArray(0)
    private var ppoPrefix = FloatArray(0)
    private var ppoPower = FloatArray(0)

    // Published (UI-safe, never mutated in place):
    @Volatile
    private var smoothedLinear: FloatArray? = null
    @Volatile
    private var ppoDb: FloatArray? = null

    /** Asymmetric ballistics; mirrored on the old renderer's public fields. */
    @Volatile
    var attackAlpha: Float = 0.6f
    @Volatile
    var releaseAlpha: Float = 0.22f
    /** PPO smoothing. 0 = disabled. Typical 1/3, 1/6, 1/12 (set 24/48/96). */
    @Volatile
    var ppoSmoothing: Int = 0

    /** Silencing mode: rings decay toward zero (playback stopped). */
    @Volatile
    var isDecaying = false
        private set

    private fun rebuildWindowBuffers(size: Int) {
        val n = size
        bufReal = FloatArray(n)
        bufImag = FloatArray(n)
        hannTable = FloatArray(n)
        val twoPi = 2.0 * PI
        for (i in 0 until n) {
            hannTable[i] = (0.5 - 0.5 * cos(twoPi * i / n)).toFloat()
        }
        val bins = n / 2
        stateLinear = FloatArray(bins)   // zeros — smoothing restarts on resize
        publishScratch = FloatArray(bins)
        ppoScratch = FloatArray(bins)
        ppoPrefix = FloatArray(bins + 1)
        ppoPower = FloatArray(bins)
        effectiveSize = n
        smoothedLinear = null
        ppoDb = null
    }

    private fun nextUsableSize(): Int {
        val have = samplesHeld
        // Ramping: analyze whatever we have rounded up to a power of two
        // until [targetSize] samples accumulate; then lock to the target.
        return if (have >= targetSize) targetSize
        else nextPowerOfTwo(have).coerceIn(1024, targetSize)
    }

    // ── Input ───────────────────────────────────────────────────────────
    /** Append one Visualizer waveform capture (unsigned 8-bit, 128 = silence). */
    fun appendWaveform(waveform: ByteArray) {
        val n = waveform.size
        var i = 0
        while (i < n) {
            ring[ringWrite] = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            ringWrite = if (ringWrite + 1 == RING_SIZE) 0 else ringWrite + 1
            if (samplesHeld < RING_SIZE) samplesHeld++
            i++
        }
        newSamplesSinceCompute += n
        isDecaying = false
        scheduleCompute(false)
    }

    /** Decay path when playback stops: scale magnitudes toward zero. */
    fun decay(step: Float) {
        isDecaying = true
        decayStep = step
        scheduleCompute(false)
    }

    @Volatile
    private var decayStep = 0.92f

    fun getSmoothedLinear(): FloatArray? = smoothedLinear
    fun getPpoDb(): FloatArray? = ppoDb
    val binCount: Int get() = effectiveSize / 2
    val binWidthHz: Float get() = sampleRate.toFloat() / effectiveSize

    // ── Compute pass ────────────────────────────────────────────────────
    private fun doCompute() {
        val available = samplesHeld
        if (available < 256) return   // still priming

        val desired = nextUsableSize()
        if (windowNeedsRebuild || desired != effectiveSize) {
            windowNeedsRebuild = false
            rebuildWindowBuffers(desired)
        }

        newSamplesSinceCompute = 0
        val n = effectiveSize
        bufReal.fill(0f)
        bufImag.fill(0f)

        // Copy the most recent n samples out of the ring (wraps), so the
        // last sample of the window is the newest one captured.
        val copyCount = minOf(n, available)
        var src = if (ringWrite == 0) RING_SIZE - 1 else ringWrite - 1
        for (i in 0 until copyCount) {
            bufReal[copyCount - 1 - i] = ring[src]
            src = if (src == 0) RING_SIZE - 1 else src - 1
        }

        // Hann window on the copied segment
        for (i in 0 until copyCount) bufReal[i] *= hannTable[i]

        SimpleFFT.fft(bufReal, bufImag)

        val bins = n / 2
        val norm = 2f / copyCount   // single-sided + Hann window average 0.5
        // EMA ballistics in linear domain (fast attack, configurable release)
        val state = stateLinear
        val old = effectiveSize / 2
        if (old < bins) return   // resized mid-pass — nothing to publish this frame

        if (isDecaying) {
            val k = decayStep
            for (i in 0 until bins) state[i] *= k
        } else {
            for (i in 0 until bins) {
                val re = bufReal[k2(i)].toDouble()
                val im = bufImag[k2(i)].toDouble()
                val cur = sqrt(re * re + im * im).toFloat() * norm
                val prev = state[i]
                val alpha = if (cur > prev) attackAlpha else releaseAlpha
                state[i] = alpha * cur + (1f - alpha) * prev
            }
        }

        // Publish: clone the state so UI threads never read a half-written array
        publishScratch = state.copyOf()
        smoothedLinear = publishScratch

        computePpo(state)
    }

    private fun k2(i: Int): Int = i

    private fun computePpo(state: FloatArray) {
        val ppo = ppoSmoothing
        if (ppo <= 0) {
            ppoDb = null
            return
        }
        val n = state.size
        val binHz = binWidthHz
        val halfOctaves = 0.5f / ppo
        val power = ppoPower
        val prefix = ppoPrefix
        for (i in 0 until n) {
            val p = state[i] * state[i]
            power[i] = p
        }
        prefix[0] = 0f
        for (i in 0 until n) prefix[i + 1] = prefix[i] + power[i]
        val smoothed = ppoScratch
        for (k in 0 until n) {
            val centerFreq = k * binHz
            if (centerFreq < 1f) { smoothed[k] = -96f; continue }
            val loBin = ((centerFreq * 2f.pow(-halfOctaves)) / binHz).toInt().coerceIn(0, n - 1)
            val hiBin = ((centerFreq * 2f.pow(halfOctaves)) / binHz).toInt().coerceIn(0, n - 1)
            val count = hiBin - loBin + 1
            val avg = if (count <= 1) power[k] else (prefix[hiBin + 1] - prefix[loBin]) / count
            smoothed[k] = if (avg > 1e-20f) (10f * log10(avg)).coerceAtLeast(-96f) else -96f
        }
        ppoDb = smoothed.copyOf()
    }

    // ── Peak finder (Dynamic Spectrum Tracker) ──────────────────────────
    data class Peak(val frequencyHz: Float, val db: Float)

    /**
     * Dominant frequency in [minHz, maxHz] from the current smoothed
     * magnitudes, refined with a 3-point parabolic (log-magnitude) fit for
     * sub-bin accuracy. Returns null when nothing rises above [minDb] or
     * no analysis has run yet.
     */
    fun findDominantPeak(minHz: Float, maxHz: Float, minDb: Float = -60f): Peak? {
        val lin = smoothedLinear ?: return null
        val n = lin.size
        if (n < 3) return null
        val binHz = binWidthHz
        val loBin = (minHz / binHz).toInt().coerceIn(1, n - 2)
        val hiBin = (maxHz / binHz).toInt().coerceIn(1, n - 2)
        if (hiBin < loBin) return null

        var bestBin = -1
        var bestMag = 0f
        for (k in loBin..hiBin) {
            val m = lin[k]
            if (m > bestMag) { bestMag = m; bestBin = k }
        }
        if (bestBin < 0) return null
        if (20f * log10(bestMag.coerceAtLeast(1e-10f)) < minDb) return null

        val y0 = ln(max(1e-12, lin[bestBin - 1].toDouble()))
        val y1 = ln(max(1e-12, lin[bestBin].toDouble()))
        val y2 = ln(max(1e-12, lin[bestBin + 1].toDouble()))
        val denom = y0 - 2.0 * y1 + y2
        val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
        val peakBin = bestBin + delta
        val peakMag = exp(y1 + 0.5 * (y0 - y2) * delta - 0.25 * denom * delta * delta)
        val dbDb = 20.0 / ln(10.0)
        return Peak((peakBin * binHz).toFloat(), (peakMag * dbDb).toFloat())
    }

    fun releaseAll() {
        smoothedLinear = null
        ppoDb = null
    }

    fun shutdown() {
        workerThread.quitSafely()
    }
}
