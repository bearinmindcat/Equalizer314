package com.bearinmind.equalizer314.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqPreferencesManager
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
     *  ChannelInputActivity's "Now playing" panel. */
    data class ActiveSession(
        val sessionId: Int,
        val packageName: String,
        val presetName: String?,
        val source: AttachSource,
    )

    private val sessions = mutableMapOf<Int, DynamicsProcessing>()
    private val reverbs = mutableMapOf<Int, EnvironmentalReverb>()
    private val sessionInfo = mutableMapOf<Int, ActiveSession>()
    /** (package, sessionId) pairs currently observed via the detection
     *  path. Used to compute the next [observeDetectedPlayback] diff so
     *  we only attach/detach for actual transitions, not on every poll. */
    private val detectedKeys = mutableSetOf<Pair<String, Int>>()
    private val eqPrefs = EqPreferencesManager(context)

    @Synchronized
    fun getActiveSessions(): List<ActiveSession> = sessionInfo.values.toList()

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
        sessionInfo[sessionId] = ActiveSession(sessionId, packageName, binding?.presetName, source)
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

        val eq = loadPresetEq(binding.presetName)
        if (eq == null) {
            Log.w(TAG, "Binding for $packageName references missing preset '${binding.presetName}'")
            return
        }

        // Replace any existing DP for this session, but preserve the
        // reverb (different effect, different lifecycle).
        sessions.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }

        try {
            val dp = createSessionDp(sessionId, eq)
            sessions[sessionId] = dp
            Log.d(TAG, "Attached DP session=$sessionId pkg=$packageName preset=${binding.presetName} source=$source")
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
     *    entries manage their own teardown via CLOSE_AUDIO_EFFECT_CONTROL_SESSION. */
    @Synchronized
    fun observeDetectedPlayback(detected: Map<String, Set<Int>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

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
        val enabled = eqPrefs.isAudioEffectEnabled(EFFECT_REVERB_NAME) &&
            eqPrefs.getAudioRoutingMode() == 1
        if (!enabled) {
            for ((_, r) in reverbs) {
                try { r.release() } catch (_: Throwable) {}
            }
            reverbs.clear()
            return
        }
        // Cover any session we're tracking but haven't reverbed yet
        // (e.g. user just turned the toggle on while sessions were
        // already open).
        for (sid in sessionInfo.keys) {
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
        if (sessionId <= 0) return
        reverbs.remove(sessionId)?.let {
            try { it.release() } catch (_: Throwable) {}
        }
        try {
            val r = EnvironmentalReverb(Integer.MAX_VALUE, sessionId)
            configureReverb(r)
            r.enabled = true
            reverbs[sessionId] = r
            Log.d(TAG, "Attached reverb session=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach reverb to session $sessionId", t)
        }
    }

    /** Pushes the persisted reverb prefs into [r]. All API setters
     *  take signed shorts/ints — we clamp every value to the doc'd
     *  range before casting so a stale pref can't crash the effect. */
    private fun configureReverb(r: EnvironmentalReverb) {
        // dB × 100 = millibel. Android caps at -9000..0 / -9000..1000 /
        // -9000..2000 depending on the setter. Clamp defensively.
        r.roomLevel = (eqPrefs.getReverbRoomLevelDb() * 100f)
            .coerceIn(-9000f, 0f).toInt().toShort()
        r.roomHFLevel = (eqPrefs.getReverbRoomHFLevelDb() * 100f)
            .coerceIn(-9000f, 0f).toInt().toShort()
        r.decayTime = eqPrefs.getReverbDecayTimeMs()
            .coerceIn(100f, 20000f).toInt()
        r.decayHFRatio = (eqPrefs.getReverbDecayHfRatio() * 1000f)
            .coerceIn(100f, 2000f).toInt().toShort()
        r.reflectionsLevel = (eqPrefs.getReverbReflectionsLevelDb() * 100f)
            .coerceIn(-9000f, 1000f).toInt().toShort()
        r.reflectionsDelay = eqPrefs.getReverbReflectionsDelayMs()
            .coerceIn(0f, 300f).toInt()
        r.reverbLevel = (eqPrefs.getReverbReverbLevelDb() * 100f)
            .coerceIn(-9000f, 2000f).toInt().toShort()
        r.reverbDelay = eqPrefs.getReverbDelayMs()
            .coerceIn(0f, 100f).toInt()
        // Percent → permille (×10).
        r.diffusion = (eqPrefs.getReverbDiffusionPct() * 10f)
            .coerceIn(0f, 1000f).toInt().toShort()
        r.density = (eqPrefs.getReverbDensityPct() * 10f)
            .coerceIn(0f, 1000f).toInt().toShort()
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
     *  to the Pre-EQ stage (both channels). No MBC / limiter /
     *  post-EQ on per-session — those are global-only concerns and
     *  the global DP on session 0 handles them. */
    private fun createSessionDp(
        sessionId: Int,
        eq: ParametricEqualizer,
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
        val response = ParametricToDpConverter.convertFeatureAware(eq)
        val cutoffs = response.cutoffs
        val gains = response.gains
        val n = cutoffs.size
        val leftEqObj = DynamicsProcessing.Eq(true, true, n)
        val rightEqObj = DynamicsProcessing.Eq(true, true, n)
        for (i in 0 until n) {
            leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
            rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
        }
        dp.setPreEqByChannelIndex(0, leftEqObj)
        dp.setPreEqByChannelIndex(1, rightEqObj)
        dp.enabled = true
        return dp
    }

    /** Loads a custom preset's bands from `custom_presets` SP and
     *  returns a populated [ParametricEqualizer]. Mirrors the same
     *  JSON shape MainActivity / AudioOutputActivity use. */
    private fun loadPresetEq(name: String): ParametricEqualizer? {
        val prefs = context.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val str = runCatching { prefs.getString("preset_$name", null) }
            .getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(str)
            val bandsArr = obj.optJSONArray("bands") ?: return@runCatching null
            val eq = ParametricEqualizer()
            for (i in 0 until bandsArr.length()) {
                val b = bandsArr.getJSONObject(i)
                val ft = runCatching {
                    BiquadFilter.FilterType.valueOf(b.getString("filterType"))
                }.getOrDefault(BiquadFilter.FilterType.BELL)
                eq.addBand(
                    b.getDouble("frequency").toFloat(),
                    b.getDouble("gain").toFloat(),
                    ft,
                    b.getDouble("q"),
                )
            }
            eq.isEnabled = true
            eq
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
    }
}
