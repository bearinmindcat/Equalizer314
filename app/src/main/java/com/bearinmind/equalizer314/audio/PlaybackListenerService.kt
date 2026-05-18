package com.bearinmind.equalizer314.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Backbone of the "Now playing" detection path. We never read
 * notifications — Android only exposes [MediaSessionManager.getActiveSessions]
 * to apps with notification-listener access, so declaring this service
 * is the cheapest legal way to obtain that privilege.
 *
 * Once the user grants the listener (Settings → Notification access),
 * we register an [AudioManager.AudioPlaybackCallback] and a
 * [MediaSessionManager.OnActiveSessionsChangedListener]. Either fires
 * whenever any app starts or stops playing audio. We debounce (100 ms)
 * and then run [AudioPolicyDumpParser.dump] on a worker thread to
 * recover the session IDs that public APIs hide. The resulting
 * `Map<packageName, Set<sessionId>>` is shipped to [EqService] via
 * an in-process `startService` intent.
 *
 * Threading: all callbacks and the dump-parse step run on a dedicated
 * [HandlerThread]. The system-binder thread that delivers
 * `onListenerConnected` hops to that thread immediately.
 */
class PlaybackListenerService : NotificationListenerService() {

    private var detectorThread: HandlerThread? = null
    private var detectorHandler: Handler? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            scheduleSnapshot("playbackConfigChanged")
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { _: List<MediaController>? ->
            scheduleSnapshot("activeSessionsChanged")
        }

