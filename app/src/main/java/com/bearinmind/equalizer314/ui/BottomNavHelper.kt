package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.content.Intent
import android.widget.ImageButton
import android.widget.TextView
import com.bearinmind.equalizer314.*
import com.bearinmind.equalizer314.state.EqPreferencesManager

enum class NavScreen { EQ, MBC, LIMITER, SETTINGS }

object BottomNavHelper {

    private const val ACTIVE_COLOR = 0xFFDDDDDD.toInt()
    private const val DIM_COLOR = 0xFF666666.toInt()
    private const val ON_COLOR = 0xFFDDDDDD.toInt()
    private const val OFF_COLOR = 0xFF888888.toInt()

    fun setup(activity: Activity, currentScreen: NavScreen, eqPrefs: EqPreferencesManager) {
        val navEq = activity.findViewById<ImageButton>(R.id.navPresetsButton)
        val navMbc = activity.findViewById<ImageButton>(R.id.navMbcButton)
        val navLimiter = activity.findViewById<ImageButton>(R.id.navLimiterButton)
        val navSettings = activity.findViewById<ImageButton>(R.id.navSettingsButton)

        navEq.setOnClickListener {
            if (currentScreen != NavScreen.EQ) {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("showSettings", false)
                })
                activity.overridePendingTransition(0, 0)
            }
        }
        navMbc.setOnClickListener {
            if (currentScreen != NavScreen.MBC) {
                activity.startActivity(Intent(activity, MbcActivity::class.java))
                activity.overridePendingTransition(0, 0)
            }
        }
        navLimiter.setOnClickListener {
            if (currentScreen != NavScreen.LIMITER) {
                activity.startActivity(Intent(activity, LimiterActivity::class.java))
                activity.overridePendingTransition(0, 0)
            }
        }
        navSettings.setOnClickListener {
            if (currentScreen != NavScreen.SETTINGS) {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("showSettings", true)
                })
                activity.overridePendingTransition(0, 0)
            }
        }

        updateHighlight(activity, currentScreen)
        updateStatus(activity, eqPrefs)
        // Set FAB from saved power state instantly — no waiting for service
        val savedPower = eqPrefs.getPowerState()
        android.util.Log.d("BottomNavHelper", "setup: screen=$currentScreen savedPower=$savedPower")
        updatePowerFab(activity, savedPower)
    }

    fun updateHighlight(activity: Activity, currentScreen: NavScreen) {
        val navEq = activity.findViewById<ImageButton>(R.id.navPresetsButton)
        val navMbc = activity.findViewById<ImageButton>(R.id.navMbcButton)
        val navLimiter = activity.findViewById<ImageButton>(R.id.navLimiterButton)
        val navSettings = activity.findViewById<ImageButton>(R.id.navSettingsButton)

        val density = activity.resources.displayMetrics.density
        val buttons = listOf(
            navEq to (currentScreen == NavScreen.EQ),
            navMbc to (currentScreen == NavScreen.MBC),
            navLimiter to (currentScreen == NavScreen.LIMITER),
            navSettings to (currentScreen == NavScreen.SETTINGS)
        )
        for ((btn, isActive) in buttons) {
            btn.setColorFilter(if (isActive) ACTIVE_COLOR else DIM_COLOR)
            val targetScale = if (isActive) 1.25f else 1.0f
            val targetTransY = if (isActive) -3f * density else 0f
            btn.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationY(targetTransY)
                .setDuration(350)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
    }

    fun updateStatus(activity: Activity, eqPrefs: EqPreferencesManager) {
        val eqStatus = activity.findViewById<TextView>(R.id.navEqStatus) ?: return
        val mbcStatus = activity.findViewById<TextView>(R.id.navMbcStatus) ?: return
        val limiterStatus = activity.findViewById<TextView>(R.id.navLimiterStatus) ?: return

        val mbcOn = eqPrefs.getMbcEnabled()
        val limiterOn = eqPrefs.getLimiterEnabled()

        eqStatus.text = "ON"
        eqStatus.setTextColor(ON_COLOR)

        mbcStatus.text = if (mbcOn) "ON" else "OFF"
        mbcStatus.setTextColor(if (mbcOn) ON_COLOR else OFF_COLOR)

        limiterStatus.text = if (limiterOn) "ON" else "OFF"
        limiterStatus.setTextColor(if (limiterOn) ON_COLOR else OFF_COLOR)
    }

    fun updatePowerFab(activity: Activity, isOn: Boolean) {
        val fab = activity.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.powerFab) ?: return
        if (isOn) {
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            fab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
        } else {
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
            fab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
        }
    }

    /** Call this whenever power state changes — saves to prefs and updates FAB */
    fun setPowerState(activity: Activity, eqPrefs: EqPreferencesManager, isOn: Boolean) {
        eqPrefs.savePowerState(isOn)
        updatePowerFab(activity, isOn)
    }
}
