package com.bearinmind.equalizer314.audio

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Binder
import android.widget.Toast
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager

class EqService : Service() {

    companion object {
        private const val TAG = "EqService"
        private const val CHANNEL_ID = "eq_service_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bearinmind.equalizer314.STOP_EQ"
        /** Re-evaluate the notification (e.g. after the "Hide notification"
         *  setting changes while the EQ is off) — issue #58. */
        const val ACTION_REFRESH_NOTIFICATION = "com.bearinmind.equalizer314.REFRESH_NOTIFICATION"
        const val ACTION_RECYCLE_DP = "com.bearinmind.equalizer314.RECYCLE_DP"
        const val ACTION_DP_RECYCLED = "com.bearinmind.equalizer314.DP_RECYCLED"
        const val ACTION_TVMODE_REFRESH = "com.bearinmind.equalizer314.TVMODE_REFRESH"
        const val ACTION_REAPPLY_MBC = "com.bearinmind.equalizer314.REAPPLY_MBC"
        /** Tile counterpart to [ACTION_STOP]: loads persisted EQ state and
         *  starts DynamicsProcessing without MainActivity running. */
        const val ACTION_START_FROM_TILE = "com.bearinmind.equalizer314.START_FROM_TILE"
        /** Idempotent headless start (start-or-no-op, never toggles off).
         *  Fired by [BootCompletedReceiver] after reboot, and by
         *  MainActivity.onCreate when `powerOn` pref is true but the service
         *  isn't running (OEMs that strip BOOT_COMPLETED). */
        const val ACTION_AUTO_START = "com.bearinmind.equalizer314.AUTO_START"
        /** "Notification body may be stale — rebuild" signal. Sent by
         *  MainActivity after `presetName` / similar pref writes so the
         *  Preset line updates even when DP is off and nothing is bound. */
        const val ACTION_NOTIFICATION_REFRESH = "com.bearinmind.equalizer314.NOTIFICATION_REFRESH"
        /** Fired by AudioOutputActivity when a device binding is added /
         *  changed / removed. Service re-runs the route coordinator for the
         *  currently-routed device so the edit hits the live DP immediately
         *  (otherwise requires disconnect/reconnect or app restart). */
        const val ACTION_REAPPLY_DEVICE_BINDING = "com.bearinmind.equalizer314.REAPPLY_DEVICE_BINDING"
        /** Fired by ChannelInputActivity on per-app binding edits. Carries
         *  [EXTRA_APP_PACKAGE]; SessionEffectManager rebuilds that package's
         *  active per-session DPs so the new preset applies without
         *  restarting the audio app. */
        const val ACTION_REAPPLY_APP_BINDING = "com.bearinmind.equalizer314.REAPPLY_APP_BINDING"
        const val EXTRA_APP_PACKAGE = "app_package"
        const val ACTION_EQ_STOPPED = "com.bearinmind.equalizer314.EQ_STOPPED"
        const val EXTRA_SILENT_TOAST = "silentToast"
        /** Broadcast on any successful headless start (QS tile etc.) so a
         *  foregrounded MainActivity can re-sync its UI. */
        const val ACTION_EQ_STARTED = "com.bearinmind.equalizer314.EQ_STARTED"

        /** In-process flag the QS tile reads for authoritative on/off state.
         *  Prefs can drift (MainActivity resets the power-state pref on every
         *  cold launch by design); same-process volatile read is safe/cheap. */
        @Volatile
        var isDpRunning: Boolean = false
            private set

        internal fun setDpRunning(running: Boolean) { isDpRunning = running }

        /** Static mirrors of instance `lastDeviceLabel`/`lastDeviceKey` for
         *  binder-free route reads — MainActivity unbinds whenever DP is
         *  toggled off (EqStateManager.stopProcessing), and the on-graph
         *  status chip still needs the current output. */
        @Volatile
        var staticLastDeviceLabel: String? = null
            internal set
        @Volatile
        var staticLastDeviceKey: String? = null
            internal set
        /** Set on [ACTION_EQ_STOPPED] broadcasts from internal state changes
         *  (e.g. routing-mode switch) rather than user gestures: MainActivity
         *  still runs full cleanup but skips the toast. */
        const val EXTRA_SILENT_STOP = "silent_stop"
        // Forwarded from AudioSessionReceiver when an audio-effect
        // control session opens / closes for a per-app session.
        const val ACTION_ATTACH_SESSION = "com.bearinmind.equalizer314.ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.bearinmind.equalizer314.DETACH_SESSION"
        const val ACTION_APPLY_ROUTING_MODE = "com.bearinmind.equalizer314.APPLY_ROUTING_MODE"
        /** Fired by EnvironmentalReverbActivity / pipeline reverb toggle.
         *  Service re-reads reverb prefs and pushes them to every attached
         *  per-session reverb (creating/releasing as the toggle requires). */
        const val ACTION_APPLY_REVERB = "com.bearinmind.equalizer314.APPLY_REVERB"
        /** Fired by [PlaybackListenerService] after each debounced dump-parse
         *  cycle. [EXTRA_DETECTED_BUNDLE]: keys = package names, values =
         *  `int[]` session IDs; routed to
         *  [SessionEffectManager.observeDetectedPlayback]. */
        const val ACTION_PLAYBACK_DETECTED = "com.bearinmind.equalizer314.PLAYBACK_DETECTED"
        const val EXTRA_DETECTED_BUNDLE = "detected_bundle"
        /** Reserved key inside [EXTRA_DETECTED_BUNDLE]: String[] of packages
         *  in `PlaybackState.STATE_PLAYING`. '_' prefix can't collide with
         *  real package names. */
        const val EXTRA_PLAYING_PACKAGES_KEY = "_playing_packages_"
        /** Fired by [PlaybackListenerService.onListenerDisconnected] (user
         *  revoked Notification access / system unbound the listener).
         *  Releases every detection-path per-session effect; broadcast-source
         *  effects survive — they have their own CLOSE lifecycle. */
        const val ACTION_RELEASE_DETECTED = "com.bearinmind.equalizer314.RELEASE_DETECTED"
        /** Fired when the user flips ChannelInputActivity's "Skip system
         *  sounds" toggle; re-evaluates the bypass against current playback
         *  configs so it applies immediately, not on next callback. */
        const val ACTION_APPLY_BYPASS_PREF = "com.bearinmind.equalizer314.APPLY_BYPASS_PREF"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        /** AudioAttributes usages that should *not* be EQ'd (see
         *  BYPASS_USAGES below): short transient-heavy streams don't survive
         *  the 127-band FFT pre-EQ + limiter cleanly (cracking on Samsung
         *  starting in 0.0.7). USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN stay
         *  processed; this set triggers a bypass while active, restored the
         *  moment the stream stops. */
        /** Mirror of `MbcActivity.DEFAULT_CUTOFFS` — fallback crossovers when
         *  a fresh install starts DP before MbcActivity has written prefs.
         *  Must stay in lock-step with MbcActivity's defaults. */
        private val MBC_DEFAULT_CUTOFFS =
            floatArrayOf(200f, 700f, 2000f, 5000f, 7000f, 10000f)

        private val BYPASS_USAGES = setOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANT,
        )

