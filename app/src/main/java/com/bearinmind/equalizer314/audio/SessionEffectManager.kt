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
 * Owns the per-session [DynamicsProcessing] instances created when an
 * audio-app broadcasts `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (Spotify,
 * Poweramp, AIMP, etc.). On `OPEN` we look up the package's bound
 * preset and attach a DP with that preset's curve to the broadcast's
 * session ID, at `Integer.MAX_VALUE` priority (matches Wavelet's
 * `a6/n0.java:46` pattern). On `CLOSE` we release the DP.
 *
 * No-binding policy: if the package has no saved preset, we do
 * nothing (option A from the spec) — the session falls through to
 * the global session-0 DP that owns the rest of the audio.
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

    /** Snapshot of a currently-known session. Shown live in
     *  ChannelInputActivity's "Now playing" panel.
     *
     *  [isPlaying] reflects whether the package's [MediaController]
     *  reports `PlaybackState.STATE_PLAYING` right now. A row can be
     *  "known" (in [sessionInfo]) but not currently playing — e.g.
     *  Spotify broadcast OPEN, you hit pause; the session stays
     *  alive but isPlaying flips false. The UI uses this to start /
     *  stop the speaker-pulse animation per row. */
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
    // The typed setParameter(int, short) / (int, int) overloads are what the
    // framework's own EnvironmentalReverb uses internally; they tend to be
    // greylist-accessible (reachable via reflection under normal hidden-API
    // enforcement) whereas setParameter(byte[], byte[]) is blocked. We prefer
    // the typed ones and fall back to the byte[] form.
    private val setParamShort: java.lang.reflect.Method? by lazy {
        reflectSetParameter(Short::class.javaPrimitiveType!!)
    }
    private val setParamInt: java.lang.reflect.Method? by lazy {
        reflectSetParameter(Int::class.javaPrimitiveType!!)
    }
    private val setParamBytes: java.lang.reflect.Method? by lazy {
        try {
            AudioEffect::class.java.getDeclaredMethod(
                "setParameter", ByteArray::class.java, ByteArray::class.java,
            ).apply { isAccessible = true }
        } catch (t: Throwable) { null }
    }
    private fun reflectSetParameter(valueType: Class<*>): java.lang.reflect.Method? = try {
        AudioEffect::class.java.getDeclaredMethod(
            "setParameter", Int::class.javaPrimitiveType, valueType,
        ).apply { isAccessible = true }
    } catch (t: Throwable) {
        Log.w(TAG, "setParameter(int, ${valueType.simpleName}) unavailable: ${t.message}")
        null
    }
    private val sessionInfo = mutableMapOf<Int, ActiveSession>()
    /** (package, sessionId) pairs currently observed via the detection
     *  path. Used to compute the next [observeDetectedPlayback] diff so
     *  we only attach/detach for actual transitions, not on every poll. */
    private val detectedKeys = mutableSetOf<Pair<String, Int>>()
    /** Snapshot of packages currently in `PlaybackState.STATE_PLAYING`.
     *  Pushed in via [observeDetectedPlayback]; consulted whenever we
     *  build a new [ActiveSession] so the UI's animated speaker pulse
     *  reflects real-time playback state. */
    private var playingPackages: Set<String> = emptySet()
    private val eqPrefs = EqPreferencesManager(context)

    @Synchronized
    fun getActiveSessions(): List<ActiveSession> = sessionInfo.values.toList()

    /** Name of the preset currently driving per-app audio, for the
     *  notification + on-graph status chip's "(app preset)" label.
     *  Returns the bound preset name of the first session that is
     *  both playing right now AND has a binding. Returns null when:
     *   - routing isn't Session-based
     *   - no session is currently playing with a binding
     *  Callers fall back to other display modes in those cases. */
    @Synchronized
    fun getCurrentDrivingPreset(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (eqPrefs.getAudioRoutingMode() != 1) return null
        return sessionInfo.values
            .firstOrNull { it.packageName in playingPackages && !it.presetName.isNullOrBlank() }
            ?.presetName
    }

    /** Re-attach every currently-active session belonging to
     *  [packageName] so the binding edit the user just made in
     *  ChannelInputActivity takes effect on the live per-session DP.
     *  Without this the existing session keeps the old preset's bands
     *  until the audio stream closes and reopens (user has to stop /
     *  restart the audio app, or toggle DP).
     *
     *  Only runs in Session-based routing mode — per-app DPs aren't
     *  attached in System-wide mode anyway, so there's nothing to
     *  rebuild there. Reverbs are left alone; they're keyed on session
     *  id and not tied to the binding's preset. */
    @Synchronized
    fun reapplyBindingFor(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (eqPrefs.getAudioRoutingMode() != 1) return
        // Snapshot first — attach() will mutate sessionInfo.
        val affected = sessionInfo.values
            .filter { it.packageName == packageName }
            .toList()
        for (entry in affected) {
            // Drop the existing per-session DP so attach() builds a
            // fresh one with the new binding's bands + preamp. If the
            // new binding is `(none)` / null, attach() short-circuits
            // after releasing — the session plays unmodified.
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
        // BROADCAST source requires a real session id — a broadcaster
        // that hands us a non-positive id is misbehaving. DETECTED is
        // allowed to use negative synthetic ids (package-hash based)
        // when the OEM blocks our `dumpsys audio` access; those entries
        // are tracked for the "Now playing" UI but skip the DP attach
        // path below since they don't address a real audio stream.
        if (source == AttachSource.BROADCAST && sessionId <= 0) return

        // Always remember the package — even if it has no binding,
        // the Channel Input screen will list it so the user can bind
        // a preset retroactively.
        eqPrefs.rememberSeenApp(packageName)

        val binding = eqPrefs.getAppBinding(packageName)
        val existing = sessionInfo[sessionId]

        // BROADCAST is authoritative. If a BROADCAST entry already exists
        // for this session, a DETECTED dump observation must not
        // overwrite it (and must not re-attach the DP — that's already
        // managed by the broadcast lifecycle).
        if (existing != null &&
            existing.source == AttachSource.BROADCAST &&
            source == AttachSource.DETECTED
        ) {
            Log.d(TAG, "DETECTED arrived for session=$sessionId pkg=$packageName but BROADCAST owns it — skipping")
            return
        }

        // Update / insert sessionInfo BEFORE any routing-mode gate so the
        // "Now playing" UI shows the session even in System-wide mode.
        sessionInfo[sessionId] = ActiveSession(
            sessionId, packageName, binding?.presetName, source,
            isPlaying = playingPackages.contains(packageName),
        )
        notifySessionsChanged()

        // Routing mode gate. Tracking is mode-independent (above); DP /
        // reverb attachment is Session-based only. 1 = SESSION_BASED.
        if (eqPrefs.getAudioRoutingMode() != 1) {
            return
        }

        // DETECTED-with-synthetic-id: no real audio stream behind this
        // entry, so skip DP/reverb attach. The "Now playing" row still
        // appears so the user sees the app, but the visible meta will
        // say "Detected (no session)".
        if (sessionId <= 0) return

        // Reverb is independent of the EQ binding — the user might
        // want reverb on a session even without a preset bound, or a
        // preset bound but reverb disabled. Attach if the pipeline's
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

        // Replace any existing DP for this session, but preserve the
        // reverb (different effect, different lifecycle).
        sessions.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }

        try {
            val dp = createSessionDp(sessionId, loaded.leftEq, loaded.rightEq, loaded.preampDb)
            sessions[sessionId] = dp
            Log.d(TAG, "Attached DP session=$sessionId pkg=$packageName preset=${binding.presetName} preamp=${"%.1f".format(loaded.preampDb)}dB source=$source")
        } catch (t: Throwable) {
            // Matches Wavelet's a6/n0.java:47 — catch and silently
            // null out on construction failure (another EQ app may
            // already own the session at higher priority, or the
            // session may have closed before we got here).
            Log.w(TAG, "Could not attach DP to session $sessionId", t)
        }
    }

    /** Called by [EqService] when [PlaybackListenerService] has run a new
     *  dump-parse snapshot. We diff against the previous detection set so
     *  attach/detach only fires for transitions, not on every 100 ms poll.
     *
     *  - Pairs in [detected] but not in [detectedKeys] → `attach(.., DETECTED)`.
     *  - Pairs in [detectedKeys] but not in [detected] → `detach(..)`
     *    **only if** the entry's current source is DETECTED. BROADCAST
     *    entries manage their own teardown via CLOSE_AUDIO_EFFECT_CONTROL_SESSION.
     *
     *  [playingNow] is the set of packages currently in
     *  `PlaybackState.STATE_PLAYING`. Every tracked entry's `isPlaying`
     *  flag is reconciled against this set — used by the row's animated
     *  speaker pulse to differentiate "outputting right now" from
     *  "session is known but paused / silent." */
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
            // Only detach if the session is still tracked as DETECTED.
            // If a BROADCAST took over since we last saw it, leave it
            // alone — the broadcast's CLOSE will manage teardown.
            if (sessionInfo[sid]?.source == AttachSource.DETECTED) {
                detach(sid)
            }
        }

        detectedKeys.clear()
        detectedKeys.addAll(newPairs)

        // Reconcile isPlaying for every tracked row (including BROADCAST
        // entries — Spotify pausing is the same signal regardless of
        // how we learned about it). Emit notifySessionsChanged once if
        // any entry's isPlaying changed.
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

    /** Re-evaluates DP / reverb attachment for every tracked session in
     *  response to a routing-mode change. The mode toggles between
     *  Session-based (1) and System-wide (0); transitioning either way
     *  needs effects to spin up or release.
     *
     *  Reverb is also handled inside [applyReverbParamsToAll]; we call
     *  it elsewhere right after this so both effect types stay in sync. */
    @Synchronized
    fun onRoutingModeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val isSessionBased = eqPrefs.getAudioRoutingMode() == 1
        if (!isSessionBased) {
            // Leaving Session-based — release every per-session DP but
            // keep sessionInfo so the UI continues to show what's
            // playing (reverbs are managed by applyReverbParamsToAll).
            for ((_, dp) in sessions) {
                try { dp.release() } catch (_: Throwable) {}
            }
            sessions.clear()
            return
        }
        // Entering Session-based — re-attach DPs for every tracked
        // session. attach() is idempotent: it releases any prior DP
        // for the same sessionId before creating a new one.
        for ((sid, info) in sessionInfo.toMap()) {
            attach(sid, info.packageName, info.source)
        }
    }

    /** Re-applies the currently persisted reverb parameters to every
     *  attached reverb. Called by the activity each time a slider /
     *  XY-graph moves. Also handles enable/disable transitions: when
     *  the pipeline's reverb toggle flips off we detach every reverb;
     *  when it flips on we attach one for every tracked session. */
    @Synchronized
    fun applyReverbParamsToAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Reverb follows the EQ's routing so it always passes through when on:
        //   • Session-based mode → one reverb per tracked app session
        //   • Global (System-wide) mode → a single reverb on session 0 (the
        //     output mix), the same place the global EQ attaches.
        // The toggle alone gates whether reverb runs; the routing mode only
        // decides where it attaches.
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
            // Instantiate the INSERT EnvironmentalReverb implementation, not the
            // high-level EnvironmentalReverb(priority, session) ctor — that one
            // resolves to the *auxiliary* reverb, which only processes audio
            // explicitly routed to it via AudioTrack.attachAuxEffect(). A
            // system-wide effect can't open that send on other apps' tracks, so
            // the aux reverb sits in the chain fed silence and does nothing
            // (confirmed in the AudioFlinger dump: "Auxiliary Environmental
            // Reverb"). The INSERT variant processes the signal inline, exactly
            // like DynamicsProcessing — so it actually affects the audio,
            // including over Bluetooth.
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
        // Short-valued param: prefer setParameter(int, short); fall back to the
        // byte[] form if the typed overload is blocked.
        fun setS(name: String, paramId: Int, value: Short) {
            try {
                val status = when {
                    setParamShort != null -> setParamShort!!.invoke(fx, paramId, value) as? Int
                    setParamBytes != null -> setParamBytes!!.invoke(fx, leBytes(paramId), leBytes(value)) as? Int
                    else -> { Log.w(TAG, "Reverb '$name': no setParameter accessible"); return }
                } ?: AudioEffect.SUCCESS
                if (status != AudioEffect.SUCCESS) Log.w(TAG, "Reverb '$name' set returned $status")
            } catch (t: Throwable) {
                Log.w(TAG, "Reverb '$name' rejected, skipping: ${(t.cause ?: t).message}")
            }
        }
        // Int-valued param (ms): prefer setParameter(int, int).
        fun setI(name: String, paramId: Int, value: Int) {
            try {
                val status = when {
                    setParamInt != null -> setParamInt!!.invoke(fx, paramId, value) as? Int
                    setParamBytes != null -> setParamBytes!!.invoke(fx, leBytes(paramId), leBytes(value)) as? Int
                    else -> { Log.w(TAG, "Reverb '$name': no setParameter accessible"); return }
                } ?: AudioEffect.SUCCESS
                if (status != AudioEffect.SUCCESS) Log.w(TAG, "Reverb '$name' set returned $status")
            } catch (t: Throwable) {
                Log.w(TAG, "Reverb '$name' rejected, skipping: ${(t.cause ?: t).message}")
            }
        }
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

    /** Little-endian (native order on Android) encodings for
     *  AudioEffect.setParameter(byte[], byte[]). */
    private fun leBytes(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun leBytes(v: Short): ByteArray {
        val i = v.toInt()
        return byteArrayOf(i.toByte(), (i shr 8).toByte())
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
        // Convert each channel independently so Channel-Side-EQ presets get
        // their separate L/R filters. For non-CSE presets leftEq === rightEq
        // so both channels resolve to identical gains (cutoffs are the same
        // log-spaced set either way).
        val leftResp = ParametricToDpConverter.convertFeatureAware(leftEq)
        val rightResp = ParametricToDpConverter.convertFeatureAware(rightEq)
        val leftCutoffs = leftResp.cutoffs
        val leftGains = leftResp.gains
        val rightGains = rightResp.gains
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
