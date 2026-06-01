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
        /** Tile-side counterpart to [ACTION_STOP]. Loads the persisted
         *  EQ state and starts DynamicsProcessing without needing
         *  MainActivity to be running. */
        const val ACTION_START_FROM_TILE = "com.bearinmind.equalizer314.START_FROM_TILE"
        /** Idempotent headless start. Fired by [BootCompletedReceiver]
         *  after a device reboot, and by MainActivity.onCreate as a
         *  fallback when the persisted `powerOn` pref is true but the
         *  service isn't running (OEMs that strip BOOT_COMPLETED).
         *  Unlike [ACTION_START_FROM_TILE] this never toggles off —
         *  it's start-or-no-op. */
        const val ACTION_AUTO_START = "com.bearinmind.equalizer314.AUTO_START"
        /** Generic "the notification body might be stale — please
         *  rebuild it" signal. Sent by MainActivity after writes to
         *  `presetName` / similar prefs that the notification reads,
         *  so the Preset line flips immediately even when DP is off
         *  and MainActivity isn't currently bound to the service. */
        const val ACTION_NOTIFICATION_REFRESH = "com.bearinmind.equalizer314.NOTIFICATION_REFRESH"
        /** Fired by AudioOutputActivity whenever a device binding is
         *  added, changed, or removed. The service re-runs the route
         *  coordinator for the currently-routed device so a binding
         *  edit takes effect immediately on the live DP — without it
         *  the user has to disconnect/reconnect the device or restart
         *  the app to see the new preset apply. */
        const val ACTION_REAPPLY_DEVICE_BINDING = "com.bearinmind.equalizer314.REAPPLY_DEVICE_BINDING"
        /** Fired by ChannelInputActivity whenever a per-app binding is
         *  added, changed, or removed. Carries [EXTRA_APP_PACKAGE] —
         *  the audio app's package whose binding was edited. The
         *  service tells SessionEffectManager to rebuild every active
         *  per-session DP belonging to that package so the new preset
         *  takes effect without the user having to stop / restart the
         *  audio app. */
        const val ACTION_REAPPLY_APP_BINDING = "com.bearinmind.equalizer314.REAPPLY_APP_BINDING"
        const val EXTRA_APP_PACKAGE = "app_package"
        const val ACTION_EQ_STOPPED = "com.bearinmind.equalizer314.EQ_STOPPED"
        /** Broadcast on a successful start from the QS tile (or any
         *  other headless start path), so MainActivity can re-sync its
         *  UI if it's currently in the foreground. */
        const val ACTION_EQ_STARTED = "com.bearinmind.equalizer314.EQ_STARTED"

        /** In-process flag the QS tile reads to render its on/off
         *  state authoritatively. Set whenever the global DP changes
         *  state — preferences can drift (MainActivity resets the
         *  power-state pref on every cold launch by design), so the
         *  tile needs a live signal it can trust. Same-process volatile
         *  read is safe and cheap. */
        @Volatile
        var isDpRunning: Boolean = false
            private set

        internal fun setDpRunning(running: Boolean) { isDpRunning = running }

        /** Static mirrors of the instance `lastDeviceLabel` /
         *  `lastDeviceKey` so callers can read the current route even
         *  when they're not bound to the service. MainActivity unbinds
         *  whenever DP is toggled off (see EqStateManager.stopProcessing),
         *  so the on-graph status chip needs a binder-free way to know
         *  what device audio is going to. */
        @Volatile
        var staticLastDeviceLabel: String? = null
            internal set
        @Volatile
        var staticLastDeviceKey: String? = null
            internal set
        /** Set on [ACTION_EQ_STOPPED] broadcasts whose source is an
         *  internal state change (e.g. routing-mode switch) rather
         *  than a user gesture. MainActivity's receiver still runs
         *  its full state cleanup but skips the user-facing toast. */
        const val EXTRA_SILENT_STOP = "silent_stop"
        // Forwarded from AudioSessionReceiver when an audio-effect
        // control session opens / closes for a per-app session.
        const val ACTION_ATTACH_SESSION = "com.bearinmind.equalizer314.ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.bearinmind.equalizer314.DETACH_SESSION"
        const val ACTION_APPLY_ROUTING_MODE = "com.bearinmind.equalizer314.APPLY_ROUTING_MODE"
        /** Fired by EnvironmentalReverbActivity and the pipeline's reverb
         *  card toggle. Service re-reads reverb prefs and pushes them to
         *  every currently attached per-session reverb (creating /
         *  releasing reverbs as the toggle state requires). */
        const val ACTION_APPLY_REVERB = "com.bearinmind.equalizer314.APPLY_REVERB"
        /** Fired by [PlaybackListenerService] after each debounced
         *  dump-parse cycle. Carries [EXTRA_DETECTED_BUNDLE] — a Bundle
         *  whose keys are package names and values are `int[]` session
         *  IDs. The service decodes it and routes to
         *  [SessionEffectManager.observeDetectedPlayback]. */
        const val ACTION_PLAYBACK_DETECTED = "com.bearinmind.equalizer314.PLAYBACK_DETECTED"
        const val EXTRA_DETECTED_BUNDLE = "detected_bundle"
        /** Reserved key inside [EXTRA_DETECTED_BUNDLE]. Value is a
         *  String[] of packages currently in `PlaybackState.STATE_PLAYING`.
         *  Reserved-name prefix ('_') avoids any collision with real
         *  Android package names. */
        const val EXTRA_PLAYING_PACKAGES_KEY = "_playing_packages_"
        /** Fired by [PlaybackListenerService.onListenerDisconnected] when
         *  the user revokes Notification access (or the system unbinds
         *  the listener for any other reason). The service tells the
         *  manager to release every per-session effect attached via the
         *  detection path; broadcast-source effects survive because they
         *  have their own CLOSE lifecycle. */
        const val ACTION_RELEASE_DETECTED = "com.bearinmind.equalizer314.RELEASE_DETECTED"
        /** Fired by ChannelInputActivity when the user flips the
         *  "Skip system sounds" toggle. Service re-evaluates the
         *  bypass against the current playback configurations so the
         *  change takes effect immediately, not on next callback. */
        const val ACTION_APPLY_BYPASS_PREF = "com.bearinmind.equalizer314.APPLY_BYPASS_PREF"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        /** AudioAttributes usages that should *not* be EQ'd. Notification
         *  / ringtone / alarm / call / navigation / assistant streams
         *  are short and transient-heavy — they don't survive the
         *  127-band FFT pre-EQ + limiter cleanly (cracking on Samsung
         *  starting in 0.0.7). USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
         *  stay processed; everything in this set triggers a bypass
         *  while the stream is active, restoring it the moment the
         *  stream stops. */
        /** Mirror of `MbcActivity.DEFAULT_CUTOFFS`. Used as the
         *  fallback band-crossover frequencies when a fresh install
         *  starts DP before MbcActivity has ever written per-band
         *  crossovers to prefs. If `MbcActivity` ever changes its
         *  defaults, this must be updated in lock-step. */
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

        fun stop(context: Context) {
            // Route through ACTION_STOP rather than stopService so the
            // service stays alive and the persistent notification flips
            // to "Offline + Turn On". stopService would tear down the
            // foreground service entirely and the notification would
            // disappear — fine for shutdown, wrong for a Power-FAB tap.
            val intent = Intent(context, EqService::class.java)
                .setAction(ACTION_STOP)
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

    /** Owns the per-app DynamicsProcessing instances attached via
     *  OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcasts. Public so the
     *  Channel Input screen could read it for diagnostics later. */
    var sessionEffects: SessionEffectManager? = null
        private set

    // Volume change listener
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotification()
        }
    }

    /** Cached label of the currently-routed output device (BT name,
     *  "Phone speaker", "USB DAC", etc.). Updated by the routing
     *  monitor and the ACTION_ROUTE_PRESET_APPLIED receiver. Read by
     *  buildNotification to show "Device: X" in the expanded body and
     *  by MainActivity's status indicator above the graph. */
    @Volatile
    var lastDeviceLabel: String? = null
        private set

    /** Stable key for the currently-routed device (e.g. BT MAC,
     *  "speaker", "usb_dac:VENDOR:PID"). Used to look up the active
     *  device binding so the notification can show "Mode: Device"
     *  when device-routing is what's driving the live preset, and the
     *  main-screen status indicator does the same. */
    @Volatile
    var lastDeviceKey: String? = null
        private set

    /** Listens for RouteSwitchCoordinator's "I just applied a bound
     *  preset" broadcast and refreshes the notification so the
     *  Preset + Device lines reflect the new state without waiting
     *  for the next volume tick. Also handles ACTION_NOTIFICATION_REFRESH
     *  (preset-name pref changed) and ACTION_REAPPLY_DEVICE_BINDING
     *  (user just edited a binding in AudioOutputActivity). */
    private val routePresetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REAPPLY_DEVICE_BINDING -> {
                    // Re-run the coordinator for the currently-routed
                    // device. If its binding was just edited, the new
                    // preset takes effect on live DP immediately. If
                    // the edit was for a different (non-routed)
                    // device, this is a harmless no-op.
                    reapplyCurrentDeviceBinding()
                }
                ACTION_REAPPLY_APP_BINDING -> {
                    // Rebuild every per-session DP for the package
                    // whose binding was just edited. SessionEffectManager
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

    /** Tracks active playback usages and bypasses the global DP while
     *  any "dangerous" usage is playing — notifications, ringtones,
     *  alarms, voice calls, navigation prompts, etc. These streams are
     *  short transient-heavy signals that don't survive the 127-band
     *  FFT pre-EQ + aggressive limiter cleanly (notification audio
     *  came out distorted / crackling). The DP stays attached so the
     *  re-enable is a single `enabled = true` write — no rebuild. */
    private val systemSoundCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applySystemSoundBypass(configs ?: emptyList())
        }
    }

    /** Sets the global DP's enabled flag based on whether any active
     *  stream's usage is in [BYPASS_USAGES]. Pure transition-driven —
     *  only writes `setEnabled` when the bypass state actually flips
     *  so we don't churn the audio framework on every callback.
     *
     *  Gated by the user's [EqPreferencesManager.getBypassSystemSounds]
     *  toggle (default on). When the user has disabled the bypass, we
     *  make sure the DP is re-enabled (in case it was previously
     *  bypassed) and short-circuit — every stream gets EQ'd. */
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
            // Only flip the global DP. Per-session DPs (Session-based
            // routing) live on a specific app's audio session and never
            // see notification audio anyway.
            dynamicsManager.setEnabled(!anySystemSound)
            Log.d(TAG, "system sound ${if (anySystemSound) "started" else "stopped"} — DP ${if (anySystemSound) "bypassed" else "re-enabled"}")
        }
    }

    /** One-shot read of the current playback config list. Called the
     *  instant the DP starts so a notification already playing at
     *  power-on flips the bypass on without waiting for the next
     *  config-change callback. */
    private fun syncSystemSoundBypassFromCurrent() {
        val am = getSystemService(AudioManager::class.java) ?: return
        applySystemSoundBypass(am.activePlaybackConfigurations.orEmpty())
    }

    inner class EqBinder : Binder() {
        val service: EqService get() = this@EqService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Surfaces the same "DynamicsProcessing Start/Stop" toast as a
     *  power-FAB tap, but fires from the service so it appears even
     *  when MainActivity is closed (QS-tile and notification-button
     *  paths). Called from onStartCommand which runs on the main
     *  thread, so Toast usage here is safe. */
    private fun showDpStateToast(started: Boolean) {
        val message = if (started) "DynamicsProcessing Start" else "DynamicsProcessing Stop"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

        // Register the system-sound bypass callback unconditionally —
        // AudioPlaybackCallback needs no permission, no NLS bind. The
        // callback short-circuits when DP isn't running, so it's cheap.
        getSystemService(AudioManager::class.java)
            ?.registerAudioPlaybackCallback(systemSoundCallback, null)

        // Per-output-device EQ auto-switching. Detection lives in this
        // service so it keeps working when MainActivity is closed.
        val eqPrefs = EqPreferencesManager(this)
        val coordinator = RouteSwitchCoordinator(this, eqPrefs, dynamicsManager)
        // Per-app session attachment (Wavelet-style OPEN/CLOSE
        // broadcasts handled by AudioSessionReceiver, forwarded here).
        // Created BEFORE the AudioRoutingMonitor so the monitor's
        // onRouteRebuild listener can reach into it.
        sessionEffects = SessionEffectManager(this)

        val monitor = AudioRoutingMonitor(this).apply {
            onRouteChange = { change ->
                lastDeviceKey = change.key
                lastDeviceLabel = change.label
                staticLastDeviceKey = change.key
                staticLastDeviceLabel = change.label
                coordinator.onRouteChange(change)
                // Real physical output change → run the device lifecycle
                // with the Fix-1 recreate enabled (Disable EQ detach,
                // recovery, or clean re-attach on the new output).
                handleDeviceRouteLifecycle(change.key, recreateOnActive = true)
            }
            // Auto-populate the Audio Output screen's "seen" list as
            // soon as devices appear — even before they're routed to.
            onDeviceSeen = { key, label -> eqPrefs.rememberSeenDevice(key, label) }
            // On any device add/remove (route flip, USB-DAC plugged,
            // BT codec swap, internal sample-rate change), rebuild
            // every per-session DP so the effect tracks the new format.
            // Matches Poweramp's e80.java + s90.java:377-400 path —
            // Wavelet skips this and is known to glitch on USB swaps.
            onRouteRebuild = { sessionEffects?.onRoutingModeChanged() }
        }
        routingMonitor = monitor
        routeCoordinator = coordinator
        monitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                dynamicsManager.stop()
                sessionEffects?.releaseAll()
                // Persist power-off here so tile / notification taps
                // sync the pref even when MainActivity isn't around to
                // run its eqStoppedReceiver.
                EqPreferencesManager(this).savePowerState(false)
                setDpRunning(false)
                showDpStateToast(started = false)
                sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
                // Keep the service alive so the notification persists
                // as "Offline + Turn On" — users can flip DP back on
                // straight from the notification without opening the
                // app. Refresh the notification to reflect the new
                // state. The service stays foreground so it's not
                // memory-reclaimed; tapping Turn On loops through
                // ACTION_AUTO_START to bring DP back up.
                updateNotification()
                return START_STICKY
            }
            ACTION_START_FROM_TILE -> {
                Log.d(TAG, "ACTION_START_FROM_TILE — toggle requested, dynamicsManager.isActive=${dynamicsManager.isActive}")
                startForeground(NOTIFICATION_ID, buildNotification())
                if (dynamicsManager.isActive) {
                    // Tile was tapped while the DP is already running —
                    // toggle off. Same path ACTION_STOP runs: keep the
                    // service alive so the notification persists with
                    // the Turn On affordance.
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
                    // Configure DP properties from prefs first — mirrors
                    // EqStateManager.doStartEq's setup so a tile-driven
                    // start gives the user the same audio as a FAB tap.
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
                startForeground(NOTIFICATION_ID, buildNotification())
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
                    } else {
                        Log.w(TAG, "ACTION_AUTO_START: dynamicsManager.start failed silently")
                    }
                } else {
                    Log.w(TAG, "ACTION_AUTO_START: no persisted bands to start with")
                }
                return START_STICKY
            }
            ACTION_ATTACH_SESSION -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, 0)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                sessionEffects?.attach(sessionId, pkg)
                return START_STICKY
            }
            ACTION_DETACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, 0)
                sessionEffects?.detach(sessionId)
                // Don't stopSelf — other sessions / the global DP may
                // still be active. Service lifecycle is otherwise
                // managed by the EQ on/off flow.
                return START_STICKY
            }
            ACTION_APPLY_REVERB -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                sessionEffects?.applyReverbParamsToAll()
                return START_STICKY
            }
            ACTION_RELEASE_DETECTED -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                sessionEffects?.releaseDetected()
                return START_STICKY
            }
            ACTION_APPLY_BYPASS_PREF -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                syncSystemSoundBypassFromCurrent()
                return START_STICKY
            }
            ACTION_PLAYBACK_DETECTED -> {
                startForeground(NOTIFICATION_ID, buildNotification())
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
                startForeground(NOTIFICATION_ID, buildNotification())
                // When the user picks Session-based, stop the global
                // DP so bound apps don't get their EQ applied twice
                // (once on session 0, once on their per-app session).
                // Session-based is "Wavelet-style": only per-session
                // effects, never a parallel session-0 instance.
                val prefs = EqPreferencesManager(this)
                if (prefs.getAudioRoutingMode() == 1) {
                    dynamicsManager.stop()
                    setDpRunning(false)
                    // Mark this stop as silent — the user didn't tap
                    // the power button, they flipped routing mode. We
                    // still want MainActivity to drop its bind /
                    // animate the FAB off, but the toast is noise here.
                    sendBroadcast(
                        Intent(ACTION_EQ_STOPPED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SILENT_STOP, true),
                    )
                    updateNotification()
                }
                // Re-evaluate per-session reverbs — applyReverbParamsToAll
                // handles both "mode just became Session-based with the
                // reverb toggle on → attach" and "mode just left
                // Session-based → release" symmetrically.
                sessionEffects?.applyReverbParamsToAll()
                // DPs are independent of reverbs — onRoutingModeChanged
                // releases per-session DPs when leaving Session-based and
                // re-attaches them when entering.
                sessionEffects?.onRoutingModeChanged()
                // When the user picks System-wide, we don't auto-start
                // the global DP here — MainActivity owns the EQ
                // instance (band data, preamp, MBC config). The user
                // tapping the Power button restarts it cleanly.
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    fun startEq(eq: ParametricEqualizer): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        dynamicsManager.start(eq)
        val active = dynamicsManager.isActive
        setDpRunning(active)
        if (active) {
            syncSystemSoundBypassFromCurrent()
            // Override the bands we just started with the bound preset
            // for the current output device, if any. Without this the
            // first audio frame after Power-on plays through whatever
            // EQ was on the main screen — the route monitor's
            // short-circuit on same-key prevents `onRouteChange` from
            // firing again on a warm start (service was already alive),
            // so the binding never reaches DP via the normal path.
            reapplyCurrentDeviceBinding()
            // Flip the persistent notification from "Offline" to "Online"
            // immediately rather than waiting for the next volume-change
            // tick to repost it.
            updateNotification()
        }
        return active
    }

    /** Push the current output device's bound preset into the live DP,
     *  if a binding exists. Used after every DP-start path (FAB / tile
     *  / boot) to compensate for AudioRoutingMonitor short-circuiting
     *  on same-key events — when the service was already alive and DP
     *  was off, the monitor doesn't re-emit on Power-on, so the
     *  coordinator never gets a chance to apply the binding. No-op when
     *  the current device has no binding (coordinator handles that
     *  internally with an early-return on `getDeviceBinding == null`). */
    private fun reapplyCurrentDeviceBinding() {
        val key = lastDeviceKey ?: return
        val label = lastDeviceLabel ?: ""
        routeCoordinator?.onRouteChange(AudioRoutingMonitor.RouteChange(key, label))
        // Apply disable/recovery lifecycle for the binding too, but
        // WITHOUT the unconditional route-change recreate (no physical
        // output change happened on a binding edit / headless start).
        handleDeviceRouteLifecycle(key, recreateOnActive = false)
    }

    /** True while DynamicsProcessing has been detached specifically
     *  because the active output device is bound to "Disable EQ"
     *  ([EqPreferencesManager.DEVICE_PRESET_DISABLED]). Distinct from a
     *  user power-off: `powerOn` stays true, so switching to a non-
     *  disable device auto-resumes processing. */
    @Volatile
    private var disabledByDevice = false

    /** Owns the global-DP lifecycle decisions that depend on the active
     *  output device, so `isDpRunning` / notification / MBC / bypass
     *  all stay consistent (no parallel ownership in the coordinator):
     *
     *   - device bound to "Disable EQ"  → stop DP (detach), keep
     *     `powerOn` so it resumes elsewhere.
     *   - DP active + [recreateOnActive] → recreate on the new output
     *     (Fix 1: dodges the Adaptive-Sound route-transition silence).
     *   - DP off but `powerOn` and we were device-disabled → resume via
     *     the normal AUTO_START sequence.
     *
     *  [recreateOnActive] is true only for real physical route changes
     *  (from the AudioRoutingMonitor); false for binding edits / start-
     *  path reapplies where no output actually changed. */
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
                // Recover: a non-disable device routed in after we'd
                // detached for a Disable device. Bring DP back via the
                // standard AUTO_START sequence (which also re-applies
                // this device's bound preset through reapplyCurrentDeviceBinding).
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

    /** Load the persisted MBC band params and crossovers and push them
     *  to the live DP. Idempotent — safe to call after every start
     *  path. No-op if DP isn't running or MBC isn't enabled. Mirrors
     *  the work `MbcActivity.pushMbcToService` does on a slider
     *  change, but driven from prefs so it runs on cold-start without
     *  the user ever opening MbcActivity. Fixes the "MBC says on but
     *  isn't compressing until you touch a slider" symptom. */
    fun applyPersistedMbcConfig() {
        if (!dynamicsManager.isActive) return
        if (!dynamicsManager.mbcEnabled) return
        val p = EqPreferencesManager(this)
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

    /** Builds a [ParametricEqualizer] from the live `eq_settings.bands`
     *  JSON the rest of the app persists into. Used by the QS tile to
     *  kick the EQ on without MainActivity needing to be alive. Returns
     *  null when there's no usable band data (fresh install, corrupt
     *  state, etc.) — caller logs and bails. */
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

    /** Re-post the notification with current state. Public so callers
     *  outside this file (e.g. MainActivity after the user taps a
     *  preset row in the custom presets list) can force an immediate
     *  refresh of the Preset / Device / Mode lines without waiting
     *  for the next volume tick or route change. */
    fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(routePresetReceiver) } catch (_: Exception) {}
        try {
            getSystemService(AudioManager::class.java)
                ?.unregisterAudioPlaybackCallback(systemSoundCallback)
        } catch (_: Exception) {}
        routingMonitor?.stop()
        routingMonitor = null
        routeCoordinator = null
        sessionEffects?.releaseAll()
        sessionEffects = null
        dynamicsManager.stop()
        // Make sure the QS tile flag follows process death — otherwise
        // a system-reclaim could leave the tile stuck reading "on"
        // until the next startEq writes it again.
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

        // Notification mirrors the live DP state. When DP is running the
        // title reads "Equalizer314: Online" and the action button stops
        // DP via ACTION_STOP. When DP is off the title reads
        // "Equalizer314: Offline" and the action button restarts DP via
        // ACTION_AUTO_START. Service stays alive across the toggle so
        // the notification persists either way.
        val isOn = dynamicsManager.isActive
        val toggleIntent = Intent(this, EqService::class.java).apply {
            action = if (isOn) ACTION_STOP else ACTION_AUTO_START
        }
        // Different requestCodes for the on/off PendingIntents so Android
        // doesn't collapse them across a state flip when FLAG_UPDATE_CURRENT
        // rewrites the extras.
        val togglePending = PendingIntent.getService(
            this, if (isOn) 1 else 2, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioManager = getSystemService(AudioManager::class.java)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVol > 0) (currentVol * 100 / maxVol) else 0

        val title = if (isOn) "Equalizer314: Online" else "Equalizer314: Offline"
        val actionLabel = if (isOn) "Turn Off" else "Turn On"
        val volumeLine = "Volume: $volumePercent%"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_equalizer)
            .setContentTitle(title)
            .setContentText(volumeLine)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_nav_power, actionLabel, togglePending)

        // When DP is on, surface the active preset + output device in
        // the expanded notification body so the user can see what's
        // driving their audio at a glance. Sources:
        //   - Preset: eqPrefs.getPresetName() is the single source of
        //     truth — RouteSwitchCoordinator now updates it when a
        //     device-bound preset auto-applies. In Session-based
        //     routing the global preset isn't meaningful (per-app
        //     sessions own their own EQs) so we show the mode label
        //     instead.
        //   - Device: cached from AudioRoutingMonitor's onRouteChange
        //     and from the ACTION_ROUTE_PRESET_APPLIED broadcast.
        // BigText body shows in the EXPANDED notification (tap the
        // chevron). We surface it on both Online and Offline states —
        // when DP is off it acts as a "here's what would apply if you
        // turn me on" status line.
        val prefs = EqPreferencesManager(this)
        val routingMode = prefs.getAudioRoutingMode()
        val activePresetName = prefs.getPresetName()
        // Only treat the current name as a real preset if it points at
        // an actual saved entry in `custom_presets` SP. Import flows
        // (AutoEQ / APO Import / Generate Custom EQ), built-ins, and
        // manual edits all write a non-bindable label here — surface
        // those as "App Set" since the live EQ is just the in-app
        // state, not a re-selectable preset.
        val customPresetsPrefs = getSharedPreferences("custom_presets", Context.MODE_PRIVATE)
        val isRealPreset = activePresetName.isNotBlank() &&
            customPresetsPrefs.contains("preset_$activePresetName")
        val presetDisplay = if (isRealPreset) activePresetName else "none"
        // Always show three labelled lines so the user can see at a
        // glance whether a rule is driving the current sound vs they
        // picked the preset themselves:
        //  Mode    = how the current EQ was selected
        //              "Session" — Session-based routing (per-app bindings)
        //              "Device"  — System-wide + current device's
        //                          binding is what's live
        //              "System"  — System-wide + the user's own pick
        //  Preset  = the actual preset name being applied (or "(none
        //            active)" in Session-based mode when nothing's
        //            playing with a binding)
        //  Device  = currently-routed output label
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
        val bigText = "$volumeLine\n$modeLine\n$presetLine\n$deviceLine"
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))

        return builder.build()
    }
}
