package com.bearinmind.equalizer314.autoeq

import android.content.Context
import org.json.JSONArray

class AutoEqDatabase(private val context: Context) {

    private var entries: List<AutoEqEntry>? = null

    private fun ensureLoaded(): List<AutoEqEntry> {
        entries?.let { return it }
        val loaded = mutableListOf<AutoEqEntry>()
        try {
            val json = context.assets.open("autoeq/index.json").bufferedReader().readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                loaded.add(
                    AutoEqEntry(
                        name = obj.getString("n"),
                        source = obj.getString("s"),
                        type = obj.getString("t"),
                        rig = obj.optString("r", ""),
                        path = obj.getString("p")
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoEqDB", "Failed to load index", e)
        }
        entries = loaded
        return loaded
    }

    fun search(query: String): List<AutoEqEntry> {
        val all = ensureLoaded()
        if (query.isBlank()) return all

        val q = query.trim().lowercase()
        val words = q.split("\\s+".toRegex())

        return all
            .filter { entry ->
                val name = entry.name.lowercase()
                words.all { word -> name.contains(word) }
            }
            .sortedWith(compareBy(
                { !it.name.lowercase().startsWith(q) },
                { it.name.lowercase().indexOf(q).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } },
                { it.name.lowercase() }
            ))
    }

    fun loadProfile(entry: AutoEqEntry): AutoEqProfile? {
        return try {
            val text = context.assets.open("autoeq/profiles/${entry.path}").bufferedReader().readText()
            AutoEqParser.parse(text)
        } catch (e: Exception) {
            android.util.Log.e("AutoEqDB", "Failed to load profile: ${entry.path}", e)
            null
        }
    }

    fun totalCount(): Int = ensureLoaded().size
}
