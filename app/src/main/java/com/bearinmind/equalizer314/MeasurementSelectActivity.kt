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
                val fileName = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Measurement"
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

        adapter = MeasAdapter { entry -> onMeasSelected(entry) }
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
                    activeCard.animate().alpha(1f).setDuration(120).start()
                }.start()
            } else {
                activeName.text = name
                activeInfo.text = eqPrefs.getSelectedMeasurementInfo() ?: ""
                activeCard.alpha = 0f
                activeCard.visibility = View.VISIBLE
                activeCard.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    // ---- Adapter ----

    private class MeasAdapter(
        private val onItemClick: (MeasEntry) -> Unit
    ) : RecyclerView.Adapter<MeasAdapter.ViewHolder>() {

        private var items = listOf<MeasEntry>()

        fun submitList(list: List<MeasEntry>) {
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
            holder.text2.text = entry.info
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
