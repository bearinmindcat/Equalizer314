package com.bearinmind.equalizer314.audio

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.saneOr

/** System-wide EQ on session-0 DynamicsProcessing (API 28+): limiter → MBC → pre-EQ → enable, preamp on the input-gain stage. */
class DynamicsProcessingManager {

    companion object {
        private const val TAG = "DynamicsProcessingMgr"
        // FFT frame duration (ms), mirrored into the converter; default 80 ms ≈ 12 Hz bins (issue #26).
        const val FRAME_DURATION_DEFAULT_MS = 80f
        const val FRAME_DURATION_LOW_LATENCY_MS = 40f
        // 160 ms ≈ 5.9 Hz bins — "Maximum bass precision".
        const val FRAME_DURATION_MAX_PRECISION_MS = 160f
        @Volatile
        var frameDurationMs = FRAME_DURATION_DEFAULT_MS
        // Pre+Post-EQ interleave: Post cutoffs offset half a stair — 256 effective stairs; baked at DP creation.
        @Volatile
        var interleaveEnabled = false
        // Compat Mode (Wavelet's aidl_mode): 32 bands, 10 ms frame, no Post-EQ; latency/interleave settings go inert.
        const val COMPAT_BAND_COUNT = 32
        const val COMPAT_FRAME_MS = 10f
        @Volatile
        var compatMode = false
        // Creation-time effective values — compat overrides the user settings.
        val effectiveFrameMs: Float get() = if (compatMode) COMPAT_FRAME_MS else frameDurationMs
        val effectiveInterleave: Boolean get() = !compatMode && interleaveEnabled

        /** Writes MBC bands into any DP — disabled bands get neutral pass-through params (AOSP ignores inUse=false). */
        fun writeMbcBands(
            dp: DynamicsProcessing,
            bands: List<MbcBandParams>,
            crossovers: FloatArray,
            thresholdOffsetDb: Float,
        ) {
            for (i in bands.indices) {
                val b = bands[i]
                val cutoff = if (i < crossovers.size) crossovers[i] else 20000f
                // Clamp to the slider ranges here too — a NaN threshold turns the whole output into silence.
                val offset = thresholdOffsetDb.saneOr(0f, -60f, 0f)
                val safeCutoff = cutoff.saneOr(20000f, 20f, 20000f)
                val attack = b.attackMs.saneOr(1f, 0.01f, 500f)
                val release = b.releaseMs.saneOr(100f, 1f, 5000f)
                val knee = b.kneeDb.saneOr(8f, 0.01f, 24f)
                val mbcBand = if (b.enabled) DynamicsProcessing.MbcBand(
                    true, safeCutoff, attack, release, b.ratio.saneOr(2f, 1f, 50f),
                    (b.thresholdDb.saneOr(0f, -60f, 0f) + offset).coerceIn(-125f, 0f),
                    knee,
                    (b.noiseGateDb.saneOr(-60f, -90f, 0f) + offset).coerceIn(-125f, 0f),
                    b.expanderRatio.saneOr(1f, 1f, 50f), b.preGainDb.saneOr(0f, -30f, 30f), b.postGainDb.saneOr(0f, -30f, 30f),
                ) else DynamicsProcessing.MbcBand(
                    false, safeCutoff, attack, release, 1f, 0f, knee, -125f, 1f, 0f, 0f,
                )
                dp.setMbcBandByChannelIndex(0, i, mbcBand)
                dp.setMbcBandByChannelIndex(1, i, mbcBand)
            }
        }

        /** Inaudible passthrough band for an MBC stage that must exist but stay off. */
        fun mbcPassthroughBand(): DynamicsProcessing.MbcBand =
            DynamicsProcessing.MbcBand(false, 20000f, 1f, 100f, 1f, 0f, 0f, -120f, 1f, 0f, 0f)
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentBandCount = 0
    // Whether the LIVE config allocated Post-EQ — band writes must match it, not the static flag.
    private var currentInterleave = false
    private var lastEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    // Right-channel EQ for per-channel mode; null = lastEq on both channels.
    private var lastRightEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    private var lastReclaimTime = 0L
    private val reclaimCooldownMs = 2000L  // Don't reclaim more than once every 2 seconds
    // Read off the main thread by the watchdog; written from start()/stop().
    @Volatile
    var isActive = false
        private set

    // Preamp
    var preampGainDb: Float = 0f

    // Auto-gain
    var autoGainEnabled: Boolean = false
    var lastAutoGainOffset: Float = 0f
        private set
    // Issue #61: hold the auto-gain offset during a drag — per-write recompute pumps the whole mix.
    @Volatile
    var gainHold = false

    // MBC
    var mbcEnabled: Boolean = false
    var mbcBandCount: Int = 3
    // Volume compensation: dB shift (≤ 0) on MBC thresholds/gates so compression tracks the pre-volume signal.
    @Volatile var mbcThresholdOffsetDb: Float = 0f

    // Limiter defaults (Wavelet's baseline); overwritten from prefs before start().
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 60f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -2f
    var limiterPostGainDb: Float = 0f

    // Channel balance + per-channel preamp — applied on the input-gain stage, not baked into bands.
    var channelBalancePercent: Int = 0     // -100..100, 0 = center
    var leftChannelGainDb: Float = 0f      // -12..12
    var rightChannelGainDb: Float = 0f     // -12..12

    // Worker thread for binder calls — on the UI thread they block rendering during drags.
    private val workerThread = android.os.HandlerThread("EqDpWorker").apply { start() }
    private val workerHandler = android.os.Handler(workerThread.looper)
    @Volatile private var pendingApply: Runnable? = null
    @Volatile private var pendingLimiter: Runnable? = null

    // Issue #61: space band writes ≥ 50 ms apart — faster rewrite storms stutter on some HALs.
    private val minWriteSpacingMs = 50L
    @Volatile private var lastWriteAtMs = 0L
    // Retry for writes skipped on transient control loss — nothing else re-applies them.
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var controlRetryPending = false
    @Volatile private var controlRetryCount = 0

    fun start(eq: ParametricEqualizer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }

        stop() // Clean up any existing instance

        // A recreate mid-drag must not carry stale drag-freeze state (#61).
        ParametricToDpConverter.layoutFrozen = false
        gainHold = false
        // Keep the converter's FFT geometry in sync with the engine (issue #26).
        ParametricToDpConverter.frameDurationMs = effectiveFrameMs
        // Compat → 32 bands; else 128 (AIDL ceiling) with a 127 fallback.
        val bandLadder = if (compatMode) intArrayOf(COMPAT_BAND_COUNT) else intArrayOf(128, 127)
        for (tryBands in bandLadder) {
            ParametricToDpConverter.setNumBands(tryBands)
            if (startWithBandCount(eq, ParametricToDpConverter.numBands)) return
            Log.w(TAG, "DP creation failed with ${ParametricToDpConverter.numBands} bands")
        }
        // Fallback: an OEM may reject the Post-EQ stage — retry single-stage.
        if (effectiveInterleave) {
            Log.w(TAG, "Retrying without Pre+Post interleave")
            interleaveEnabled = false
            for (tryBands in bandLadder) {
                ParametricToDpConverter.setNumBands(tryBands)
                if (startWithBandCount(eq, ParametricToDpConverter.numBands)) return
                Log.w(TAG, "DP creation failed with ${ParametricToDpConverter.numBands} bands")
            }
        }
        Log.e(TAG, "DynamicsProcessing could not be started with any band count")
    }

