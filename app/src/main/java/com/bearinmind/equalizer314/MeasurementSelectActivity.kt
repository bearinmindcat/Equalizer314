package com.bearinmind.equalizer314

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.autoeq.FreqResponse
import com.bearinmind.equalizer314.autoeq.FreqResponseParser
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.textfield.TextInputEditText

class MeasurementSelectActivity : AppCompatActivity() {

    data class MeasEntry(val name: String, val info: String)

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var resultCount: TextView
    private lateinit var activeCard: View
    private lateinit var activeName: TextView
    private lateinit var activeInfo: TextView
    private lateinit var clearButton: ImageButton
    private lateinit var adapter: MeasAdapter

    private val allMeasurements = mutableListOf<MeasEntry>()
    private var searchRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val fr = FreqResponseParser.parse(text)
            if (fr != null) {
                val fileName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast("/") ?: "Measurement"
                val info = "${fr.frequencies.size} points (${fr.frequencies.first().toInt()}Hz - ${fr.frequencies.last().toInt()}Hz)"
                eqPrefs.addImportedMeasurement(fileName, text)
                eqPrefs.saveSelectedMeasurement(fileName)
                eqPrefs.saveSelectedMeasurementInfo(info)
                loadMeasurements()
                performSearch(searchInput.text?.toString() ?: "")
                updateActiveCard()
                setResult(Activity.RESULT_OK)
            } else {
                Toast.makeText(this, "Could not parse — need at least 10 frequency,dB pairs", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement_select)

        eqPrefs = EqPreferencesManager(this)

        searchInput = findViewById(R.id.measSearchInput)
        resultCount = findViewById(R.id.measResultCount)
        recyclerView = findViewById(R.id.measRecyclerView)
        activeCard = findViewById(R.id.measActiveCard)
        activeName = findViewById(R.id.measActiveName)
        activeInfo = findViewById(R.id.measActiveInfo)
        clearButton = findViewById(R.id.measClearButton)

        loadMeasurements()

        adapter = MeasAdapter(
            onItemClick = { entry -> onMeasSelected(entry) },
            onDeleteClick = { entry -> showDeleteDialog(entry.name) {
                eqPrefs.removeImportedMeasurement(entry.name)
                if (eqPrefs.getSelectedMeasurement() == entry.name) {
                    eqPrefs.saveSelectedMeasurement("")
                    eqPrefs.saveSelectedMeasurementInfo("")
                    updateActiveCard()
                    setResult(Activity.RESULT_OK)
                }
                loadMeasurements()
                performSearch(searchInput.text?.toString() ?: "")
            } },
            frLoader = { entry ->
                val text = eqPrefs.getImportedMeasurementText(entry.name)
                if (text != null) FreqResponseParser.parse(text) else null
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.measBackButton).setOnClickListener { finish() }
        clearButton.setOnClickListener { clearMeasurement() }

        findViewById<View>(R.id.measImportButton).setOnClickListener {
            importLauncher.launch("*/*")
        }

        updateActiveCard()
        performSearch("")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable { performSearch(s?.toString() ?: "") }
                handler.postDelayed(searchRunnable!!, 250)
            }
        })
    }

    private fun loadMeasurements() {
        allMeasurements.clear()
        val imported = eqPrefs.getImportedMeasurements()
        for (name in imported) {
            val text = eqPrefs.getImportedMeasurementText(name)
            val fr = if (text != null) FreqResponseParser.parse(text) else null
            val info = if (fr != null) "${fr.frequencies.size} points" else "Imported"
            allMeasurements.add(MeasEntry(name, info))
        }
    }

    private fun performSearch(query: String) {
        val results = if (query.isBlank()) {
            allMeasurements
        } else {
            val q = query.trim().lowercase()
            allMeasurements.filter { it.name.lowercase().contains(q) }
        }
        adapter.submitList(results)
        resultCount.text = "${results.size} measurements"
    }

    private fun onMeasSelected(entry: MeasEntry) {
        eqPrefs.saveSelectedMeasurement(entry.name)
        eqPrefs.saveSelectedMeasurementInfo(entry.info)
        setResult(Activity.RESULT_OK)
        Toast.makeText(this, "Measurement: ${entry.name}", Toast.LENGTH_SHORT).show()
        updateActiveCard()
    }

    private fun clearMeasurement() {
        eqPrefs.saveSelectedMeasurement("")
        eqPrefs.saveSelectedMeasurementInfo("")
        setResult(Activity.RESULT_OK)
        updateActiveCard()
    }

    private fun updateActiveCard() {
        val name = eqPrefs.getSelectedMeasurement()
        if (name.isNullOrBlank()) {
            if (activeCard.visibility == View.VISIBLE) {
                activeCard.animate().alpha(0f).setDuration(200).withEndAction {
                    activeCard.visibility = View.GONE
                }.start()
            }
        } else {
            if (activeCard.visibility == View.VISIBLE) {
                activeCard.animate().alpha(0f).setDuration(120).withEndAction {
                    activeName.text = name
                    activeInfo.text = eqPrefs.getSelectedMeasurementInfo() ?: ""
                    updateActiveGraph(name)
                    activeCard.animate().alpha(1f).setDuration(120).start()
                }.start()
            } else {
                activeName.text = name
                activeInfo.text = eqPrefs.getSelectedMeasurementInfo() ?: ""
                updateActiveGraph(name)
                activeCard.alpha = 0f
                activeCard.visibility = View.VISIBLE
                activeCard.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    private fun updateActiveGraph(name: String) {
        val container = findViewById<android.widget.FrameLayout>(R.id.measActiveGraph)
        container.removeAllViews()
        val text = eqPrefs.getImportedMeasurementText(name)
        val fr = if (text != null) FreqResponseParser.parse(text) else null
        if (fr != null) {
            val view = MiniFrView(this)
            view.setData(fr)
            container.addView(view, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun showDeleteDialog(name: String, onConfirm: () -> Unit) {
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        val title = android.widget.TextView(this).apply {
            text = "Delete"
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val message = android.widget.TextView(this).apply {
            text = "Delete \"$name\"?"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 14f
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        }
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val deleteBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete"; layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (3 * density).toInt() }
            cornerRadius = (12 * density).toInt(); setTextColor(0xFFEF9A9A.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000); insetTop = 0; insetBottom = 0
        }
        val cancelBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"; layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (3 * density).toInt() }
            cornerRadius = (12 * density).toInt(); setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000); strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt()); strokeWidth = (1 * density).toInt()
            insetTop = 0; insetBottom = 0
        }
        btnRow.addView(deleteBtn); btnRow.addView(cancelBtn)
        dialogView.addView(title); dialogView.addView(message); dialogView.addView(divider); dialogView.addView(btnRow)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog).setView(dialogView).create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        deleteBtn.setOnClickListener { onConfirm(); dialog.dismiss() }
        dialog.show()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ---- Adapter ----

    private class MeasAdapter(
        private val onItemClick: (MeasEntry) -> Unit,
        private val onDeleteClick: (MeasEntry) -> Unit,
        private val frLoader: (MeasEntry) -> FreqResponse?
    ) : RecyclerView.Adapter<MeasAdapter.ViewHolder>() {

        private var items = listOf<MeasEntry>()
        private val frCache = HashMap<String, FreqResponse?>()

        fun submitList(list: List<MeasEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val hPad = (16 * density).toInt()
            val vPad = (10 * density).toInt()

            val rippleAttr = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT)
                setPadding(hPad, vPad, hPad, vPad)
                setBackgroundResource(rippleAttr.resourceId)
                isClickable = true
                isFocusable = true
            }
            val textCol = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val text1 = TextView(ctx).apply { setTextColor(0xFFE2E2E2.toInt()); textSize = 14f; isSingleLine = true }
            val text2 = TextView(ctx).apply { setTextColor(0xFF888888.toInt()); textSize = 12f; isSingleLine = true }
            textCol.addView(text1)
            textCol.addView(text2)
            row.addView(textCol)

            val thumbW = (48 * density).toInt()
            val thumbH = (24 * density).toInt()
            val rightCol = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * density).toInt()
                }
            }
            val thumbView = MiniFrView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(thumbW, thumbH)
            }
            val deleteBtn = android.widget.TextView(ctx).apply {
                text = "×"
                setTextColor(0xFFEF9A9A.toInt())
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                val btnSize = (30 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((1 * density).toInt(), 0xFF444444.toInt())
                    cornerRadius = 10 * density
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Remove"
            }
            row.addView(deleteBtn)

            rightCol.addView(thumbView)
            row.addView(rightCol)

            return ViewHolder(row, text1, text2, thumbView, deleteBtn)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.text1.text = entry.name
            holder.text2.text = entry.info
            holder.itemView.setOnClickListener { onItemClick(entry) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(entry) }

            val fr = frCache.getOrPut(entry.name) { frLoader(entry) }
            holder.thumbView.setData(fr)
        }

        class ViewHolder(
            view: View,
            val text1: TextView,
            val text2: TextView,
            val thumbView: MiniFrView,
            val deleteBtn: android.widget.TextView
        ) : RecyclerView.ViewHolder(view)
    }

    private class MiniFrView(context: android.content.Context) : View(context) {
        private var smoothFreqs: FloatArray? = null
        private var smoothLevels: FloatArray? = null
        private val density = context.resources.displayMetrics.density
        private val curvePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            strokeWidth = 0.5f * density
            style = android.graphics.Paint.Style.STROKE
        }
        private val gridPaint = android.graphics.Paint().apply {
            color = 0xFF6A6A6A.toInt(); strokeWidth = 1f
        }

        fun setData(data: FreqResponse?) {
            if (data != null && data.frequencies.size > 10) {
                // Resample at 50 log-spaced points for a smooth curve
                val targetFreqs = FreqResponseParser.logSpace(50)
                smoothFreqs = targetFreqs
                smoothLevels = FreqResponseParser.interpolateAt(data, targetFreqs)
            } else {
                smoothFreqs = null; smoothLevels = null
            }
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawLine(0f, 0f, 0f, h, gridPaint)
            val freqs = smoothFreqs ?: return
            val levels = smoothLevels ?: return

            val minDb = levels.min(); val maxDb = levels.max()
            val range = (maxDb - minDb).coerceAtLeast(1f)
            val center = (maxDb + minDb) / 2f
            val halfRange = range / 2f * 1.15f

            val logMin = Math.log10(20.0)
            val logMax = Math.log10(20000.0)
            val logSpan = logMax - logMin

            val path = android.graphics.Path()
            for (i in freqs.indices) {
                val logF = Math.log10(freqs[i].toDouble())
                val x = ((logF - logMin) / logSpan * w).toFloat()
                val y = (h / 2f - ((levels[i] - center) / halfRange) * (h / 2f)).coerceIn(0f, h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, curvePaint)
        }
    }
}
