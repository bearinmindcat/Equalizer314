package com.bearinmind.equalizer314.audio

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
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
        const val ACTION_EQ_STOPPED = "com.bearinmind.equalizer314.EQ_STOPPED"
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
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        fun start(context: Context) {
            val intent = Intent(context, EqService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EqService::class.java))
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

    inner class EqBinder : Binder() {
        val service: EqService get() = this@EqService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
        }

        // Per-output-device EQ auto-switching. Detection lives in this
        // service so it keeps working when MainActivity is closed.
        val eqPrefs = EqPreferencesManager(this)
        val coordinator = RouteSwitchCoordinator(this, eqPrefs, dynamicsManager)
        val monitor = AudioRoutingMonitor(this).apply {
            onRouteChange = { coordinator.onRouteChange(it) }
            // Auto-populate the Audio Output screen's "seen" list as
            // soon as devices appear — even before they're routed to.
            onDeviceSeen = { key, label -> eqPrefs.rememberSeenDevice(key, label) }
        }
        routingMonitor = monitor
        routeCoordinator = coordinator
        monitor.start()

        // Per-app session attachment (Wavelet-style OPEN/CLOSE
        // broadcasts handled by AudioSessionReceiver, forwarded here).
        sessionEffects = SessionEffectManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                dynamicsManager.stop()
                sessionEffects?.releaseAll()
                sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
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
                    sendBroadcast(Intent(ACTION_EQ_STOPPED).setPackage(packageName))
                }
                // Re-evaluate per-session reverbs — applyReverbParamsToAll
                // handles both "mode just became Session-based with the
                // reverb toggle on → attach" and "mode just left
                // Session-based → release" symmetrically.
                sessionEffects?.applyReverbParamsToAll()
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
        return dynamicsManager.isActive
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

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        routingMonitor?.stop()
        routingMonitor = null
        routeCoordinator = null
        sessionEffects?.releaseAll()
        sessionEffects = null
        dynamicsManager.stop()
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

        val stopIntent = Intent(this, EqService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioManager = getSystemService(AudioManager::class.java)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVol > 0) (currentVol * 100 / maxVol) else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_equalizer)
            .setContentTitle("Equalizer314 Online")
            .setContentText("Volume: $volumePercent%")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_nav_power, "Turn Off", stopPending)
            .build()
    }
}
