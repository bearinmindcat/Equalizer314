package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager

class ExperimentalActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_experimental)

        val root = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupDpBandCount()
        setupMaxEqBands()
        setupHideNotification()

        // Hide the legacy "Experimental DP Engine" switch row — the
        // experimental path is now the only path. Keeping the view
        // hidden (rather than removing the layout XML) preserves the
        // surrounding card structure for the remaining rows.
        findViewById<android.view.View>(R.id.expDpModeSwitch)
            ?.let { switch ->
                (switch.parent as? android.view.View)?.visibility = android.view.View.GONE
            }
    }

    // Gain Compensation (auto-gain) graduated out of Experimental — it now
    // lives as a real, enabled "Auto-Gain" card on the main settings screen
    // (on by default; see EqPreferencesManager.getAutoGainEnabled).
    // DP Band Count is now read-only: the converter always uses the full
    // Wavelet band table (ParametricToDpConverter.numBands), so we just show
    // that number rather than a slider that didn't actually change anything.
    private fun setupDpBandCount() {
        findViewById<android.widget.TextView>(R.id.expDpBandCountValue).text =
            com.bearinmind.equalizer314.dsp.ParametricToDpConverter.numBands.toString()
    }

    // Experimental "Add more EQ bands" toggle (issue #31). On → cap 64,
    // off → default 16. Updates the live cap so it takes effect on the next
    // band add / EQ-screen interaction.
    private fun setupMaxEqBands() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expMaxBandsSwitch)
        switch.isChecked = eqPrefs.getMaxEqBands() > 16
        switch.setOnCheckedChangeListener { _, isChecked ->
            val cap = if (isChecked) EqStateManager.ABSOLUTE_MAX_BANDS else 16
            eqPrefs.saveMaxEqBands(cap)
            EqStateManager.MAX_BANDS = cap
        }
    }

    // Issue #58: hide the foreground-service notification while the EQ is off.
    private fun setupHideNotification() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expHideNotifSwitch)
        switch.isChecked = eqPrefs.getHideNotificationWhenOff()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.setHideNotificationWhenOff(isChecked)
            // Apply immediately to the running service.
            try {
                startService(
                    android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                        .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_REFRESH_NOTIFICATION)
                )
            } catch (_: Exception) {}
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