    private val snapshotRunnable = Runnable { runSnapshot() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected — starting playback detection")
        val thread = HandlerThread("PlaybackDetector").also { it.start() }
        detectorThread = thread
        val handler = Handler(thread.looper)
        detectorHandler = handler

        // Hop off the binder thread immediately. Registration touches
        // system services that prefer to be called from a Looper thread.
        handler.post {
            registerCallbacks(handler)
            // Initial scan once the listener is alive so the UI sees
            // whatever was already playing at the moment of bind.
            scheduleSnapshot("listenerConnected")
        }
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "onListenerDisconnected — stopping playback detection")
        // Wavelet's SessionListenerService.java:71-80 clears its session
        // map on disconnect, cascading effect release. We mirror that:
        // tell EqService to drop every per-session effect attached via
        // the DETECTED path. Broadcast effects survive — they have their
        // own CLOSE lifecycle. Fired BEFORE we quit the detector thread
        // so the dispatch can ride the still-live handler.
        dispatchReleaseDetected()
        val handler = detectorHandler
        val thread = detectorThread
        detectorHandler = null
        detectorThread = null
        if (handler != null) {
            handler.removeCallbacks(snapshotRunnable)
            handler.post {
                unregisterCallbacks()
                thread?.quitSafely()
            }
        } else {
            unregisterCallbacks()
            thread?.quitSafely()
        }
        super.onListenerDisconnected()
    }

    /** Public-API fallback when audioserver's `dumpAsync` is denied.
     *  Returns the set of packages currently owning an active media
     *  session — no session IDs (Android marks
     *  `MediaController.getSessionToken().getBinder()` as the session
     *  identity but `AudioSessionId` is gated by `@SystemApi`). The
     *  user can see "YouTube is playing" but cannot per-session EQ it
     *  on this device. */
    private fun collectActiveSessionPackages(): Set<String> {
        return try {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return emptySet()
            val component = ComponentName(this, PlaybackListenerService::class.java)
            val controllers = msm.getActiveSessions(component) ?: return emptySet()
            val own = packageName
            controllers.asSequence()
                .mapNotNull { it.packageName }
                .filter { it != own && it.isNotBlank() }
                .toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions fallback failed", t)
            emptySet()
        }
    }

    /** Stable negative session id per package — used to mark "detected
     *  but no recoverable session id" entries. Negative so it can't
     *  collide with a real audioserver-assigned session id (always
     *  positive). Stable so the observe-diff doesn't churn between
     *  snapshots. */
    private fun syntheticSessionId(pkg: String): Int {
        val h = pkg.hashCode()
        return if (h == Int.MIN_VALUE) -1 else -kotlin.math.abs(h)
    }

    private fun dispatchReleaseDetected() {
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_RELEASE_DETECTED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not dispatch ACTION_RELEASE_DETECTED", t)
        }
    }

    private fun registerCallbacks(handler: Handler) {
        val audio = getSystemService(AudioManager::class.java)
        audio?.registerAudioPlaybackCallback(playbackCallback, handler)
        val msm = getSystemService(MediaSessionManager::class.java)
        if (msm != null) {
            val component = ComponentName(this, PlaybackListenerService::class.java)
            // getActiveSessions / addOnActiveSessionsChangedListener
            // both throw SecurityException if the listener isn't bound
            // yet. We're called from onListenerConnected so binding
            // is guaranteed, but a slow/buggy OEM still might race —
            // catch and degrade.
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "addOnActiveSessionsChangedListener failed", t)
            }
        }
    }

    private fun unregisterCallbacks() {
        val audio = getSystemService(AudioManager::class.java)
        try { audio?.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
        val msm = getSystemService(MediaSessionManager::class.java)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
    }

    private fun scheduleSnapshot(reason: String) {
        val handler = detectorHandler ?: return
        // Coalesce bursts of callbacks (BT route flip, codec change,
        // ExoPlayer rebuild) into one snapshot.
        handler.removeCallbacks(snapshotRunnable)
        handler.postDelayed(snapshotRunnable, DEBOUNCE_MS)
    }

    private fun runSnapshot() {
        // Runs on the detector HandlerThread — safe to block on the
        // dumpAsync pipe read.
        val dumpResult = AudioPolicyDumpParser.dump(applicationContext)

        // Stock-Android devices grant audioserver's dumpAsync to any
        // binder caller; Samsung One UI + several other OEM ROMs
        // explicitly deny it. When that happens, dump returns empty
        // and we fall back to the public MediaSessionManager API —
        // it can't give us session IDs (those are @SystemApi from
        // API 29+), but it at least tells us which packages are
        // playing so the user sees "YouTube is playing" with a
        // "Detected (no session)" badge. The fallback assigns a
        // stable synthetic negative session id per package so the
        // observe-diff logic still computes correctly.
        val merged = mutableMapOf<String, MutableSet<Int>>()
        for ((pkg, sids) in dumpResult) {
            merged.getOrPut(pkg) { mutableSetOf() }.addAll(sids)
        }
        if (dumpResult.isEmpty()) {
            val fallback = collectActiveSessionPackages()
            for (pkg in fallback) {
                if (pkg in merged) continue
                merged.getOrPut(pkg) { mutableSetOf() }.add(syntheticSessionId(pkg))
            }
            if (fallback.isNotEmpty()) {
                Log.d(TAG, "dumpsys denied — fallback surfaced ${fallback.size} package(s) via MediaSessionManager")
            }
        }

        Log.d(TAG, "snapshot detected=${merged.size} packages")

        // Pack into a Bundle of int[]s (Map<String, Set<Int>> isn't
        // parcelable). The receiver decodes by iterating keySet().
        val bundle = Bundle()
        for ((pkg, sids) in merged) {
            bundle.putIntArray(pkg, sids.toIntArray())
        }
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_PLAYBACK_DETECTED)
            .putExtra(EqService.EXTRA_DETECTED_BUNDLE, bundle)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            // Service may be shutting down — next snapshot will retry.
            Log.w(TAG, "could not dispatch ACTION_PLAYBACK_DETECTED", t)
        }
    }

    // ----- notification surface (intentionally empty) ------------------

    // We declared android.service.notification.disabled_filter_types in
    // the manifest covering every category; in practice the system will
    // not deliver any notifications here. These overrides exist to make
    // the "we don't read notifications" contract explicit in code.

    @SuppressLint("MissingPermission")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    @SuppressLint("MissingPermission")
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    companion object {
        private const val TAG = "PlaybackListenerSvc"
        private const val DEBOUNCE_MS = 100L
    }
}
