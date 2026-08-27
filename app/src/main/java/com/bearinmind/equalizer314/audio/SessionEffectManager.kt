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

/**
 * Owns per-session [DynamicsProcessing] instances created when an audio app
 * broadcasts `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (Spotify, Poweramp, AIMP…).
 * OPEN → attach a DP with the package's bound preset at `Integer.MAX_VALUE`
 * priority (Wavelet `a6/n0.java:46` pattern); CLOSE → release.
 * No-binding policy (option A): do nothing — the session falls through to
 * the global session-0 DP.
 */
class SessionEffectManager(private val context: Context) {

    /** Where the system learned this session was alive.
     *  - [BROADCAST]: app called `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`
     *    (Spotify, Poweramp, AIMP, …). Authoritative; cannot be downgraded
     *    or removed by detection.
     *  - [DETECTED]: surfaced via the NLS + dump-parse path
     *    ([PlaybackListenerService] → [AudioPolicyDumpParser]). Used for
     *    YouTube / Chrome / ExoPlayer apps that never broadcast. */
    enum class AttachSource { BROADCAST, DETECTED }

    /** Snapshot of a known session; shown live in ChannelInputActivity's
     *  "Now playing" panel. [isPlaying] = package's [MediaController]
     *  reports `PlaybackState.STATE_PLAYING` now; a session can be known
     *  but paused (Spotify OPEN then pause). Drives the per-row
     *  speaker-pulse animation. */
    data class ActiveSession(
        val sessionId: Int,
        val packageName: String,
        val presetName: String?,
        val source: AttachSource,
        val isPlaying: Boolean = false,
    )

    private val sessions = mutableMapOf<Int, DynamicsProcessing>()
    // Reverb is the *insert* EnvironmentalReverb implementation, created via the
    // low-level AudioEffect ctor so it attaches inline like DynamicsProcessing
    // (the convenience EnvironmentalReverb(...) ctor gives the silent auxiliary
    // variant). Hence AudioEffect, not EnvironmentalReverb, as the value type.
    private val reverbs = mutableMapOf<Int, AudioEffect>()

    // The (type, uuid, priority, session) AudioEffect ctor and the
    // setParameter(byte[], byte[]) method aren't in the public SDK, so we
    // reach them by reflection (this is exactly what the framework's own
    // EnvironmentalReverb does internally). Resolved once, lazily; null if the
    // platform blocks the access, in which case reverb just doesn't attach.
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
    // Param-setting overload selection matters under hidden-API enforcement:
    // setParameter(int,short) / (int,int) / (byte[],byte[]) are all @TestApi
    // → BLOCKED for normal apps. But setParameter(int[] param, short[] value)
    // carries @UnsupportedAppUsage with NO maxTargetSdk (the light greylist),
    // so it stays reflection-accessible under normal enforcement — the same
    // tier as the AudioEffect(UUID,UUID,int,int) ctor we already rely on. This
    // is the one path that lets us configure the insert reverb in production.
    // (Int-valued params are packed into two little-endian shorts = 4 bytes.)
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
    /** (package, sessionId) pairs observed via detection — diffed in
     *  [observeDetectedPlayback] so attach/detach fires only on
     *  transitions, not every poll. */
    private val detectedKeys = mutableSetOf<Pair<String, Int>>()
    /** Packages currently in `PlaybackState.STATE_PLAYING` (pushed via
     *  [observeDetectedPlayback]); consulted when building an
     *  [ActiveSession] so the speaker pulse tracks real-time playback. */
    private var playingPackages: Set<String> = emptySet()
    private val eqPrefs = EqPreferencesManager(context)

    @Synchronized
    fun getActiveSessions(): List<ActiveSession> = sessionInfo.values.toList()

    /** Preset currently driving per-app audio (notification + status chip
     *  "(app preset)" label): bound preset of the first session that is
     *  playing AND bound. Null when routing isn't Session-based or nothing
     *  playing has a binding — callers fall back to other display modes. */
    @Synchronized
    fun getCurrentDrivingPreset(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (eqPrefs.getAudioRoutingMode() != 1) return null
        return sessionInfo.values
            .firstOrNull { it.packageName in playingPackages && !it.presetName.isNullOrBlank() }
            ?.presetName
    }