    private fun startWithBandCount(eq: ParametricEqualizer, bandCount: Int): Boolean {
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        val useInterleave = effectiveInterleave
        Log.d(TAG, "DP variant=FREQUENCY bands=$bandCount frame=${effectiveFrameMs}ms interleave=$useInterleave compat=$compatMode")

        // MBC stage always allocated (≥ 1 passthrough band when off) — Wavelet's pattern.
        val mbcStageBandCount = if (mbcEnabled) mbcBandCount else 1
        val configBuilder = DynamicsProcessing.Config.Builder(
            variant,
            2,                  // channel count (stereo)
            true,               // pre-EQ stage enabled
            bandCount,          // pre-EQ band count
            true,               // MBC stage allocated
            mbcStageBandCount,
            useInterleave,      // post-EQ stage: interleave's second staircase
            if (useInterleave) bandCount else 0,
            true                // limiter stage enabled
        )
        // Frame length = frequency resolution; a 10 ms frame crushed narrow features (issue #26).
        configBuilder.setPreferredFrameDuration(effectiveFrameMs)
        val config = configBuilder.build()

        try {
            lastEq = eq
            // Set BEFORE the band write below — it picks the conversion path from currentInterleave.
            currentInterleave = useInterleave
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                // Stage order: limiter → MBC → pre-EQ → enable (never process default bands).

                // Limiter for clipping protection
                val limiter = buildLimiter()
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Passthrough MBC band when MBC is off.
                if (!mbcEnabled) {
                    val dummyMbc = mbcPassthroughBand()
                    setMbcBandByChannelIndex(0, 0, dummyMbc)
                    setMbcBandByChannelIndex(1, 0, dummyMbc)
                }

                // Apply response, then enable — drain blocks until the band write lands.
                applyParametricResponse(this, eq)
                drainPendingApply()
                enabled = true

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
            Log.d(TAG, "DynamicsProcessing started with $bandCount bands (interleave=$useInterleave)")
            // Diagnostic readback: engine-accepted vs requested (catches OEM clamping).
            try {
                val actual = dynamicsProcessing?.config
                Log.i(TAG, "DP config readback: variant=${actual?.variant} " +
                    "frameDuration=${actual?.preferredFrameDuration}ms " +
                    "(requested ${effectiveFrameMs}ms) " +
                    "preEqBands=${actual?.preEqBandCount} (requested $bandCount) " +
                    "postEqBands=${actual?.postEqBandCount} (interleave=$useInterleave) " +
                    "converter: fs=${ParametricToDpConverter.deviceSampleRateHz}Hz")
            } catch (e: Exception) {
                Log.w(TAG, "DP config readback failed: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicsProcessing ($bandCount bands)", e)
            dynamicsProcessing = null
            isActive = false
            return false
        }
    }

    /** Limiter from the live fields, clamped to the slider ranges — the last guard before the engine. */
    private fun buildLimiter() = DynamicsProcessing.Limiter(
        limiterEnabled, limiterEnabled, 0,
        limiterAttackMs.saneOr(1f, 0.01f, 100f), limiterReleaseMs.saneOr(60f, 1f, 500f), limiterRatio.saneOr(10f, 1f, 50f),
        limiterThresholdDb.saneOr(-2f, -30f, 0f), limiterPostGainDb.saneOr(0f, -12f, 12f),
    )

    /** Block until any queued band write lands — start() enables only after the bands are in. */
    private fun drainPendingApply() {
        val job = pendingApply ?: return
        // Dequeue and run synchronously — only ordering matters.
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

    /** Power-cycle-equivalent recreate on the current output with the last EQ; caller re-applies MBC / bypass. */
    fun reattachActive(): Boolean {
        if (!isActive) return false
        val eq = lastEq ?: return false
        stop()
        start(eq)
        return isActive
    }

    /** True when the live effect lost control or got disabled (a failed native read counts as lost). */
    fun hasLostControl(): Boolean {
        if (!isActive) return false
        val dp = dynamicsProcessing ?: return false
        return try {
            !dp.hasControl() || !dp.enabled
        } catch (e: Throwable) {
            Log.w(TAG, "hasLostControl read threw — treating as lost", e)
            true
        }
    }

    /** Shared 2 s reclaim cooldown — consumes the window when it returns true. */
    fun reclaimCooldownElapsed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReclaimTime < reclaimCooldownMs) return false
        lastReclaimTime = now
        return true
    }

