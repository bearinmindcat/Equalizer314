package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch

/** Groups Channel Settings, Audio Effects Pipeline, and Gain Reduction.
 *  Switches save prefs only — MainActivity applies them on resume. */
class AudioMiscSettingsActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_misc_settings)
        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.audioMiscBackButton).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.channelSideEqCard).setOnClickListener {
            startActivity(android.content.Intent(this, ChannelSideEqActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<android.view.View>(R.id.audioEffectsPipelineCard).setOnClickListener {
            startActivity(android.content.Intent(this, AudioEffectsPipelineActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

    }

    override fun onResume() {
        super.onResume()
        // Re-sync both switches — prefs can change from the graph popout or Channel Settings.
        findViewById<MaterialSwitch>(R.id.channelSideEqSwitch).apply {
            setOnCheckedChangeListener(null)
            isChecked = eqPrefs.getChannelSideEqEnabled()
            setOnCheckedChangeListener { _, isChecked -> eqPrefs.saveChannelSideEqEnabled(isChecked) }
        }
        findViewById<MaterialSwitch>(R.id.gainReductionSwitch).apply {
            setOnCheckedChangeListener(null)
            isChecked = eqPrefs.getAutoGainEnabled()
            setOnCheckedChangeListener { _, isChecked -> eqPrefs.saveAutoGainEnabled(isChecked) }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
