package com.bearinmind.equalizer314

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whole-app backup / restore. Serializes every SharedPreferences file the
 * app uses — settings, the custom-preset pool, and the device/app bindings
 * — into a single JSON document the user can save and re-import on a fresh
 * install (e.g. when switching the APK source, which wipes app data).
 *
 * Each value carries a one-char type tag so it round-trips back to the
 * exact SharedPreferences type. Adding a new prefs file later means adding
 * it to [PREF_FILES] (old backups simply won't contain it — restore skips
 * missing files).
 */
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

    /** Returns true if the document looked like a valid backup and was
     *  applied. The caller should reload UI/state afterwards (the simplest
     *  is to recreate the activity). */
    fun importAll(context: Context, json: String): Boolean {
        val root = try { JSONObject(json) } catch (_: Exception) { return false }
        // Sanity check — a real backup always carries the settings or the
        // preset pool. Guards against importing an arbitrary JSON file.
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
                    "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
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
        return true
    }
}