        fun start(context: Context) {
            val intent = Intent(context, EqService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, silentToast: Boolean = false) {
            // ACTION_STOP (not stopService) keeps the service alive so the
            // notification flips to "Offline + Turn On" instead of vanishing.
            val intent = Intent(context, EqService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SILENT_TOAST, silentToast)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    val dynamicsManager = DynamicsProcessingManager()
    private val binder = EqBinder()

    /** Public so [com.bearinmind.equalizer314.AudioOutputActivity] can
     *  read the currently routed device for its "Active" pin. */
    var routingMonitor: AudioRoutingMonitor? = null
        private set
    private var routeCoordinator: RouteSwitchCoordinator? = null

    /** Per-app DP instances attached via OPEN_AUDIO_EFFECT_CONTROL_SESSION
     *  broadcasts. Public for Channel Input diagnostics. */
    var sessionEffects: SessionEffectManager? = null
        private set

    // Volume change listener
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotification()
            // MBC volume compensation: re-apply thresholds at the new volume.
            if (EqPreferencesManager(this@EqService).getMbcVolumeCompEnabled()) {
                mbcCompHandler.removeCallbacks(mbcCompRunnable)
                mbcCompHandler.postDelayed(mbcCompRunnable, 150L)
            }
        }
    }

    private val mbcCompHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mbcCompRunnable = Runnable { applyPersistedMbcConfig() }

