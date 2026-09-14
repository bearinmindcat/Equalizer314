package com.bearinmind.equalizer314

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.color.MaterialColors

/**
 * Sub-settings screen grouping the preset/conversion entries: AutoEQ & Presets,
 * Generate Custom EQ, and Convert to APO. Reached from the "Presets & Conversions" card.
 *
 * AutoEQ / Generate Custom EQ persist to prefs; on their RESULT_OK we propagate
 * RESULT_OK up to MainActivity so it reloads the EQ from prefs.
 */
class PresetsConversionsActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    private val autoEqLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)        // tell MainActivity to reload on return
            updateAutoEqStatus()
        }
    }

    private val targetCurveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            updateTargetStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_presets_conversions)
        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.presetsConvBackButton).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.autoEqCard).setOnClickListener {
            autoEqLauncher.launch(Intent(this, AutoEqActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<android.view.View>(R.id.targetCard).setOnClickListener {
            targetCurveLauncher.launch(Intent(this, TargetCurveActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<android.view.View>(R.id.convertToApoCard).setOnClickListener {
            startActivity(Intent(this, ConvertToApoActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutoEqStatus()
        updateTargetStatus()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun updateAutoEqStatus() {
        val statusText = findViewById<TextView>(R.id.autoEqStatusText) ?: return
        val name = eqPrefs.getAutoEqName()
        if (!name.isNullOrBlank()) {
            val source = eqPrefs.getAutoEqSource() ?: ""
            statusText.text = getString(R.string.name_by_source, name, source)
            statusText.setTextColor(MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = getString(R.string.select_or_import_a_preset)
            statusText.setTextColor(MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }

    private fun updateTargetStatus() {
        val statusText = findViewById<TextView>(R.id.targetStatusText) ?: return
        val name = eqPrefs.getSelectedTargetName()
        if (!name.isNullOrBlank()) {
            val type = eqPrefs.getSelectedTargetType() ?: ""
            statusText.text = if (type.isNotBlank()) "$name · $type" else name
            statusText.setTextColor(MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = getString(R.string.import_measurement_blurb)
            statusText.setTextColor(MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }
}
