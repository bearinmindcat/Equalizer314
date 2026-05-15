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

    /** Snapshot of a currently-broadcasting session. Shown live in
     *  ChannelInputActivity's "Current session" panel. */
    data class ActiveSession(
        val sessionId: Int,
        val packageName: String,
        val presetName: String?,
    )

    private val sessions = mutableMapOf<Int, DynamicsProcessing>()
    private val reverbs = mutableMapOf<Int, EnvironmentalReverb>()
    private val sessionInfo = mutableMapOf<Int, ActiveSession>()
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
    fun attach(sessionId: Int, packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (sessionId <= 0) return  // session 0 is owned by the global DP

        // Routing mode gate. 1 = SESSION_BASED → attach. Anything else
        // (0 = SYSTEM_WIDE, legacy 2 = old "Both") → skip per-app.
        // Wavelet's design: only one DP per stream, never stacked.
        val mode = eqPrefs.getAudioRoutingMode()
        if (mode != 1) {
            Log.d(TAG, "Routing mode is SYSTEM_WIDE — skipping attach for $packageName")
            return
        }

        // Always remember the package — even if it has no binding,
        // the Channel Input screen will list it so the user can bind
        // a preset retroactively.
        eqPrefs.rememberSeenApp(packageName)

        val binding = eqPrefs.getAppBinding(packageName)
        // Always track the live session for the UI's "Current session"
        // panel, regardless of whether we actually attach a DP. This
        // lets the user see that an app IS broadcasting (so they can
        // bind a preset to it) even before a binding exists.
        sessionInfo[sessionId] = ActiveSession(sessionId, packageName, binding?.presetName)
        notifySessionsChanged()

        // Reverb is independent of the EQ binding — the user might
        // want reverb on a session even without a preset bound, or a
        // preset bound but reverb disabled. Attach if the pipeline's
        // ENVIRONMENTAL_REVERB toggle is on.
        if (eqPrefs.isAudioEffectEnabled(EFFECT_REVERB_NAME)) {
            attachReverbLocked(sessionId)
        }

        if (binding == null) {
            Log.d(TAG, "No binding for $packageName — tracking session only (session=$sessionId)")
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
            Log.d(TAG, "Attached DP session=$sessionId pkg=$packageName preset=${binding.presetName}")
        } catch (t: Throwable) {
            // Matches Wavelet's a6/n0.java:47 — catch and silently
            // null out on construction failure (another EQ app may
            // already own the session at higher priority, or the
            // session may have closed before we got here).
            Log.w(TAG, "Could not attach DP to session $sessionId", t)
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
        if (sessionInfo.remove(sessionId) != null) {
            notifySessionsChanged()
        }
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
