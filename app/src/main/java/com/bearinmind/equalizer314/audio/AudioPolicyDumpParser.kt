package com.bearinmind.equalizer314.audio

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bearinmind.equalizer314.BuildConfig
import java.io.BufferedReader
import java.io.FileReader
import java.util.regex.Pattern

/** Session ids of playing streams, even for apps that never broadcast OPEN_AUDIO_EFFECT_CONTROL_SESSION: dump the "audio" service via reflection and parse its AudioPlaybackConfiguration table (Poweramp-style lines first, Wavelet-style fallback); any failure returns an empty map. */
object AudioPolicyDumpParser {

    private const val TAG = "AudioPolicyDumpParser"

    /** Wavelet's regex (see `SessionListenerService.f2179d`). */
    private val WAVELET_LINE: Pattern =
        Pattern.compile("Session\\sID:\\s(\\d+);?\\sUID:?\\s(\\d+)")

    /** Pulls `u/pid:<UID>/<PID>` from a Poweramp-format line. */
    private val POWERAMP_UID_PID: Pattern =
        Pattern.compile("u/pid:(\\d+)/(\\d+)")

    /** Pulls the session id: `session ID: <N>` on older builds, `sessionId:<N>` on current ones. */
    private val POWERAMP_SESSION: Pattern =
        Pattern.compile("(?i)session\\s?id:\\s*(\\d+)")

    /** Playing apps grouped by package (an app may own several sessions); [timeoutMs] caps the blocking pipe read. */
    fun dump(context: Context, timeoutMs: Long = 1500L): Map<String, Set<Int>> {
        return try {
            dumpInternal(context, timeoutMs)
        } catch (t: Throwable) {
            // DUMP denied, hidden-API blocklist, OOM or IO error — all land here.
            Log.w(TAG, "dump failed, falling back to public-API-only path", t)
            emptyMap()
        }
    }

    private fun dumpInternal(context: Context, timeoutMs: Long): Map<String, Set<Int>> {
        val binder = obtainAudioBinder() ?: return emptyMap()
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // Close our write-end once the binder has it, or the reader never sees EOF.
        try {
            invokeDumpAsync(binder, writeFd.fileDescriptor)
        } finally {
            try { writeFd.close() } catch (_: Throwable) {}
        }

        // Bound the blocking read. Caller already runs on a HandlerThread, so stay single-threaded.
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
                    // Sample unrecognised lines for format-drift triage (debug builds only).
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

    /** Poweramp prefix format; true when the line was consumed (pair added, or a prefix line skipped on purpose). */
    private fun tryParsePowerampLine(
        line: String,
        out: MutableMap<Int, MutableSet<Int>>,
    ): Boolean {
        // audioserver indents `AudioPlaybackConfiguration` two spaces; some OEM forks drop them.
        val isPrefix = line.startsWith("  AudioPlaybackConfiguration ") ||
            line.startsWith("AudioPlaybackConfiguration ") ||
            line.startsWith("  ID:") ||
            line.startsWith("ID:")
        if (!isPrefix) return false

        // SoundPool players (UI clicks, alarm tones, game SFX) aren't music-stream candidates.
        if (line.contains("type:android.media.SoundPool")) return true
        // Only music-ish usage tags get EQ. USAGE_MEDIA = music/video; USAGE_UNKNOWN = untagged third-party players.
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

    /** Wavelet's terser format. Only fires when the Poweramp parser found nothing on this line. */
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

    /** UID map → package names; our own UID is dropped and a shared UID resolves to its first (primary) package. */
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

    /** Cached binder — reused across calls (via `IBinder.isBinderAlive`) until audioserver dies. */
    @Volatile private var cachedBinder: IBinder? = null

    private fun obtainAudioBinder(): IBinder? {
        cachedBinder?.takeIf { it.isBinderAlive }?.let { return it }
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        // "audio" emits the AudioPlaybackConfiguration rows on the widest range of builds.
        val obj = getService.invoke(null, "audio")
        val binder = obj as? IBinder ?: return null
        cachedBinder = binder
        return binder
    }

    /** IBinder.dumpAsync(FileDescriptor, String[]) via reflection, so a stricter hidden-API list can't break the rest. */
    private fun invokeDumpAsync(binder: IBinder, writeFd: java.io.FileDescriptor) {
        val dumpAsync = binder.javaClass.getMethod(
            "dumpAsync",
            java.io.FileDescriptor::class.java,
            Array<String>::class.java,
        )
        dumpAsync.invoke(binder, writeFd, emptyArray<String>())
    }
}
