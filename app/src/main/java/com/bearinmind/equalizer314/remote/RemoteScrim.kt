package com.bearinmind.equalizer314.remote

import com.bearinmind.equalizer314.R
import android.app.Activity
import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * App-wide TV Mode touch lock (issues #35/#55): while a remote is connected
 * in Server mode, every resumed activity gets a dimmed touch blocker + a
 * "Remote Controlled" pill; UI stays visible/animating, local input eaten.
 * Also the screen tracker: each resume records [TvRemoteHub.topScreen] and
 * nudges a nav sync (peer screen-follow). Long-press = take control (lock
 * re-arms on the remote's next change).
 */
object RemoteScrim {

    private const val SCRIM_TAG = "eq314RemoteScrim"

    @Volatile
    var active = false
        private set

    private var lastResumed: WeakReference<Activity>? = null

    /** Called once from EqApp. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                lastResumed = WeakReference(activity)
                TvRemoteHub.topScreen = activity.javaClass.simpleName
                // Screen change = nav change: nudge a sync so the peer's
                // screen can follow (debounced in the hub).
                TvRemoteHub.onLocalEqChanged()
                if (active) attach(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                detach(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun setActive(on: Boolean) {
        active = on
        val activity = lastResumed?.get() ?: return
        if (on) attach(activity) else detach(activity)
    }

    private fun attach(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(SCRIM_TAG) != null) return
        val density = activity.resources.displayMetrics.density
        val topInset = try {
            androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top
        } catch (_: Exception) { null } ?: (24 * density).toInt()

        val scrim = FrameLayout(activity).apply {
            tag = SCRIM_TAG
            setBackgroundColor(0x66000000)
            isClickable = true
            isFocusable = true
            addView(TextView(activity).apply {
                text = activity.getString(R.string.remote_controlled)
                setTextColor(0xFFE2E2E2.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xE6222222.toInt())
                    cornerRadius = 16 * density
                    setStroke((1 * density).toInt(), 0xFF444444.toInt())
                }
                setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply {
                    topMargin = topInset + (10 * density).toInt()
                    leftMargin = (16 * density).toInt()
                    rightMargin = (16 * density).toInt()
                }
            })
            setOnLongClickListener {
                setActive(false)
                true
            }
        }
        decor.addView(
            scrim,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun detach(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(SCRIM_TAG)?.let { decor.removeView(it) }
    }
}