    fun updateFromEqualizer(eq: ParametricEqualizer) {
        updateFromEqualizers(eq, eq)
    }

    /** Apply per-channel EQs; pass the same instance twice for shared mode. */
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
        // Sampling on the caller's thread (UI-owned EQ); binder writes on the worker. One conversion path for every mode.
        val useInterleave = currentInterleave
        val cutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        // Interleave: Post-EQ carries the offset second staircase; null without a Post stage.
        val postCutoffs: FloatArray?
        val leftPostGains: FloatArray?
        val rightPostGains: FloatArray?
        if (useInterleave) {
            if (leftEq === rightEq) {
                val li = ParametricToDpConverter.convertInterleaved(leftEq)
                cutoffs = li.preCutoffs
                leftGains = li.preGains
                postCutoffs = li.postCutoffs
                leftPostGains = li.postGains
                rightGains = leftGains.copyOf()
                rightPostGains = leftPostGains.copyOf()
            } else {
                val di = ParametricToDpConverter.convertInterleavedDual(leftEq, rightEq)
                cutoffs = di.preCutoffs
                leftGains = di.leftPreGains
                rightGains = di.rightPreGains
                postCutoffs = di.postCutoffs
                leftPostGains = di.leftPostGains
                rightPostGains = di.rightPostGains
            }
        } else {
            if (leftEq === rightEq) {
                val l = ParametricToDpConverter.convertFeatureAware(leftEq)
                cutoffs = l.cutoffs
                leftGains = l.gains
                rightGains = leftGains.copyOf()
            } else {
                val d = ParametricToDpConverter.convertFeatureAwareDual(leftEq, rightEq)
                cutoffs = d.cutoffs
                leftGains = d.leftGains
                rightGains = d.rightGains
            }
            postCutoffs = null
            leftPostGains = null
            rightPostGains = null
        }

