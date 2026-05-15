package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

/**
 * Manifest receiver for the standard Android audio-effect control
 * session broadcasts. Music apps that opt in send these whenever
 * their audio session starts / stops:
 *
 *   - `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`
 *   - `AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`
 *
 * Both carry:
 *   - `AudioEffect.EXTRA_AUDIO_SESSION` (int) — the session ID
 *   - `AudioEffect.EXTRA_PACKAGE_NAME` (String) — which app sent it
 *
 * Wavelet uses this exact mechanism (see prior decompile notes:
 * `a6/n0.java` reads both extras, constructs a `DynamicsProcessing`
 * with `Integer.MAX_VALUE` priority on the session). We do the
 * same: forward both extras to [EqService] via a custom action, and
 * [EqService] hands them to [SessionEffectManager].
 */
class AudioSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(
            AudioEffect.EXTRA_AUDIO_SESSION,
            AudioEffect.ERROR_BAD_VALUE,
        )
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()

        if (sessionId == AudioEffect.ERROR_BAD_VALUE) {
            Log.w(TAG, "Missing EXTRA_AUDIO_SESSION on ${intent.action} from $packageName")
            return
        }

        Log.d(TAG, "${intent.action} session=$sessionId package=$packageName")

        val forwardAction = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> EqService.ACTION_ATTACH_SESSION
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> EqService.ACTION_DETACH_SESSION
            else -> return
        }

        val serviceIntent = Intent(context, EqService::class.java).apply {
            action = forwardAction
            putExtra(EqService.EXTRA_SESSION_ID, sessionId)
            putExtra(EqService.EXTRA_PACKAGE_NAME, packageName)
        }

        // Use startForegroundService when the service isn't already
        // running; the service promotes itself via startForeground on
        // first onStartCommand to satisfy the 5-second FGS deadline.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not forward session intent to EqService", t)
        }
    }

    companion object {
        private const val TAG = "AudioSessionReceiver"
    }
}
