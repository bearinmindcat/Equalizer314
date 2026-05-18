package com.bearinmind.equalizer314.audio

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bearinmind.equalizer314.BuildConfig
import java.io.BufferedReader
import java.io.FileReader
import java.util.regex.Pattern

/**
 * Recovers the **session ID** of every currently-playing media stream on the
 * device, including apps that never broadcast `OPEN_AUDIO_EFFECT_CONTROL_SESSION`
 * (YouTube, Netflix, Chrome, etc.). Public Android APIs expose
 * [android.media.AudioPlaybackConfiguration] but **not** the audio-session ID
 * — without a session ID we can't attach a per-session [android.media.audiofx.DynamicsProcessing]
 * effect. The trick used by Wavelet and Poweramp EQ is to reflect
 * `android.os.ServiceManager.getService("audio")`, pipe the resulting binder's
 * `dumpAsync(fd, args)` output into a `BufferedReader`, and parse the line
 * format `audioserver` uses for its `AudioPlaybackConfiguration` table.
 *
 * Format differs slightly per Android version / OEM:
 * - **Poweramp-observed**: lines start with `  AudioPlaybackConfiguration `
 *   and include `u/pid:<UID>/<PID>`, `usage=USAGE_MEDIA`, `session:<N>`.
 * - **Wavelet-observed**: simpler `Session ID: <N>; UID: <UID>` lines.
 *
 * We try the Poweramp parser first (richer info), fall back to Wavelet's
 * regex if the prefix never appears. On any failure (`DUMP` denied,
 * `audioserver` rejects the dump, format unrecognised) we return an empty
 * map and the caller falls back to the public-API-only path (package name
 * via `AudioPlaybackConfiguration.getClientUid()` with no session ID).
 *
 * Reflection is confined to this object so the rest of the codebase remains
 * hidden-API-free.
 */
object AudioPolicyDumpParser {

    private const val TAG = "AudioPolicyDumpParser"

    /** Wavelet's regex (see `SessionListenerService.f2179d`). */
    private val WAVELET_LINE: Pattern =
        Pattern.compile("Session\\sID:\\s(\\d+);?\\sUID:?\\s(\\d+)")

    /** Pulls `u/pid:<UID>/<PID>` from a Poweramp-format line. */
    private val POWERAMP_UID_PID: Pattern =
        Pattern.compile("u/pid:(\\d+)/(\\d+)")

    /** Pulls `session ID: <N>` (capital ID, spaced) — the form audioserver
     *  uses in `AudioPlaybackConfiguration.toString()`. */
    private val POWERAMP_SESSION: Pattern =
        Pattern.compile("session ID:\\s*(\\d+)")

    /** Run a dump of the audio service and return the playing apps grouped
     *  by package name. Each app may have one or more concurrent sessions
     *  (e.g. ExoPlayer pre-buffering the next track).
     *
     *  @param timeoutMs hard ceiling on the blocking pipe read. The dump
     *         normally completes in a few ms — if `audioserver` stalls we
     *         abandon rather than hold the caller's thread forever. */
    fun dump(context: Context, timeoutMs: Long = 1500L): Map<String, Set<Int>> {
        return try {
            dumpInternal(context, timeoutMs)
        } catch (t: Throwable) {
            // Failure modes: SecurityException (DUMP denied), reflection
            // SDK-blocklist hit on Android 14+, OOM on a huge dump, IO
            // errors. All produce the same caller-facing result.
            Log.w(TAG, "dump failed, falling back to public-API-only path", t)
            emptyMap()
        }
    }

    private fun dumpInternal(context: Context, timeoutMs: Long): Map<String, Set<Int>> {
        val binder = obtainAudioBinder() ?: return emptyMap()
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // dumpAsync hands the write-end to audioserver, which closes it when
        // it's done writing. We must close OUR copy of the write-end as
        // soon as the binder has it, otherwise the reader never sees EOF.
        try {
            invokeDumpAsync(binder, writeFd.fileDescriptor)
        } finally {
            try { writeFd.close() } catch (_: Throwable) {}
        }

        // Bound the blocking read. We could spawn a reader thread, but the
        // caller already runs on a HandlerThread — staying single-threaded
        // keeps the lifecycle trivial.
        val deadline = System.currentTimeMillis() + timeoutMs
        val unmatched = mutableListOf<String>()
        val uidToSessions = mutableMapOf<Int, MutableSet<Int>>()

        try {
            BufferedReader(FileReader(readFd.fileDescriptor)).use { reader ->
                while (true) {
                    if (System.currentTimeMillis() > deadline) {
                        Log.w(TAG, "dump read timed out after ${timeoutMs}ms")
                        break
                    }
                    val line = reader.readLine() ?: break
                    if (tryParsePowerampLine(line, uidToSessions)) continue
                    if (tryParseWaveletLine(line, uidToSessions)) continue
                    // Anything we don't recognise gets sampled for future
                    // format-drift triage (debug builds only — release
                    // logs stay clean).
                    if (BuildConfig.DEBUG && unmatched.size < 20 && line.isNotBlank()) {
                        unmatched.add(line)
                    }
                }
            }
        } finally {
            try { readFd.close() } catch (_: Throwable) {}
        }

        if (BuildConfig.DEBUG && uidToSessions.isEmpty() && unmatched.isNotEmpty()) {
            Log.d(TAG, "no rows matched; first ${unmatched.size} unmatched lines for triage:")
            unmatched.forEach { Log.d(TAG, "  | $it") }
        }

        return resolveUidsToPackages(context, uidToSessions)
    }

