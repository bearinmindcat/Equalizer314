package com.bearinmind.equalizer314.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.widget.ImageButton
import android.widget.TextView
import com.bearinmind.equalizer314.*
import com.bearinmind.equalizer314.state.EqPreferencesManager

enum class NavScreen { EQ, MBC, LIMITER, SETTINGS }

object BottomNavHelper {

    // The bottom bar background is colorSurfaceContainerHigh (#252525
    // dark / #DDDDDD light). These tints are set programmatically (they
    // override the XML's ?attr/colorOnSurface), so they must flip with
    // the theme or the active icon turns near-white on the light bar and
    // vanishes. Light values mirror the dark ones across the bar bg.
    private fun isLight(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
    private fun activeColor(activity: Activity) = if (isLight(activity)) 0xFF252525.toInt() else 0xFFDDDDDD.toInt()
    private fun dimColor(activity: Activity) = if (isLight(activity)) 0xFF8A8A8A.toInt() else 0xFF666666.toInt()
    private fun onColor(activity: Activity) = if (isLight(activity)) 0xFF252525.toInt() else 0xFFDDDDDD.toInt()
    private const val OFF_COLOR = 0xFF888888.toInt()

    /** Power-FAB colors per state, themed. The "on" state is the bold,
     *  high-contrast one in both palettes (bright FAB on the dark bar /
     *  dark FAB on the light bar); "off" recedes into the bar. */
    private data class FabPalette(val bg: Int, val icon: Int, val stroke: Int, val ripple: Int)
    private fun fabPalette(activity: Activity, isOn: Boolean): FabPalette {
        val light = isLight(activity)
        return if (isOn) {
            if (light) FabPalette(0xFF2A2A2A.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0x33FFFFFF)
            else FabPalette(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF666666.toInt(), 0xCC000000.toInt())
        } else {
            if (light) FabPalette(0xFFCFCFCF.toInt(), 0xFF888888.toInt(), 0xFFBBBBBB.toInt(), 0x22000000)
            else FabPalette(0xFF2A2A2A.toInt(), 0xFF555555.toInt(), 0xFF444444.toInt(), 0x33FFFFFF)
        }
    }

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
        val active = activeColor(activity); val dim = dimColor(activity)
        for ((btn, isActive) in buttons) {
            btn.setColorFilter(if (isActive) active else dim)
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
        val active = activeColor(activity); val dim = dimColor(activity)
        for ((btn, isActive) in buttons) {
            btn.setColorFilter(if (isActive) active else dim)
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

        val eqOn = eqPrefs.getEqEnabled()
        val mbcOn = eqPrefs.getMbcEnabled()
        val limiterOn = eqPrefs.getLimiterEnabled()
        val onCol = onColor(activity)

        eqStatus.text = if (eqOn) "ON" else "OFF"
        eqStatus.setTextColor(if (eqOn) onCol else OFF_COLOR)

        mbcStatus.text = if (mbcOn) "ON" else "OFF"
        mbcStatus.setTextColor(if (mbcOn) onCol else OFF_COLOR)

        limiterStatus.text = if (limiterOn) "ON" else "OFF"
        limiterStatus.setTextColor(if (limiterOn) onCol else OFF_COLOR)
    }

    private fun makePowerBg(density: Float, fillColor: Int, strokeColor: Int, rippleColor: Int): android.graphics.drawable.RippleDrawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(fillColor)
            setStroke((1 * density).toInt(), strokeColor)
        }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(0xFFFFFFFF.toInt())
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor), shape, mask)
    }

    fun setPowerFabInstant(activity: Activity, isOn: Boolean) {
        val btn = activity.findViewById<ImageButton>(R.id.powerFab) ?: return
        val density = activity.resources.displayMetrics.density
        val p = fabPalette(activity, isOn)
        btn.background = makePowerBg(density, p.bg, p.stroke, p.ripple)
        btn.setColorFilter(p.icon)
        btn.foreground = null
        btn.scaleX = if (isOn) 1.2f else 1.0f
        btn.scaleY = if (isOn) 1.2f else 1.0f
        btn.translationY = if (isOn) -4f * density else 0f
    }

    fun updatePowerFab(activity: Activity, isOn: Boolean) {
        val btn = activity.findViewById<ImageButton>(R.id.powerFab) ?: return
        val density = activity.resources.displayMetrics.density
        val fromP = fabPalette(activity, !isOn)
        val toP = fabPalette(activity, isOn)
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
        val ripple = makePowerBg(density, fromP.bg, fromP.stroke, fromP.ripple)
        val innerShape = ripple.getDrawable(0) as android.graphics.drawable.GradientDrawable
        btn.background = ripple
        btn.foreground = null

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val bg = blendColor(fromP.bg, toP.bg, f)
                val icon = blendColor(fromP.icon, toP.icon, f)
                val stroke = blendColor(fromP.stroke, toP.stroke, f)
                innerShape.setColor(bg)
                innerShape.setStroke((1 * density).toInt(), stroke)
                btn.setColorFilter(icon)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Set final ripple with correct color for the new state
                    btn.background = makePowerBg(density, toP.bg, toP.stroke, toP.ripple)
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
