package com.bearinmind.equalizer314

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

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
        setupExperimentalDpMode()
        setupGainCompensation()
    }

    /**
     * Toggle for the experimental DP engine pipeline. When ON the audio
     * engine uses 32 bands + VARIANT_FAVOR_TIME_RESOLUTION; when OFF it
     * uses the legacy 128 bands + VARIANT_FAVOR_FREQUENCY_RESOLUTION.
     * This screen only persists the pref — MainActivity picks the
     * change up on its next onResume and triggers a live DP restart so
     * the user can A/B without reopening the app.
     */
    private fun setupExperimentalDpMode() {
        val switch = findViewById<MaterialSwitch>(R.id.expDpModeSwitch)
        switch.isChecked = eqPrefs.getExperimentalDpMode()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveExperimentalDpMode(isChecked)
        }
    }

    // NOTE: DP Band Count and Gain Compensation are intentionally disabled for
    // the current release. The controls remain visible so users can see the
    // planned capability, but they are non-interactive and their persisted
    // values are forced to the safe defaults (128 bands, gain comp off) on
    // every entry to this screen. Re-enable by removing the `isEnabled = false`
    // lines below and restoring the listeners.
    private fun setupDpBandCount() {
        val slider = findViewById<Slider>(R.id.expDpBandCountSlider)
        val text = findViewById<EditText>(R.id.expDpBandCountText)

        // Force default value at runtime (ignore anything previously saved).
        eqPrefs.saveDpBandCount(128)
        slider.value = 128f
        text.setText("128")

        // Disabled for release — no-op listeners, controls grayed out.
        slider.isEnabled = false
        text.isEnabled = false
        slider.alpha = 0.4f
        text.alpha = 0.4f
    }

    private fun setupGainCompensation() {
        val switch = findViewById<MaterialSwitch>(R.id.expGainCompSwitch)

        // Force off at runtime (ignore anything previously saved).
        eqPrefs.saveAutoGainEnabled(false)
        switch.isChecked = false

        // Disabled for release — no listener, switch grayed out.
        switch.isEnabled = false
        switch.alpha = 0.4f
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
