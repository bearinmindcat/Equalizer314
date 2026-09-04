package com.bearinmind.equalizer314

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Whole-app backup/restore: every prefs file in [PREF_FILES] round-trips through one JSON with per-value type tags. */
object BackupManager {
    const val BACKUP_VERSION = 1
    private val PREF_FILES = listOf(
        "eq_settings",      // app state, simple/advanced settings, theme, etc.
        "custom_presets",   // the shared custom-preset pool
        "device_bindings",  // per-output-device preset bindings
        "app_bindings",     // per-app session bindings
    )

    fun exportAll(context: Context): String {
        val root = JSONObject()
        root.put("app", "Equalizer314")
        root.put("backupVersion", BACKUP_VERSION)
        for (file in PREF_FILES) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val fileObj = JSONObject()
            for ((key, value) in prefs.all) {
                val entry = JSONObject()
                when (value) {
                    is String -> { entry.put("t", "s"); entry.put("v", value) }
                    is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                    is Int -> { entry.put("t", "i"); entry.put("v", value) }
                    is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                    is Long -> { entry.put("t", "l"); entry.put("v", value) }
                    is Set<*> -> {
                        entry.put("t", "ss")
                        val arr = JSONArray()
                        value.forEach { arr.put(it.toString()) }
                        entry.put("v", arr)
                    }
                    else -> continue
                }
                fileObj.put(key, entry)
            }
            root.put(file, fileObj)
        }
        return root.toString(2)
    }

    /** True when the document was a valid backup and got applied; the caller reloads UI/state afterwards. */
    fun importAll(context: Context, json: String): Boolean {
        val root = try { JSONObject(json) } catch (_: Exception) { return false }
        // A real backup always carries the settings or the preset pool — rejects arbitrary JSON.
        if (!root.has("eq_settings") && !root.has("custom_presets")) return false

        for (file in PREF_FILES) {
            val fileObj = root.optJSONObject(file) ?: continue
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            editor.clear()
            val keys = fileObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = fileObj.optJSONObject(key) ?: continue
                when (entry.optString("t")) {
                    "s" -> editor.putString(key, entry.optString("v"))
                    "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "i" -> editor.putInt(key, entry.optInt("v"))
                    "f" -> entry.optDouble("v").takeIf { !it.isNaN() && !it.isInfinite() }
                        ?.let { editor.putFloat(key, it.toFloat()) }
                    "l" -> editor.putLong(key, entry.optLong("v"))
                    "ss" -> {
                        val arr = entry.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()
        }
        // Old backups carry values the current build never writes itself — clamp them to the slider ranges.
        com.bearinmind.equalizer314.state.EqPreferencesManager(context).sanitizeDspSettings()
        return true
    }
}