    /** Media-volume attenuation vs max for the active route, in dB (≤0). */
    private fun currentVolumeAttenuationDb(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0f
        return try {
            val am = getSystemService(AudioManager::class.java)
            val dev = when {
                lastDeviceKey?.startsWith("BT:") == true -> android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                lastDeviceKey?.startsWith("USB") == true -> android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                lastDeviceKey?.startsWith("WIRED") == true -> android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                else -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            val cur = am.getStreamVolumeDb(
                AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC), dev)
            val max = am.getStreamVolumeDb(
                AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), dev)
            (cur - max).coerceIn(-60f, 0f)
        } catch (_: Exception) { 0f }
    }

    /** Label of the currently-routed output (BT name, "Phone speaker",
     *  "USB DAC", …). Updated by the routing monitor and the
     *  ACTION_ROUTE_PRESET_APPLIED receiver; read by buildNotification's
     *  "Device: X" line and MainActivity's status indicator. */
    @Volatile
    var lastDeviceLabel: String? = null
        private set

    /** Stable key for the routed device (BT MAC, "speaker",
     *  "usb_dac:VENDOR:PID"). Used to look up the active device binding for
     *  the "Mode: Device" notification line and main-screen indicator. */
    @Volatile
    var lastDeviceKey: String? = null
        private set

    /** Refreshes the notification on RouteSwitchCoordinator's bound-preset-
     *  applied broadcast, ACTION_NOTIFICATION_REFRESH (preset-name pref
     *  changed), and ACTION_REAPPLY_DEVICE_BINDING (binding edited). */
    private val routePresetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REAPPLY_DEVICE_BINDING -> {
                    // Re-run the coordinator for the routed device; edits to
                    // a non-routed device are a harmless no-op.
                    reapplyCurrentDeviceBinding()
                }
                ACTION_REAPPLY_APP_BINDING -> {
                    // Rebuild the edited package's per-session DPs; manager
                    // short-circuits when routing mode isn't Session-based.
                    val pkg = intent.getStringExtra(EXTRA_APP_PACKAGE)
                    if (pkg != null) {
                        sessionEffects?.reapplyBindingFor(pkg)
                    }
                }
                else -> {
                    intent?.getStringExtra(RouteSwitchCoordinator.EXTRA_DEVICE_LABEL)?.let {
                        lastDeviceLabel = it
                    }
                    updateNotification()
                }
            }
        }
    }

    /** Last seen "system sound active" state — tracked so we only call
     *  setEnabled() on actual transitions, not on every callback. */
    private var systemSoundBypassActive = false

    // ---- Session-0 control watchdog ----------------------------------
    // Session 0 is a shared/non-exclusive effect chain: when another app
    // opens its own session (Spotify → TikTok → Spotify), aggressive OEM
    // policies (Vivo, Pixel Adaptive Sound) silently drop our effect, and
    // AudioEffect OnControl/OnEnable listeners often don't fire on those
    // ROMs — EQ goes flat until a manual power toggle. Watchdog re-verifies
    // control and re-attaches: promptly on playback changes (event hook in
    // systemSoundCallback) plus a 5s backstop timer.
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogIntervalMs = 5_000L
    private val watchdogTick = object : Runnable {
        override fun run() {
            try { verifyAndReclaimGlobalDp() } finally {
                watchdogHandler.postDelayed(this, watchdogIntervalMs)
            }
        }
    }
    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.postDelayed(watchdogTick, watchdogIntervalMs)
    }
    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogTick)
    }

    /** Re-verify the session-0 effect holds control; re-attach if displaced.
     *  No-op unless the EQ should be live in System-wide mode (early-returns
     *  keep it cheap per tick). Reuses the reclaim cooldown to avoid
     *  tug-of-war with competing apps; re-applies MBC + system-sound bypass
     *  after recreate (same sequence as a route change). */
    private fun verifyAndReclaimGlobalDp() {
        val prefs = EqPreferencesManager(this)
        if (prefs.getAudioRoutingMode() == 1) return   // Session-based: no global DP
        if (!prefs.getPowerState()) return              // EQ powered off
        if (!dynamicsManager.isActive) return
        if (!dynamicsManager.hasLostControl()) return
        if (!dynamicsManager.reclaimCooldownElapsed()) return
        Log.w(TAG, "Watchdog: global DP lost control — reattaching")
        if (dynamicsManager.reattachActive()) {
            applyPersistedMbcConfig()
            syncSystemSoundBypassFromCurrent()
            updateNotification()
        }
    }

    /** Main-thread re-check requested from outside (e.g. MainActivity
     *  returning to the foreground after an app-switch dropout). */
    fun requestWatchdogCheck() {
        watchdogHandler.post { verifyAndReclaimGlobalDp() }
    }

    /** Bypasses the global DP while any [BYPASS_USAGES] stream plays
     *  (notifications, ringtones, alarms, calls, navigation…) — these don't
     *  survive the 127-band FFT pre-EQ + limiter (distortion/crackle). DP
     *  stays attached so re-enable is a single `enabled = true`, no rebuild. */
    private val systemSoundCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applySystemSoundBypass(configs ?: emptyList())
            // API 33+: report the actual routed device (output-switcher moves fire no device callbacks).
            feedRoutedDeviceFromPlayback()
            // Playback config changes are exactly when OEM ROMs drop the
            // session-0 effect — re-verify after the foreign session settles
            // (mirrors reclaimSession's small delay).
            watchdogHandler.postDelayed({ verifyAndReclaimGlobalDp() }, 300)
        }
    }

    /** Sets the global DP's enabled flag from whether any active stream's
     *  usage is in [BYPASS_USAGES]. Transition-driven — only writes
     *  `setEnabled` on an actual flip, no framework churn per callback.
     *  Gated by [EqPreferencesManager.getBypassSystemSounds] (default on);
     *  when disabled, re-enables DP if previously bypassed and
     *  short-circuits — every stream gets EQ'd. */
    private fun applySystemSoundBypass(configs: List<AudioPlaybackConfiguration>) {
        val bypassEnabled = EqPreferencesManager(this).getBypassSystemSounds()
        if (!bypassEnabled) {
            if (systemSoundBypassActive) {
                systemSoundBypassActive = false
                if (dynamicsManager.isActive) dynamicsManager.setEnabled(true)
                Log.d(TAG, "system-sound bypass disabled by user — DP re-enabled")
            }
            return
        }
        val anySystemSound = configs.any { c -> c.audioAttributes.usage in BYPASS_USAGES }
        if (anySystemSound == systemSoundBypassActive) return
        systemSoundBypassActive = anySystemSound
        if (dynamicsManager.isActive) {
            // Global DP only — per-session DPs never see notification audio.
            dynamicsManager.setEnabled(!anySystemSound)
            Log.d(TAG, "system sound ${if (anySystemSound) "started" else "stopped"} — DP ${if (anySystemSound) "bypassed" else "re-enabled"}")
        }
    }

    /** One-shot playback-config read at DP start so a notification already
     *  playing at power-on flips the bypass without waiting for a callback. */
    private fun syncSystemSoundBypassFromCurrent() {
        val am = getSystemService(AudioManager::class.java) ?: return
        applySystemSoundBypass(am.activePlaybackConfigurations.orEmpty())
    }

    inner class EqBinder : Binder() {
        val service: EqService get() = this@EqService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** API 33+: read the routed device off live media playback and feed the routing monitor. */
    private fun feedRoutedDeviceFromPlayback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val configs = getSystemService(AudioManager::class.java)?.activePlaybackConfigurations ?: return
        val routed = configs
            .filter { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA }
            .firstNotNullOfOrNull { it.audioDeviceInfo }
            ?: configs.firstNotNullOfOrNull { it.audioDeviceInfo }
        routingMonitor?.reportRoutedDevice(routed)
    }

    // MediaRouter2 selected-route watcher (API 30+): the system output switcher changes
    // the SELECTED route with no device add/remove, so AudioDeviceCallback (and on some
    // HALs the playback callback) never fires — Wavelet catches this with a
    // ControllerCallback (docs/WAVELET_DEVICE_SWITCHING.md).
    private var mediaRouter2: android.media.MediaRouter2? = null
    private var mr2RouteCallback: android.media.MediaRouter2.RouteCallback? = null
    private var mr2ControllerCallback: android.media.MediaRouter2.ControllerCallback? = null

    private fun startRouteSelectionWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val mr2 = android.media.MediaRouter2.getInstance(this)
            val pref = android.media.RouteDiscoveryPreference.Builder(
                listOf(android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO), true
            ).build()
            val rcb = object : android.media.MediaRouter2.RouteCallback() {}
            val ccb = object : android.media.MediaRouter2.ControllerCallback() {
                override fun onControllerUpdated(controller: android.media.MediaRouter2.RoutingController) {
                    // Poll now and again after the route settles — the playback
                    // configs can lag the selection by a beat.
                    feedRoutedDeviceFromPlayback()
                    watchdogHandler.postDelayed({ feedRoutedDeviceFromPlayback() }, 350)
                }
            }
            mr2.registerRouteCallback(mainExecutor, rcb, pref)
            mr2.registerControllerCallback(mainExecutor, ccb)
            mediaRouter2 = mr2; mr2RouteCallback = rcb; mr2ControllerCallback = ccb
        } catch (e: Exception) {
            Log.w(TAG, "MediaRouter2 route watcher unavailable: ${e.message}")
        }
    }

    private fun stopRouteSelectionWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            mr2RouteCallback?.let { mediaRouter2?.unregisterRouteCallback(it) }
            mr2ControllerCallback?.let { mediaRouter2?.unregisterControllerCallback(it) }
        } catch (_: Exception) {}
        mediaRouter2 = null; mr2RouteCallback = null; mr2ControllerCallback = null
    }

    /** Same "DynamicsProcessing Start/Stop" toast as a power-FAB tap, fired
     *  from the service so it shows when MainActivity is closed (QS tile /
     *  notification button). onStartCommand is main-thread — Toast is safe. */
    private fun showDpStateToast(started: Boolean) {
        val message = if (started) "DynamicsProcessing Start" else "DynamicsProcessing Stop"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Tell the converter the device's real mixer sample rate so its
        // bin-aware gain sampling matches DP's actual FFT bin layout (#26).
        try {
            getSystemService(AudioManager::class.java)
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toFloatOrNull()?.takeIf { it > 0f }
                ?.let { com.bearinmind.equalizer314.dsp.ParametricToDpConverter.deviceSampleRateHz = it }
        } catch (_: Exception) {}
        // Experimental frame size (#26): user-selected DP FFT window.
        // And the Pre+Post interleave toggle — both baked in at DP creation.
        EqPreferencesManager(this).let { p ->
            DynamicsProcessingManager.frameDurationMs = p.getDpFrameMs()
            DynamicsProcessingManager.interleaveEnabled = p.getDpInterleave()
            DynamicsProcessingManager.compatMode = p.getDpCompatMode()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                routePresetReceiver,
                IntentFilter().apply {
                    addAction(RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED)
                    addAction(ACTION_NOTIFICATION_REFRESH)
                    addAction(ACTION_REAPPLY_DEVICE_BINDING)
                    addAction(ACTION_REAPPLY_APP_BINDING)
                },
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                routePresetReceiver,
                IntentFilter().apply {
                    addAction(RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED)
                    addAction(ACTION_NOTIFICATION_REFRESH)
                    addAction(ACTION_REAPPLY_DEVICE_BINDING)
                    addAction(ACTION_REAPPLY_APP_BINDING)
                }
            )
        }

        // Register unconditionally — AudioPlaybackCallback needs no
        // permission / NLS bind, and short-circuits when DP isn't running.
        getSystemService(AudioManager::class.java)
            ?.registerAudioPlaybackCallback(systemSoundCallback, null)

        // Per-output-device EQ auto-switching lives in this service so it
        // works when MainActivity is closed.
        val eqPrefs = EqPreferencesManager(this)
        val coordinator = RouteSwitchCoordinator(this, eqPrefs, dynamicsManager)
        // Per-app session attachment (Wavelet-style OPEN/CLOSE broadcasts via
        // AudioSessionReceiver). Created BEFORE AudioRoutingMonitor so the
        // monitor's onRouteRebuild listener can reach it.
        sessionEffects = SessionEffectManager(this)

        val monitor = AudioRoutingMonitor(this).apply {
            onRouteChange = { change ->
                lastDeviceKey = change.key
                lastDeviceLabel = change.label
                staticLastDeviceKey = change.key
                staticLastDeviceLabel = change.label
                coordinator.onRouteChange(change)
                // Physical output change → device lifecycle with the Fix-1
                // recreate (Disable-EQ detach, recovery, or re-attach on the
                // new output).
                handleDeviceRouteLifecycle(change.key, recreateOnActive = true)
            }
            // Populate the Audio Output screen's "seen" list as soon as
            // devices appear — even before they're routed to.
            onDeviceSeen = { key, label -> eqPrefs.rememberSeenDevice(key, label) }
            // On any device add/remove (route flip, USB-DAC plug, BT codec
            // swap, sample-rate change), rebuild every per-session DP to
            // track the new format. Matches Poweramp's e80.java +
            // s90.java:377-400 path — Wavelet skips this and glitches on
            // USB swaps.
            onRouteRebuild = { sessionEffects?.onRoutingModeChanged() }
        }
        routingMonitor = monitor
        routeCoordinator = coordinator
        monitor.start()
        startRouteSelectionWatcher()

        // Session-0 control watchdog: self-gates (early-returns unless EQ is
        // live in System-wide mode); real recovery is driven by the
        // playback-change / session-open event hooks above.
        startWatchdog()
    }

    /** startForeground that survives OS refusal of the mediaPlayback FGS.
     *  Android 14+ (strict on Pixel / API 34+, also seen on Android 17)
     *  throws ForegroundServiceStartNotAllowedException when started from a
     *  BOOT_COMPLETED receiver. Catch, stopSelf cleanly (satisfies the
     *  startForegroundService → startForeground contract, avoiding the
     *  "did not start in time" crash), and rely on MainActivity's app-open
     *  fallback. Returns true only if we actually went foreground. */
    private fun safeStartForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            // Re-assert hide-when-off so service pings can't resurrect a hidden notification (issue #58).
            updateNotification()
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground blocked (${e.javaClass.simpleName}): ${e.message}")
            try { stopSelf() } catch (_: Throwable) {}
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH_NOTIFICATION -> {
                // Apply a changed "Hide notification" setting immediately.
                // Never forces foreground (startService, no FGS contract).
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_TVMODE_REFRESH -> {
                // TV Mode toggled. While a link role (TV/Remote) is active the
                // service must hold FOREGROUND even with DP off — else the
                // cached-app freezer suspends the process on app switch and
                // the LAN socket goes silent ("sync stops when I leave the
                // app"). Also retitles the notification (Remote / Remote
                // Controlled).
                if (com.bearinmind.equalizer314.remote.TvRemoteHub.getMode(this) !=
                    com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_OFF) {
                    safeStartForeground()
                } else {
                    updateNotification()
                }
                return START_NOT_STICKY
            }
            ACTION_REAPPLY_MBC -> {
                // Prefs are the source of truth (preset loads write prefs only, issue #78):
                // sync limiter + MBC live fields here, rebuild when the MBC structure changed.
                val p = EqPreferencesManager(this)
                val limChanged = dynamicsManager.limiterEnabled != p.getLimiterEnabled() ||
                    dynamicsManager.limiterAttackMs != p.getLimiterAttack() ||
                    dynamicsManager.limiterReleaseMs != p.getLimiterRelease() ||
                    dynamicsManager.limiterRatio != p.getLimiterRatio() ||
                    dynamicsManager.limiterThresholdDb != p.getLimiterThreshold() ||
                    dynamicsManager.limiterPostGainDb != p.getLimiterPostGain()
                if (limChanged) {
                    dynamicsManager.limiterEnabled = p.getLimiterEnabled()
                    dynamicsManager.limiterAttackMs = p.getLimiterAttack()
                    dynamicsManager.limiterReleaseMs = p.getLimiterRelease()
                    dynamicsManager.limiterRatio = p.getLimiterRatio()
                    dynamicsManager.limiterThresholdDb = p.getLimiterThreshold()
                    dynamicsManager.limiterPostGainDb = p.getLimiterPostGain()
                    if (dynamicsManager.isActive) dynamicsManager.pushLimiterUpdate()
                }
                val newMbcEnabled = p.getMbcEnabled()
                val newMbcCount = p.getMbcBandCount()
                val mbcStructureChanged = newMbcEnabled != dynamicsManager.mbcEnabled ||
                    newMbcCount != dynamicsManager.mbcBandCount
                dynamicsManager.mbcEnabled = newMbcEnabled
                dynamicsManager.mbcBandCount = newMbcCount
                if (mbcStructureChanged && dynamicsManager.isActive) {
                    if (dynamicsManager.reattachActive()) {
                        applyPersistedMbcConfig()
                        syncSystemSoundBypassFromCurrent()
                    }
                } else {
                    applyPersistedMbcConfig()
                }
                return START_NOT_STICKY
            }
            ACTION_RECYCLE_DP -> {
                // Rebuild live DP in place so creation-time settings (Pre+Post
                // interleave, DP Latency Window) apply without a power cycle.
                // Same post-reattach sequence as watchdog / route-change.
                // No-op when EQ is off or session-based.
                // Refresh MBC stage geometry from prefs — reattach reuses live fields (#78).
                EqPreferencesManager(this).let { p ->
                    dynamicsManager.mbcEnabled = p.getMbcEnabled()
                    dynamicsManager.mbcBandCount = p.getMbcBandCount()
                }
                if (dynamicsManager.reattachActive()) {
                    applyPersistedMbcConfig()
                    syncSystemSoundBypassFromCurrent()
                    updateNotification()
                    Log.d(TAG, "DP recycled on request (settings change)")
                    android.widget.Toast.makeText(
                        this, "DP Power Cycled", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Let MainActivity echo the off→on cycle on the power FAB.
                    sendBroadcast(Intent(ACTION_DP_RECYCLED).setPackage(packageName))
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                dynamicsManager.stop()
                sessionEffects?.releaseAll()
                // Persist power-off so tile / notification taps sync the pref
                // when MainActivity isn't around to run eqStoppedReceiver.
                EqPreferencesManager(this).savePowerState(false)
                setDpRunning(false)
                if (intent?.getBooleanExtra(EXTRA_SILENT_TOAST, false) != true)
                    showDpStateToast(started = false)
                sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
                // Service stays alive + foreground as "Offline + Turn On";
                // Turn On loops through ACTION_AUTO_START.
                updateNotification()
                scheduleNotificationSettle()
                return START_STICKY
            }
            ACTION_START_FROM_TILE -> {
                Log.d(TAG, "ACTION_START_FROM_TILE — toggle requested, dynamicsManager.isActive=${dynamicsManager.isActive}")
                if (!safeStartForeground()) return START_NOT_STICKY
                if (dynamicsManager.isActive) {
                    // Tile tapped while DP running — toggle off (same path as
                    // ACTION_STOP; service stays alive for Turn On).
                    dynamicsManager.stop()
                    sessionEffects?.releaseAll()
                    EqPreferencesManager(this).savePowerState(false)
                    setDpRunning(false)
                    showDpStateToast(started = false)
                    sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
                    updateNotification()
                    return START_STICKY
                }
                val eq = loadPersistedParametricEq()
                if (eq != null) {
                    // Configure DP from prefs first — mirrors
                    // EqStateManager.doStartEq so tile start == FAB tap.
                    val p = EqPreferencesManager(this)
                    with(dynamicsManager) {
                        preampGainDb = p.getPreampGain()
                        autoGainEnabled = p.getAutoGainEnabled()
                        channelBalancePercent = p.getChannelBalancePercent()
                        leftChannelGainDb = p.getLeftChannelGainDb()
                        rightChannelGainDb = p.getRightChannelGainDb()
                        limiterEnabled = p.getLimiterEnabled()
                        limiterAttackMs = p.getLimiterAttack()
                        limiterReleaseMs = p.getLimiterRelease()
                        limiterRatio = p.getLimiterRatio()
                        limiterThresholdDb = p.getLimiterThreshold()
                        limiterPostGainDb = p.getLimiterPostGain()
                        mbcEnabled = p.getMbcEnabled()
                        mbcBandCount = p.getMbcBandCount()
                    }
                    dynamicsManager.start(eq)
                    if (dynamicsManager.isActive) {
                        p.savePowerState(true)
                        setDpRunning(true)
                        syncSystemSoundBypassFromCurrent()
                        applyPersistedMbcConfig()
                        reapplyCurrentDeviceBinding()
                        showDpStateToast(started = true)
                        sendBroadcast(Intent(ACTION_EQ_STARTED).setPackage(packageName))
                        updateNotification()
                        // Re-attach reverb for the active routing mode (global → session 0).
                        sessionEffects?.applyReverbParamsToAll()
                    } else {
                        Log.w(TAG, "ACTION_START_FROM_TILE: dynamicsManager.start failed silently")
                    }
                } else {
                    Log.w(TAG, "ACTION_START_FROM_TILE: no persisted bands to start with")
                }
                return START_STICKY
            }
            ACTION_AUTO_START -> {
                Log.d(TAG, "ACTION_AUTO_START — boot/cold-open restore, dynamicsManager.isActive=${dynamicsManager.isActive}")
                if (!safeStartForeground()) return START_NOT_STICKY
                if (dynamicsManager.isActive) return START_STICKY
                val eq = loadPersistedParametricEq()
                if (eq != null) {
                    val p = EqPreferencesManager(this)
                    with(dynamicsManager) {
                        preampGainDb = p.getPreampGain()
                        autoGainEnabled = p.getAutoGainEnabled()
                        channelBalancePercent = p.getChannelBalancePercent()
                        leftChannelGainDb = p.getLeftChannelGainDb()
                        rightChannelGainDb = p.getRightChannelGainDb()
                        limiterEnabled = p.getLimiterEnabled()
                        limiterAttackMs = p.getLimiterAttack()
                        limiterReleaseMs = p.getLimiterRelease()
                        limiterRatio = p.getLimiterRatio()
                        limiterThresholdDb = p.getLimiterThreshold()
                        limiterPostGainDb = p.getLimiterPostGain()
                        mbcEnabled = p.getMbcEnabled()
                        mbcBandCount = p.getMbcBandCount()
                    }
                    dynamicsManager.start(eq)
                    if (dynamicsManager.isActive) {
                        p.savePowerState(true)
                        setDpRunning(true)
                        syncSystemSoundBypassFromCurrent()
                        applyPersistedMbcConfig()
                        reapplyCurrentDeviceBinding()
                        showDpStateToast(started = true)
                        sendBroadcast(Intent(ACTION_EQ_STARTED).setPackage(packageName))
                        updateNotification()
                        // Re-attach reverb for the active routing mode (global → session 0).
                        sessionEffects?.applyReverbParamsToAll()
                    } else {
                        Log.w(TAG, "ACTION_AUTO_START: dynamicsManager.start failed silently")
                    }
                } else {
                    Log.w(TAG, "ACTION_AUTO_START: no persisted bands to start with")
                }
                return START_STICKY
            }
            ACTION_ATTACH_SESSION -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, 0)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                sessionEffects?.attach(sessionId, pkg)
                // Another app opening an effect-control session means the
                // session-0 chain is contested — re-verify the global DP
                // (no-op in Session-based mode / when off).
                watchdogHandler.postDelayed({ verifyAndReclaimGlobalDp() }, 300)
                return START_STICKY
            }
            ACTION_DETACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, 0)
                sessionEffects?.detach(sessionId)
                watchdogHandler.postDelayed({ verifyAndReclaimGlobalDp() }, 300)
                // No stopSelf — other sessions / global DP may still be
                // active; lifecycle is managed by the EQ on/off flow.
                return START_STICKY
            }
            ACTION_APPLY_REVERB -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                sessionEffects?.applyReverbParamsToAll()
                return START_STICKY
            }
            ACTION_RELEASE_DETECTED -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                sessionEffects?.releaseDetected()
                return START_STICKY
            }
            ACTION_APPLY_BYPASS_PREF -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                syncSystemSoundBypassFromCurrent()
                return START_STICKY
            }
            ACTION_PLAYBACK_DETECTED -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                val bundle = intent.getBundleExtra(EXTRA_DETECTED_BUNDLE)
                val detected = mutableMapOf<String, Set<Int>>()
                var playingNow: Set<String> = emptySet()
                if (bundle != null) {
                    for (key in bundle.keySet()) {
                        if (key == EXTRA_PLAYING_PACKAGES_KEY) {
                            playingNow = bundle.getStringArray(key)?.toSet().orEmpty()
                            continue
                        }
                        val ints = bundle.getIntArray(key) ?: continue
                        detected[key] = ints.toSet()
                    }
                }
                sessionEffects?.observeDetectedPlayback(detected, playingNow)
                return START_STICKY
            }
            ACTION_APPLY_ROUTING_MODE -> {
                if (!safeStartForeground()) return START_NOT_STICKY
                // Session-based is Wavelet-style: per-session effects only,
                // never a parallel session-0 instance — stop the global DP so
                // bound apps aren't EQ'd twice.
                val prefs = EqPreferencesManager(this)
                if (prefs.getAudioRoutingMode() == 1) {
                    dynamicsManager.stop()
                    setDpRunning(false)
                    // Silent stop — routing-mode flip, not a power tap.
                    // MainActivity still drops its bind / animates the FAB.
                    sendBroadcast(
                        Intent(ACTION_EQ_STOPPED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SILENT_STOP, true),
                    )
                    updateNotification()
                }
                // applyReverbParamsToAll handles both entering Session-based
                // (attach, if reverb toggle on) and leaving it (release).
                sessionEffects?.applyReverbParamsToAll()
                // DPs are independent of reverbs — onRoutingModeChanged
                // releases per-session DPs on leave, re-attaches on enter.
                sessionEffects?.onRoutingModeChanged()
                // On System-wide we don't auto-start the global DP —
                // MainActivity owns the EQ instance (bands, preamp, MBC);
                // the Power button restarts it cleanly.
                return START_STICKY
            }
        }

        if (!safeStartForeground()) return START_NOT_STICKY
        // null intent = OS restarted the killed STICKY service — restore the DP if power was on.
        if (intent == null && !dynamicsManager.isActive) {
            val p = EqPreferencesManager(this)
            if (p.getPowerState() && p.getAudioRoutingMode() != 1) {
                Log.i(TAG, "STICKY restart with power on — restoring DP via AUTO_START")
                startService(Intent(this, EqService::class.java).setAction(ACTION_AUTO_START))
            }
        }
        return START_STICKY
    }

    fun startEq(eq: ParametricEqualizer): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        dynamicsManager.start(eq)
        val active = dynamicsManager.isActive
        setDpRunning(active)
        if (active) {
            syncSystemSoundBypassFromCurrent()
            // Apply the current device's bound preset now: the route
            // monitor's same-key short-circuit means onRouteChange doesn't
            // re-fire on a warm start, so without this the first audio after
            // Power-on plays whatever EQ was on the main screen.
            reapplyCurrentDeviceBinding()
            // Flip the notification to "Online" immediately, not on the next
            // volume tick.
            updateNotification()
            scheduleNotificationSettle()
            // Attach reverb per current routing mode (session 0 in global
            // mode). No-op unless the reverb toggle is on.
            sessionEffects?.applyReverbParamsToAll()
        }
        return active
    }

    /** Push the current device's bound preset into the live DP, if any.
     *  Called after every DP-start path (FAB / tile / boot) because
     *  AudioRoutingMonitor short-circuits same-key events — on a warm start
     *  the monitor never re-emits, so the coordinator wouldn't apply the
     *  binding. No-op when unbound (coordinator early-returns). */
    private fun reapplyCurrentDeviceBinding() {
        val key = lastDeviceKey ?: return
        val label = lastDeviceLabel ?: ""
        routeCoordinator?.onRouteChange(AudioRoutingMonitor.RouteChange(key, label))
        // Run disable/recovery lifecycle too, but WITHOUT the route-change
        // recreate — no physical output changed here.
        handleDeviceRouteLifecycle(key, recreateOnActive = false)
    }

    /** True while DP is detached because the active output is bound to
     *  "Disable EQ" ([EqPreferencesManager.DEVICE_PRESET_DISABLED]).
     *  Distinct from user power-off: `powerOn` stays true, so a non-disable
     *  device auto-resumes processing. */
    @Volatile
    private var disabledByDevice = false

    /** Owns device-dependent global-DP lifecycle so isDpRunning /
     *  notification / MBC / bypass stay consistent (no parallel ownership in
     *  the coordinator):
     *   - device bound "Disable EQ" → stop DP, keep `powerOn` for resume.
     *   - DP active + [recreateOnActive] → recreate on the new output
     *     (Fix 1: dodges the Adaptive-Sound route-transition silence).
     *   - DP off, `powerOn`, previously device-disabled → resume via
     *     AUTO_START.
     *  [recreateOnActive] true only for real physical route changes (from
     *  AudioRoutingMonitor); false for binding edits / start-path reapplies. */
    private fun handleDeviceRouteLifecycle(deviceKey: String, recreateOnActive: Boolean) {
        val prefs = EqPreferencesManager(this)
        // Session-based routing doesn't use the global DP at all.
        if (prefs.getAudioRoutingMode() == 1) return
        if (!prefs.getDeviceAutoSwitchEnabled()) return
        val binding = prefs.getDeviceBinding(deviceKey)
        val isDisable = binding?.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED
        when {
            isDisable -> {
                if (dynamicsManager.isActive) {
                    dynamicsManager.stop()
                    setDpRunning(false)
                    disabledByDevice = true
                    // Silent stop: sync MainActivity FAB without a toast.
                    sendBroadcast(
                        Intent(ACTION_EQ_STOPPED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SILENT_STOP, true),
                    )
                    updateNotification()
                    Log.d(TAG, "Device '$deviceKey' bound to Disable EQ — DP detached")
                }
            }
            dynamicsManager.isActive -> {
                disabledByDevice = false
                if (recreateOnActive) {
                    if (dynamicsManager.reattachActive()) {
                        applyPersistedMbcConfig()
                        syncSystemSoundBypassFromCurrent()
                        updateNotification()
                        Log.d(TAG, "Route change → recreated global DP on new output")
                    }
                }
            }
            prefs.getPowerState() && disabledByDevice -> {
                // Non-disable device routed in after a Disable detach —
                // resume via AUTO_START (which also re-applies this device's
                // binding through reapplyCurrentDeviceBinding).
                disabledByDevice = false
                val svc = Intent(this, EqService::class.java)
                    .setAction(ACTION_AUTO_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                Log.d(TAG, "Non-disable device '$deviceKey' routed in — resuming DP")
            }
        }
    }

    fun updateEq(eq: ParametricEqualizer) {
        dynamicsManager.updateFromEqualizer(eq)
    }

    fun updateEqPerChannel(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer) {
        dynamicsManager.updateFromEqualizers(leftEq, rightEq)
    }

    fun setEqEnabled(enabled: Boolean) {
        dynamicsManager.setEnabled(enabled)
    }

    fun updateMbc(bands: List<DynamicsProcessingManager.MbcBandParams>, crossovers: FloatArray) {
        dynamicsManager.applyMbcBands(bands, crossovers)
    }

    /** Load persisted MBC band params + crossovers into the live DP.
     *  Idempotent; no-op if DP off or MBC disabled. Mirrors
     *  MbcActivity.pushMbcToService but prefs-driven, so it runs on
     *  cold-start — fixes "MBC says on but isn't compressing until you
     *  touch a slider". */
    fun applyPersistedMbcConfig() {
        if (!dynamicsManager.isActive) return
        if (!dynamicsManager.mbcEnabled) return
        val p = EqPreferencesManager(this)
        // Volume compensation: thresholds track the media volume when enabled.
        dynamicsManager.mbcThresholdOffsetDb =
            if (p.getMbcVolumeCompEnabled()) currentVolumeAttenuationDb() else 0f
        val bandCount = dynamicsManager.mbcBandCount
        val bands = (0 until bandCount).map { i ->
            DynamicsProcessingManager.MbcBandParams(
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
        val crossovers = FloatArray(maxOf(0, bandCount - 1)) { i ->
            p.getMbcCrossover(i, MBC_DEFAULT_CUTOFFS.getOrElse(i) { 1000f })
        }
        dynamicsManager.applyMbcBands(bands, crossovers)
    }

    /** Build a [ParametricEqualizer] from the persisted `eq_settings.bands`
     *  JSON — lets the QS tile start EQ without MainActivity. Null when no
     *  usable band data (fresh install, corrupt state); caller logs and bails. */
    private fun loadPersistedParametricEq(): ParametricEqualizer? {
        val prefs = getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
        val str = runCatching { prefs.getString("bands", null) }.getOrNull() ?: return null
        return runCatching {
            val arr = org.json.JSONArray(str)
            val eq = ParametricEqualizer()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = runCatching {
                    com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(o.getString("filterType"))
                }.getOrDefault(com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL)
                eq.addBand(
                    o.getDouble("frequency").toFloat(),
                    o.getDouble("gain").toFloat(),
                    type,
                    o.getDouble("q"),
                )
                if (o.has("enabled")) eq.setBandEnabled(i, o.getBoolean("enabled"))
            }
            eq.isEnabled = true
            eq
        }.getOrNull()
    }

    /** Android rate-limits back-to-back NotificationManager.notify() calls
     *  and silently DROPS the excess — a state flip landing with a volume
     *  tick can lose its update. This settle pass re-posts once (1200ms)
     *  after any state change so a swallowed update self-heals. */
    private val notifSettleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notifSettleRunnable = Runnable { updateNotification() }
    private fun scheduleNotificationSettle() {
        notifSettleHandler.removeCallbacks(notifSettleRunnable)
        notifSettleHandler.postDelayed(notifSettleRunnable, 1200L)
    }

    /** Re-post the notification with current state. Public so outside
     *  callers (e.g. MainActivity on preset-row tap) can refresh the
     *  Preset / Device / Mode lines immediately. */
    fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        // Issue #58: optionally hide the notification while EQ is off (drop
        // foreground + cancel); returns when DP starts (startEq re-enters
        // foreground). TV Mode pins the service foreground regardless —
        // never drop the notification while a link role is active.
        val tvModeOn = com.bearinmind.equalizer314.remote.TvRemoteHub.getMode(this) !=
            com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_OFF
        if (!tvModeOn && !dynamicsManager.isActive && EqPreferencesManager(this).getHideNotificationWhenOff()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
            } catch (_: Exception) {}
            nm.cancel(NOTIFICATION_ID)
            return
        }
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        stopWatchdog()
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(routePresetReceiver) } catch (_: Exception) {}
        try {
            getSystemService(AudioManager::class.java)
                ?.unregisterAudioPlaybackCallback(systemSoundCallback)
        } catch (_: Exception) {}
        stopRouteSelectionWatcher()
        routingMonitor?.stop()
        routingMonitor = null
        routeCoordinator = null
        sessionEffects?.releaseAll()
        sessionEffects = null
        dynamicsManager.stop()
        // Keep the QS tile flag in sync across process death — else a
        // system-reclaim leaves the tile stuck reading "on".
        setDpRunning(false)
        Log.d(TAG, "EqService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when system-wide EQ is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification mirrors live DP state: Online + Turn Off (ACTION_STOP)
        // vs Offline + Turn On (ACTION_AUTO_START). Service stays alive
        // across the toggle so the notification persists.
        val isOn = dynamicsManager.isActive
        val toggleIntent = Intent(this, EqService::class.java).apply {
            action = if (isOn) ACTION_STOP else ACTION_AUTO_START
        }
        // Different requestCodes for on/off so Android doesn't collapse the
        // PendingIntents when FLAG_UPDATE_CURRENT rewrites extras.
        val togglePending = PendingIntent.getService(
            this, if (isOn) 1 else 2, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioManager = getSystemService(AudioManager::class.java)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVol > 0) (currentVol * 100 / maxVol) else 0

        val title = when (com.bearinmind.equalizer314.remote.TvRemoteHub.getMode(this)) {
            com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_SERVER -> "Equalizer314: Remote Controlled"
            com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_CLIENT -> "Equalizer314: Remote"
            else -> if (isOn) "Equalizer314: Online" else "Equalizer314: Offline"
        }
        val actionLabel = if (isOn) "Turn Off" else "Turn On"
        val volumeLine = "Volume: $volumePercent%"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_equalizer)
            .setContentTitle(title)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_nav_power, actionLabel, togglePending)

        // Expanded (BigText) body surfaces preset + device on both Online and
        // Offline (off = "what would apply if turned on"). Preset source:
        // eqPrefs.getPresetName() (RouteSwitchCoordinator updates it when a
        // device-bound preset auto-applies); in Session-based routing the
        // global preset isn't meaningful. Device: cached from
        // AudioRoutingMonitor.onRouteChange + ACTION_ROUTE_PRESET_APPLIED.
        val prefs = EqPreferencesManager(this)
        val routingMode = prefs.getAudioRoutingMode()
        val activePresetName = prefs.getPresetName()
        // Only a name backed by a saved `custom_presets` entry counts as a
        // real preset — import flows (AutoEQ / APO Import / Generate Custom
        // EQ), built-ins, and manual edits write non-bindable labels; those
        // display as "none" since the live EQ isn't a re-selectable preset.
        val customPresetsPrefs = getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val isRealPreset = activePresetName.isNotBlank() &&
            customPresetsPrefs.contains("preset_$activePresetName")
        val presetDisplay = if (isRealPreset) activePresetName else "none"
        // Three labelled lines:
        //  Mode: "Session" = per-app bindings; "Device" = System-wide with
        //        the current device's binding live; "System" = user's pick.
        //  Preset: name being applied ("none" in Session-based when nothing
        //          playing has a binding).
        //  Device: currently-routed output label.
        val appPreset = sessionEffects?.getCurrentDrivingPreset()
        val deviceBinding = lastDeviceKey?.let { prefs.getDeviceBinding(it) }
        val deviceDrivesPreset = routingMode != 1 &&
            deviceBinding != null &&
            deviceBinding.presetName == activePresetName
        val mode = when {
            routingMode == 1 -> "Session"
            deviceDrivesPreset -> "Device"
            else -> "System"
        }
        val presetForDisplay = when {
            routingMode == 1 -> appPreset ?: "none"
            else -> presetDisplay
        }
        val modeLine = "Mode: $mode"
        val presetLine = "Preset: $presetForDisplay"
        val deviceLine = lastDeviceLabel?.let { "Device: $it" } ?: "Device: —"
        // User-selected info lines, in the user's order (issue #65).
        val lines = mutableListOf<String>()
        for (key in prefs.getNotifLineOrder()) when (key) {
            "volume" -> if (prefs.getNotifShowVolume()) lines.add(volumeLine)
            "mode" -> if (prefs.getNotifShowMode()) lines.add(modeLine)
            "preset" -> if (prefs.getNotifShowPreset()) lines.add(presetLine)
            "device" -> if (prefs.getNotifShowDevice()) lines.add(deviceLine)
        }
        builder.setContentText(lines.firstOrNull() ?: "")
        if (lines.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        }

        return builder.build()
    }
}
