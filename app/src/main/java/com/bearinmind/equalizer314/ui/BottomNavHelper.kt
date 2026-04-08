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

        fun navigateWithAnimation(targetScreen: NavScreen, navigate: () -> Unit) {
            if (currentScreen == targetScreen) return
            navigate()
        }

        navEq.setOnClickListener {
            navigateWithAnimation(NavScreen.EQ) {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("showSettings", false)
                })
                activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
        }
        navMbc.setOnClickListener {
            navigateWithAnimation(NavScreen.MBC) {
                activity.startActivity(Intent(activity, MbcActivity::class.java))
                activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
        }
        navLimiter.setOnClickListener {
            navigateWithAnimation(NavScreen.LIMITER) {
                activity.startActivity(Intent(activity, LimiterActivity::class.java))
                activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
        }
        navSettings.setOnClickListener {
            navigateWithAnimation(NavScreen.SETTINGS) {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("showSettings", true)
                })
                activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
        }

        setHighlightInstant(activity, currentScreen)
        updateStatus(activity, eqPrefs)
        // Set FAB from saved power state instantly — no animation on startup
        val savedPower = eqPrefs.getPowerState()
        setPowerFabInstant(activity, savedPower)
    }

    private fun setHighlightInstant(activity: Activity, currentScreen: NavScreen) {
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
            // Start from default state and animate to active
            btn.scaleX = 1.0f
            btn.scaleY = 1.0f
            btn.translationY = 0f
            if (isActive) {
                btn.animate()
                    .scaleX(1.25f)
                    .scaleY(1.25f)
                    .translationY(-3f * density)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .start()
            }
        }
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
                .setDuration(200)
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

    private fun makePowerBg(density: Float, fillColor: Int, strokeColor: Int, isOn: Boolean): android.graphics.drawable.RippleDrawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(fillColor)
            setStroke((1 * density).toInt(), strokeColor)
        }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(0xFFFFFFFF.toInt())
        }
        val rippleColor = if (isOn) 0xCC000000.toInt() else 0x33FFFFFF.toInt()
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor), shape, mask)
    }

    fun setPowerFabInstant(activity: Activity, isOn: Boolean) {
        val btn = activity.findViewById<ImageButton>(R.id.powerFab) ?: return
        val density = activity.resources.displayMetrics.density
        btn.background = makePowerBg(density,
            if (isOn) 0xFFFFFFFF.toInt() else 0xFF2A2A2A.toInt(),
            if (isOn) 0xFF666666.toInt() else 0xFF444444.toInt(), isOn)
        btn.setColorFilter(if (isOn) 0xFF000000.toInt() else 0xFF555555.toInt())
        btn.foreground = null
        btn.scaleX = if (isOn) 1.2f else 1.0f
        btn.scaleY = if (isOn) 1.2f else 1.0f
        btn.translationY = if (isOn) -4f * density else 0f
    }

    fun updatePowerFab(activity: Activity, isOn: Boolean) {
        val btn = activity.findViewById<ImageButton>(R.id.powerFab) ?: return
        val density = activity.resources.displayMetrics.density
        val fromBg = if (isOn) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt()
        val toBg = if (isOn) 0xFFFFFFFF.toInt() else 0xFF2A2A2A.toInt()
        val fromIcon = if (isOn) 0xFF555555.toInt() else 0xFF000000.toInt()
        val toIcon = if (isOn) 0xFF000000.toInt() else 0xFF555555.toInt()
        val fromStroke = if (isOn) 0xFF444444.toInt() else 0xFF666666.toInt()
        val toStroke = if (isOn) 0xFF666666.toInt() else 0xFF444444.toInt()
        val targetScale = if (isOn) 1.2f else 1.0f
        val targetTransY = if (isOn) -4f * density else 0f

        // Animate scale + translate
        btn.animate()
            .scaleX(targetScale).scaleY(targetScale)
            .translationY(targetTransY)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()

        // Set up RippleDrawable once, animate inner shape
        val ripple = makePowerBg(density, fromBg, fromStroke, isOn)
        val innerShape = ripple.getDrawable(0) as android.graphics.drawable.GradientDrawable
        btn.background = ripple
        btn.foreground = null

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val bg = blendColor(fromBg, toBg, f)
                val icon = blendColor(fromIcon, toIcon, f)
                val stroke = blendColor(fromStroke, toStroke, f)
                innerShape.setColor(bg)
                innerShape.setStroke((1 * density).toInt(), stroke)
                btn.setColorFilter(icon)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Set final ripple with correct color for the new state
                    btn.background = makePowerBg(density, toBg, toStroke, isOn)
                }
            })
            start()
        }
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val fromA = (from shr 24) and 0xFF; val fromR = (from shr 16) and 0xFF
        val fromG = (from shr 8) and 0xFF; val fromB = from and 0xFF
        val toA = (to shr 24) and 0xFF; val toR = (to shr 16) and 0xFF
        val toG = (to shr 8) and 0xFF; val toB = to and 0xFF
        val a = (fromA + (toA - fromA) * ratio).toInt()
        val r = (fromR + (toR - fromR) * ratio).toInt()
        val g = (fromG + (toG - fromG) * ratio).toInt()
        val b = (fromB + (toB - fromB) * ratio).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Call this whenever power state changes — saves to prefs and updates FAB */
    fun setPowerState(activity: Activity, eqPrefs: EqPreferencesManager, isOn: Boolean) {
        eqPrefs.savePowerState(isOn)
        updatePowerFab(activity, isOn)
    }
}
