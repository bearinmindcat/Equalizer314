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
            val fileName = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "APO Import"
            eqPrefs.addImportedPreset(fileName, text)
            Toast.makeText(this, "Imported: $fileName", Toast.LENGTH_SHORT).show()
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

        adapter = HeadphoneAdapter { entry -> onHeadphoneSelected(entry) }
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
        Toast.makeText(this, "Applied: ${entry.name}", Toast.LENGTH_SHORT).show()
        updateActiveCard()
    }

    private fun applyProfile(entry: AutoEqEntry, profile: AutoEqProfile) {
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
        Toast.makeText(this, "AutoEQ cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateActiveCard() {
        val name = eqPrefs.getAutoEqName()
        if (name.isNullOrBlank()) {
            activeCard.visibility = View.GONE
        } else {
            activeCard.visibility = View.VISIBLE
            activeName.text = name
            activeSource.text = "by ${eqPrefs.getAutoEqSource()}"
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    // ---- RecyclerView Adapter ----

    private class HeadphoneAdapter(
        private val onItemClick: (AutoEqEntry) -> Unit
    ) : RecyclerView.Adapter<HeadphoneAdapter.ViewHolder>() {

        private var items = listOf<AutoEqEntry>()

        fun submitList(list: List<AutoEqEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.text1.text = entry.name
            val parts = mutableListOf<String>()
            parts.add("Source: ${entry.source}")
            if (entry.rig.isNotBlank()) parts.add("Rig: ${entry.rig}")
            parts.add("10 filters")
            holder.text2.text = parts.joinToString(" \u00B7 ")
            holder.text1.setTextColor(0xFFE2E2E2.toInt())
            holder.text2.setTextColor(0xFF888888.toInt())
            holder.text2.textSize = 12f
            holder.itemView.setOnClickListener { onItemClick(entry) }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text1: TextView = view.findViewById(android.R.id.text1)
            val text2: TextView = view.findViewById(android.R.id.text2)
        }
    }
}
