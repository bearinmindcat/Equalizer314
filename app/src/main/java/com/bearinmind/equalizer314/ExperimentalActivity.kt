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
        setupPreamp()
        setupGainCompensation()
    }

    private fun setupDpBandCount() {
        val slider = findViewById<Slider>(R.id.expDpBandCountSlider)
        val text = findViewById<EditText>(R.id.expDpBandCountText)

        val savedCount = eqPrefs.getDpBandCount()
        slider.value = savedCount.toFloat().coerceIn(32f, 128f)
        text.setText(savedCount.toString())

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val count = value.toInt()
            text.setText(count.toString())
            eqPrefs.saveDpBandCount(count)
        }

        text.setOnEditorActionListener { _, _, _ ->
            val count = text.text.toString().toIntOrNull()?.coerceIn(32, 128) ?: 128
            slider.value = count.toFloat()
            text.setText(count.toString())
            eqPrefs.saveDpBandCount(count)
            false
        }
    }

    private fun setupGainCompensation() {
        val switch = findViewById<MaterialSwitch>(R.id.expGainCompSwitch)
        switch.isChecked = eqPrefs.getAutoGainEnabled()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveAutoGainEnabled(isChecked)
        }
    }

    private fun setupPreamp() {
        val slider = findViewById<Slider>(R.id.expPreampSlider)
        val text = findViewById<EditText>(R.id.expPreampText)

        val savedGain = eqPrefs.getPreampGain()
        slider.value = savedGain.coerceIn(-12f, 12f)
        text.setText(String.format("%.1f", savedGain))

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            text.setText(String.format("%.1f", value))
            eqPrefs.savePreampGain(value)
        }

        text.setOnEditorActionListener { _, _, _ ->
            val gain = text.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
            slider.value = gain
            text.setText(String.format("%.1f", gain))
            eqPrefs.savePreampGain(gain)
            false
        }
    }
}
