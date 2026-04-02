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
    private lateinit var resultText: TextView
    private lateinit var bandCountSlider: Slider
    private lateinit var bandCountText: TextView

    private var measurement: FreqResponse? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val fr = FreqResponseParser.parse(text)
            if (fr != null) {
                measurement = fr
                measurementStatus.text = "${fr.frequencies.size} data points (${fr.frequencies.first().toInt()}Hz - ${fr.frequencies.last().toInt()}Hz)"
                updateComputeEnabled()
            } else {
                Toast.makeText(this, "Could not parse file — need at least 10 frequency,dB pairs", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
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
        resultText = findViewById(R.id.resultText)
        bandCountSlider = findViewById(R.id.bandCountSlider)
        bandCountText = findViewById(R.id.bandCountText)

        findViewById<ImageButton>(R.id.targetBackButton).setOnClickListener { finish() }

        // Measurement import
        findViewById<MaterialButton>(R.id.importFileButton).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // Target card — opens TargetSelectActivity
        findViewById<android.view.View>(R.id.targetSelectCard).setOnClickListener {
            targetSelectLauncher.launch(Intent(this, TargetSelectActivity::class.java))
            overridePendingTransition(0, 0)
        }

        // Filter count — round to integer
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

        updateTargetCard()
        updateComputeEnabled()
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
        val hasTarget = !eqPrefs.getSelectedTarget().isNullOrBlank()
        computeButton.isEnabled = measurement != null && hasTarget
    }

    private fun computeAndApply() {
        val meas = measurement ?: return
        val targetFile = eqPrefs.getSelectedTarget() ?: return

        val target = try {
            val text = assets.open("targets/${targetFile}.csv").bufferedReader().readText()
            FreqResponseParser.parse(text)
        } catch (e: Exception) {
            null
        }

        if (target == null) {
            Toast.makeText(this, "Failed to load target curve", Toast.LENGTH_SHORT).show()
            return
        }

        val numBands = bandCountSlider.value.toInt()
        computeButton.isEnabled = false
        computeButton.text = "Computing..."

        // Run on background thread
        Thread {
            val profile = EqFitter.computeCorrection(meas, target, numBands)

            runOnUiThread {
                // Apply
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
                eqPrefs.savePresetName("Target Curve")
                eqPrefs.saveAutoEqName("")
                eqPrefs.saveAutoEqSource("")

                // Show result
                resultText.visibility = android.view.View.VISIBLE
                val sb = StringBuilder("Applied ${profile.filters.size} filters (preamp: ${String.format("%.1f", profile.preampDb)} dB)\n")
                for ((i, f) in profile.filters.withIndex()) {
                    sb.append("${i + 1}: ${f.filterType} ${f.frequency.toInt()}Hz ${String.format("%+.1f", f.gain)}dB Q=${String.format("%.2f", f.q)}\n")
                }
                resultText.text = sb.toString()

                computeButton.text = "Compute EQ"
                computeButton.isEnabled = true
                setResult(Activity.RESULT_OK)
                Toast.makeText(this, "EQ computed and applied", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
