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
    private lateinit var resultText: android.widget.EditText
    private lateinit var resultCard: android.view.View
    private lateinit var resultGraphContainer: android.widget.FrameLayout
    private lateinit var resultTimestamp: TextView
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

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val apoText = resultText.text.toString().trim()
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(apoText) }
                Toast.makeText(this, "Exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
        resultCard = findViewById(R.id.resultCard)
        resultGraphContainer = findViewById(R.id.resultGraphContainer)
        resultTimestamp = findViewById(R.id.resultTimestamp)
        bandCountSlider = findViewById(R.id.bandCountSlider)
        bandCountText = findViewById(R.id.bandCountText)

        findViewById<ImageButton>(R.id.targetBackButton).setOnClickListener { finish() }

        exportButton.setOnClickListener { exportApo() }

        // Edit button opens editor dialog
        val editBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.resultEditButton)
        editBtn.setOnClickListener { showEditDialog() }

        // Measurement card — opens MeasurementSelectActivity
        findViewById<android.view.View>(R.id.measurementSelectCard).setOnClickListener {
            measurementSelectLauncher.launch(Intent(this, MeasurementSelectActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Target card — opens TargetSelectActivity
        findViewById<android.view.View>(R.id.targetSelectCard).setOnClickListener {
            targetSelectLauncher.launch(Intent(this, TargetSelectActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
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
        restoreGeneratedResult()
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

    private fun restoreGeneratedResult() {
        val apoText = eqPrefs.getGeneratedEqApo() ?: return
        val timestamp = eqPrefs.getGeneratedEqTimestamp() ?: ""
        val profile = AutoEqParser.parse(apoText) ?: return
        lastComputedProfile = profile
        resultCard.visibility = android.view.View.VISIBLE
        exportButton.visibility = android.view.View.VISIBLE
        resultText.setText(apoText)
        resultTimestamp.text = timestamp
        resultGraphContainer.removeAllViews()
        val view = MiniEqResultView(this, profile)
        resultGraphContainer.addView(view)
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

        // Animate "Generating" "Generating." "Generating.." "Generating..."
        val dotHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var dotCount = 0
        val dotRunnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount + 1) % 4
                computeButton.text = "Generating" + ".".repeat(dotCount)
                dotHandler.postDelayed(this, 400)
            }
        }
        dotHandler.post(dotRunnable)

        Thread {
            try {
            val profile = EqFitter.computeCorrection(meas, target, numBands)

            runOnUiThread {
                val eq = ParametricEqualizer()
                eq.clearBands()
                for (filter in profile.filters) {
                    val filterType = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(filter.filterType)
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
                val apoText = profileToApoText(profile)
                val timestamp = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

                // Persist
                eqPrefs.saveGeneratedEq(apoText, timestamp)

                // Show result card with mini EQ graph
                resultCard.visibility = android.view.View.VISIBLE
                exportButton.visibility = android.view.View.VISIBLE
                resultText.setText(apoText)
                resultTimestamp.text = timestamp

                // Mini EQ graph for generated result
                resultGraphContainer.removeAllViews()
                val resultEqView = MiniEqResultView(this@TargetCurveActivity, profile)
                resultGraphContainer.addView(resultEqView)

                dotHandler.removeCallbacks(dotRunnable)
                computeButton.text = "Generate EQ"
                computeButton.isEnabled = true
                setResult(Activity.RESULT_OK)
            }
            } catch (e: Exception) {
                runOnUiThread {
                    dotHandler.removeCallbacks(dotRunnable)
                    computeButton.text = "Generate EQ"
                    computeButton.isEnabled = true
                    android.widget.Toast.makeText(this, "EQ generation failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showEditDialog() {
        val density = resources.displayMetrics.density
        val originalText = resultText.text.toString()
        val editHistory = mutableListOf(originalText)
        var historyIndex = 0

        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }

        // Top row: title on left, undo/redo on right
        val titleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val title = android.widget.TextView(this).apply {
            text = "Edit Generated EQ"
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undoDlgBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val sz = (28 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply { marginStart = (4 * density).toInt() }
            icon = resources.getDrawable(R.drawable.ic_undo, theme); iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0; iconTint = null; setPadding(0, 0, 0, 0); insetTop = 0; insetBottom = 0; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            cornerRadius = (8 * density).toInt(); strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
        }
        val redoDlgBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val sz = (28 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply { marginStart = (4 * density).toInt() }
            icon = resources.getDrawable(R.drawable.ic_redo, theme); iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0; iconTint = null; setPadding(0, 0, 0, 0); insetTop = 0; insetBottom = 0; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            cornerRadius = (8 * density).toInt(); strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
        }
        titleRow.addView(title); titleRow.addView(undoDlgBtn); titleRow.addView(redoDlgBtn)

        val editBox = android.widget.EditText(this).apply {
            setText(originalText)
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            minLines = 8
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A1A1A.toInt())
                setStroke((1 * density).toInt(), 0xFF555555.toInt())
                cornerRadius = 12 * density
            }
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                topMargin = (12 * density).toInt(); bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        }

        fun saveState() {
            val current = editBox.text.toString()
            if (historyIndex < editHistory.size && editHistory[historyIndex] == current) return
            while (editHistory.size > historyIndex + 1) editHistory.removeAt(editHistory.size - 1)
            editHistory.add(current)
            historyIndex = editHistory.size - 1
        }

        // Bottom row: Reset + Save full width
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val resetDlgBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt(); setTextColor(0xFFEF9A9A.toInt())
            setBackgroundColor(0x00000000); strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            insetTop = 0; insetBottom = 0
        }
        val saveBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Save"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
            cornerRadius = (12 * density).toInt(); setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000); strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            insetTop = 0; insetBottom = 0
        }
        btnRow.addView(resetDlgBtn); btnRow.addView(saveBtn)

        dialogView.addView(titleRow); dialogView.addView(editBox); dialogView.addView(divider); dialogView.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(dialogView).create()

        undoDlgBtn.setOnClickListener {
            saveState()
            if (historyIndex > 0) { historyIndex--; editBox.setText(editHistory[historyIndex]) }
        }
        redoDlgBtn.setOnClickListener {
            if (historyIndex < editHistory.size - 1) { historyIndex++; editBox.setText(editHistory[historyIndex]) }
        }
        resetDlgBtn.setOnClickListener {
            saveState()
            editBox.setText(editHistory[0])
        }
        saveBtn.setOnClickListener {
            val edited = editBox.text.toString().trim()
            if (edited.isNotEmpty()) {
                resultText.setText(edited)
                val ts = eqPrefs.getGeneratedEqTimestamp() ?: ""
                eqPrefs.saveGeneratedEq(edited, ts)
            }
            dialog.dismiss()
        }
        dialog.show()
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
        val apoText = resultText.text.toString().trim()
        if (apoText.isEmpty()) return
        val measName = eqPrefs.getSelectedMeasurement() ?: "custom"
        val fileName = "${measName}_EQ.txt"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportLauncher.launch(intent)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Mini EQ result view — plots generated parametric EQ response */
    private class MiniEqResultView(
        context: android.content.Context,
        private val profile: AutoEqProfile
    ) : android.view.View(context) {

        private val density = context.resources.displayMetrics.density
        private val curvePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            strokeWidth = 0.5f * density
            style = android.graphics.Paint.Style.STROKE
        }
        private val gridPaint = android.graphics.Paint().apply {
            color = 0xFF6A6A6A.toInt(); strokeWidth = 1f
        }

        init {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            canvas.drawLine(0f, 0f, 0f, h, gridPaint)

            val eq = ParametricEqualizer()
            eq.clearBands()
            for (f in profile.filters) {
                val ft = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(f.filterType)
                eq.addBand(f.frequency, f.gain, ft, f.q.toDouble())
            }
            val path = android.graphics.Path()
            val maxDb = 15f; val steps = 80
            for (s in 0..steps) {
                val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
                val freq = Math.pow(10.0, logF.toDouble()).toFloat()
                val db = eq.getFrequencyResponse(freq)
                val x = w * s / steps
                val y = (h / 2f - (db / maxDb) * (h / 2f)).coerceIn(0f, h)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, curvePaint)
        }
    }
}
