package com.bearinmind.equalizer314

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.state.EqPreferencesManager
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
    // (on by default; see EqPreferencesManager.getAutoGainEnabled). DP Band
    // Count remains here and is adjustable for A/B testing.
    private fun setupDpBandCount() {
        val slider = findViewById<Slider>(R.id.expDpBandCountSlider)
        val text = findViewById<EditText>(R.id.expDpBandCountText)

        // Hydrate from prefs and let the user adjust 32–128 bands.
        // Re-enabled (was previously force-locked at 128) so testers can
        // A/B band-count vs the experimental DP variant toggle.
        val saved = eqPrefs.getDpBandCount().coerceIn(32, 128)
        slider.value = saved.toFloat()
        text.setText(saved.toString())

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.toInt().coerceIn(32, 128)
            text.setText(v.toString())
            eqPrefs.saveDpBandCount(v)
        }
        text.setOnEditorActionListener { _, _, _ ->
            val v = text.text.toString().toIntOrNull()?.coerceIn(32, 128)
            if (v != null) {
                slider.value = v.toFloat()
                text.setText(v.toString())
                eqPrefs.saveDpBandCount(v)
            }
            true
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
