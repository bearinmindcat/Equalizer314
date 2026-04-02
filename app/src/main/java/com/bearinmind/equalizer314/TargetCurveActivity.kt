package com.bearinmind.equalizer314

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.autoeq.*
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class TargetCurveActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var measurementStatus: TextView
    private lateinit var targetSelectStatus: TextView
    private lateinit var computeButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var resultText: TextView
    private lateinit var bandCountSlider: Slider
    private lateinit var bandCountText: TextView
    private var lastComputedProfile: AutoEqProfile? = null

    private val measurementSelectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateMeasurementCard()
            updateComputeEnabled()
        }
    }

    private val targetSelectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateTargetCard()
            updateComputeEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_target_curve)

        eqPrefs = EqPreferencesManager(this)

        measurementStatus = findViewById(R.id.measurementStatus)
        targetSelectStatus = findViewById(R.id.targetSelectStatus)
        computeButton = findViewById(R.id.computeButton)
        exportButton = findViewById(R.id.exportButton)
        resultText = findViewById(R.id.resultText)
        bandCountSlider = findViewById(R.id.bandCountSlider)
        bandCountText = findViewById(R.id.bandCountText)

        findViewById<ImageButton>(R.id.targetBackButton).setOnClickListener { finish() }

        exportButton.setOnClickListener { exportApo() }

        // Measurement card — opens MeasurementSelectActivity
        findViewById<android.view.View>(R.id.measurementSelectCard).setOnClickListener {
            measurementSelectLauncher.launch(Intent(this, MeasurementSelectActivity::class.java))
            overridePendingTransition(0, 0)
        }

        // Target card — opens TargetSelectActivity
        findViewById<android.view.View>(R.id.targetSelectCard).setOnClickListener {
            targetSelectLauncher.launch(Intent(this, TargetSelectActivity::class.java))
            overridePendingTransition(0, 0)
        }

        // Filter count
        bandCountSlider.addOnChangeListener { slider, value, fromUser ->
            val rounded = kotlin.math.round(value)
            if (fromUser) {
                bandCountText.setText(rounded.toInt().toString())
                if (value != rounded) slider.value = rounded
            }
        }
        bandCountText.setOnEditorActionListener { _, _, _ ->
            val v = (bandCountText as android.widget.EditText).text.toString().toIntOrNull()
            if (v != null) bandCountSlider.value = v.coerceIn(3, 15).toFloat()
            false
        }

        // Compute
        computeButton.setOnClickListener { computeAndApply() }

        updateMeasurementCard()
        updateTargetCard()
        updateComputeEnabled()
    }

    private fun updateMeasurementCard() {
        val name = eqPrefs.getSelectedMeasurement()
        if (!name.isNullOrBlank()) {
            val info = eqPrefs.getSelectedMeasurementInfo() ?: ""
            measurementStatus.text = if (info.isNotBlank()) "$name \u00B7 $info" else name
            measurementStatus.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    measurementStatus, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()
                )
            )
        } else {
            measurementStatus.text = "No measurement selected"
            measurementStatus.setTextColor(0xFF888888.toInt())
        }
    }

    private fun updateTargetCard() {
        val name = eqPrefs.getSelectedTargetName()
        if (!name.isNullOrBlank()) {
            val type = eqPrefs.getSelectedTargetType() ?: ""
            targetSelectStatus.text = if (type.isNotBlank()) "$name \u00B7 $type" else name
            targetSelectStatus.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    targetSelectStatus, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()
                )
            )
        } else {
            targetSelectStatus.text = "No target selected"
            targetSelectStatus.setTextColor(0xFF888888.toInt())
        }
    }

    private fun updateComputeEnabled() {
        val hasMeas = !eqPrefs.getSelectedMeasurement().isNullOrBlank()
        val hasTarget = !eqPrefs.getSelectedTarget().isNullOrBlank()
        computeButton.isEnabled = hasMeas && hasTarget
    }

    private fun computeAndApply() {
        val measName = eqPrefs.getSelectedMeasurement() ?: return
        val targetFile = eqPrefs.getSelectedTarget() ?: return

        // Load measurement from stored imported data
        val measText = eqPrefs.getImportedMeasurementText(measName)
        val meas = if (measText != null) FreqResponseParser.parse(measText) else null
        if (meas == null) {
            Toast.makeText(this, "Failed to load measurement", Toast.LENGTH_SHORT).show()
            return
        }

        val target = try {
            if (targetFile == "__custom__") {
                // Custom imported target — would need stored text too
                null
            } else {
                val text = assets.open("targets/${targetFile}.csv").bufferedReader().readText()
                FreqResponseParser.parse(text)
            }
        } catch (e: Exception) {
            null
        }

        if (target == null) {
            Toast.makeText(this, "Failed to load target curve", Toast.LENGTH_SHORT).show()
            return
        }

        val numBands = bandCountSlider.value.toInt()
        computeButton.isEnabled = false
        computeButton.text = "Generating EQ..."

        Thread {
            val profile = EqFitter.computeCorrection(meas, target, numBands)

            runOnUiThread {
                val eq = ParametricEqualizer()
                eq.clearBands()
                for (filter in profile.filters) {
                    val filterType = when (filter.filterType) {
                        "LSC" -> BiquadFilter.FilterType.LOW_SHELF
                        "HSC" -> BiquadFilter.FilterType.HIGH_SHELF
                        else -> BiquadFilter.FilterType.BELL
                    }
                    eq.addBand(filter.frequency, filter.gain, filterType, filter.q.toDouble())
                }
                eq.isEnabled = true

                val slots = (0 until eq.getBandCount()).toList()
                eqPrefs.saveState(eq, slots)
                eqPrefs.savePreampGain(profile.preampDb)
                eqPrefs.savePresetName("Generate Custom EQ")
                eqPrefs.saveAutoEqName("")
                eqPrefs.saveAutoEqSource("")

                lastComputedProfile = profile

                // Display in APO format
                resultText.visibility = android.view.View.VISIBLE
                resultText.text = profileToApoText(profile)

                exportButton.visibility = android.view.View.VISIBLE
                computeButton.text = "Generate EQ"
                computeButton.isEnabled = true
                setResult(Activity.RESULT_OK)
            }
        }.start()
    }

    private fun profileToApoText(profile: AutoEqProfile): String {
        val sb = StringBuilder()
        sb.append("Preamp: ${String.format("%.1f", profile.preampDb)} dB\n")
        for ((i, f) in profile.filters.withIndex()) {
            sb.append("Filter ${i + 1}: ON ${f.filterType} Fc ${f.frequency.toInt()} Hz Gain ${String.format("%.1f", f.gain)} dB Q ${String.format("%.2f", f.q)}\n")
        }
        return sb.toString()
    }

    private fun exportApo() {
        val profile = lastComputedProfile ?: return
        val apoText = profileToApoText(profile)
        val measName = eqPrefs.getSelectedMeasurement() ?: "custom"
        val fileName = "${measName}_EQ.txt"

        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, fileName)
            file.writeText(apoText)
            Toast.makeText(this, "Exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
