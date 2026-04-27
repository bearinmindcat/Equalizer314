package com.bearinmind.equalizer314

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.autoeq.ApoConverter
import com.bearinmind.equalizer314.autoeq.AutoEqParser
import com.bearinmind.equalizer314.autoeq.AutoEqProfile
import com.bearinmind.equalizer314.autoeq.apoTokenToFilterType
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ConvertToApoActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var powerampCard: MaterialCardView
    private lateinit var powerampStatus: TextView
    private lateinit var waveletCard: MaterialCardView
    private lateinit var waveletStatus: TextView
    private lateinit var convertButton: MaterialButton
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultTimestamp: TextView
    private lateinit var resultFilterCount: TextView
    private lateinit var resultGraphContainer: FrameLayout
    private lateinit var resultText: EditText
    private lateinit var editButton: MaterialButton
    private lateinit var addToPresetsButton: MaterialButton
    private lateinit var exportButton: MaterialButton

    private var pendingText: String? = null
    private var pendingFileName: String = ""
    private var pendingFromPoweramp: Boolean = true
    private var lastSourceName: String = "Converted preset"

    private val powerampLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePicked(uri, fromPoweramp = true)
    }
    private val waveletLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePicked(uri, fromPoweramp = false)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(resultText.text.toString()) }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_convert_to_apo)
        eqPrefs = EqPreferencesManager(this)

        powerampCard = findViewById(R.id.powerampCard)
        powerampStatus = findViewById(R.id.powerampStatus)
        waveletCard = findViewById(R.id.waveletCard)
        waveletStatus = findViewById(R.id.waveletStatus)
        convertButton = findViewById(R.id.convertButton)
        resultCard = findViewById(R.id.convertResultCard)
        resultTimestamp = findViewById(R.id.convertResultTimestamp)
        resultFilterCount = findViewById(R.id.convertResultFilterCount)
        resultGraphContainer = findViewById(R.id.convertResultGraphContainer)
        resultText = findViewById(R.id.convertResultText)
        editButton = findViewById(R.id.convertEditButton)
        addToPresetsButton = findViewById(R.id.convertAddToPresetsButton)
        exportButton = findViewById(R.id.convertExportButton)

        findViewById<ImageButton>(R.id.convertBackButton).setOnClickListener { finish() }

        powerampCard.setOnClickListener { powerampLauncher.launch("application/json") }
        waveletCard.setOnClickListener { waveletLauncher.launch("text/*") }
        convertButton.setOnClickListener { runConversion() }

        editButton.setOnClickListener {
            val nowEditable = !resultText.isFocusable
            resultText.isFocusable = nowEditable
            resultText.isFocusableInTouchMode = nowEditable
            resultText.isCursorVisible = nowEditable
            if (nowEditable) {
                resultText.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(resultText, 0)
            }
        }

        addToPresetsButton.setOnClickListener {
            val apoText = resultText.text.toString().trim()
            if (apoText.isEmpty()) return@setOnClickListener
            eqPrefs.addImportedPreset(lastSourceName, apoText)
            Toast.makeText(this, "Added \"$lastSourceName\" to AutoEQ & Presets", Toast.LENGTH_SHORT).show()
        }
        exportButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "${lastSourceName}_APO.txt")
            }
            exportLauncher.launch(intent)
        }
    }

    private fun handlePicked(uri: Uri, fromPoweramp: Boolean) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            val fileName = contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "Converted preset"

            pendingText = text
            pendingFileName = fileName
            pendingFromPoweramp = fromPoweramp

            // Update only the card the user actually picked from; clear the
            // other so it's obvious which input is current.
            if (fromPoweramp) {
                powerampStatus.text = fileName
                waveletStatus.text = "No file selected"
            } else {
                waveletStatus.text = fileName
                powerampStatus.text = "No file selected"
            }
            convertButton.isEnabled = text.isNotBlank()
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runConversion() {
        val text = pendingText ?: return
        when (val r = ApoConverter.convert(text)) {
            is ApoConverter.Result.Ok -> {
                lastSourceName = pendingFileName.substringBeforeLast('.').ifBlank { pendingFileName }
                showResult(r.apoText, r.sourceLabel)
            }
            is ApoConverter.Result.Err -> {
                resultCard.visibility = View.GONE
                addToPresetsButton.visibility = View.GONE
                exportButton.visibility = View.GONE
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResult(apoText: String, sourceLabelText: String) {
        val timestamp = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        resultTimestamp.text = "$timestamp · $sourceLabelText"

        val profile = AutoEqParser.parse(apoText)
        val filterCount = profile?.filters?.size ?: 0
        resultFilterCount.text = "$filterCount filters"

        resultGraphContainer.removeAllViews()
        if (profile != null) {
            val view = MiniEqResultView(this, profile)
            resultGraphContainer.addView(view)
        }

        resultText.setText(apoText)
        resultCard.visibility = View.VISIBLE
        addToPresetsButton.visibility = View.VISIBLE
        exportButton.visibility = View.VISIBLE
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Mini EQ result view — plots converted parametric EQ response.
     *  Mirrors the one used in TargetCurveActivity. */
    private class MiniEqResultView(
        context: Context,
        private val profile: AutoEqProfile,
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            strokeWidth = 0.5f * density
            style = Paint.Style.STROKE
        }
        private val gridPaint = Paint().apply {
            color = 0xFF6A6A6A.toInt(); strokeWidth = 1f
        }

        init {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            canvas.drawLine(0f, 0f, 0f, h, gridPaint)

            val eq = ParametricEqualizer()
            eq.clearBands()
            for (f in profile.filters) {
                val ft = apoTokenToFilterType(f.filterType)
                eq.addBand(f.frequency, f.gain, ft, f.q.toDouble())
            }
            val path = Path()
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
