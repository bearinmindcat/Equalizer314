package com.bearinmind.equalizer314.audio

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.os.Build
import android.util.Log
import java.util.UUID
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.json.JSONArray
import org.json.JSONObject

/** Per-app DynamicsProcessing instances: OPEN session → attach the bound preset's DP, CLOSE → release; unbound sessions fall through to session 0. */
class SessionEffectManager(private val context: Context) {

    /** How the session was learned: BROADCAST (app's effect-control broadcast, authoritative) or DETECTED (NLS + dump-parse). */
    enum class AttachSource { BROADCAST, DETECTED }

    /** Known session for the "Now playing" panel; [isPlaying] drives the speaker pulse. */
    data class ActiveSession(
        val sessionId: Int,
        val packageName: String,
        val presetName: String?,
        val source: AttachSource,
        val isPlaying: Boolean = false,
    )

    private val sessions = mutableMapOf<Int, DynamicsProcessing>()
    // Insert reverb via the low-level AudioEffect ctor (the SDK ctor gives the silent auxiliary variant).
    private val reverbs = mutableMapOf<Int, AudioEffect>()

    // Hidden AudioEffect ctor reached by reflection; null when blocked (reverb then doesn't attach).
    private val insertReverbCtor: java.lang.reflect.Constructor<*>? by lazy {
        try {
            AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java, UUID::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "Insert-reverb ctor reflection unavailable: ${t.message}")
            null
        }
    }
    // setParameter(int[], short[]) is the only overload still reflection-accessible under hidden-API enforcement.
    private val setParamArr: java.lang.reflect.Method? by lazy {
        try {
            AudioEffect::class.java.getDeclaredMethod(
                "setParameter", IntArray::class.java, ShortArray::class.java,
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "setParameter(int[], short[]) unavailable: ${t.message}")
            null
        }
    }
    private val sessionInfo = mutableMapOf<Int, ActiveSession>()
    /** Detected (package, sessionId) pairs — diffed so attach/detach fires only on transitions. */
    private val detectedKeys = mutableSetOf<Pair<String, Int>>()
    /** Packages currently in STATE_PLAYING — feeds the speaker pulse. */
    private var playingPackages: Set<String> = emptySet()
    private val eqPrefs = EqPreferencesManager(context)

    @Synchronized
    fun getActiveSessions(): List<ActiveSession> = sessionInfo.values.toList()

    /** Bound preset of the first playing bound session; null outside Session mode or when none. */
    @Synchronized
    fun getCurrentDrivingPreset(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (eqPrefs.getAudioRoutingMode() != 1) return null
        return sessionInfo.values
            .firstOrNull { it.packageName in playingPackages && !it.presetName.isNullOrBlank() }
            ?.presetName
    }

    /** Rebuild every active session of [packageName] so a binding edit hits the live DP (reverbs untouched). */
    @Synchronized
    fun reapplyBindingFor(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (eqPrefs.getAudioRoutingMode() != 1) return
        // Snapshot first — attach() will mutate sessionInfo.
        val affected = sessionInfo.values
            .filter { it.packageName == packageName }
            .toList()
        for (entry in affected) {
            // Drop the existing DP so attach() builds a fresh one for the new binding.
            sessions.remove(entry.sessionId)?.let {
                try { it.release() } catch (_: Throwable) {}
            }
            attach(entry.sessionId, entry.packageName, entry.source)
        }
        if (affected.isNotEmpty()) {
            Log.d(TAG, "reapplyBindingFor($packageName) rebuilt ${affected.size} session(s)")
        }
    }

    private fun notifySessionsChanged() {
        drivingPresetName = getCurrentDrivingPreset()
        context.sendBroadcast(
            android.content.Intent(ACTION_SESSIONS_CHANGED)
                .setPackage(context.packageName),
        )
    }

    @Synchronized
    fun attach(
        sessionId: Int,
        packageName: String,
        source: AttachSource = AttachSource.BROADCAST,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // BROADCAST needs a real session id; DETECTED may carry synthetic negative ids (UI-only).
        if (source == AttachSource.BROADCAST && sessionId <= 0) return

        // Remember the package even without a binding — Channel Input lists it.
        eqPrefs.rememberSeenApp(packageName)

        val binding = eqPrefs.getAppBinding(packageName)
        val existing = sessionInfo[sessionId]

        // BROADCAST is authoritative — a DETECTED observation never overrides it.
        if (existing != null &&
            existing.source == AttachSource.BROADCAST &&
            source == AttachSource.DETECTED
        ) {
            Log.d(TAG, "DETECTED arrived for session=$sessionId pkg=$packageName but BROADCAST owns it — skipping")
            return
        }

        // Track before the routing-mode gate so "Now playing" works in System-wide too.
        sessionInfo[sessionId] = ActiveSession(
            sessionId, packageName, binding?.presetName, source,
            isPlaying = playingPackages.contains(packageName),
        )
        notifySessionsChanged()

        // DP / reverb attachment is Session-based only (mode 1).
        if (eqPrefs.getAudioRoutingMode() != 1) {
            return
        }

        // Synthetic id = no real stream; skip attach.
        if (sessionId <= 0) return

        // Reverb is independent of the EQ binding — attach when the pipeline toggle is on.
        if (eqPrefs.isAudioEffectEnabled(EFFECT_REVERB_NAME)) {
            attachReverbLocked(sessionId)
        }

        if (binding == null) {
            Log.d(TAG, "No binding for $packageName — tracking only (session=$sessionId source=$source)")
            return
        }

        val loaded = loadPreset(binding.presetName)
        if (loaded == null) {
            Log.w(TAG, "Binding for $packageName references missing preset '${binding.presetName}'")
            return
        }

        // Replace any existing DP for this session; the reverb has its own lifecycle.
        sessions.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }

        try {
            val dp = createSessionDp(sessionId, loaded)
            sessions[sessionId] = dp
            Log.d(TAG, "Attached DP session=$sessionId pkg=$packageName preset=${binding.presetName} preamp=${"%.1f".format(loaded.preampDb)}dB source=$source")
        } catch (t: Throwable) {
            // Swallow construction failure — another EQ may own the session, or it closed.
            Log.w(TAG, "Could not attach DP to session $sessionId", t)
        }
    }

    /** Per detection snapshot: new pairs attach (DETECTED), vanished DETECTED pairs detach, isPlaying reconciled. */
    @Synchronized
    fun observeDetectedPlayback(
        detected: Map<String, Set<Int>>,
        playingNow: Set<String> = emptySet(),
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        playingPackages = playingNow

        val newPairs = mutableSetOf<Pair<String, Int>>()
        for ((pkg, sids) in detected) for (sid in sids) newPairs.add(pkg to sid)

        val added = newPairs - detectedKeys
        val removed = detectedKeys - newPairs

        for ((pkg, sid) in added) {
            attach(sid, pkg, AttachSource.DETECTED)
        }
        for ((_, sid) in removed) {
            // Detach only if still DETECTED — BROADCAST's CLOSE owns teardown otherwise.
            if (sessionInfo[sid]?.source == AttachSource.DETECTED) {
                detach(sid)
            }
        }

        detectedKeys.clear()
        detectedKeys.addAll(newPairs)

        // Reconcile isPlaying for every row; notify once if anything changed.
        var changed = false
        for ((sid, info) in sessionInfo.toMap()) {
            val nowPlaying = playingPackages.contains(info.packageName)
            if (info.isPlaying != nowPlaying) {
                sessionInfo[sid] = info.copy(isPlaying = nowPlaying)
                changed = true
            }
        }
        if (changed) notifySessionsChanged()
    }

    /** Re-evaluate per-session DP attachment on a routing-mode change. */
    @Synchronized
    fun onRoutingModeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val isSessionBased = eqPrefs.getAudioRoutingMode() == 1
        if (!isSessionBased) {
            // Leaving Session-based: release per-session DPs, keep sessionInfo for the UI.
            for ((_, dp) in sessions) {
                try { dp.release() } catch (_: Throwable) {}
            }
            sessions.clear()
            return
        }
        // Entering Session-based: re-attach DPs for every tracked session (attach is idempotent).
        for ((sid, info) in sessionInfo.toMap()) {
            attach(sid, info.packageName, info.source)
        }
    }

    /** Re-apply persisted reverb params everywhere; handles the toggle's attach/detach transitions. */
    @Synchronized
    fun applyReverbParamsToAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Reverb follows the EQ's routing: per session in Session-based, session 0 in System-wide.
        val reverbOn = eqPrefs.isAudioEffectEnabled(EFFECT_REVERB_NAME)
        if (!reverbOn) {
            for ((_, r) in reverbs) {
                try { r.release() } catch (_: Throwable) {}
            }
            reverbs.clear()
            return
        }
        val sessionMode = eqPrefs.getAudioRoutingMode() == 1
        val wanted: Set<Int> = if (sessionMode) {
            sessionInfo.keys.filter { it > 0 }.toSet()
        } else {
            setOf(GLOBAL_REVERB_SESSION)
        }
        // Release reverbs that no longer belong so global and per-session never run at once.
        for (sid in reverbs.keys.filter { it !in wanted }) {
            reverbs.remove(sid)?.let { try { it.release() } catch (_: Throwable) {} }
        }
        // Attach any that are missing.
        for (sid in wanted) {
            if (sid !in reverbs) attachReverbLocked(sid)
        }
        // Push current params into every attached reverb.
        for ((_, r) in reverbs) {
            try { configureReverb(r) } catch (t: Throwable) {
                Log.w(TAG, "Reverb param push failed", t)
            }
        }
    }

    private fun attachReverbLocked(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Allow session 0 (global output mix); only reject negative/synthetic ids.
        if (sessionId < 0) return
        reverbs.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }
        try {
            // INSERT reverb impl — the SDK ctor's auxiliary variant only hears attachAuxEffect sends (silent system-wide).
            val ctor = insertReverbCtor ?: run {
                Log.w(TAG, "Insert reverb unavailable (reflection blocked) — session=$sessionId")
                return
            }
            val fx = ctor.newInstance(
                EFFECT_TYPE_NULL_UUID, INSERT_ENV_REVERB_UUID, Integer.MAX_VALUE, sessionId,
            ) as AudioEffect
            configureReverb(fx)
            fx.enabled = true
            reverbs[sessionId] = fx
            Log.d(TAG, "Attached INSERT reverb session=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach reverb to session $sessionId", t)
        }
    }

    /** Push persisted reverb prefs into [r], clamped to the documented ranges. */
    private fun configureReverb(fx: AudioEffect) {
        // Low-level setParameter with the public PARAM_* ids; each applied independently so one rejection can't abort the rest.
        val m = setParamArr
        fun invoke(name: String, paramId: Int, value: ShortArray) {
            if (m == null) { Log.w(TAG, "Reverb '$name': setParameter unavailable"); return }
            try {
                val status = m.invoke(fx, intArrayOf(paramId), value) as? Int ?: AudioEffect.SUCCESS
                if (status != AudioEffect.SUCCESS) Log.w(TAG, "Reverb '$name' set returned $status")
            } catch (t: Throwable) {
                Log.w(TAG, "Reverb '$name' rejected, skipping: ${(t.cause ?: t).message}")
            }
        }
        // Short-valued param → one short.
        fun setS(name: String, paramId: Int, value: Short) =
            invoke(name, paramId, shortArrayOf(value))
        // Int-valued param (ms) → low + high 16 bits, little-endian (4 bytes).
        fun setI(name: String, paramId: Int, value: Int) =
            invoke(name, paramId, shortArrayOf((value and 0xFFFF).toShort(), (value ushr 16).toShort()))
        // dB×100 = mB, %×10 = permille; clamped to what real engines accept (decay ≤ 7 s, reverbLevel ≤ 0).
        setS("roomLevel", EnvironmentalReverb.PARAM_ROOM_LEVEL,
            (eqPrefs.getReverbRoomLevelDb() * 100f).coerceIn(-9000f, 0f).toInt().toShort())
        setS("roomHFLevel", EnvironmentalReverb.PARAM_ROOM_HF_LEVEL,
            (eqPrefs.getReverbRoomHFLevelDb() * 100f).coerceIn(-9000f, 0f).toInt().toShort())
        setI("decayTime", EnvironmentalReverb.PARAM_DECAY_TIME,
            eqPrefs.getReverbDecayTimeMs().coerceIn(100f, 7000f).toInt())
        setS("decayHFRatio", EnvironmentalReverb.PARAM_DECAY_HF_RATIO,
            (eqPrefs.getReverbDecayHfRatio() * 1000f).coerceIn(100f, 2000f).toInt().toShort())
        setS("reflectionsLevel", EnvironmentalReverb.PARAM_REFLECTIONS_LEVEL,
            (eqPrefs.getReverbReflectionsLevelDb() * 100f).coerceIn(-9000f, 1000f).toInt().toShort())
        setI("reflectionsDelay", EnvironmentalReverb.PARAM_REFLECTIONS_DELAY,
            eqPrefs.getReverbReflectionsDelayMs().coerceIn(0f, 300f).toInt())
        setS("reverbLevel", EnvironmentalReverb.PARAM_REVERB_LEVEL,
            (eqPrefs.getReverbReverbLevelDb() * 100f).coerceIn(-9000f, 0f).toInt().toShort())
        setI("reverbDelay", EnvironmentalReverb.PARAM_REVERB_DELAY,
            eqPrefs.getReverbDelayMs().coerceIn(0f, 100f).toInt())
        setS("diffusion", EnvironmentalReverb.PARAM_DIFFUSION,
            (eqPrefs.getReverbDiffusionPct() * 10f).coerceIn(0f, 1000f).toInt().toShort())
        setS("density", EnvironmentalReverb.PARAM_DENSITY,
            (eqPrefs.getReverbDensityPct() * 10f).coerceIn(0f, 1000f).toInt().toShort())
    }


    @Synchronized
    fun detach(sessionId: Int) {
        sessions.remove(sessionId)?.let { dp ->
            try { dp.release() } catch (_: Throwable) {}
            Log.d(TAG, "Detached DP from session $sessionId")
        }
        reverbs.remove(sessionId)?.let { r ->
            try { r.release() } catch (_: Throwable) {}
            Log.d(TAG, "Detached reverb from session $sessionId")
        }
        val removed = sessionInfo.remove(sessionId)
        // Clear from the detection set so it isn't re-detached later.
        if (removed != null) {
            detectedKeys.removeAll { it.second == sessionId }
            notifySessionsChanged()
        }
    }

    /** Release every DETECTED-source effect (BROADCAST entries keep their own CLOSE lifecycle). */
    @Synchronized
    fun releaseDetected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val toDrop = sessionInfo.entries
            .filter { it.value.source == AttachSource.DETECTED }
            .map { it.key }
        if (toDrop.isEmpty()) return
        for (sid in toDrop) {
            sessions.remove(sid)?.let {
                try { it.release() } catch (_: Throwable) {}
            }
            reverbs.remove(sid)?.let {
                try { it.release() } catch (_: Throwable) {}
            }
            sessionInfo.remove(sid)
        }
        detectedKeys.clear()
        notifySessionsChanged()
        Log.d(TAG, "Released ${toDrop.size} DETECTED-source session(s)")
    }

    @Synchronized
    fun releaseAll() {
        for ((_, dp) in sessions) {
            try { dp.release() } catch (_: Throwable) {}
        }
        sessions.clear()
        for ((_, r) in reverbs) {
            try { r.release() } catch (_: Throwable) {}
        }
        reverbs.clear()
        val hadInfo = sessionInfo.isNotEmpty()
        sessionInfo.clear()
        detectedKeys.clear()
        if (hadInfo) notifySessionsChanged()
    }

    /** Full-chain per-app DP from the preset's chain (global prefs as fallback) — same engine settings as session 0. */
    private fun createSessionDp(sessionId: Int, loaded: LoadedPreset): DynamicsProcessing {
        if (ParametricToDpConverter.numBands < 32) ParametricToDpConverter.setNumBands(127)
        val bandCount = ParametricToDpConverter.numBands
        val frameMs = DynamicsProcessingManager.effectiveFrameMs
        ParametricToDpConverter.frameDurationMs = frameMs
        val chain = resolveChain(loaded.json)
        val mbcStageBands = if (chain.mbcEnabled) chain.mbcBands.size.coerceAtLeast(1) else 1

        fun build(interleave: Boolean): DynamicsProcessing {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, bandCount,
                true, mbcStageBands,
                interleave, if (interleave) bandCount else 0,
                true,
            ).setPreferredFrameDuration(frameMs).build()
            return DynamicsProcessing(Integer.MAX_VALUE, sessionId, config)
        }
        var interleave = DynamicsProcessingManager.effectiveInterleave
        val dp = try {
            build(interleave)
        } catch (t: Throwable) {
            if (!interleave) throw t
            interleave = false
            build(false)
        }

        // Stage order matches the global DP: limiter -> MBC -> EQ -> enable.
        val limiter = DynamicsProcessing.Limiter(
            chain.limiterEnabled, chain.limiterEnabled, 0,
            chain.limiterAttack, chain.limiterRelease, chain.limiterRatio,
            chain.limiterThreshold, chain.limiterPostGain,
        )
        dp.setLimiterByChannelIndex(0, limiter)
        dp.setLimiterByChannelIndex(1, limiter)
        if (chain.mbcEnabled) {
            DynamicsProcessingManager.writeMbcBands(dp, chain.mbcBands, chain.crossovers, 0f)
        } else {
            val dummy = DynamicsProcessingManager.mbcPassthroughBand()
            dp.setMbcBandByChannelIndex(0, 0, dummy)
            dp.setMbcBandByChannelIndex(1, 0, dummy)
        }

        applySessionEq(dp, loaded.leftEq, loaded.rightEq, interleave)

        if (loaded.preampDb != 0f) {
            try {
                dp.setInputGainbyChannel(0, loaded.preampDb)
                dp.setInputGainbyChannel(1, loaded.preampDb)
            } catch (e: Throwable) {
                Log.w(TAG, "setInputGainbyChannel failed for session $sessionId", e)
            }
        }
        dp.enabled = true
        return dp
    }

    private fun applySessionEq(
        dp: DynamicsProcessing,
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
        interleave: Boolean,
    ) {
        val cutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        var postCutoffs: FloatArray? = null
        var leftPost: FloatArray? = null
        var rightPost: FloatArray? = null
        if (interleave) {
            if (leftEq === rightEq) {
                val li = ParametricToDpConverter.convertInterleaved(leftEq)
                cutoffs = li.preCutoffs; leftGains = li.preGains; rightGains = li.preGains.copyOf()
                postCutoffs = li.postCutoffs; leftPost = li.postGains; rightPost = li.postGains.copyOf()
            } else {
                val di = ParametricToDpConverter.convertInterleavedDual(leftEq, rightEq)
                cutoffs = di.preCutoffs; leftGains = di.leftPreGains; rightGains = di.rightPreGains
                postCutoffs = di.postCutoffs; leftPost = di.leftPostGains; rightPost = di.rightPostGains
            }
        } else {
            if (leftEq === rightEq) {
                val r = ParametricToDpConverter.convertFeatureAware(leftEq)
                cutoffs = r.cutoffs; leftGains = r.gains; rightGains = r.gains.copyOf()
            } else {
                val d = ParametricToDpConverter.convertFeatureAwareDual(leftEq, rightEq)
                cutoffs = d.cutoffs; leftGains = d.leftGains; rightGains = d.rightGains
            }
        }
        // Auto-gain: flat shift on the Pre stage so the loudest band lands at <= 0 dB.
        if (eqPrefs.getAutoGainEnabled()) {
            val scale = if (interleave) 2f else 1f
            var peak = Float.NEGATIVE_INFINITY
            for (g in leftGains) if (g * scale > peak) peak = g * scale
            for (g in rightGains) if (g * scale > peak) peak = g * scale
            leftPost?.forEach { if (it * scale > peak) peak = it * scale }
            rightPost?.forEach { if (it * scale > peak) peak = it * scale }
            if (peak > 0f) {
                for (i in leftGains.indices) leftGains[i] -= peak
                for (i in rightGains.indices) rightGains[i] -= peak
            }
        }
        fun eqOf(c: FloatArray, g: FloatArray): DynamicsProcessing.Eq {
            val e = DynamicsProcessing.Eq(true, true, c.size)
            for (i in c.indices) e.setBand(i, DynamicsProcessing.EqBand(true, c[i], g[i]))
            return e
        }
        dp.setPreEqByChannelIndex(0, eqOf(cutoffs, leftGains))
        dp.setPreEqByChannelIndex(1, eqOf(cutoffs, rightGains))
        if (interleave && postCutoffs != null && leftPost != null && rightPost != null) {
            dp.setPostEqByChannelIndex(0, eqOf(postCutoffs, leftPost))
            dp.setPostEqByChannelIndex(1, eqOf(postCutoffs, rightPost))
        }
    }

    private class SessionChain(
        val limiterEnabled: Boolean,
        val limiterAttack: Float,
        val limiterRelease: Float,
        val limiterRatio: Float,
        val limiterThreshold: Float,
        val limiterPostGain: Float,
        val mbcEnabled: Boolean,
        val mbcBands: List<DynamicsProcessingManager.MbcBandParams>,
        val crossovers: FloatArray,
    )

    /** Preset chain with the global prefs filling anything the preset omits. */
    private fun resolveChain(json: JSONObject): SessionChain {
        val p = eqPrefs
        val lim = json.optJSONObject("limiter")
        val mbc = json.optJSONObject("mbc")
        val mbcEnabled = mbc?.optBoolean("enabled", p.getMbcEnabled()) ?: p.getMbcEnabled()
        val count = (mbc?.optInt("bandCount", p.getMbcBandCount()) ?: p.getMbcBandCount()).coerceIn(1, 8)
        val bandsJson = mbc?.optJSONArray("bands")
        val bands = (0 until count).map { i ->
            val b = bandsJson?.optJSONObject(i)
            if (b != null) DynamicsProcessingManager.MbcBandParams(
                enabled = b.optBoolean("enabled", true),
                attackMs = b.optDouble("attack", 1.0).toFloat(),
                releaseMs = b.optDouble("release", 100.0).toFloat(),
                ratio = b.optDouble("ratio", 2.0).toFloat(),
                thresholdDb = b.optDouble("threshold", 0.0).toFloat(),
                kneeDb = b.optDouble("knee", 8.0).toFloat(),
                noiseGateDb = b.optDouble("noiseGate", -60.0).toFloat(),
                expanderRatio = b.optDouble("expander", 1.0).toFloat(),
                preGainDb = b.optDouble("preGain", 0.0).toFloat(),
                postGainDb = b.optDouble("postGain", 0.0).toFloat(),
            ) else DynamicsProcessingManager.MbcBandParams(
                enabled = p.getMbcBandEnabled(i),
                attackMs = p.getMbcBandAttack(i),
                releaseMs = p.getMbcBandRelease(i),
                ratio = p.getMbcBandRatio(i),
                thresholdDb = p.getMbcBandThreshold(i),
                kneeDb = p.getMbcBandKnee(i),
                noiseGateDb = p.getMbcBandNoiseGate(i),
                expanderRatio = p.getMbcBandExpander(i),
                preGainDb = p.getMbcBandPreGain(i),
                postGainDb = p.getMbcBandPostGain(i),
            )
        }
        val crossJson = mbc?.optJSONArray("crossovers")
        val crossovers = FloatArray(maxOf(0, count - 1)) { i ->
            val fallback = p.getMbcCrossover(i, EqService.MBC_DEFAULT_CUTOFFS.getOrElse(i) { 1000f })
            crossJson?.optDouble(i, fallback.toDouble())?.toFloat() ?: fallback
        }
        return SessionChain(
            limiterEnabled = lim?.optBoolean("enabled", p.getLimiterEnabled()) ?: p.getLimiterEnabled(),
            limiterAttack = lim?.optDouble("attack", p.getLimiterAttack().toDouble())?.toFloat() ?: p.getLimiterAttack(),
            limiterRelease = lim?.optDouble("release", p.getLimiterRelease().toDouble())?.toFloat() ?: p.getLimiterRelease(),
            limiterRatio = lim?.optDouble("ratio", p.getLimiterRatio().toDouble())?.toFloat() ?: p.getLimiterRatio(),
            limiterThreshold = lim?.optDouble("threshold", p.getLimiterThreshold().toDouble())?.toFloat() ?: p.getLimiterThreshold(),
            limiterPostGain = lim?.optDouble("postGain", p.getLimiterPostGain().toDouble())?.toFloat() ?: p.getLimiterPostGain(),
            mbcEnabled = mbcEnabled,
            mbcBands = bands,
            crossovers = crossovers,
        )
    }

    /** Parsed preset: L/R EQs (same instance for non-CSE presets), preamp, and the raw JSON for the chain. */
    private data class LoadedPreset(
        val leftEq: ParametricEqualizer,
        val rightEq: ParametricEqualizer,
        val preampDb: Float,
        val json: JSONObject,
    )

    /** Load a pool preset's bands + preamp (CSE leftBands/rightBands honored); preamp defaults to 0 dB. */
    private fun loadPreset(name: String): LoadedPreset? {
        val prefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val str = runCatching { prefs.getString("preset_$name", null) }
            .getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(str)
            fun buildEq(arr: JSONArray): ParametricEqualizer {
                val eq = ParametricEqualizer()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val ft = runCatching {
                        BiquadFilter.FilterType.valueOf(b.getString("filterType"))
                    }.getOrDefault(BiquadFilter.FilterType.BELL)
                    eq.addBand(
                        b.getDouble("frequency").toFloat(),
                        b.getDouble("gain").toFloat(),
                        ft,
                        b.getDouble("q"),
                    )
                    if (b.has("enabled")) eq.setBandEnabled(i, b.getBoolean("enabled"))
                }
                eq.isEnabled = true
                return eq
            }
            val preamp = if (obj.has("preamp")) obj.getDouble("preamp").toFloat() else 0f
            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                LoadedPreset(
                    buildEq(obj.getJSONArray("leftBands")),
                    buildEq(obj.getJSONArray("rightBands")),
                    preamp,
                    obj,
                )
            } else {
                val bandsArr = obj.optJSONArray("bands") ?: return@runCatching null
                val eq = buildEq(bandsArr)
                LoadedPreset(eq, eq, preamp, obj)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SessionEffectManager"
        /** Binder-free mirror of [getCurrentDrivingPreset] for MainActivity's graph follow. */
        @Volatile
        var drivingPresetName: String? = null
            private set
        /** Package-targeted broadcast when the active session set changes (Channel Input listens). */
        const val ACTION_SESSIONS_CHANGED =
            "com.bearinmind.equalizer314.SESSIONS_CHANGED"
        /** Pipeline EffectId.name for the reverb card — keep in sync with AudioEffectsPipelineActivity. */
        const val EFFECT_REVERB_NAME = "ENVIRONMENTAL_REVERB"
        /** Session 0 = global output mix — where System-wide reverb attaches. */
        const val GLOBAL_REVERB_SESSION = 0
        /** Insert Environmental Reverb impl UUID — the auxiliary variant the SDK ctor picks is silent system-wide. */
        val INSERT_ENV_REVERB_UUID: UUID =
            UUID.fromString("c7a511a0-a3bb-11df-860e-0002a5d5c51b")
        /** AudioEffect.EFFECT_TYPE_NULL (hidden) — lets the impl uuid above select the effect. */
        val EFFECT_TYPE_NULL_UUID: UUID =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
    }
}