        // Auto-gain: flat shift bringing the loudest band to ≤ 0 dB.
        if (autoGainEnabled) {
            if (!gainHold) {
                var peak = Float.NEGATIVE_INFINITY
                if (useInterleave && leftPostGains != null && rightPostGains != null) {
                    // Split-half: the true peak is ~2× a single stage's gain.
                    for (g in leftGains) if (2f * g > peak) peak = 2f * g
                    for (g in rightGains) if (2f * g > peak) peak = 2f * g
                    for (g in leftPostGains) if (2f * g > peak) peak = 2f * g
                    for (g in rightPostGains) if (2f * g > peak) peak = 2f * g
                } else {
                    for (g in leftGains) if (g > peak) peak = g
                    for (g in rightGains) if (g > peak) peak = g
                }
                lastAutoGainOffset = if (peak > 0f) -peak else 0f
            }
            if (lastAutoGainOffset != 0f) {
                // Pre stage only — the per-bin total still moves by exactly the offset.
                for (i in leftGains.indices) leftGains[i] += lastAutoGainOffset
                for (i in rightGains.indices) rightGains[i] += lastAutoGainOffset
            }
        } else {
            lastAutoGainOffset = 0f
        }

        // Channel offsets + preamp go on the input-gain stage, not into band gains.
        val (leftOffsetDb, rightOffsetDb) = computeChannelOffsets()

