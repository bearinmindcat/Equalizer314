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
                val fileName = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Custom Import"
                eqPrefs.saveSelectedTarget("__custom__")
                eqPrefs.saveSelectedTargetName(fileName)
                eqPrefs.saveSelectedTargetType("Imported")
                eqPrefs.addImportedTarget(fileName)
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

        adapter = TargetAdapter { entry -> onTargetSelected(entry) }
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
                    activeCard.animate().alpha(1f).setDuration(120).start()
                }.start()
            } else {
                activeName.text = name
                activeType.text = eqPrefs.getSelectedTargetType() ?: ""
                activeCard.alpha = 0f
                activeCard.visibility = android.view.View.VISIBLE
                activeCard.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    // ---- RecyclerView Adapter ----

    private class TargetAdapter(
        private val onItemClick: (TargetEntry) -> Unit
    ) : RecyclerView.Adapter<TargetAdapter.ViewHolder>() {

        private var items = listOf<TargetEntry>()

        fun submitList(list: List<TargetEntry>) {
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
            holder.text2.text = entry.type
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
