package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the active audio output and emits a debounced
 * `RouteChange(deviceKey, deviceLabel)` event whenever it changes.
 * Owned by [EqService] for its lifetime.
 *
 * Detection uses [AudioManager.registerAudioDeviceCallback] (API 23+),
 * the canonical surface for output routing on modern Android. We also
 * register an [AudioManager.ACTION_AUDIO_BECOMING_NOISY] receiver so
 * abrupt pulls (yanked headphones, BT walked-out-of-range) trigger a
 * recompute even when the device-removed callback lags.
 *
 * Active-output picker: among all connected outputs filtered by
 * [DeviceIdentity], we pick the highest-priority one (BT > USB >
 * wired > speaker). This is the same heuristic Wavelet uses; it
 * matches Android's own routing default for `STREAM_MUSIC`.
 *
 * Debounce: BT A2DP routing flaps during connect/handover. We
 * coalesce events with a 400 ms postDelayed window before emitting.
 */
class AudioRoutingMonitor(
    private val context: Context,
) {

    data class RouteChange(val key: String, val label: String)

    /** Listener fires on the main thread after the debounce window. */
    var onRouteChange: ((RouteChange) -> Unit)? = null

    /** Fires on the main thread for every tracked output device the
     *  monitor learns about — immediately on connect, and once at
     *  start-up for everything already connected. Used to populate the
     *  Audio Output screen's "seen devices" list so a device shows up
     *  as soon as it's plugged in, not only once it's been routed to. */
    var onDeviceSeen: ((key: String, label: String) -> Unit)? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { recomputeAndEmit() }
    private var lastEmittedKey: String? = null
    private var registered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Remember every tracked output as soon as it appears, not
            // just when it becomes the active sink. This is what makes
            // a freshly-plugged-in device show up in the Audio Output
            // screen immediately.
            addedDevices?.forEach { reportSeen(it) }
            schedule()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            schedule()
        }
    }

    private fun reportSeen(info: AudioDeviceInfo) {
        if (!info.isSink) return
        val key = DeviceIdentity.keyOf(info) ?: return
        val label = DeviceIdentity.labelOf(info)
        onDeviceSeen?.invoke(key, label)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Audio is becoming noisy → routing is about to flip back
            // to the speaker. Schedule a recompute; the device-removed
            // callback usually fires immediately after, but this gives
            // us a head-start.
            schedule()
        }
    }

    fun start() {
        if (registered) return
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
        registered = true
        // Kick once immediately so a cold-start with a device already
        // routed emits a RouteChange.
        schedule()
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacks(debounceRunnable)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { context.unregisterReceiver(noisyReceiver) }
        registered = false
    }

    private fun schedule() {
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private fun recomputeAndEmit() {
        val active = pickActiveOutput() ?: return
        val key = DeviceIdentity.keyOf(active) ?: return
        val label = DeviceIdentity.labelOf(active)
        if (key == lastEmittedKey) return
        lastEmittedKey = key
        Log.d(TAG, "Active output → $key ($label)")
        onRouteChange?.invoke(RouteChange(key, label))
    }

    /** Among all connected output sinks, pick the highest-priority one
     *  that [DeviceIdentity] tracks. Returns null if none are tracked
     *  (e.g. only HDMI/cast is connected). */
    fun pickActiveOutput(): AudioDeviceInfo? {
        val all = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var best: AudioDeviceInfo? = null
        var bestPri = 0
        for (d in all) {
            if (!d.isSink) continue
            DeviceIdentity.keyOf(d) ?: continue   // skips HFP/SCO/HDMI/etc.
            val p = DeviceIdentity.priority(d)
            if (p > bestPri) {
                bestPri = p
                best = d
            }
        }
        return best
    }

    companion object {
        private const val TAG = "AudioRoutingMonitor"
        private const val DEBOUNCE_MS = 400L
    }
}
