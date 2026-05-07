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

    /**
     * Experimental DP engine mode. When false (legacy), the engine uses
     * [DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION] with the
     * full 128-band per-band gain feed — the original 0.0.6-beta path.
     * When true (experimental), it switches to
     * [DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION] with 32 bands —
     * the candidate fix for the bass-boom / thin-treble bug.
     *
     * EqStateManager hydrates this from prefs at startup and re-applies
     * it (via [restartForVariantChange]) whenever the user flips the
     * experimental toggle.
     */
    var experimentalDpMode: Boolean = false

    /**
     * Direct-graphic path flag. When true (and [experimentalDpMode] is
     * also true), the EQ engine bypasses the parametric biquad model and
     * passes the user's `(frequency, gain)` pairs straight to DP — the
     * Wavelet / Poweramp / RootlessJamesDSP behaviour. EqStateManager
     * sets this whenever the active UI mode is GRAPHIC, TABLE, or SIMPLE.
     * Parametric mode keeps the biquad math (where it's correct because
     * filter types matter).
     *
     * No effect when [experimentalDpMode] is false — legacy log-spaced
     * sampling stays intact across all UI modes in that case.
     */
    var useDirectGraphicPath: Boolean = false

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

        // [experimentalDpMode] now ONLY controls diagnostic logging —
        // both states use 128 bands + FAVOR_FREQUENCY_RESOLUTION (the
        // proven-working combo on tester devices). When ON, every EQ
        // update logs the full (cutoff, gain) pair list plus the
        // preamp / auto-gain / channel offsets being baked in. Compare
        // those values against the source AutoEQ profile to see where
        // the curve is being misrepresented.
        ParametricToDpConverter.setNumBands(128)
        val bandCount = ParametricToDpConverter.numBands
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        Log.d(TAG, "DP variant=FREQUENCY bands=$bandCount (experimental=$experimentalDpMode)")

        // Decompiling Wavelet (com.pittvandewitt.wavelet) and Poweramp Eq
        // (com.maxmpz.equalizer) showed both apps explicitly call
        // `setPreferredFrameDuration` on the Config builder — 314Eq did
        // not. DP's silent default frame duration produces a longer FFT
        // window that smears bass periods and adds pre/post-echo on
        // transients (the exact tester-reported "boomy bass + harsh-thin
        // treble" symptoms). 10 ms is the typical music-tuned value the
        // reference apps appear to use; gated by [experimentalDpMode] so
        // the legacy 0.0.6-beta path stays bit-identical when off.
        // MBC stage: in experimental we always allocate the stage with at
        // least 1 (dummy, disabled) band — that's what Wavelet does
        // (v6=0x1, v7=0x1 in their Builder regardless of user MBC state).
        // Allocating the stage even when unused appears to be the
        // expected DP usage pattern; the dummy band stays disabled and
        // passes audio through. Legacy keeps the original conditional
        // allocation.
        val mbcStageInUse = if (experimentalDpMode) true else mbcEnabled
        val mbcStageBandCount = when {
            mbcEnabled -> mbcBandCount
            experimentalDpMode -> 1   // dummy disabled band
            else -> 0
        }
        val configBuilder = DynamicsProcessing.Config.Builder(
            variant,
            2,                  // channel count (stereo)
            true,               // pre-EQ stage enabled
            bandCount,          // pre-EQ band count
            mbcStageInUse,      // MBC stage allocated (always in experimental)
            mbcStageBandCount,
            false,              // post-EQ disabled
            0,
            true                // limiter stage enabled (Wavelet does the same)
        )
        if (experimentalDpMode) {
            configBuilder.setPreferredFrameDuration(10f)
        }
        val config = configBuilder.build()

        try {
            lastEq = eq
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                // Wavelet's order (a6/b0.smali): set Pre-EQ → MBC →
                // Limiter → THEN setEnabled. We previously did setEnabled
                // first, then bands. Reverse it under [experimentalDpMode]
                // so DP doesn't see a "live" instance with default bands
                // before our real values arrive.
                if (!experimentalDpMode) {
                    enabled = true
                }

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Dummy MBC band — must be present so the stage reports
                // a band slot, but it's disabled so audio passes through.
                if (experimentalDpMode && !mbcEnabled) {
                    val dummyMbc = DynamicsProcessing.MbcBand(
                        false,        // enabled = false (passthrough)
                        20000f,       // cutoff well above audible
                        1f, 100f, 1f, 0f, 0f, -120f, 1f, 0f, 0f
                    )
                    setMbcBandByChannelIndex(0, 0, dummyMbc)
                    setMbcBandByChannelIndex(1, 0, dummyMbc)
                }

                // Apply parametric response sampled at N frequencies.
                // In experimental we want the band write to land BEFORE
                // setEnabled — applyParametricResponse normally posts to
                // a worker, so block until the post completes.
                applyParametricResponse(this, eq)
                if (experimentalDpMode) {
                    drainPendingApply()
                    enabled = true
                }

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

    /**
     * Block the calling thread until any pending [applyParametricResponse]
     * worker job has executed. Used in [start] when [experimentalDpMode] is
     * on so the band feed lands BEFORE we toggle `enabled = true` — the
     * Wavelet ordering. No-op when no job is queued.
     */
    private fun drainPendingApply() {
        val job = pendingApply ?: return
        // Remove from queue, run synchronously on the caller's thread.
        // Safe because `setPreEqBandByChannelIndex` etc. are thread-safe
        // wrappers over binder calls — only ordering matters, not which
        // thread issues them.
        workerHandler.removeCallbacks(job)
        try { job.run() } catch (_: Exception) {}
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
        //
        // Conversion path selection (only [experimentalDpMode] enables the
        // newer paths; otherwise the original log-spaced sampler runs).
        //   • experimental + direct-graphic   → convertDirect: user
        //     (freq, gain) pairs verbatim, no biquad math. Matches Wavelet
        //     / Poweramp / RootlessJamesDSP behaviour for GRAPHIC / TABLE
        //     / SIMPLE modes.
        //   • experimental + parametric       → convertFeatureAware:
        //     biquad composite sampled with anchors at every parametric
        //     centre + per-filter-type support points around them.
        //   • legacy (experimental off)       → convert: biquad composite
        //     sampled at fixed log-spaced cutoffs (the 0.0.6-beta path).
        val cutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        val pathTag: String
        if (experimentalDpMode && useDirectGraphicPath) {
            val l = ParametricToDpConverter.convertDirect(leftEq)
            cutoffs = l.cutoffs
            leftGains = l.gains
            rightGains = if (leftEq === rightEq) leftGains.copyOf()
                else ParametricToDpConverter.convertDirect(rightEq).gains
            pathTag = "direct-graphic"
        } else if (experimentalDpMode) {
            val l = ParametricToDpConverter.convertFeatureAware(leftEq)
            cutoffs = l.cutoffs
            leftGains = l.gains
            rightGains = if (leftEq === rightEq) leftGains.copyOf()
                else ParametricToDpConverter.convertFeatureAware(rightEq).gains
            pathTag = "feature-aware"
        } else {
            cutoffs = ParametricToDpConverter.cutoffFrequencies
            leftGains = ParametricToDpConverter.convert(leftEq)
            rightGains = if (leftEq === rightEq) leftGains.copyOf()
                else ParametricToDpConverter.convert(rightEq)
            pathTag = "log-spaced"
        }

        // Preamp routing.
        //   • Experimental: apply preamp via DP's documented input-gain
        //     stage (Wavelet does this — a6/b0.smali:343,379 calls
        //     `setInputGainbyChannel(channel, gainDb)`). Keeps band gains
        //     at their natural levels so DP's internal headroom /
        //     normalization doesn't clip them.
        //   • Legacy: bake preamp into every band gain (the 0.0.6-beta
        //     behaviour). Audibly equivalent for level but pushes band
        //     gains into ranges where DP may auto-attenuate.
        if (!experimentalDpMode && preampGainDb != 0f) {
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

        // Diagnostic dump — only when experimental mode is on. Writes
        // every band's cutoff and the *final* gain (after preamp +
        // auto-gain + channel offsets) so testers can compare against
        // the source AutoEQ / Wavelet profile and see exactly what's
        // being sent to AudioFlinger. Filter the log with:
        //   adb logcat -s DynamicsProcessingMgr:D
        if (experimentalDpMode) {
            Log.d(TAG, "[DUMP] preamp=${"%.2f".format(preampGainDb)} dB, " +
                "autoGain=$autoGainEnabled (offset=${"%.2f".format(lastAutoGainOffset)} dB), " +
                "channelOffsets L=${"%.2f".format(leftOffsetDb)} R=${"%.2f".format(rightOffsetDb)} dB, " +
                "bands=${cutoffs.size}, path=$pathTag")
            val sb = StringBuilder("[DUMP] (cutoff Hz = sample Hz, L gain dB, R gain dB) per band:\n")
            for (i in cutoffs.indices) {
                sb.append("  [%3d] cutoff=%-9.1f L=%+6.2f R=%+6.2f\n"
                    .format(i, cutoffs[i], leftGains[i], rightGains[i]))
            }
            sb.toString().split('\n').forEach { line ->
                if (line.isNotEmpty()) Log.d(TAG, line)
            }
            Log.d(TAG, "[DUMP] Parametric source bands (left EQ):")
            for (i in 0 until leftEq.getBandCount()) {
                val b = leftEq.getBand(i) ?: continue
                Log.d(TAG, "  src[%2d] type=%-12s freq=%-8.1f Hz gain=%+6.2f dB Q=%.3f enabled=%s"
                    .format(i, b.filterType.name, b.frequency, b.gain, b.q, b.enabled))
            }
        }

        // Coalesce: drop any in-flight job in favour of the latest gains. The
        // Volatile read is a stale-but-correct check; the only consequence of
        // a race is one extra binder loop, which is harmless.
        //
        // Decompiling Wavelet / Poweramp showed both apps use the *atomic*
        // `setPreEqAllChannelsTo(Eq)` / `setPreEqByChannelIndex(channel, Eq)`
        // batch update — one binder transaction per channel that swaps the
        // entire EQ in. Our legacy path uses 2 × N (per-band, per-channel)
        // transactions, which means the audio thread can observe partial
        // EQ state between band writes during rapid updates. The batch
        // path is the same shape Wavelet uses; gated by [experimentalDpMode]
        // so the legacy 0.0.6-beta behaviour is bit-identical when off.
        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        val useBatch = experimentalDpMode
        val inputGainDb = if (experimentalDpMode) preampGainDb else 0f
        val job = Runnable {
            try {
                if (useBatch) {
                    // Push preamp via DP's input-gain stage (Wavelet pattern)
                    // before the EQ bands. Channel offsets remain baked into
                    // band gains since they're per-channel asymmetric (left
                    // and right may differ), which the input-gain stage
                    // can't represent in a single call.
                    try {
                        dp.setInputGainAllChannelsTo(inputGainDb)
                    } catch (e: Throwable) {
                        Log.w(TAG, "setInputGainAllChannelsTo failed", e)
                    }
                    val leftEqObj = DynamicsProcessing.Eq(true, true, n)
                    val rightEqObj = DynamicsProcessing.Eq(true, true, n)
                    for (i in 0 until n) {
                        leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i]))
                        rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i]))
                    }
                    dp.setPreEqByChannelIndex(0, leftEqObj)
                    dp.setPreEqByChannelIndex(1, rightEqObj)
                } else {
                    for (i in 0 until n) {
                        dp.setPreEqBandByChannelIndex(
                            0, i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i])
                        )
                        dp.setPreEqBandByChannelIndex(
                            1, i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i])
                        )
                    }
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
