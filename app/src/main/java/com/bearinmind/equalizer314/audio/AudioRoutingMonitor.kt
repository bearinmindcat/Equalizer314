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

/** Watches the active audio output and emits a debounced RouteChange; BT > USB > wired > speaker priority guess unless a routed device was observed. */
class AudioRoutingMonitor(
    private val context: Context,
) {

    data class RouteChange(val key: String, val label: String)

    /** Listener fires on the main thread after the debounce window. */
    var onRouteChange: ((RouteChange) -> Unit)? = null

    /** Fires for every tracked output on connect (and at start-up) — feeds the "seen devices" list. */
    var onDeviceSeen: ((key: String, label: String) -> Unit)? = null

    /** Fires on every device add/remove (no same-key short-circuit) — internal route/sample-rate changes. */
    var onRouteRebuild: (() -> Unit)? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { recomputeAndEmit() }
    private var lastEmittedKey: String? = null
    private var registered = false

    // Actual routed device from AudioPlaybackConfiguration (API 33+); overrides the priority guess.
    private var observedKey: String? = null
    private var observedLabel: String? = null

    /** Feed the routed device observed on a live playback config (API 33+). */
    fun reportRoutedDevice(info: AudioDeviceInfo?) {
        if (info == null) return
        val key = DeviceIdentity.keyOf(info) ?: return
        if (key == observedKey) return
        observedKey = key
        observedLabel = DeviceIdentity.labelOf(info)
        schedule()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Remember every tracked output on appearance, not just when routed to.
            addedDevices?.forEach { reportSeen(it) }
            schedule()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // Drop a stale observation if its device just disappeared.
            removedDevices?.forEach {
                if (DeviceIdentity.keyOf(it) == observedKey) {
                    observedKey = null
                    observedLabel = null
                }
            }
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
            // Becoming noisy — routing is about to flip to speaker; recompute early.
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
        // Kick once so a cold-start emits the already-routed device.
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
        // Rebuild listeners fire even when the active-sink key is unchanged.
        onRouteRebuild?.invoke()

        // Observed routed device beats the priority guess.
        val key: String
        val label: String
        val obs = observedKey
        if (obs != null) {
            key = obs
            label = observedLabel ?: ""
        } else {
            val active = pickActiveOutput() ?: return
            key = DeviceIdentity.keyOf(active) ?: return
            label = DeviceIdentity.labelOf(active)
        }
        if (key == lastEmittedKey) return
        lastEmittedKey = key
        Log.d(TAG, "Active output → $key ($label)")
        onRouteChange?.invoke(RouteChange(key, label))
    }

    /** Highest-priority connected sink [DeviceIdentity] tracks; null when none. */
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