        Log.d(TAG, "[DUMP] preamp=${"%.2f".format(preampGainDb)} dB, " +
            "autoGain=$autoGainEnabled (offset=${"%.2f".format(lastAutoGainOffset)} dB), " +
            "channelOffsets L=${"%.2f".format(leftOffsetDb)} R=${"%.2f".format(rightOffsetDb)} dB, " +
            "bands=${cutoffs.size}")
        run {
            val sb = StringBuilder("[DUMP] (cutoff Hz, L gain dB, R gain dB) per band:\n")
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
            if (useInterleave && postCutoffs != null && leftPostGains != null && rightPostGains != null) {
                val psb = StringBuilder("[DUMP] interleave POST stage (cutoff Hz, L gain dB, R gain dB):\n")
                for (i in postCutoffs.indices) {
                    psb.append("  [%3d] cutoff=%-9.1f L=%+6.2f R=%+6.2f\n"
                        .format(i, postCutoffs[i], leftPostGains[i], rightPostGains[i]))
                }
                psb.toString().split('\n').forEach { line ->
                    if (line.isNotEmpty()) Log.d(TAG, line)
                }
            }
        }

        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        // Input gain = preamp + channel offset (auto-gain is already in the bands).
        val preamp = preampGainDb.saneOr(0f, -20f, 20f)
        val leftInputGainDb = preamp + leftOffsetDb
        val rightInputGainDb = preamp + rightOffsetDb
        val job = Runnable {
            try {
                // Without control every setter silently no-ops — skip; reclaim recreates later.
                if (!dp.hasControl()) {
                    Log.w(TAG, "DP lost control — band write skipped, scheduling retry")
                    scheduleControlRetry()
                    return@Runnable
                }
                controlRetryCount = 0
                // Preamp + offset via the per-channel input-gain stage.
                try {
                    dp.setInputGainbyChannel(0, leftInputGainDb)
                    dp.setInputGainbyChannel(1, rightInputGainDb)
                } catch (e: Throwable) {
                    Log.w(TAG, "setInputGainbyChannel failed", e)
                }
                // Atomic per-channel EQ swap — one binder transaction per channel.
                val leftEqObj = DynamicsProcessing.Eq(true, true, n)
                val rightEqObj = DynamicsProcessing.Eq(true, true, n)
                for (i in 0 until n) {
                    leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i]))
                    rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i]))
                }
                dp.setPreEqByChannelIndex(0, leftEqObj)
                dp.setPreEqByChannelIndex(1, rightEqObj)
                // Interleave: second staircase on the Post-EQ stage when the live config has one.
                if (postCutoffs != null && leftPostGains != null && rightPostGains != null) {
                    val leftPostObj = DynamicsProcessing.Eq(true, true, n)
                    val rightPostObj = DynamicsProcessing.Eq(true, true, n)
                    for (i in 0 until n) {
                        leftPostObj.setBand(i, DynamicsProcessing.EqBand(true, postCutoffs[i], leftPostGains[i]))
                        rightPostObj.setBand(i, DynamicsProcessing.EqBand(true, postCutoffs[i], rightPostGains[i]))
                    }
                    dp.setPostEqByChannelIndex(0, leftPostObj)
                    dp.setPostEqByChannelIndex(1, rightPostObj)
                }
                lastWriteAtMs = android.os.SystemClock.uptimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "DP band write failed", e)
            } finally {
                pendingApply = null
            }
        }
        pendingApply?.let { workerHandler.removeCallbacks(it) }
        pendingApply = job
        val delay = (lastWriteAtMs + minWriteSpacingMs - android.os.SystemClock.uptimeMillis())
            .coerceIn(0L, minWriteSpacingMs)
        workerHandler.postDelayed(job, delay)
    }

    /** Retry a band write skipped on transient control loss; after 4 misses fall back to a reclaim (issue #61). */
    private fun scheduleControlRetry() {
        if (controlRetryPending) return
        controlRetryPending = true
        retryHandler.postDelayed({
            controlRetryPending = false
            val dp = dynamicsProcessing ?: return@postDelayed
            val eq = lastEq ?: return@postDelayed
            if (!isActive) return@postDelayed
            val hasControl = try { dp.hasControl() } catch (_: Throwable) { false }
            if (hasControl) {
                Log.i(TAG, "control restored — re-applying skipped band write")
                try { applyParametricResponse(dp, eq, lastRightEq ?: eq) } catch (_: Exception) {}
            } else if (++controlRetryCount >= 4) {
                controlRetryCount = 0
                reclaimSession()
            } else {
                scheduleControlRetry()
            }
        }, 300L)
    }

    /** Per-channel input-gain offsets: channel preamp + balance attenuation (pan wins over preamp). */
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
        // Clamp to -60..+24 dB before feeding DynamicsProcessing.
        val left = (leftChannelGainDb.saneOr(0f, -12f, 12f) + leftBalanceDb).coerceIn(-60f, 24f)
        val right = (rightChannelGainDb.saneOr(0f, -12f, 12f) + rightBalanceDb).coerceIn(-60f, 24f)
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
            val limiter = buildLimiter()
            dp.setLimiterByChannelIndex(0, limiter)
            dp.setLimiterByChannelIndex(1, limiter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update limiter", e)
        }
    }

    /** Apply MBC bands + crossovers (bands.size - 1) to the live DP. */
    fun applyMbcBands(
        bands: List<MbcBandParams>,
        crossovers: FloatArray
    ) {
        val dp = dynamicsProcessing ?: return
        if (!mbcEnabled) return

        try {
            writeMbcBands(dp, bands, crossovers, mbcThresholdOffsetDb)

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

    /** Push the limiter fields to the live DP without a rebuild — worker-thread, coalesced. */
    fun pushLimiterUpdate() {
        val dp = dynamicsProcessing ?: return
        val limiter = buildLimiter()
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
        // Drain queued band writes before teardown — else they hit a released handle.
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
