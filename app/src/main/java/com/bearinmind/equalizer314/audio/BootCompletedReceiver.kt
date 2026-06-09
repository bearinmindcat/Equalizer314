package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.state.EqPreferencesManager

/** Restores the global DynamicsProcessing engine after a device reboot
 *  if the user had it on before powering off. BOOT_COMPLETED receivers
 *  are explicitly exempted from background-start restrictions on API
 *  31+, so the foreground-service start is legal here. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = EqPreferencesManager(context)
        if (!prefs.getPowerState()) return
        Log.d(TAG, "BOOT_COMPLETED — persisted powerOn=true, starting EqService")
        val svc = Intent(context, EqService::class.java)
            .setAction(EqService.ACTION_AUTO_START)
        // Newer Android (Pixel / API 34+, seen on Android 17) forbids
        // starting a mediaPlayback foreground service from BOOT_COMPLETED
        // and throws ForegroundServiceStartNotAllowedException. EqService
        // also guards its own startForeground, but guard here too so a
        // throw from startForegroundService itself can't crash the boot
        // broadcast. When blocked, DP simply comes up when the user next
        // opens the app (MainActivity's auto-start fallback).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Boot DP start blocked by OS: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
