package com.bearinmind.equalizer314

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import java.util.WeakHashMap

/** Applies the saved light/dark theme before any activity inflates (prefs read raw to keep startup light). */
class EqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val light = getSharedPreferences("eq_settings", MODE_PRIVATE)
            .getBoolean("lightTheme", false)
        AppCompatDelegate.setDefaultNightMode(
            if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        registerActivityLifecycleCallbacks(amoledHook)
        // TV Mode: app-wide screen tracking (peer nav-follow) + the remote-controlled touch lock on every activity.
        com.bearinmind.equalizer314.remote.RemoteScrim.install(this)
    }

    // Black (AMOLED) theme: overlay every activity at creation; activities built under an older stamp recreate on resume.
    private val createdUnderStamp = WeakHashMap<Activity, Int>()
    private val amoledHook = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (amoledActive(activity)) activity.theme.applyStyle(R.style.ThemeOverlay_Equalizer314_Amoled, true)
            createdUnderStamp[activity] = themeStamp
        }
        override fun onActivityResumed(activity: Activity) {
            if (createdUnderStamp[activity] != themeStamp) activity.recreate()
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    companion object {
        /** Bumped by the Black theme toggle so live activities rebuild with the new palette. */
        @Volatile var themeStamp = 0

        fun amoledActive(context: Context): Boolean {
            val p = context.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
            return p.getBoolean("amoledTheme", false) && !p.getBoolean("lightTheme", false)
        }
    }
}
