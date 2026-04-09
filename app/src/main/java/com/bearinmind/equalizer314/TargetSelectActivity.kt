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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.autoeq.FreqResponse
import com.bearinmind.equalizer314.autoeq.FreqResponseParser
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray

class TargetSelectActivity : AppCompatActivity() {

    data class TargetEntry(val name: String, val type: String, val file: String)

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var resultCount: TextView
    private lateinit var activeCard: View
    private lateinit var activeName: TextView
    private lateinit var activeType: TextView
    private lateinit var clearButton: ImageButton
    private lateinit var adapter: TargetAdapter

    private val allTargets = mutableListOf<TargetEntry>()
    private var searchRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val fr = com.bearinmind.equalizer314.autoeq.FreqResponseParser.parse(text)
            if (fr != null) {
                val fileName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast("/") ?: "Custom Import"
                eqPrefs.saveSelectedTarget("__custom__")
                eqPrefs.saveSelectedTargetName(fileName)
                eqPrefs.saveSelectedTargetType("Imported")
                eqPrefs.addImportedTarget(fileName, text)
                setResult(Activity.RESULT_OK)
                updateActiveCard()
                performSearch(searchInput.text?.toString() ?: "")
                // Custom target loaded
            } else {
                android.widget.Toast.makeText(this, "Could not parse target file", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_target_select)

        eqPrefs = EqPreferencesManager(this)

        searchInput = findViewById(R.id.targetSearchInput)
        resultCount = findViewById(R.id.targetResultCount)
        recyclerView = findViewById(R.id.targetRecyclerView)
        activeCard = findViewById(R.id.targetActiveCard)
        activeName = findViewById(R.id.targetActiveName)
        activeType = findViewById(R.id.targetActiveType)
        clearButton = findViewById(R.id.targetClearButton)

        loadTargets()

        adapter = TargetAdapter(
            onItemClick = { entry -> onTargetSelected(entry) },
            onDeleteClick = { entry -> showDeleteDialog(entry.name) {
                eqPrefs.removeImportedTarget(entry.name)
                if (eqPrefs.getSelectedTargetName() == entry.name) {
                    eqPrefs.saveSelectedTarget("")
                    eqPrefs.saveSelectedTargetName("")
                    eqPrefs.saveSelectedTargetType("")
                    updateActiveCard()
                    setResult(Activity.RESULT_OK)
                }
                performSearch(searchInput.text?.toString() ?: "")
            } },
            frLoader = { entry ->
                try {
                    if (entry.file != "__custom__") {
                        val text = assets.open("targets/${entry.file}.csv").bufferedReader().readText()
                        FreqResponseParser.parse(text)
                    } else {
                        val text = eqPrefs.getImportedTargetText(entry.name)
                        if (text != null) FreqResponseParser.parse(text) else null
                    }
                } catch (_: Exception) { null }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.targetBackButton).setOnClickListener { finish() }
        clearButton.setOnClickListener { clearTarget() }

        findViewById<android.view.View>(R.id.targetImportButton).setOnClickListener {
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

    private fun loadTargets() {
        try {
            val json = assets.open("targets/targets_index.json").bufferedReader().readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                allTargets.add(TargetEntry(
                    name = obj.getString("name"),
                    type = obj.optString("type", ""),
                    file = obj.getString("file")
                ))
            }
        } catch (e: Exception) {
            android.util.Log.e("TargetSelect", "Failed to load targets", e)
        }
    }

    private fun performSearch(query: String) {
        val dbResults = if (query.isBlank()) {
            allTargets
        } else {
            val q = query.trim().lowercase()
            val words = q.split("\\s+".toRegex())
            allTargets.filter { entry ->
                val searchable = "${entry.name} ${entry.type}".lowercase()
                words.all { word -> searchable.contains(word) }
            }
        }
        // Prepend imported targets at top
        val imported = eqPrefs.getImportedTargets()
        val importedEntries = imported
            .filter { name ->
                query.isBlank() || name.lowercase().contains(query.trim().lowercase())
            }
            .map { TargetEntry(it, "Imported", "__custom__") }
        val results = importedEntries + dbResults
        adapter.submitList(results)
        resultCount.text = if (query.isBlank()) {
            "${allTargets.size + imported.size} targets"
        } else {
            "${results.size} targets"
        }
    }

    private fun onTargetSelected(entry: TargetEntry) {
        eqPrefs.saveSelectedTarget(entry.file)
        eqPrefs.saveSelectedTargetName(entry.name)
        eqPrefs.saveSelectedTargetType(entry.type)
        setResult(Activity.RESULT_OK)
        android.widget.Toast.makeText(this, "Target: ${entry.name}", android.widget.Toast.LENGTH_SHORT).show()
        updateActiveCard()
    }

    private fun clearTarget() {
        eqPrefs.saveSelectedTarget("")
        eqPrefs.saveSelectedTargetName("")
        eqPrefs.saveSelectedTargetType("")
        setResult(Activity.RESULT_OK)
        updateActiveCard()
    }

    private fun updateActiveCard() {
        val name = eqPrefs.getSelectedTargetName()
        if (name.isNullOrBlank()) {
            if (activeCard.visibility == android.view.View.VISIBLE) {
                activeCard.animate().alpha(0f).setDuration(200).withEndAction {
                    activeCard.visibility = android.view.View.GONE
                }.start()
            }
        } else {
            if (activeCard.visibility == android.view.View.VISIBLE) {
                activeCard.animate().alpha(0f).setDuration(120).withEndAction {
                    activeName.text = name
                    activeType.text = eqPrefs.getSelectedTargetType() ?: ""
                    updateActiveGraph()
                    activeCard.animate().alpha(1f).setDuration(120).start()
                }.start()
            } else {
                activeName.text = name
                activeType.text = eqPrefs.getSelectedTargetType() ?: ""
                updateActiveGraph()
                activeCard.alpha = 0f
                activeCard.visibility = android.view.View.VISIBLE
                activeCard.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    private fun updateActiveGraph() {
        val container = findViewById<android.widget.FrameLayout>(R.id.targetActiveGraph)
        container.removeAllViews()
        val targetFile = eqPrefs.getSelectedTarget()
        val fr = try {
            if (targetFile != null && targetFile != "__custom__") {
                val text = assets.open("targets/${targetFile}.csv").bufferedReader().readText()
                FreqResponseParser.parse(text)
            } else {
                val name = eqPrefs.getSelectedTargetName() ?: return
                val text = eqPrefs.getImportedTargetText(name)
                if (text != null) FreqResponseParser.parse(text) else null
            }
        } catch (_: Exception) { null }
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

    // ---- RecyclerView Adapter ----

    private class TargetAdapter(
        private val onItemClick: (TargetEntry) -> Unit,
        private val onDeleteClick: (TargetEntry) -> Unit,
        private val frLoader: (TargetEntry) -> FreqResponse?
    ) : RecyclerView.Adapter<TargetAdapter.ViewHolder>() {

        private var items = listOf<TargetEntry>()
        private val frCache = HashMap<String, FreqResponse?>()

        fun submitList(list: List<TargetEntry>) {
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
            holder.text2.text = entry.type
            holder.itemView.setOnClickListener { onItemClick(entry) }

            val isImported = entry.file == "__custom__"
            holder.deleteBtn.visibility = if (isImported) View.VISIBLE else View.GONE
            holder.deleteBtn.setOnClickListener { onDeleteClick(entry) }

            val cacheKey = entry.file.ifEmpty { entry.name }
            val fr = frCache.getOrPut(cacheKey) { frLoader(entry) }
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