    /** Re-attach every active session of [packageName] so a binding edit in
     *  ChannelInputActivity hits the live per-session DP — otherwise the old
     *  preset's bands persist until the stream closes/reopens. Session-based
     *  mode only (per-app DPs aren't attached otherwise). Reverbs untouched:
     *  keyed on session id, not tied to the binding's preset. */
    @Synchronized
    fun reapplyBindingFor(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (eqPrefs.getAudioRoutingMode() != 1) return
        // Snapshot first — attach() will mutate sessionInfo.
        val affected = sessionInfo.values
            .filter { it.packageName == packageName }
            .toList()
        for (entry in affected) {
            // Drop the existing DP so attach() builds a fresh one with the
            // new binding's bands + preamp; if the new binding is null,
            // attach() short-circuits after releasing (plays unmodified).
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
        // BROADCAST requires a real session id (non-positive = misbehaving
        // broadcaster). DETECTED may use negative synthetic ids
        // (package-hash based) when the OEM blocks `dumpsys audio`; those
        // are tracked for the "Now playing" UI but skip DP attach — no real
        // audio stream behind them.
        if (source == AttachSource.BROADCAST && sessionId <= 0) return

        // Remember the package even without a binding so Channel Input
        // lists it for retroactive binding.
        eqPrefs.rememberSeenApp(packageName)

        val binding = eqPrefs.getAppBinding(packageName)
        val existing = sessionInfo[sessionId]

        // BROADCAST is authoritative: a DETECTED observation must not
        // overwrite it or re-attach the DP (broadcast lifecycle owns it).
        if (existing != null &&
            existing.source == AttachSource.BROADCAST &&
            source == AttachSource.DETECTED
        ) {
            Log.d(TAG, "DETECTED arrived for session=$sessionId pkg=$packageName but BROADCAST owns it — skipping")
            return
        }

        // sessionInfo update BEFORE the routing-mode gate so "Now playing"
        // shows the session even in System-wide mode.
        sessionInfo[sessionId] = ActiveSession(
            sessionId, packageName, binding?.presetName, source,
            isPlaying = playingPackages.contains(packageName),
        )
        notifySessionsChanged()

        // Tracking is mode-independent (above); DP / reverb attachment is
        // Session-based only (1 = SESSION_BASED).
        if (eqPrefs.getAudioRoutingMode() != 1) {
            return
        }

        // Synthetic-id DETECTED entry: no real stream, skip DP/reverb attach;
        // the "Now playing" row shows "Detected (no session)".
        if (sessionId <= 0) return

        // Reverb is independent of the EQ binding (reverb without preset, or
        // preset without reverb, are both valid). Attach if the pipeline's
        // ENVIRONMENTAL_REVERB toggle is on.
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

        // Replace any existing DP for this session; preserve the reverb
        // (different effect, different lifecycle).
        sessions.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }

        try {
            val dp = createSessionDp(sessionId, loaded.leftEq, loaded.rightEq, loaded.preampDb)
            sessions[sessionId] = dp
            Log.d(TAG, "Attached DP session=$sessionId pkg=$packageName preset=${binding.presetName} preamp=${"%.1f".format(loaded.preampDb)}dB source=$source")
        } catch (t: Throwable) {
            // Wavelet a6/n0.java:47 — swallow construction failure (another
            // EQ app may own the session at higher priority, or it closed).
            Log.w(TAG, "Could not attach DP to session $sessionId", t)
        }
    }

