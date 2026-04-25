package com.bearinmind.equalizer314

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.autoeq.AutoEqDatabase
import com.bearinmind.equalizer314.autoeq.AutoEqEntry
import com.bearinmind.equalizer314.autoeq.AutoEqParser
import com.bearinmind.equalizer314.autoeq.AutoEqProfile
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.textfield.TextInputEditText

class AutoEqActivity : AppCompatActivity() {

    private lateinit var database: AutoEqDatabase
    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var resultCount: TextView
    private lateinit var activeCard: View
    private lateinit var activeName: TextView
    private lateinit var activeSource: TextView
    private lateinit var clearButton: ImageButton
    private lateinit var adapter: HeadphoneAdapter

    private var searchRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val profile = AutoEqParser.parse(text)
            if (profile == null || profile.filters.isEmpty()) {
                Toast.makeText(this, "Could not parse APO preset", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val fileName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast("/") ?: "APO Import"
            eqPrefs.addImportedPreset(fileName, text)
            // Imported successfully
            performSearch(searchInput.text?.toString() ?: "")
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autoeq)

        database = AutoEqDatabase(this)
        eqPrefs = EqPreferencesManager(this)

        searchInput = findViewById(R.id.autoEqSearchInput)
        resultCount = findViewById(R.id.autoEqResultCount)
        recyclerView = findViewById(R.id.autoEqRecyclerView)
        activeCard = findViewById(R.id.autoEqActiveCard)
        activeName = findViewById(R.id.autoEqActiveName)
        activeSource = findViewById(R.id.autoEqActiveSource)
        clearButton = findViewById(R.id.autoEqClearButton)

        adapter = HeadphoneAdapter(
            onItemClick = { entry -> onHeadphoneSelected(entry) },
            onDeleteClick = { entry -> showDeleteDialog(entry.name) {
                eqPrefs.removeImportedPreset(entry.name)
                performSearch(searchInput.text?.toString() ?: "")
            } },
            profileLoader = { entry ->
                if (entry.source == "Imported") {
                    val text = eqPrefs.getImportedPresetText(entry.name)
                    if (text != null) AutoEqParser.parse(text) else null
                } else {
                    database.loadProfile(entry)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.autoEqBackButton).setOnClickListener { finish() }

        clearButton.setOnClickListener { clearAutoEq() }

        findViewById<android.view.View>(R.id.autoEqImportButton).setOnClickListener {
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

    private fun performSearch(query: String) {
        val dbResults = database.search(query)
        // Prepend imported presets at top
        val imported = eqPrefs.getImportedPresets()
        val importedEntries = imported
            .filter { name ->
                query.isBlank() || name.lowercase().contains(query.trim().lowercase())
            }
            .map { AutoEqEntry(it, "Imported", "", "", "") }
        val results = importedEntries + dbResults
        adapter.submitList(results)
        resultCount.text = if (query.isBlank()) {
            "${database.totalCount() + imported.size} presets"
        } else {
            "${results.size} presets"
        }
    }

    private fun onHeadphoneSelected(entry: AutoEqEntry) {
        val profile = if (entry.source == "Imported") {
            val text = eqPrefs.getImportedPresetText(entry.name)
            if (text != null) AutoEqParser.parse(text) else null
        } else {
            database.loadProfile(entry)
        }

        if (profile == null) {
            Toast.makeText(this, "Failed to load profile", Toast.LENGTH_SHORT).show()
            return
        }

        applyProfile(entry, profile)
        lastAppliedProfile = profile
        Toast.makeText(this, "Applied: ${entry.name}", Toast.LENGTH_SHORT).show()
        updateActiveCard()
    }

    private fun applyProfile(entry: AutoEqEntry, profile: AutoEqProfile) {
        val eq = ParametricEqualizer()
        eq.clearBands()

        for (filter in profile.filters) {
            val filterType = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(filter.filterType)
            eq.addBand(filter.frequency, filter.gain, filterType, filter.q.toDouble())
        }
        eq.isEnabled = true

        // Sequential band slots: 0, 1, 2, 3, ...
        val slots = (0 until eq.getBandCount()).toList()
        eqPrefs.saveState(eq, slots)
        eqPrefs.savePreampGain(profile.preampDb)
        eqPrefs.saveAutoEqName(entry.name)
        eqPrefs.saveAutoEqSource(entry.source)
        eqPrefs.savePresetName("AutoEQ")

        setResult(RESULT_OK)
    }

    private fun clearAutoEq() {
        eqPrefs.saveAutoEqName("")
        eqPrefs.saveAutoEqSource("")
        updateActiveCard()
        // Cleared
    }

    private var lastAppliedProfile: AutoEqProfile? = null

    private fun updateActiveCard() {
        val name = eqPrefs.getAutoEqName()
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
                    activeSource.text = "by ${eqPrefs.getAutoEqSource()}"
                    updateActiveGraph()
                    activeCard.animate().alpha(1f).setDuration(120).start()
                }.start()
            } else {
                activeName.text = name
                activeSource.text = "by ${eqPrefs.getAutoEqSource()}"
                updateActiveGraph()
                activeCard.alpha = 0f
                activeCard.visibility = View.VISIBLE
                activeCard.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    private fun updateActiveGraph() {
        val container = findViewById<android.widget.FrameLayout>(R.id.autoEqActiveGraph)
        container.removeAllViews()
        var profile = lastAppliedProfile
        if (profile == null) {
            // Reload from database/imports on cold start
            val name = eqPrefs.getAutoEqName() ?: return
            val source = eqPrefs.getAutoEqSource() ?: ""
            profile = if (source == "Imported") {
                val text = eqPrefs.getImportedPresetText(name)
                if (text != null) AutoEqParser.parse(text) else null
            } else {
                val entries = database.search(name)
                val entry = entries.firstOrNull { it.name == name && it.source == source }
                    ?: entries.firstOrNull { it.name == name }
                if (entry != null) database.loadProfile(entry) else null
            }
            lastAppliedProfile = profile
        }
        if (profile != null) {
            val view = MiniEqView(this)
            view.setProfile(profile)
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

    private class HeadphoneAdapter(
        private val onItemClick: (AutoEqEntry) -> Unit,
        private val onDeleteClick: (AutoEqEntry) -> Unit,
        private val profileLoader: (AutoEqEntry) -> AutoEqProfile?
    ) : RecyclerView.Adapter<HeadphoneAdapter.ViewHolder>() {

        private var items = listOf<AutoEqEntry>()
        private val profileCache = HashMap<String, AutoEqProfile?>()

        fun submitList(list: List<AutoEqEntry>) {
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
            // Left: text column
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

            // Right: mini EQ thumbnail + filter count
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
            val thumbView = MiniEqView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(thumbW, thumbH)
            }
            val filterText = TextView(ctx).apply {
                setTextColor(0xFF888888.toInt()); textSize = 10f
                gravity = android.view.Gravity.CENTER
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
            rightCol.addView(filterText)
            row.addView(rightCol)

            return ViewHolder(row, text1, text2, thumbView, filterText, deleteBtn)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.text1.text = entry.name
            val parts = mutableListOf<String>()
            parts.add(entry.source)
            if (entry.rig.isNotBlank()) parts.add(entry.rig)
            holder.text2.text = parts.joinToString(" \u00B7 ")
            holder.itemView.setOnClickListener { onItemClick(entry) }

            val isImported = entry.source == "Imported"
            holder.deleteBtn.visibility = if (isImported) View.VISIBLE else View.GONE
            holder.deleteBtn.setOnClickListener { onDeleteClick(entry) }

            // Load profile for thumbnail (cached)
            val cacheKey = entry.path.ifEmpty { entry.name }
            val profile = profileCache.getOrPut(cacheKey) { profileLoader(entry) }
            holder.filterText.text = "${profile?.filters?.size ?: "?"} filters"
            holder.thumbView.setProfile(profile)
        }

        class ViewHolder(
            view: View,
            val text1: TextView,
            val text2: TextView,
            val thumbView: MiniEqView,
            val filterText: TextView,
            val deleteBtn: android.widget.TextView
        ) : RecyclerView.ViewHolder(view)
    }

    private class MiniEqView(context: android.content.Context) : View(context) {
        private var profile: AutoEqProfile? = null
        private val curvePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            strokeWidth = 0.5f * context.resources.displayMetrics.density
            style = android.graphics.Paint.Style.STROKE
        }
        private val gridPaint = android.graphics.Paint().apply {
            color = 0xFF6A6A6A.toInt(); strokeWidth = 1f
        }

        fun setProfile(p: AutoEqProfile?) {
            profile = p
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            canvas.drawLine(0f, 0f, 0f, h, gridPaint)

            val prof = profile ?: return
            val eq = ParametricEqualizer()
            eq.clearBands()
            for (f in prof.filters) {
                val ft = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(f.filterType)
                eq.addBand(f.frequency, f.gain, ft, f.q.toDouble())
            }
            val path = android.graphics.Path()
            val maxDb = 15f; val steps = 50
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