    /** Tries the Poweramp prefix format. Returns true when this line
     *  contributed a UID + session pair (or was a valid prefix line that
     *  we deliberately skipped, e.g. `SoundPool`). */
    private fun tryParsePowerampLine(
        line: String,
        out: MutableMap<Int, MutableSet<Int>>,
    ): Boolean {
        // audioserver formats `AudioPlaybackConfiguration` with two-space
        // indentation. Some OEM forks drop the leading spaces.
        val isPrefix = line.startsWith("  AudioPlaybackConfiguration ") ||
            line.startsWith("AudioPlaybackConfiguration ") ||
            line.startsWith("  ID:") ||
            line.startsWith("ID:")
        if (!isPrefix) return false

        // SoundPool players (UI clicks, alarm tones, game SFX) are
        // explicitly not music-stream candidates — Poweramp filters them
        // at this point and so do we.
        if (line.contains("type:android.media.SoundPool")) return true
        // Only music-ish usage tags get EQ. USAGE_MEDIA = music/video;
        // USAGE_UNKNOWN = many third-party players that didn't tag.
        if (!line.contains("USAGE_MEDIA") && !line.contains("USAGE_UNKNOWN")) return true

        val uidMatch = POWERAMP_UID_PID.matcher(line)
        if (!uidMatch.find()) return true   // prefix matched but no UID — skip
        val uid = uidMatch.group(1)?.toIntOrNull() ?: return true

        val sessionMatch = POWERAMP_SESSION.matcher(line)
        if (!sessionMatch.find()) return true
        val sid = sessionMatch.group(1)?.toIntOrNull() ?: return true
        if (sid <= 0) return true            // session 0 is the global mix

        out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    /** Wavelet's terser format. Only fires when the Poweramp parser
     *  found nothing on this line. */
    private fun tryParseWaveletLine(
        line: String,
        out: MutableMap<Int, MutableSet<Int>>,
    ): Boolean {
        val m = WAVELET_LINE.matcher(line)
        if (!m.find()) return false
        val sid = m.group(1)?.toIntOrNull() ?: return false
        val uid = m.group(2)?.toIntOrNull() ?: return false
        if (sid <= 0) return false
        out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    /** Resolves a raw UID map to package names. Our own UID is dropped so
     *  the global session-0 DP doesn't accidentally show up.
     *
     *  Shared-UID handling: a single UID may map to several packages
     *  (e.g. `com.google.android.gms` shares its UID with other Google
     *  apps). Poweramp's `i0.java:925-928` picks element [0] of the
     *  package array and so do we — exploding to N rows for one
     *  session would put N misleading entries in the "Now playing"
     *  list. Wavelet sidesteps the question entirely by using
     *  `MediaController.getPackageName()` for canonical names; we
     *  don't have that signal here. Index [0] is the documented
     *  "primary" package for the UID. */
    private fun resolveUidsToPackages(
        context: Context,
        uidToSessions: Map<Int, Set<Int>>,
    ): Map<String, Set<Int>> {
        val pm = context.packageManager
        val ourUid = context.applicationInfo.uid
        val out = mutableMapOf<String, MutableSet<Int>>()
        for ((uid, sids) in uidToSessions) {
            if (uid == ourUid) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            out.getOrPut(pkg) { mutableSetOf() }.addAll(sids)
        }
        return out
    }

    /** Cached binder reference — `IBinder.isBinderAlive` lets us reuse it
     *  across calls until audioserver dies and a fresh one is needed. */
    @Volatile private var cachedBinder: IBinder? = null

    private fun obtainAudioBinder(): IBinder? {
        cachedBinder?.takeIf { it.isBinderAlive }?.let { return it }
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        // "audio" is the AudioFlinger-side service that emits
        // AudioPlaybackConfiguration rows. "media.audio_policy" works on
        // some Android versions too; "audio" has the widest coverage.
        val obj = getService.invoke(null, "audio")
        val binder = obj as? IBinder ?: return null
        cachedBinder = binder
        return binder
    }

    /** IBinder.dumpAsync exists on the public API since API 24 but the
     *  signature is `(FileDescriptor, String[])`. We use reflection so a
     *  stricter hidden-API list on a future Android version can't break
     *  the rest of the parser. */
    private fun invokeDumpAsync(binder: IBinder, writeFd: java.io.FileDescriptor) {
        val dumpAsync = binder.javaClass.getMethod(
            "dumpAsync",
            java.io.FileDescriptor::class.java,
            Array<String>::class.java,
        )
        dumpAsync.invoke(binder, writeFd, emptyArray<String>())
    }
}