    /** Called by [EqService] on each [PlaybackListenerService] dump-parse
     *  snapshot. Diffs against the previous detection set so attach/detach
     *  fires only on transitions, not every 100 ms poll:
     *  new pairs → `attach(.., DETECTED)`; vanished pairs → `detach(..)`
     *  only if still DETECTED-sourced (BROADCAST entries tear down via
     *  CLOSE_AUDIO_EFFECT_CONTROL_SESSION). [playingNow] = packages in
     *  `PlaybackState.STATE_PLAYING`; every entry's `isPlaying` is
     *  reconciled against it for the speaker-pulse UI. */
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
            // Detach only if still DETECTED — if BROADCAST took over,
            // its CLOSE manages teardown.
            if (sessionInfo[sid]?.source == AttachSource.DETECTED) {
                detach(sid)
            }
        }

        detectedKeys.clear()
        detectedKeys.addAll(newPairs)

        // Reconcile isPlaying for every row (BROADCAST included — a pause is
        // the same signal either way); notify once if anything changed.
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

    /** Re-evaluate DP / reverb attachment for every tracked session on a
     *  routing-mode change (Session-based=1 ↔ System-wide=0). Reverb is
     *  also handled in [applyReverbParamsToAll], called right after this
     *  elsewhere so both effect types stay in sync. */
    @Synchronized
    fun onRoutingModeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val isSessionBased = eqPrefs.getAudioRoutingMode() == 1
        if (!isSessionBased) {
            // Leaving Session-based: release per-session DPs but keep
            // sessionInfo for the "what's playing" UI (reverbs handled by
            // applyReverbParamsToAll).
            for ((_, dp) in sessions) {
                try { dp.release() } catch (_: Throwable) {}
            }
            sessions.clear()
            return
        }
        // Entering Session-based: re-attach DPs for every tracked session.
        // attach() is idempotent (releases any prior DP for the sessionId).
        for ((sid, info) in sessionInfo.toMap()) {
            attach(sid, info.packageName, info.source)
        }
    }

    /** Re-apply persisted reverb params to every attached reverb (called per
     *  slider / XY-graph move). Also handles toggle transitions: off →
     *  detach all; on → attach one per tracked session. */
    @Synchronized
    fun applyReverbParamsToAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Reverb follows the EQ's routing: Session-based → one per tracked
        // app session; System-wide → single reverb on session 0 (output
        // mix), same place as the global EQ. Toggle gates whether it runs;
        // routing mode only decides where it attaches.
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
        // Release any reverb that no longer belongs (wrong mode, or a session
        // that closed) so the global and per-session paths never run at once.
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
            // INSERT EnvironmentalReverb impl, NOT the high-level
            // EnvironmentalReverb(priority, session) ctor — that resolves to
            // the *auxiliary* reverb, which only processes audio routed via
            // AudioTrack.attachAuxEffect(); a system-wide effect can't open
            // that send on other apps' tracks, so aux sits fed silence
            // (AudioFlinger dump confirmed: "Auxiliary Environmental
            // Reverb"). INSERT processes inline like DynamicsProcessing —
            // it actually affects audio, including over Bluetooth.
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

    /** Pushes the persisted reverb prefs into [r]. All API setters
     *  take signed shorts/ints — we clamp every value to the doc'd
     *  range before casting so a stale pref can't crash the effect. */
    private fun configureReverb(fx: AudioEffect) {
        // Params go through the low-level AudioEffect.setParameter(byte[],byte[])
        // (the high-level EnvironmentalReverb setters aren't available on a raw
        // AudioEffect). Param ids are the public EnvironmentalReverb.PARAM_*
        // constants; values are little-endian shorts (mB / permille) or ints
        // (ms), matching what the EnvironmentalReverb wrapper would send.
        // Each is applied independently so one rejected value can't abort the
        // whole config (which would leave reverb attached but silent).
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
        // dB × 100 = millibel; % × 10 = permille; ms as-is. Ranges clamped to
        // what real engines accept (decay ≤7 s, reverbLevel ≤0) — wider doc
        // maxima get ERROR_BAD_VALUE and silently leave the wet level muted.
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
        // Clear from the detection set too so we don't try to re-detach
        // a session we already let go.
        if (removed != null) {
            detectedKeys.removeAll { it.second == sessionId }
            notifySessionsChanged()
        }
    }

    /** Releases every effect attached via the DETECTED source (and only
     *  those — BROADCAST entries are managed by their own CLOSE
     *  lifecycle). Called when the user revokes Notification access,
     *  matching Wavelet's `SessionListenerService.java:71-80` teardown
     *  where the session map is cleared to empty on
     *  `onListenerDisconnected`, cascading effect release. */
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

    /** Build a fresh DP on [sessionId] with the [eq]'s curve applied
     *  to the Pre-EQ stage (both channels) and [preampDb] applied via
     *  the input-gain stage on both channels (matches how the global
     *  DP on session 0 handles preamp). No MBC / limiter / post-EQ on
     *  per-session — those are global-only concerns and the global DP
     *  handles them. */
    private fun createSessionDp(
        sessionId: Int,
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
        preampDb: Float = 0f,
    ): DynamicsProcessing {
        // Keep the same band count as the global DP so a preset
        // renders identically across session 0 and the per-app
        // attachment.
        if (ParametricToDpConverter.numBands < 32) {
            ParametricToDpConverter.setNumBands(127)
        }
        val bandCount = ParametricToDpConverter.numBands

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                  // stereo
            true,               // pre-EQ on
            bandCount,
            false,              // MBC off (handled globally)
            0,
            false,              // post-EQ off
            0,
            false,              // limiter off (handled globally)
        ).setPreferredFrameDuration(10f).build()

        val dp = DynamicsProcessing(Integer.MAX_VALUE, sessionId, config)
        val leftCutoffs: FloatArray
        val leftGains: FloatArray
        val rightGains: FloatArray
        if (leftEq === rightEq) {
            val resp = ParametricToDpConverter.convertFeatureAware(leftEq)
            leftCutoffs = resp.cutoffs
            leftGains = resp.gains
            rightGains = resp.gains
        } else {
            val dual = ParametricToDpConverter.convertFeatureAwareDual(leftEq, rightEq)
            leftCutoffs = dual.cutoffs
            leftGains = dual.leftGains
            rightGains = dual.rightGains
        }
        val n = leftCutoffs.size
        val leftEqObj = DynamicsProcessing.Eq(true, true, n)
        val rightEqObj = DynamicsProcessing.Eq(true, true, n)
        for (i in 0 until n) {
            leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, leftCutoffs[i], leftGains[i]))
            rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, leftCutoffs[i], rightGains[i]))
        }
        dp.setPreEqByChannelIndex(0, leftEqObj)
        dp.setPreEqByChannelIndex(1, rightEqObj)
        // Apply preamp via the DP's native input-gain stage on both
        // channels — same approach DynamicsProcessingManager uses for
        // the global DP (setInputGainbyChannel at line ~334), so a
        // preset sounds identical at session 0 and per-app.
        if (preampDb != 0f) {
            try {
                dp.setInputGainbyChannel(0, preampDb)
                dp.setInputGainbyChannel(1, preampDb)
            } catch (e: Throwable) {
                Log.w(TAG, "setInputGainbyChannel failed for session $sessionId", e)
            }
        }
        dp.enabled = true
        return dp
    }

    /** Left + right EQ + preamp as parsed out of a saved preset JSON.
     *  For non-CSE presets [leftEq] and [rightEq] reference the same bands
     *  (identical channels); for Channel-Side-EQ presets they hold the
     *  independent per-channel filters. */
    private data class LoadedPreset(
        val leftEq: ParametricEqualizer,
        val rightEq: ParametricEqualizer,
        val preampDb: Float,
    )

    /** Loads a custom preset's bands AND preamp from `custom_presets`
     *  SP and returns them together. Preamp defaults to 0 dB when the
     *  preset JSON is missing the field (older presets, or imports
     *  that never went through Save Preset). Mirrors the same JSON
     *  shape MainActivity / AudioOutputActivity / RouteSwitchCoordinator
     *  use — including Channel-Side-EQ's leftBands / rightBands so a
     *  per-app binding applies independent L/R filters just like the
     *  global/device path. */
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
                )
            } else {
                val bandsArr = obj.optJSONArray("bands") ?: return@runCatching null
                val eq = buildEq(bandsArr)
                LoadedPreset(eq, eq, preamp)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SessionEffectManager"
        /** Broadcast (package-targeted) emitted whenever the set of
         *  active broadcasting sessions changes. The Channel Input
         *  screen's "Current session" panel listens for this. */
        const val ACTION_SESSIONS_CHANGED =
            "com.bearinmind.equalizer314.SESSIONS_CHANGED"
        /** Pipeline EffectId.name for the reverb card — must stay in
         *  sync with [com.bearinmind.equalizer314.AudioEffectsPipelineActivity.EffectId.ENVIRONMENTAL_REVERB]. */
        const val EFFECT_REVERB_NAME = "ENVIRONMENTAL_REVERB"
        /** Audio session 0 = the global output mix. Used to attach reverb in
         *  System-wide (global) routing mode, the same place the global EQ
         *  (DynamicsProcessing) lives. */
        const val GLOBAL_REVERB_SESSION = 0
        /** Implementation UUID of the *insert* Environmental Reverb (AOSP/NXP
         *  reverb bundle, `reverb_env_ins`). The high-level EnvironmentalReverb
         *  ctor resolves to the auxiliary variant (`4a387fc0-…`), which is
         *  silent for system-wide use; the insert variant processes inline like
         *  DynamicsProcessing. */
        val INSERT_ENV_REVERB_UUID: UUID =
            UUID.fromString("c7a511a0-a3bb-11df-860e-0002a5d5c51b")
        /** AudioEffect.EFFECT_TYPE_NULL (hidden in the SDK). Passed as the
         *  `type` arg so the specific impl `uuid` above is what gets loaded. */
        val EFFECT_TYPE_NULL_UUID: UUID =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
    }
}
