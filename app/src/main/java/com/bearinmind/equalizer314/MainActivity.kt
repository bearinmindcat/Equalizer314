package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.ui.BandToggleManager
import com.bearinmind.equalizer314.ui.EqGraphView
import com.bearinmind.equalizer314.ui.GraphicEqController
import com.bearinmind.equalizer314.ui.SimpleEqController
import com.bearinmind.equalizer314.ui.TableEqController

import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlin.math.pow

class  MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Equalizer314"
    }

    // State manager
    private lateinit var stateManager: EqStateManager
    private lateinit var eqPrefs: EqPreferencesManager

    private var pendingExportText: String? = null
    // Once-per-process POST_NOTIFICATIONS gate.
    private var notificationPermissionRequested = false
    private val presetExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val text = pendingExportText ?: return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                android.widget.Toast.makeText(this, "Exported successfully", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            pendingExportText = null
        }
    }

    internal fun launchPresetExport(text: String, fileName: String, mimeType: String = "text/plain") {
        pendingExportText = text
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(android.content.Intent.EXTRA_TITLE, fileName)
        }
        presetExportLauncher.launch(intent)
    }

    // ---- Preset import ----
    private var refreshPresetPicker: (() -> Unit)? = null

    private val presetImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("empty file")
            var fileName = "Imported"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) fileName = c.getString(idx) ?: fileName
            }
            fileName = fileName.substringBeforeLast('.')
            val presetJson = com.bearinmind.equalizer314.state.PresetFileIo.parseImportedPreset(text)
                ?: throw Exception("unrecognized preset format")
            val name = com.bearinmind.equalizer314.state.PresetFileIo.uniquePresetName(
                this, presetJson.optString("presetName").ifBlank { fileName })
            presetJson.remove("presetName")
            com.bearinmind.equalizer314.state.PresetFileIo.saveUserPreset(this, name, presetJson)
            refreshPresetPicker?.invoke()
            android.widget.Toast.makeText(this, "Imported \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Import failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Clean APO text for a preset (EQ only — the chain travels via the .json export). */
    private fun buildApoExportText(obj: org.json.JSONObject): String {
        val sb = StringBuilder()
        // Locale.US: comma-decimal locales exported "Q 0,71", which APO and our own importer can't read.
        sb.append("Preamp: ${String.format(java.util.Locale.US, "%.1f", obj.optDouble("preamp", 0.0))} dB\n")
        fun appendFilters(bands: org.json.JSONArray, indexOffset: Int = 0) {
            for (i in 0 until bands.length()) {
                val b = bands.getJSONObject(i)
                // FilterType → APO token. BP/NO/AP/LP/HP have no Gain; the 6 dB shelves and 1st-order LP/HP have no Q.
                val apoType: String
                val hasGain: Boolean
                val hasQ: Boolean
                when (b.getString("filterType")) {
                    "BELL"         -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                    "LOW_SHELF"    -> { apoType = "LSC"; hasGain = true;  hasQ = true  }
                    "HIGH_SHELF"   -> { apoType = "HSC"; hasGain = true;  hasQ = true  }
                    "LOW_PASS"     -> { apoType = "LPQ"; hasGain = false; hasQ = true  }
                    "HIGH_PASS"    -> { apoType = "HPQ"; hasGain = false; hasQ = true  }
                    "LOW_SHELF_1"  -> { apoType = "LS 6dB"; hasGain = true; hasQ = false }
                    "HIGH_SHELF_1" -> { apoType = "HS 6dB"; hasGain = true; hasQ = false }
                    "LOW_PASS_1"   -> { apoType = "LP";  hasGain = false; hasQ = false }
                    "HIGH_PASS_1"  -> { apoType = "HP";  hasGain = false; hasQ = false }
                    "BAND_PASS"    -> { apoType = "BP";  hasGain = false; hasQ = true  }
                    "NOTCH"        -> { apoType = "NO";  hasGain = false; hasQ = true  }
                    "ALL_PASS"     -> { apoType = "AP";  hasGain = false; hasQ = true  }
                    else           -> { apoType = "PK";  hasGain = true;  hasQ = true  }
                }
                val line = StringBuilder("Filter ${i + 1 + indexOffset}: ON $apoType Fc ${b.getDouble("frequency").toInt()} Hz")
                if (hasGain) line.append(" Gain ${String.format(java.util.Locale.US, "%.1f", b.getDouble("gain"))} dB")
                if (hasQ) line.append(" Q ${String.format(java.util.Locale.US, "%.2f", b.getDouble("q"))}")
                sb.append(line).append('\n')
            }
        }
        val cseOn = obj.optBoolean("channelSideEqEnabled", false)
        if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
            val leftArr = obj.getJSONArray("leftBands")
            sb.append("Channel: L\n")
            appendFilters(leftArr)
            sb.append("Channel: R\n")
            appendFilters(obj.getJSONArray("rightBands"), indexOffset = leftArr.length())
        } else {
            appendFilters(obj.getJSONArray("bands"))
        }
        return sb.toString()
    }

    /** Export format popup (issue #78): clean APO .txt, or full native .json incl. MBC & limiter. */
    private fun showExportFormatDialog(name: String, presetJson: String) {
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        dialogView.addView(android.widget.TextView(this).apply {
            text = "Export \"$name\""
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        dialogView.addView(android.widget.TextView(this).apply {
            text = "EqualizerAPO (.txt)\nEqualizer314 (.json)"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
            setPadding(0, 0, 0, (16 * density).toInt())
        })
        dialogView.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { bottomMargin = (12 * density).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        })
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun formatBtn(label: String) =
            com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt()
                setTextColor(0xFFDDDDDD.toInt())
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000)
                insetTop = 0; insetBottom = 0
            }
        val apoBtn = formatBtn("EqualizerAPO")
        val nativeBtn = formatBtn("Equalizer314")
        btnRow.addView(apoBtn); btnRow.addView(nativeBtn)
        dialogView.addView(btnRow)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(dialogView).create()
        apoBtn.setOnClickListener {
            dialog.dismiss()
            launchPresetExport(buildApoExportText(org.json.JSONObject(presetJson)), "$name.txt")
        }
        nativeBtn.setOnClickListener {
            dialog.dismiss()
            val json = org.json.JSONObject(presetJson).put("presetName", name)
            launchPresetExport(json.toString(2), "$name.json", "application/json")
        }
        dialog.show()
    }

    private fun showPickerDeleteDialog(name: String, onConfirm: () -> Unit) {
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        dialogView.addView(android.widget.TextView(this).apply {
            text = "Delete"
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        dialogView.addView(android.widget.TextView(this).apply {
            text = "Delete \"$name\"?"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 14f
            setPadding(0, 0, 0, (16 * density).toInt())
        })
        dialogView.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        })
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun dlgBtn(label: String, color: Int, endMargin: Boolean) =
            com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (endMargin) marginEnd = (3 * density).toInt() else marginStart = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt(); setTextColor(color)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000); insetTop = 0; insetBottom = 0
            }
        val deleteBtn = dlgBtn("Delete", 0xFFEF9A9A.toInt(), endMargin = true)
        val cancelBtn = dlgBtn("Cancel", 0xFFDDDDDD.toInt(), endMargin = false)
        btnRow.addView(deleteBtn); btnRow.addView(cancelBtn)
        dialogView.addView(btnRow)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(dialogView).create()
        deleteBtn.setOnClickListener { dialog.dismiss(); onConfirm() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }




    // ---- Whole-app backup / restore ----
    private val backupExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val json = BackupManager.exportAll(this)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                android.widget.Toast.makeText(this, "Backup saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Backup failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (text != null && BackupManager.importAll(this, text)) {
                android.widget.Toast.makeText(this, "Backup restored", android.widget.Toast.LENGTH_SHORT).show()
                // Rebuild the live DSP from the restored settings before the UI reloads.
                startService(Intent(this, EqService::class.java).setAction(EqService.ACTION_RELOAD_PREFS))
                // Re-apply the restored theme choice, then recreate so all screens, presets, and bindings reload from the new prefs.
                val light = getSharedPreferences("eq_settings", MODE_PRIVATE).getBoolean("lightTheme", false)
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (light) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                )
                recreate()
            } else {
                android.widget.Toast.makeText(this, "Not a valid Equalizer314 backup", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Restore failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchBackupExport() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, "Equalizer314-backup.json")
        }
        backupExportLauncher.launch(intent)
    }

    private fun showBackupRestoreDialog() {
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        val title = android.widget.TextView(this).apply {
            text = "Backup & Restore"
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val msg = android.widget.TextView(this).apply {
            text = "Export or import settings, presets & bindings to a .json ; importing will override all current settings in the app"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { bottomMargin = (12 * density).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        }
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun outlinedBtn(label: String, textColor: Int) =
            com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (3 * density).toInt(); marginStart = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt()
                setTextColor(textColor)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000)
                insetTop = 0; insetBottom = 0
            }
        val importBtn = outlinedBtn("Import", 0xFFDDDDDD.toInt())
        val exportBtn = outlinedBtn("Export", 0xFFDDDDDD.toInt())
        btnRow.addView(importBtn)
        btnRow.addView(exportBtn)
        dialogView.addView(title)
        dialogView.addView(msg)
        dialogView.addView(divider)
        dialogView.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(dialogView).create()
        exportBtn.setOnClickListener { dialog.dismiss(); launchBackupExport() }
        importBtn.setOnClickListener { dialog.dismiss(); backupImportLauncher.launch("application/json") }
        dialog.show()
    }

    // UI controllers
    private lateinit var graphicController: GraphicEqController
    private lateinit var tableController: TableEqController
    private lateinit var simpleEqController: SimpleEqController
    private lateinit var bandToggleManager: BandToggleManager
    private val visualizerHelper = com.bearinmind.equalizer314.audio.VisualizerHelper()

    private fun reloadEqFromPrefs() {
        stateManager.initEq(eqGraphView)
        stateManager.preampGainDb = eqPrefs.getPreampGain()
        stateManager.preampLeftDb = eqPrefs.getPreampLeft()
        stateManager.preampRightDb = eqPrefs.getPreampRight()
        preampSlider.value = stateManager.getActivePreamp().coerceIn(-preampRange(), preampRange())
        preampText.setText(String.format("%.1f", stateManager.getActivePreamp()))
        eqGraphView.updateBandLevels()
        bandToggleManager.setupToggles()
        stateManager.pushEqUpdate()
        val preset = eqPrefs.getPresetName()
        presetDropdown.setText(preset, false)
        updateAutoEqStatus()
        // Re-apply current mode visibility (toggles may have been rebuilt)
        if (stateManager.currentEqUiMode == EqUiMode.TABLE) {
            bandToggleGroup.visibility = View.GONE
            bandToggleGroup2.visibility = View.GONE
            tableController.buildTable()
        }
    }

    private val autoEqLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) handlePresetReturn()
    }

    private val targetCurveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) handlePresetReturn()
    }

    // PresetsConversionsActivity returns RESULT_OK when a preset was applied there.
    private val presetsConversionsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) handlePresetReturn()
    }

    /** Common refresh for AutoEQ / TargetCurve preset returns: rebind graph + audio to the new active EQ. */
    private fun handlePresetReturn() {
        // Reset stale band selection — if CSE was on, selectedBandIndex may point past the end of the new bothEq.
        stateManager.selectedBandIndex = 0
        reloadEqFromPrefs()
        rebindActiveEq()
        if (stateManager.isProcessing) {
            val (lEq, rEq) = stateManager.getChannelEqs()
            stateManager.eqService?.let { svc ->
                svc.dynamicsManager.stop()
                svc.dynamicsManager.start(stateManager.parametricEq)
                svc.updateEqPerChannel(lEq, rEq)
            }
        }
    }

    private val apoImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val profile = com.bearinmind.equalizer314.autoeq.AutoEqParser.parse(text)
            if (profile == null || profile.filters.isEmpty()) {
                android.widget.Toast.makeText(this, "Could not parse APO preset", android.widget.Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            applyParsedProfile(profile, "APO Import", "", "")
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Apply a parsed AutoEQ/APO profile to the live EQ (APO import + the picker's AutoEQ rows). */
    private fun applyParsedProfile(
        profile: com.bearinmind.equalizer314.autoeq.AutoEqProfile,
        presetLabel: String,
        autoEqName: String,
        autoEqSource: String,
    ) {
        try {
            fun toBandSpecs(filters: List<com.bearinmind.equalizer314.autoeq.AutoEqFilter>):
                    List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec> =
                filters.map {
                    com.bearinmind.equalizer314.state.EqStateManager.BandSpec(
                        frequency = it.frequency,
                        gain = it.gain,
                        q = it.q.toDouble(),
                        filterType = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(it.filterType),
                    )
                }

            eqPrefs.savePreampGain(profile.preampDb)
            eqPrefs.savePresetName(presetLabel)
            eqPrefs.saveAutoEqName(autoEqName)
            eqPrefs.saveAutoEqSource(autoEqSource)
            presetDropdown.setText(presetLabel, false)

            if (profile.perChannel) {
                // Fork into L/R editors and flip Channel Side EQ on; filters scoped to both channels land in the shared "Both" layer.
                val leftSpecs = toBandSpecs(profile.leftFilters)
                val rightSpecs = toBandSpecs(profile.rightFilters)
                val sharedSpecs = toBandSpecs(profile.sharedFilters)
                // CSE mode reads the per-side preamps, not the shared one (issue #50).
                eqPrefs.savePreampLeft(profile.preampLeftDb ?: profile.preampDb)
                eqPrefs.savePreampRight(profile.preampRightDb ?: profile.preampDb)
                // bothBands falls back to the combined flat list in case the user later turns CSE off.
                val bothSpecs = toBandSpecs(profile.filters)
                stateManager.applyPresetEqs(
                    cseEnabled = true,
                    bothBands = bothSpecs,
                    leftBands = leftSpecs,
                    rightBands = rightSpecs,
                    sharedBands = sharedSpecs,
                )
                // Persist L as the main "bands" state + L/R under their own keys so the divergence survives a process restart.
                eqPrefs.saveState(stateManager.parametricEq, (0 until stateManager.parametricEq.getBandCount()).toList())
                stateManager.persistLeftRightIfCse()
                if (stateManager.isProcessing) {
                    val (lEq, rEq) = stateManager.getChannelEqs()
                    stateManager.eqService?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.start(stateManager.parametricEq)
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                reloadEqFromPrefs()
                rebindActiveEq()
                refreshChannelPopoutDim()
                android.widget.Toast.makeText(
                    this,
                    "Applied L:${profile.leftFilters.size} R:${profile.rightFilters.size} Both:${profile.sharedFilters.size} filters",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                // Flat single-channel preset — CSE off.
                stateManager.selectedBandIndex = 0
                val specs = toBandSpecs(profile.filters)
                stateManager.applyPresetEqs(
                    cseEnabled = false,
                    bothBands = specs,
                    leftBands = specs,
                    rightBands = specs,
                )
                eqPrefs.saveState(stateManager.parametricEq, (0 until stateManager.parametricEq.getBandCount()).toList())
                reloadEqFromPrefs()
                // Full rebind so graph/toggles/inputs retarget bothEq when CSE was previously on (otherwise UI stays on the old leftEq).
                rebindActiveEq()
                // If DP is running, push the new bothEq to both channels so audio matches the display.
                if (stateManager.isProcessing) {
                    val (lEq, rEq) = stateManager.getChannelEqs()
                    stateManager.eqService?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.start(stateManager.parametricEq)
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                android.widget.Toast.makeText(this, "Applied ${profile.filters.size} filters", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val autoEqDatabase by lazy { com.bearinmind.equalizer314.autoeq.AutoEqDatabase(this) }
    private val autoEqThumbCache = HashMap<String, com.bearinmind.equalizer314.autoeq.AutoEqProfile?>()

    // Views
    private lateinit var eqGraphView: EqGraphView
    private lateinit var eqToggleButton: MaterialButton
    private lateinit var eqPowerToggle: MaterialButton
    private lateinit var presetDropdown: MaterialAutoCompleteTextView
    private lateinit var filterTypeGroup: LinearLayout
    private lateinit var qSlider: Slider
    private lateinit var qValueText: TextView
    private lateinit var qControlGroup: View
    private lateinit var powerButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var modeDescriptionText: TextView
    private lateinit var devicePresetStatusText: TextView
    private lateinit var bandToggleGroup: LinearLayout
    private lateinit var bandToggleGroup2: LinearLayout
    private lateinit var bandToggleExtraRows: LinearLayout
    private lateinit var bandToggleExtraScroll: View
    private lateinit var bandAddButtonRow: LinearLayout
    // Last MAX_BANDS we built the toggle layout for.
    private var lastAppliedMaxBands = 16
    private lateinit var triangleIndicator: View
    private lateinit var bandInputGroup: View
    private lateinit var pageEq: View
    private lateinit var pageSettings: View

    // ---- Graph-header button palette (themed) ----.
    private val isLightUi: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val graphBtnLitBg: Int get() = if (isLightUi) 0xFFADADAD.toInt() else 0xFF555555.toInt()
    private val graphBtnLitStroke: Int get() = if (isLightUi) 0xFF7A7A7A.toInt() else 0xFF888888.toInt()
    private val graphBtnLitContent: Int get() = if (isLightUi) 0xFF252525.toInt() else 0xFFDDDDDD.toInt()
    private val graphBtnDimStroke: Int get() = if (isLightUi) 0xFFBEBEBE.toInt() else 0xFF444444.toInt()
    private val graphBtnDimContent: Int get() = if (isLightUi) 0xFF555555.toInt() else 0xFF888888.toInt()
    // Opaque dim fill matching EqGraphView's canvas background.
    private val graphBtnDimBg: Int get() = com.google.android.material.color.MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorSurfaceVariant, if (isLightUi) 0xFFE4E4E4.toInt() else 0xFF1E1E1E.toInt())
    private lateinit var navSettingsButton: ImageButton
    private lateinit var navPresetsButton: ImageButton
    private lateinit var powerFab: android.widget.ImageButton
    private lateinit var dpBandCountGroup: View
    private lateinit var dpBandCountSlider: Slider
    private lateinit var dpBandCountText: EditText
    private lateinit var bandHzSlider: Slider
    private lateinit var bandDbSlider: Slider
    private lateinit var bandHzInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandDbInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandQInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandQInputLayout: com.google.android.material.textfield.TextInputLayout
    private var isUpdatingInputs = false

    // Settings views
    private lateinit var preampSlider: Slider
    private lateinit var preampText: EditText
    private lateinit var autoGainSwitch: MaterialSwitch
    private lateinit var autoGainOffsetText: TextView
    // Old inline limiter controls removed — now in LimiterActivity

    // EQ UI mode
    private lateinit var modeParametricBtn: MaterialButton
    private lateinit var modeGraphicBtn: MaterialButton
    private lateinit var modeTableBtn: MaterialButton
    private lateinit var modeSimpleBtn: MaterialButton

    // The eye and channel popouts share row-2 cells — only one may be open (L-button tap steal).
    private var closeViewOptionsPopoutInstant: (() -> Unit)? = null
    private var closeChannelPopoutInstant: (() -> Unit)? = null
    private lateinit var parametricControlsCard: View
    private lateinit var hzControlRow: View
    private lateinit var tableEqCard: View
    private lateinit var tableEqRowContainer: LinearLayout
    private lateinit var graphicScrollView: HorizontalScrollView
    private lateinit var graphicSlidersContainer: LinearLayout
    private lateinit var colorSwatchRow: LinearLayout
    private lateinit var simpleEqContainer: LinearLayout
    private lateinit var eqControlsContainer: LinearLayout
    private lateinit var graphCardView: View
    private lateinit var modeSelectorGroup: LinearLayout

    // Hz slider uses logarithmic mapping: slider 0–1000 → 10–20000 Hz
    private val hzLogMin = kotlin.math.log10(10f)
    private val hzLogMax = kotlin.math.log10(20000f)

    /** Fires when EqService starts DP from a headless context (currently only the QS tile). */
    private val eqStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stateManager.isProcessing = true
            animatePowerFab(true)
            com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this@MainActivity, true)
            // Toast fired by EqService (surfaces even with MainActivity dead) — don't double up.
            if (!stateManager.serviceBound) {
                try {
                    bindService(
                        Intent(this@MainActivity, EqService::class.java),
                        stateManager.serviceConnection,
                        BIND_AUTO_CREATE,
                    )
                } catch (_: Throwable) {}
            }
            updateDevicePresetStatus()
            // TV Mode: sync power to peer.
            com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        }
    }

    private val eqStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stateManager.isProcessing = false
            stateManager.eqService = null
            if (stateManager.serviceBound) {
                try { unbindService(stateManager.serviceConnection) } catch (_: Exception) {}
                stateManager.serviceBound = false
            }
            animatePowerFab(false)
            // State commit only — toast fired by EqService so it surfaces with the app closed.
            if (intent?.getBooleanExtra(EqService.EXTRA_SILENT_STOP, false) != true) {
                eqPrefs.savePowerState(false)
            }
            com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this@MainActivity, false)
            updateDevicePresetStatus()
            // TV Mode: DP stopped — sync power to the peer.
            com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        }
    }

    /** Visual echo of a settings-triggered DP recycle. */
    private val dpRecycledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            animatePowerFab(false)
            com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this@MainActivity, false)
            eqGraphView.postDelayed({
                if (eqPrefs.getPowerState()) {
                    animatePowerFab(true)
                    com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this@MainActivity, true)
                }
            }, 550)
        }
    }

    /** Refreshes the status line on route/preset broadcasts; a route-applied preset also reloads the graph. */
    private val sessionsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { syncGraphToDrivingAppPreset() }
    }

    private fun parsePresetBands(arr: org.json.JSONArray): List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec> {
        val out = mutableListOf<com.bearinmind.equalizer314.state.EqStateManager.BandSpec>()
        for (i in 0 until arr.length()) {
            val bj = arr.getJSONObject(i)
            val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(bj.getString("filterType")) }
                     catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
            out += com.bearinmind.equalizer314.state.EqStateManager.BandSpec(
                frequency = bj.getDouble("frequency").toFloat(),
                gain = bj.getDouble("gain").toFloat(),
                q = bj.getDouble("q"),
                filterType = ft,
                enabled = if (bj.has("enabled")) bj.getBoolean("enabled") else true,
            )
        }
        return out
    }

    /** Session-based mode: the graph follows the playing bound app's preset (view + edit target). */
    private fun syncGraphToDrivingAppPreset() {
        if (eqPrefs.getAudioRoutingMode() != 1) return
        val name = com.bearinmind.equalizer314.audio.SessionEffectManager.drivingPresetName ?: return
        if (name == eqPrefs.getPresetName()) return
        val json = eqPrefs.getCustomPresetJson(name) ?: return
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { return }
        stateManager.lastPresetApplyMs = System.currentTimeMillis()
        val cseOn = obj.optBoolean("channelSideEqEnabled", false)
        val bothBands = if (obj.has("bands")) parsePresetBands(obj.getJSONArray("bands")) else emptyList()
        val leftBands = if (obj.has("leftBands")) parsePresetBands(obj.getJSONArray("leftBands")) else bothBands
        val rightBands = if (obj.has("rightBands")) parsePresetBands(obj.getJSONArray("rightBands")) else bothBands
        val sharedBands = if (obj.has("sharedBands")) parsePresetBands(obj.getJSONArray("sharedBands")) else null
        stateManager.applyPresetEqs(cseOn, bothBands, leftBands, rightBands, sharedBands)
        stateManager.preampGainDb = obj.optDouble("preamp", 0.0).toFloat()
        stateManager.preampLeftDb = obj.optDouble("preampLeft", 0.0).toFloat()
        stateManager.preampRightDb = obj.optDouble("preampRight", 0.0).toFloat()
        eqPrefs.savePreampGain(stateManager.preampGainDb)
        eqPrefs.savePreampLeft(stateManager.preampLeftDb)
        eqPrefs.savePreampRight(stateManager.preampRightDb)
        eqPrefs.saveState(stateManager.parametricEq)
        stateManager.persistLeftRightIfCse()
        eqPrefs.savePresetName(name)
        presetDropdown.setText(name, false)
        stateManager.initBandSlots()
        rebindActiveEq()
        updateDevicePresetStatus()
    }

    private val statusRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.bearinmind.equalizer314.audio.RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED) {
                // A device-bound preset landed in prefs — pull it into the graph, not just the chip.
                stateManager.lastPresetApplyMs = System.currentTimeMillis()
                reloadEqFromPrefs()
                rebindActiveEq()
                presetDropdown.setText(eqPrefs.getPresetName(), false)
            }
            updateDevicePresetStatus()
        }
    }

    /** Refresh the device/preset status line above the EQ graph. */
    private fun updateDevicePresetStatus() {
        if (!::devicePresetStatusText.isInitialized) return
        val routingMode = eqPrefs.getAudioRoutingMode()
        val activePresetName = eqPrefs.getPresetName()
        val customPresetsPrefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        val isRealPreset = activePresetName.isNotBlank() &&
            customPresetsPrefs.contains("preset_$activePresetName")
        val presetDisplay = if (isRealPreset) activePresetName else "none"
        // Same three-piece logic as EqService.buildNotification's BigText, compacted to one chip line: Mode · Preset · Device.
        val appPreset = stateManager.eqService?.sessionEffects?.getCurrentDrivingPreset()
        val deviceKey = EqService.staticLastDeviceKey
        val deviceBinding = deviceKey?.let { eqPrefs.getDeviceBinding(it) }
        val deviceDrivesPreset = routingMode != 1 &&
            deviceBinding != null &&
            deviceBinding.presetName == activePresetName
        val mode = when {
            routingMode == 1 -> "Session"
            deviceDrivesPreset -> "Device"
            else -> "System"
        }
        val presetForDisplay = when {
            routingMode == 1 -> appPreset ?: "none"
            else -> presetDisplay
        }
        // EqService's static cache keeps the device label after DP off — MainActivity unbinds in stopProcessing.
        val deviceLabel = EqService.staticLastDeviceLabel
        devicePresetStatusText.text = buildString {
            append(mode).append(" · ").append(presetForDisplay)
            if (deviceLabel != null) append(" · ").append(deviceLabel)
        }
        devicePresetStatusText.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        eqPrefs = EqPreferencesManager(this)
        stateManager = EqStateManager(this, eqPrefs)

        // Fresh-launch DP power reconciliation — never blindly overwrite the powerOn pref with the live flag (issue #28).
        if (savedInstanceState == null) {
            val dpAlreadyRunning = EqService.isDpRunning
            val persistedPowerOn = eqPrefs.getPowerState()
            when {
                dpAlreadyRunning -> eqPrefs.savePowerState(true)
                persistedPowerOn -> {
                    val svc = Intent(this, EqService::class.java)
                        .setAction(EqService.ACTION_AUTO_START)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc)
                    } else {
                        startService(svc)
                    }
                }
                else -> eqPrefs.savePowerState(false)
            }
            stateManager.pendingStartEq = false
            // Experimental lock retired (LegacyFeatures.kt); DP band count forced to 128 on fresh launch.
            eqPrefs.saveDpBandCount(128)
        }

        initViews()
        initControllers()
        initEQ()
        syncPreampUI()
        setupListeners()

        // TV Mode (issues #35/#55): register this UI as remote state provider/applier, hook EQ pushes for live sync.
        com.bearinmind.equalizer314.remote.TvRemoteHub.stateProvider = {
            // Preset JSON + nav block for screen-state follow.
            val state = org.json.JSONObject(buildCurrentPresetJson())
            state.put("power", stateManager.isProcessing)
            state.put("nav", org.json.JSONObject().apply {
                put("screen", com.bearinmind.equalizer314.remote.TvRemoteHub.topScreen)
                put("uiMode", stateManager.currentEqUiMode.name)
                put("selectedBand", stateManager.selectedBandIndex ?: -1)
                put("channelView", when {
                    stateManager.bothViewActive -> "BOTH_VIEW"
                    else -> stateManager.activeChannel.name
                })
            })
            state.toString()
        }
        com.bearinmind.equalizer314.remote.TvRemoteHub.stateApplier = { obj -> applyRemotePresetJson(obj) }
        stateManager.onEqPushed = {
            com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
            presetAutosaveHandler.removeCallbacks(presetAutosaveRunnable)
            presetAutosaveHandler.postDelayed(presetAutosaveRunnable, 1500L)
        }
        com.bearinmind.equalizer314.remote.TvRemoteHub.resetModeOnColdStart(this)
        com.bearinmind.equalizer314.remote.TvRemoteHub.onUiReady()
        com.bearinmind.equalizer314.remote.RemoteScrim.setActive(
            (com.bearinmind.equalizer314.remote.TvRemoteHub.server?.connectedRemotes() ?: 0) > 0
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                eqStoppedReceiver,
                IntentFilter(EqService.ACTION_EQ_STOPPED),
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                eqStartedReceiver,
                IntentFilter(EqService.ACTION_EQ_STARTED),
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                statusRefreshReceiver,
                IntentFilter().apply {
                    addAction(com.bearinmind.equalizer314.audio.RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED)
                    addAction(EqService.ACTION_NOTIFICATION_REFRESH)
                },
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                dpRecycledReceiver,
                IntentFilter(EqService.ACTION_DP_RECYCLED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                eqStoppedReceiver,
                IntentFilter(EqService.ACTION_EQ_STOPPED)
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                eqStartedReceiver,
                IntentFilter(EqService.ACTION_EQ_STARTED)
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                statusRefreshReceiver,
                IntentFilter().apply {
                    addAction(com.bearinmind.equalizer314.audio.RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED)
                    addAction(EqService.ACTION_NOTIFICATION_REFRESH)
                }
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                dpRecycledReceiver,
                IntentFilter(EqService.ACTION_DP_RECYCLED)
            )
        }

        val sessionsFilter = IntentFilter(com.bearinmind.equalizer314.audio.SessionEffectManager.ACTION_SESSIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionsChangedReceiver, sessionsFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionsChangedReceiver, sessionsFilter)
        }

        // A saved mode whose tab is disabled lands on Parametric (issue #77).
        val savedMode = try { EqUiMode.valueOf(eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
            .let { if (it == EqUiMode.GRAPHIC && !eqPrefs.getEqModeEnabled("graphic")) EqUiMode.PARAMETRIC else it }
        val launchMode =
            if (eqPrefs.getSimpleEqEnabled() && eqPrefs.getEqModeEnabled("simple")) EqUiMode.SIMPLE else savedMode
        switchEqUiMode(launchMode)
        applyEqModeTabs()
        pageEq.viewTreeObserver.addOnGlobalLayoutListener { fitToViewport() }
        // Ensure rows are properly ordered after views are laid out
        pageEq.post { reorderToggleRows(animate = false) }

        // Reposition header buttons whenever the graph width actually changes (one-shot post{} can fire at a transient width).
        eqGraphView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val w = right - left
            val oldW = oldRight - oldLeft
            if (w > 0 && w != oldW && stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
                // Defer out of the layout traversal — repositioning calls requestLayout.
                eqGraphView.post { relayoutGraphHeaderButtons() }
            }
        }

        // Restore the Settings page across recreation (theme toggle calls setDefaultNightMode, which recreates the activity).
        if (savedInstanceState?.getBoolean("onSettingsPage", false) == true) {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        }

        // DP already running at open (QS tile toggled while the activity was away) — sync the UI.
        if (EqService.isDpRunning) {
            stateManager.isProcessing = true
            animatePowerFab(true)
            com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this, true)
            if (!stateManager.serviceBound) {
                try {
                    bindService(
                        Intent(this, EqService::class.java),
                        stateManager.serviceConnection,
                        BIND_AUTO_CREATE,
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private fun initViews() {
        eqGraphView = findViewById(R.id.eqGraphView)
        eqToggleButton = findViewById(R.id.eqToggleButton)
        eqPowerToggle = findViewById(R.id.eqPowerToggle)
        presetDropdown = findViewById(R.id.presetSpinner)
        filterTypeGroup = findViewById(R.id.filterTypeGroup)
        qSlider = findViewById(R.id.qSlider)
        qValueText = findViewById(R.id.qValueText)
        qControlGroup = findViewById(R.id.qControlGroup)
        powerButton = findViewById(R.id.powerButton)
        statusText = findViewById(R.id.statusText)
        modeDescriptionText = findViewById(R.id.modeDescriptionText)
        devicePresetStatusText = findViewById(R.id.devicePresetStatusText)
        bandToggleGroup = findViewById(R.id.bandToggleGroup)
        bandToggleGroup2 = findViewById(R.id.bandToggleGroup2)
        bandToggleExtraRows = findViewById(R.id.bandToggleExtraRows)
        bandToggleExtraScroll = findViewById(R.id.bandToggleExtraScroll)
        bandAddButtonRow = findViewById(R.id.bandAddButtonRow)
        triangleIndicator = findViewById<View>(R.id.triangleIndicator).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_triangle_up)
        }
        bandInputGroup = findViewById(R.id.bandInputGroup)
        pageEq = findViewById(R.id.pageEq)
        pageSettings = findViewById(R.id.pageSettings)
        navSettingsButton = findViewById(R.id.navSettingsButton)
        navPresetsButton = findViewById(R.id.navPresetsButton)
        powerFab = findViewById(R.id.powerFab)
        dpBandCountGroup = findViewById(R.id.dpBandCountGroup)
        dpBandCountSlider = findViewById(R.id.dpBandCountSlider)
        dpBandCountText = findViewById(R.id.dpBandCountText)
        preampSlider = findViewById(R.id.preampSliderBar)
        preampText = findViewById(R.id.preampTextBar)
        autoGainSwitch = findViewById(R.id.autoGainSwitch)
        autoGainOffsetText = findViewById(R.id.autoGainOffsetText)
        bandHzSlider = findViewById(R.id.bandHzSlider)
        bandDbSlider = findViewById(R.id.bandDbSlider)
        bandHzInput = findViewById(R.id.bandHzInput)
        bandDbInput = findViewById(R.id.bandDbInput)
        bandQInput = findViewById(R.id.bandQInput)
        bandQInputLayout = findViewById(R.id.bandQInputLayout)
        modeParametricBtn = findViewById(R.id.modeParametricBtn)
        modeGraphicBtn = findViewById(R.id.modeGraphicBtn)
        modeTableBtn = findViewById(R.id.modeTableBtn)
        modeSimpleBtn = findViewById(R.id.modeSimpleBtn)
        parametricControlsCard = findViewById(R.id.parametricControlsCard)
        hzControlRow = findViewById(R.id.hzControlRow)
        tableEqCard = findViewById(R.id.tableEqCard)
        tableEqRowContainer = findViewById(R.id.tableEqRowContainer)
        graphicScrollView = findViewById(R.id.graphicScrollView)
        graphicSlidersContainer = findViewById(R.id.graphicSlidersContainer)
        colorSwatchRow = findViewById(R.id.colorSwatchRow)
        simpleEqContainer = findViewById(R.id.simpleEqContainer)
        eqControlsContainer = findViewById(R.id.eqControlsContainer)
        modeSelectorGroup = findViewById(R.id.modeSelectorGroup)
        graphCardView = (eqGraphView.parent as View).parent as View // FrameLayout → MaterialCardView

        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presets)
        presetDropdown.setAdapter(adapter)
        presetDropdown.setText("Flat", false)

        val savedBandCount = eqPrefs.getDpBandCount().coerceIn(128, 1024)
        ParametricToDpConverter.setNumBands(savedBandCount)
        dpBandCountSlider.value = savedBandCount.toFloat()
        dpBandCountText.setText(savedBandCount.toString())

        eqGraphView.showSaturationCurve = false

        updateBottomBarHighlight(isEqPage = true)
    }

    /** Called after initEQ() to sync preamp UI with restored state */
    private fun syncPreampUI() {
        preampSlider.value = stateManager.preampGainDb.coerceIn(-preampRange(), preampRange())
        preampText.setText(String.format("%.1f", stateManager.preampGainDb))
        autoGainSwitch.isChecked = stateManager.autoGainEnabled
        updateAutoGainOffsetText()
    }

    private fun initControllers() {
        val onEqChanged = {
            stateManager.pushEqUpdate()
        }

        val onBandCountChanged = {
            onEqChanged()
            bandToggleManager.updateIcons()
            setupFilterTypeButtons()
            stateManager.selectedBandIndex?.let { updateFilterTypeButtons(it) }
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
            reorderToggleRows()
        }

        val onBandSelected = { bandIndex: Int? ->
            updateFilterTypeButtons(bandIndex)
            updateBandInputs(bandIndex)
            // In graphic mode, rebuild sliders if we switched to a different page
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC && graphicController.updatePageForBand(bandIndex)) {
                graphicController.buildSliders(graphicController.targetCardHeight)
            }
            reorderToggleRows()
            // Refresh the "Both" button's lit state for the newly selected band.
            paintChannelButtonStyles()
        }

        graphicController = GraphicEqController(
            this, graphicSlidersContainer, eqGraphView, stateManager,
            onEqChanged, onBandCountChanged
        )
        graphicController.onColorChanged = {
            bandToggleManager.updateSelection(stateManager.selectedBandIndex)
        }

        tableController = TableEqController(
            this, tableEqRowContainer, eqGraphView, stateManager, onEqChanged
        )

        simpleEqController = SimpleEqController(
            this, simpleEqContainer, stateManager, eqPrefs, onEqChanged
        )

        bandToggleManager = BandToggleManager(
            this, bandToggleGroup, bandToggleGroup2, bandToggleExtraRows, bandToggleExtraScroll, bandAddButtonRow, triangleIndicator, eqGraphView, stateManager,
            onEqChanged, onBandCountChanged, onBandSelected
            // Per-band L/Both/R picker popup retired — see LegacyFeatures.kt.
        )

        // Wire state manager callbacks
        stateManager.onProcessingChanged = { active ->
            animatePowerFab(active)
            // TV Mode: power on/off is part of the synced state.
            com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        }
        stateManager.onServiceConnected = {
            doStartEq()
        }
    }

    private fun initEQ() {
        stateManager.initEq(eqGraphView)

        val savedPreset = eqPrefs.getPresetName()
        presetDropdown.setText(savedPreset, false)

        updateEqToggleUI()
        eqGraphView.updateBandLevels()
        bandToggleManager.setupToggles()

        // Always have a band selected in parametric mode
        if (stateManager.currentEqUiMode == EqUiMode.PARAMETRIC && stateManager.parametricEq.getBandCount() > 0) {
            val defaultBand = stateManager.selectedBandIndex ?: 0
            stateManager.selectedBandIndex = defaultBand
            eqGraphView.setActiveBand(defaultBand)
            updateFilterTypeButtons(defaultBand)
            updateBandInputs(defaultBand)
        }
    }

    private fun setupListeners() {
        // Navigation
        navSettingsButton.setOnClickListener {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        }
        navPresetsButton.setOnClickListener {
            pageEq.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
            updateBottomBarHighlight(isEqPage = true)
            // Graph was GONE (width 0) while Settings showed, so header buttons weren't positioned.
            if (stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
                eqGraphView.doOnLayout { eqGraphView.post { relayoutGraphHeaderButtons() } }
            }
        }
        val navMbcBtn = findViewById<ImageButton>(R.id.navMbcButton)
        val navLimiterBtn = findViewById<ImageButton>(R.id.navLimiterButton)
        navMbcBtn.setOnClickListener {
            startActivity(Intent(this, MbcActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        navLimiterBtn.setOnClickListener {
            startActivity(Intent(this, LimiterActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        powerFab.setOnClickListener {
            if (stateManager.isProcessing) stopProcessing() else startProcessing()
        }

        // Visualizer toggle + Edit + Reset + Undo/Redo + Band points toggle + Save preset
        val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.visualizerToggle)
        val editBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.editButton)
        val resetBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.resetButton)
        // Reset is a destructive action — make its icon red in light mode (dark mode keeps the XML colorOnSurface tint).
        if (isLightUi) resetBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
        val undoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.undoButton)
        val redoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.redoButton)
        val bandPtsBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandPointsToggle)
        val saveBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.savePresetButton)
        val altRouteBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.altRouteButton)
        val settingsGearBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.settingsGearButton)
        val channelLBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelLButton)
        val channelRBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelRButton)
        val channelBothBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelBothButton)
        val vizDensity = resources.displayMetrics.density
        val gapPx = (2 * vizDensity).toInt()
        // post{}, NOT doOnLayout{}: offsets derive from eqGraphView.width.
        eqGraphView.post {
            val viewWidth = eqGraphView.width
            if (viewWidth <= 0) return@post
            val vPadPx = 80
            val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
            val btnTop = gapPx
            val btnBottom = vPadPx - gapPx
            val btnHeight = btnBottom - btnTop
            val specWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)

            // Spectrum button: between 10kHz line and right edge
            val specLeft = gridLine10k + gapPx
            val specLp = vizToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            specLp.width = specWidth
            specLp.height = btnHeight
            specLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            specLp.leftMargin = specLeft
            specLp.topMargin = btnTop
            vizToggle.layoutParams = specLp
            vizToggle.minimumWidth = 0; vizToggle.minimumHeight = 0
            vizToggle.setPadding(0, 0, 0, 0)

            // Edit button: to the left of spectrum
            val editLeft = specLeft - gapPx - specWidth
            val editLp = editBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            editLp.width = specWidth
            editLp.height = btnHeight
            editLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            editLp.leftMargin = editLeft.coerceAtLeast(gapPx)
            editLp.topMargin = btnTop
            editBtn.layoutParams = editLp
            editBtn.minimumWidth = 0; editBtn.minimumHeight = 0
            editBtn.setPadding(0, 0, 0, 0)

            // Reset button: to the left of edit
            val resetLeft = editLeft - gapPx - specWidth
            val resetLp = resetBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            resetLp.width = specWidth
            resetLp.height = btnHeight
            resetLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            resetLp.leftMargin = resetLeft.coerceAtLeast(gapPx)
            resetLp.topMargin = btnTop
            resetBtn.layoutParams = resetLp
            resetBtn.minimumWidth = 0; resetBtn.minimumHeight = 0
            resetBtn.setPadding(0, 0, 0, 0)
            resetBtn.visibility = android.view.View.GONE

            // EQ on/off toggle: occupies the (hidden) reset slot, immediately to the left of the Edit button.
            val eqPowerLp = eqPowerToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            eqPowerLp.width = specWidth
            eqPowerLp.height = btnHeight
            eqPowerLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            eqPowerLp.leftMargin = resetLeft.coerceAtLeast(gapPx)
            eqPowerLp.topMargin = btnTop
            eqPowerToggle.layoutParams = eqPowerLp
            eqPowerToggle.minimumWidth = 0; eqPowerToggle.minimumHeight = 0
            eqPowerToggle.setPadding(0, 0, 0, 0)
            applyEqToggleVisual(stateManager.parametricEq.isEnabled)

            // Undo button: below reset
            val undoLp = undoBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            undoLp.width = specWidth
            undoLp.height = btnHeight
            undoLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            undoLp.leftMargin = resetLeft.coerceAtLeast(gapPx)
            undoLp.topMargin = btnTop + btnHeight + gapPx
            undoBtn.layoutParams = undoLp
            undoBtn.minimumWidth = 0; undoBtn.minimumHeight = 0
            undoBtn.setPadding(0, 0, 0, 0)

            // Redo button: below edit
            val redoLp = redoBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            redoLp.width = specWidth
            redoLp.height = btnHeight
            redoLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            redoLp.leftMargin = editLeft.coerceAtLeast(gapPx)
            redoLp.topMargin = btnTop + btnHeight + gapPx
            redoBtn.layoutParams = redoLp
            redoBtn.minimumWidth = 0; redoBtn.minimumHeight = 0
            redoBtn.setPadding(0, 0, 0, 0)

            // Band points toggle: top-left, same size
            val bpLp = bandPtsBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            bpLp.width = specWidth
            bpLp.height = btnHeight
            bpLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            bpLp.leftMargin = gapPx
            bpLp.topMargin = btnTop
            bandPtsBtn.layoutParams = bpLp
            bandPtsBtn.minimumWidth = 0; bandPtsBtn.minimumHeight = 0
            bandPtsBtn.setPadding(0, 0, 0, 0)

            // View-options popout (revealed by the eye): ON/OFF toggle below the eye, per-band fill toggle below save.
            val onOffBtn0 = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandPointsOnOffBtn)
            val onOffLp = onOffBtn0.layoutParams as android.widget.FrameLayout.LayoutParams
            onOffLp.width = specWidth
            onOffLp.height = btnHeight
            onOffLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            onOffLp.leftMargin = gapPx
            onOffLp.topMargin = btnTop + btnHeight + gapPx
            onOffBtn0.layoutParams = onOffLp
            onOffBtn0.minimumWidth = 0; onOffBtn0.minimumHeight = 0
            onOffBtn0.setPadding(0, 0, 0, 0)

            val fillBtn0 = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandFillToggle)
            val fillLp = fillBtn0.layoutParams as android.widget.FrameLayout.LayoutParams
            fillLp.width = specWidth
            fillLp.height = btnHeight
            fillLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            fillLp.leftMargin = gapPx + specWidth + gapPx
            fillLp.topMargin = btnTop + btnHeight + gapPx
            fillBtn0.layoutParams = fillLp
            fillBtn0.minimumWidth = 0; fillBtn0.minimumHeight = 0
            fillBtn0.setPadding(0, 0, 0, 0)

            val heatBtn0 = findViewById<com.google.android.material.button.MaterialButton>(R.id.gainHeatToggle)
            val heatLp = heatBtn0.layoutParams as android.widget.FrameLayout.LayoutParams
            heatLp.width = specWidth
            heatLp.height = btnHeight
            heatLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            heatLp.leftMargin = gapPx + (specWidth + gapPx) * 2
            heatLp.topMargin = btnTop + btnHeight + gapPx
            heatBtn0.layoutParams = heatLp
            heatBtn0.minimumWidth = 0; heatBtn0.minimumHeight = 0
            heatBtn0.setPadding(0, 0, 0, 0)

            // Save preset button: right next to eye button
            val saveLeft = gapPx + specWidth + gapPx
            val saveLp = saveBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            saveLp.width = specWidth
            saveLp.height = btnHeight
            saveLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            saveLp.leftMargin = saveLeft
            saveLp.topMargin = btnTop
            saveBtn.layoutParams = saveLp
            saveBtn.minimumWidth = 0; saveBtn.minimumHeight = 0
            saveBtn.setPadding(0, 0, 0, 0)

            // Alt-route button: right after save preset button
            val altRouteLeft = saveLeft + specWidth + gapPx
            val altRouteLp = altRouteBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            altRouteLp.width = specWidth
            altRouteLp.height = btnHeight
            altRouteLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            altRouteLp.leftMargin = altRouteLeft
            altRouteLp.topMargin = btnTop
            altRouteBtn.layoutParams = altRouteLp
            altRouteBtn.minimumWidth = 0; altRouteBtn.minimumHeight = 0
            altRouteBtn.setPadding(0, 0, 0, 0)

            // Mini L/R badge over altRouteButton's top-right; translationZ = 16dp.
            val badge = findViewById<android.widget.TextView>(R.id.altRouteChannelBadge)
            badgeAnchorAltRouteLeft = altRouteLeft
            badgeAnchorSpecWidth = specWidth
            badgeAnchorBtnTop = btnTop
            repositionChannelBadge(badge)
            // Force the badge above the MaterialButton's drawing layer.
            badge.translationZ = 16f * vizDensity
            badge.bringToFront()

            // Settings gear button: directly to the right of the alt-route button
            val settingsLeft = altRouteLeft + specWidth + gapPx
            val settingsLp = settingsGearBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            settingsLp.width = specWidth
            settingsLp.height = btnHeight
            settingsLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            settingsLp.leftMargin = settingsLeft
            settingsLp.topMargin = btnTop
            settingsGearBtn.layoutParams = settingsLp
            settingsGearBtn.minimumWidth = 0; settingsGearBtn.minimumHeight = 0
            settingsGearBtn.setPadding(0, 0, 0, 0)

            // L / R popout sit on row 2 directly below alt-route and settings.
            val row2Top = btnTop + btnHeight + gapPx
            val lLeft = altRouteLeft
            val rLeft = settingsLeft

            val lLp = channelLBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            lLp.width = specWidth; lLp.height = btnHeight
            lLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lLp.leftMargin = lLeft; lLp.topMargin = row2Top
            channelLBtn.layoutParams = lLp
            channelLBtn.minimumWidth = 0; channelLBtn.minimumHeight = 0
            channelLBtn.setPadding(0, 0, 0, 0)

            val rLp = channelRBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            rLp.width = specWidth; rLp.height = btnHeight
            rLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            rLp.leftMargin = rLeft; rLp.topMargin = row2Top
            channelRBtn.layoutParams = rLp
            channelRBtn.minimumWidth = 0; channelRBtn.minimumHeight = 0
            channelRBtn.setPadding(0, 0, 0, 0)

            // "Both" sits on row 2 just left of L, same size as the other buttons; the label auto-shrinks to fit the narrow cell.
            val bothLp = channelBothBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            bothLp.width = specWidth; bothLp.height = btnHeight
            bothLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            bothLp.leftMargin = (lLeft - specWidth - gapPx).coerceAtLeast(0)
            bothLp.topMargin = row2Top
            channelBothBtn.layoutParams = bothLp
            channelBothBtn.minimumWidth = 0; channelBothBtn.minimumHeight = 0
            channelBothBtn.setPadding(0, 0, 0, 0)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                channelBothBtn, 6, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )

            refreshChannelPopoutDim()
        }

        // Settings gear starts hidden; the split icon pops it in alongside L / R with the same overshoot animation used by undo.
        settingsGearBtn.visibility = View.GONE

        var channelPopoutOpen = false
        closeChannelPopoutInstant = {
            if (channelPopoutOpen) {
                channelPopoutOpen = false
                for (b in listOf(channelBothBtn, channelLBtn, channelRBtn, settingsGearBtn)) {
                    b.animate().cancel()
                    b.visibility = View.GONE
                    b.alpha = 1f; b.scaleX = 1f; b.scaleY = 1f; b.translationY = 0f
                }
                altRouteBtn.setBackgroundColor(graphBtnDimBg)
                altRouteBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                altRouteBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        altRouteBtn.setOnClickListener {
            channelPopoutOpen = !channelPopoutOpen
            if (channelPopoutOpen) {
                closeViewOptionsPopoutInstant?.invoke()
                val offsetY = -(altRouteBtn.height.toFloat() + gapPx)
                // L/R dim when CSE off, bright when on.
                val lrAlpha = 1.0f
                // Paint pressed/outlined styles first so the buttons fade in already reflecting the active channel.
                paintChannelButtonStyles()
                listOf(channelBothBtn, channelLBtn, channelRBtn, settingsGearBtn).forEach { v ->
                    v.visibility = View.VISIBLE
                    v.alpha = 0f; v.scaleX = 0.3f; v.scaleY = 0.3f; v.translationY = offsetY
                }
                channelBothBtn.animate().alpha(lrAlpha).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                channelLBtn.animate().alpha(lrAlpha).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                channelRBtn.animate().alpha(lrAlpha).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                settingsGearBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(120).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                altRouteBtn.setBackgroundColor(0xFF555555.toInt())
                altRouteBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                altRouteBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                val offsetY = -(altRouteBtn.height.toFloat() + gapPx)
                settingsGearBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { settingsGearBtn.visibility = View.GONE; settingsGearBtn.translationY = 0f }.start()
                channelRBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(40).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { channelRBtn.visibility = View.GONE; channelRBtn.translationY = 0f }.start()
                channelLBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(80).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { channelLBtn.visibility = View.GONE; channelLBtn.translationY = 0f }.start()
                channelBothBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(120).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { channelBothBtn.visibility = View.GONE; channelBothBtn.translationY = 0f }.start()
                altRouteBtn.setBackgroundColor(graphBtnDimBg)
                altRouteBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                altRouteBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }

        // L/R switch which channel the graph edits; dimmed no-op unless CSE is enabled.
        channelLBtn.setOnClickListener {
            if (!eqPrefs.getChannelSideEqEnabled()) return@setOnClickListener
            // Both view parks activeChannel on LEFT — don't short-circuit while it's active.
            if (stateManager.activeChannel == EqStateManager.ActiveChannel.LEFT &&
                !stateManager.bothViewActive) return@setOnClickListener
            stateManager.exitBothView()
            stateManager.setActiveChannel(EqStateManager.ActiveChannel.LEFT)
            rebindActiveEq()
        }
        channelRBtn.setOnClickListener {
            if (!eqPrefs.getChannelSideEqEnabled()) return@setOnClickListener
            if (stateManager.activeChannel == EqStateManager.ActiveChannel.RIGHT &&
                !stateManager.bothViewActive) return@setOnClickListener
            stateManager.exitBothView()
            stateManager.setActiveChannel(EqStateManager.ActiveChannel.RIGHT)
            rebindActiveEq()
        }
        // "Both" view: shows the left curve, edits apply to BOTH channels.
        channelBothBtn.setOnClickListener {
            if (!eqPrefs.getChannelSideEqEnabled()) return@setOnClickListener
            if (stateManager.bothViewActive) stateManager.exitBothView()
            else stateManager.enterBothView()
            rebindActiveEq()
            paintChannelButtonStyles()
        }

        // Power button in the channel popout toggles Channel Side EQ directly.
        settingsGearBtn.setOnClickListener {
            val on = !eqPrefs.getChannelSideEqEnabled()
            stateManager.setChannelSideEqEnabled(on)
            rebindActiveEq()
            paintChannelButtonStyles()
            refreshChannelPopoutDim()
            Toast.makeText(
                this,
                if (on) "Channel Side EQ on" else "Channel Side EQ off",
                Toast.LENGTH_SHORT
            ).show()
        }
        // Save preset button — toggle between controls and preset picker
        val eqControlsContainerLocal = eqControlsContainer as android.view.View
        val presetPickerScroll = findViewById<android.widget.ScrollView>(R.id.presetPickerScroll)
        val presetPickerContainer = findViewById<android.widget.LinearLayout>(R.id.presetPickerContainer)
        var presetPickerOpen = false

        fun populatePresetPicker() {
            presetPickerContainer.removeAllViews()
            val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
            val presetNames = prefs.getStringSet("preset_names", emptySet())?.sorted() ?: emptyList()

            val density = resources.displayMetrics.density

            refreshPresetPicker = { populatePresetPicker() }
            fun pickerActionBtn(label: String, endMargin: Boolean) =
                com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (endMargin) marginEnd = (4 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    textSize = 14f
                    isAllCaps = false
                    val vertPad = (10 * density).toInt()
                    setPadding(0, vertPad, 0, vertPad)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0x00000000)
                    setTextColor(0xFF888888.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
            val actionRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
            }
            fun pickerBtnIcon(btn: com.google.android.material.button.MaterialButton, iconRes: Int) {
                btn.icon = resources.getDrawable(iconRes, theme)
                btn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                btn.iconPadding = (6 * density).toInt()
                btn.iconSize = (18 * density).toInt()
                btn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
            val saveCurrentBtn = pickerActionBtn("New Preset", endMargin = true)
            pickerBtnIcon(saveCurrentBtn, R.drawable.ic_add)
            val importPresetBtn = pickerActionBtn("Import Preset", endMargin = false)
            pickerBtnIcon(importPresetBtn, R.drawable.ic_export)
            importPresetBtn.setOnClickListener { presetImportLauncher.launch("*/*") }
            actionRow.addView(saveCurrentBtn)
            actionRow.addView(importPresetBtn)
            saveCurrentBtn.setOnClickListener {
                // Find next Custom # number
                var nextNum = 1
                for (n in presetNames) {
                    val match = Regex("Custom #(\\d+)").find(n)
                    if (match != null) nextNum = maxOf(nextNum, match.groupValues[1].toInt() + 1)
                }
                // Build custom dialog layout (Launcher314 style: side-by-side Cancel/Save)
                val dialogView = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
                }
                val title = android.widget.TextView(this).apply {
                    text = "Save Custom Preset"
                    setTextColor(0xFFE2E2E2.toInt())
                    textSize = 20f
                    setPadding(0, 0, 0, (12 * density).toInt())
                }
                val inputBox = android.widget.FrameLayout(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (16 * density).toInt()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x00000000)
                        setStroke((1 * density).toInt(), 0xFF555555.toInt())
                        cornerRadius = 12 * density
                    }
                }
                val defaultName = "Custom #$nextNum"
                val input = android.widget.EditText(this).apply {
                    hint = defaultName
                    setTextColor(0xFFFFFFFF.toInt())
                    setHintTextColor(0xFF888888.toInt())
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    background = null
                    val pad = (14 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    isSingleLine = true
                }
                inputBox.addView(input)
                val btnRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val cancelBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Cancel"
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (3 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    setTextColor(0xFFEF9A9A.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                    setBackgroundColor(0x00000000)
                    insetTop = 0; insetBottom = 0
                }
                val saveDialogBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "OK"
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (3 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    setTextColor(0xFFDDDDDD.toInt())
                    setBackgroundColor(0x00000000)
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                    insetTop = 0; insetBottom = 0
                }
                btnRow.addView(cancelBtn)
                btnRow.addView(saveDialogBtn)
                val divider = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                        bottomMargin = (12 * density).toInt()
                    }
                    setBackgroundColor(0xFF444444.toInt())
                }
                dialogView.addView(title)
                dialogView.addView(inputBox)
                dialogView.addView(divider)
                dialogView.addView(btnRow)

                val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
                    .setView(dialogView)
                    .create()
                cancelBtn.setOnClickListener { dialog.dismiss() }
                saveDialogBtn.setOnClickListener {
                    val name = input.text.toString().trim().ifEmpty { defaultName }
                    if (name.isNotEmpty()) {
                        prefs.edit()
                            .putString("preset_$name", buildCurrentPresetJson())
                            .putStringSet("preset_names", (presetNames.toMutableSet() + name))
                            .apply()
                        populatePresetPicker()
                        android.widget.Toast.makeText(this, "Saved \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                dialog.show()
            }
            presetPickerContainer.addView(actionRow)

            val pickerSectionPrefs = getSharedPreferences("eq_settings", MODE_PRIVATE)
            fun collapsibleSection(
                label: String,
                rightText: String?,
                prefKey: String,
                body: android.widget.LinearLayout,
            ): android.view.View {
                val header = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // Baseline alignment shoves the text below center next to the chevron.
                    isBaselineAligned = false
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        // Bottom margin mirrors the 6dp gap above so the ripple clears the card below.
                        setMargins(0, (2 * density).toInt(), 0, (6 * density).toInt())
                    }
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    val hl = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.colorControlHighlight, hl, true)
                    val rippleColor = if (hl.resourceId != 0) resources.getColor(hl.resourceId, theme) else hl.data
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(rippleColor),
                        null,
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 12 * density
                            setColor(0xFFFFFFFF.toInt())
                        }
                    )
                    isClickable = true; isFocusable = true
                }
                val chevron = android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (16 * density).toInt(), (16 * density).toInt()
                    ).apply { marginEnd = (6 * density).toInt() }
                }
                header.addView(chevron)
                header.addView(android.widget.TextView(this).apply {
                    text = label
                    textSize = 12f
                    letterSpacing = 0.08f
                    setTextColor(0xFF888888.toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (rightText != null) header.addView(android.widget.TextView(this).apply {
                    text = rightText
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                })
                var open = pickerSectionPrefs.getBoolean(prefKey, true)
                val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                fun applyOpen(animate: Boolean) {
                    val target = if (open) 90f else 0f
                    if (!animate) {
                        body.visibility = if (open) View.VISIBLE else View.GONE
                        body.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        chevron.rotation = target
                        return
                    }
                    chevron.animate().rotation(target).setDuration(200).setInterpolator(interp).start()
                    if (open) {
                        body.visibility = View.VISIBLE
                        val widthSpec = View.MeasureSpec.makeMeasureSpec(
                            (body.parent as View).width, View.MeasureSpec.EXACTLY)
                        body.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        android.animation.ValueAnimator.ofInt(0, body.measuredHeight).apply {
                            duration = 200; interpolator = interp
                            addUpdateListener { body.layoutParams.height = it.animatedValue as Int; body.requestLayout() }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    body.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                    body.requestLayout()
                                }
                            })
                            start()
                        }
                    } else {
                        android.animation.ValueAnimator.ofInt(body.height, 0).apply {
                            duration = 200; interpolator = interp
                            addUpdateListener { body.layoutParams.height = it.animatedValue as Int; body.requestLayout() }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    body.visibility = View.GONE
                                    body.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                }
                            })
                            start()
                        }
                    }
                }
                applyOpen(false)
                header.setOnClickListener {
                    open = !open
                    pickerSectionPrefs.edit().putBoolean(prefKey, open).apply()
                    applyOpen(true)
                }
                return header
            }
            val userPresetsBody = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            presetPickerContainer.addView(
                collapsibleSection("USER PRESETS", null, "pickerUserPresetsOpen", userPresetsBody))
            presetPickerContainer.addView(userPresetsBody)

            // List saved presets — styled like (+) band buttons
            for (name in presetNames) {
                // Parse preset data for thumbnail
                val presetJson = prefs.getString("preset_$name", null)
                val bandCount = com.bearinmind.equalizer314.state.PresetFileIo.filterCount(presetJson)

                val presetRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, (4 * density).toInt())
                    }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x00000000)
                        setStroke((1 * density).toInt(), 0xFF444444.toInt())
                        cornerRadius = 12 * density
                    }
                    val hPad = (12 * density).toInt()
                    val vPad = (10 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                }

                // Mini EQ curve thumbnail. CSE presets stack two mini graphs (L top, R bottom).
                val thumbW = (48 * density).toInt()
                val thumbH = (24 * density).toInt()
                val thumbnail = object : android.view.View(this) {
                    private fun buildEq(arr: org.json.JSONArray): com.bearinmind.equalizer314.dsp.ParametricEqualizer {
                        val eq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
                        eq.clearBands()
                        for (i in 0 until arr.length()) {
                            val b = arr.getJSONObject(i)
                            val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(b.getString("filterType")) }
                                     catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                            eq.addBand(b.getDouble("frequency").toFloat(), b.getDouble("gain").toFloat(), ft, b.getDouble("q"))
                        }
                        return eq
                    }

                    private fun drawCurve(
                        canvas: android.graphics.Canvas,
                        eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer,
                        x0: Float, y0: Float, w: Float, h: Float,
                        color: Int,
                    ) {
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = color; strokeWidth = 0.5f * density; style = android.graphics.Paint.Style.STROKE
                        }
                        val gridPaint = android.graphics.Paint().apply { this.color = 0xFF6A6A6A.toInt(); strokeWidth = 1f }
                        canvas.drawLine(x0, y0 + h / 2f, x0 + w, y0 + h / 2f, gridPaint)
                        canvas.drawLine(x0, y0, x0, y0 + h, gridPaint)
                        val path = android.graphics.Path()
                        val maxDb = 15f; val steps = 50
                        for (s in 0..steps) {
                            val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
                            val freq = 10f.pow(logF)
                            val db = eq.getFrequencyResponse(freq)
                            val x = x0 + w * s / steps
                            val y = (y0 + h / 2f - (db / maxDb) * (h / 2f)).coerceIn(y0, y0 + h)
                            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, paint)
                    }

                    override fun onDraw(canvas: android.graphics.Canvas) {
                        super.onDraw(canvas)
                        val w = width.toFloat(); val h = height.toFloat()
                        if (w <= 0 || h <= 0 || presetJson == null) return
                        try {
                            val obj = org.json.JSONObject(presetJson)
                            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
                            val curveColor = 0xFFAAAAAA.toInt()
                            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                                // Stacked L/R with divider + 2px gap; same grey as single-curve.
                                val labelCol = 9f * density
                                val gap = 2f * density
                                val halfH = (h - gap) / 2f
                                // Matches the "N filters" text: 10sp, medium grey (#888), not bold.
                                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = 0xFF888888.toInt()
                                    textSize = 10f * resources.displayMetrics.scaledDensity
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                val fm = labelPaint.fontMetrics
                                val textCenterOffset = (-fm.ascent - fm.descent) / 2f
                                // L label + L curve
                                canvas.drawText("L", labelCol / 2f, halfH / 2f + textCenterOffset, labelPaint)
                                drawCurve(
                                    canvas,
                                    buildEq(obj.getJSONArray("leftBands")),
                                    labelCol, 0f, w - labelCol, halfH,
                                    curveColor,
                                )
                                // Divider between the two graphs
                                val dividerPaint = android.graphics.Paint().apply {
                                    color = 0xFF444444.toInt()
                                    strokeWidth = 1f
                                }
                                canvas.drawLine(0f, halfH + gap / 2f, w, halfH + gap / 2f, dividerPaint)
                                // R label + R curve
                                val rTop = halfH + gap
                                canvas.drawText("R", labelCol / 2f, rTop + halfH / 2f + textCenterOffset, labelPaint)
                                drawCurve(
                                    canvas,
                                    buildEq(obj.getJSONArray("rightBands")),
                                    labelCol, rTop, w - labelCol, halfH,
                                    curveColor,
                                )
                            } else {
                                drawCurve(canvas, buildEq(obj.getJSONArray("bands")),
                                    0f, 0f, w, h, curveColor)
                            }
                        } catch (_: Exception) {}
                    }
                }.apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(thumbW, thumbH)
                }

                // Left: preset name over a preamp subtitle ("+8.0 dB" etc.) from the JSON `preamp` field.
                val nameText = android.widget.TextView(this).apply {
                    text = name
                    // Rows sit on the light page surface — name must be dark in light mode (#E2E2E2 was near-invisible there).
                    setTextColor(if (isLightUi) 0xFF202020.toInt() else 0xFFE2E2E2.toInt())
                    textSize = 14f
                    isSingleLine = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                val presetObj: org.json.JSONObject? = try {
                    org.json.JSONObject(presetJson ?: "{}")
                } catch (_: Exception) { null }
                val presetPreamp: Double? = presetObj?.optDouble("preamp", 0.0)
                // Chain tags: preset carries an enabled MBC / limiter (issue #78).
                val chainTags = buildString {
                    if (presetObj?.optJSONObject("mbc")?.optBoolean("enabled", false) == true) append(" · MBC")
                    if (presetObj?.optJSONObject("limiter")?.optBoolean("enabled", false) == true) append(" · LIM")
                }
                val preampSubtitle = android.widget.TextView(this).apply {
                    text = presetPreamp?.let {
                        com.bearinmind.equalizer314.ui.PresetDropdownAdapter.formatPreamp(it) + chainTags
                    } ?: ""
                    setTextColor(0xFF888888.toInt())
                    textSize = 11f
                    isSingleLine = true
                    visibility = if (presetPreamp == null) android.view.View.GONE else android.view.View.VISIBLE
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                val nameCol = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(nameText)
                    addView(preampSubtitle)
                }

                // Right side: graph + filters count stacked vertically
                val rightCol = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (8 * density).toInt()
                    }
                }
                val filtersText = android.widget.TextView(this).apply {
                    text = "$bandCount filters"
                    setTextColor(0xFF888888.toInt())
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                }

                // × delete button
                val deleteBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "×"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * density).toInt(), (36 * density).toInt()).apply {
                        marginStart = (8 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0x00000000)
                    setTextColor(0xFFEF9A9A.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                deleteBtn.setOnClickListener {
                    val d = resources.displayMetrics.density
                    val dlgView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
                    }
                    val dlgTitle = android.widget.TextView(this).apply {
                        text = "Delete"
                        setTextColor(0xFFE2E2E2.toInt())
                        textSize = 20f
                        setPadding(0, 0, 0, (12 * d).toInt())
                    }
                    val dlgMsg = android.widget.TextView(this).apply {
                        text = "Delete preset \"$name\"?"
                        setTextColor(0xFFAAAAAA.toInt())
                        textSize = 14f
                        setPadding(0, 0, 0, (16 * d).toInt())
                    }
                    val dlgDiv = android.view.View(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
                            bottomMargin = (12 * d).toInt()
                        }
                        setBackgroundColor(0xFF444444.toInt())
                    }
                    val dlgBtnRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    val dlgDeleteBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Delete"
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = (3 * d).toInt()
                        }
                        cornerRadius = (12 * d).toInt()
                        setTextColor(0xFFEF9A9A.toInt())
                        strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                        strokeWidth = (1 * d).toInt()
                        setBackgroundColor(0x00000000)
                        insetTop = 0; insetBottom = 0
                    }
                    val dlgCancelBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Cancel"
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = (3 * d).toInt()
                        }
                        cornerRadius = (12 * d).toInt()
                        setTextColor(0xFFDDDDDD.toInt())
                        setBackgroundColor(0x00000000)
                        strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                        strokeWidth = (1 * d).toInt()
                        insetTop = 0; insetBottom = 0
                    }
                    dlgBtnRow.addView(dlgDeleteBtn)
                    dlgBtnRow.addView(dlgCancelBtn)
                    dlgView.addView(dlgTitle)
                    dlgView.addView(dlgMsg)
                    dlgView.addView(dlgDiv)
                    dlgView.addView(dlgBtnRow)
                    val dlg = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
                        .setView(dlgView).create()
                    dlgCancelBtn.setOnClickListener { dlg.dismiss() }
                    dlgDeleteBtn.setOnClickListener {
                        prefs.edit()
                            .remove("preset_$name")
                            .putStringSet("preset_names", (presetNames.toMutableSet() - name))
                            .apply()
                        populatePresetPicker()
                        dlg.dismiss()
                    }
                    dlg.show()
                }
                // Export button
                val exportBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * density).toInt(), (36 * density).toInt()).apply {
                        marginStart = (8 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    setPadding(0, 0, 0, 0)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setBackgroundColor(0x00000000)
                    icon = resources.getDrawable(R.drawable.ic_export_up, theme)
                    iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    iconSize = (18 * density).toInt()
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                exportBtn.setOnClickListener {
                    val presetJson = prefs.getString("preset_$name", null) ?: return@setOnClickListener
                    showExportFormatDialog(name, presetJson)
                }

                // Overwrite button — re-saves the CURRENT EQ into this preset.
                val overwriteBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * density).toInt(), (36 * density).toInt()).apply {
                        marginStart = (8 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    setPadding(0, 0, 0, 0)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setBackgroundColor(0x00000000)
                    icon = resources.getDrawable(R.drawable.ic_save, theme)
                    iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    iconSize = (18 * density).toInt()
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                overwriteBtn.setOnClickListener {
                    val d = resources.displayMetrics.density
                    // Same style as the "+" save dialog, pre-filled with this preset's name.
                    val dlgView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
                    }
                    val dlgTitle = android.widget.TextView(this).apply {
                        text = "Overwrite Custom Preset"
                        setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
                        setPadding(0, 0, 0, (12 * d).toInt())
                    }
                    val dlgInputBox = android.widget.FrameLayout(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = (16 * d).toInt()
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0x00000000)
                            setStroke((1 * d).toInt(), 0xFF555555.toInt())
                            cornerRadius = 12 * d
                        }
                    }
                    val dlgInput = android.widget.EditText(this).apply {
                        setText(name)
                        setTextColor(0xFFFFFFFF.toInt())
                        setHintTextColor(0xFF888888.toInt())
                        inputType = android.text.InputType.TYPE_CLASS_TEXT
                        background = null
                        val pad = (14 * d).toInt()
                        setPadding(pad, pad, pad, pad)
                        isSingleLine = true
                    }
                    dlgInputBox.addView(dlgInput)
                    val dlgDiv = android.view.View(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
                            bottomMargin = (12 * d).toInt()
                        }
                        setBackgroundColor(0xFF444444.toInt())
                    }
                    val dlgBtnRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    val dlgCancelBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Cancel"
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = (3 * d).toInt()
                        }
                        cornerRadius = (12 * d).toInt()
                        setTextColor(0xFFEF9A9A.toInt())
                        strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                        strokeWidth = (1 * d).toInt()
                        setBackgroundColor(0x00000000)
                        insetTop = 0; insetBottom = 0
                    }
                    val dlgOkBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Overwrite"
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = (3 * d).toInt()
                        }
                        cornerRadius = (12 * d).toInt()
                        setTextColor(0xFFDDDDDD.toInt())
                        setBackgroundColor(0x00000000)
                        strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                        strokeWidth = (1 * d).toInt()
                        insetTop = 0; insetBottom = 0
                    }
                    dlgBtnRow.addView(dlgCancelBtn)
                    dlgBtnRow.addView(dlgOkBtn)
                    dlgView.addView(dlgTitle)
                    dlgView.addView(dlgInputBox)
                    dlgView.addView(dlgDiv)
                    dlgView.addView(dlgBtnRow)
                    val dlg = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
                        .setView(dlgView).create()
                    dlgCancelBtn.setOnClickListener { dlg.dismiss() }
                    dlgOkBtn.setOnClickListener {
                        val newName = dlgInput.text.toString().trim().ifEmpty { name }
                        // Same name → in-place overwrite; renamed → saved under new name too.
                        prefs.edit()
                            .putString("preset_$newName", buildCurrentPresetJson())
                            .putStringSet("preset_names", (presetNames.toMutableSet() + newName))
                            .apply()
                        populatePresetPicker()
                        android.widget.Toast.makeText(this, "Updated \"$newName\"", android.widget.Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    }
                    dlg.show()
                }

                rightCol.addView(thumbnail)
                rightCol.addView(filtersText)
                presetRow.addView(nameCol)
                presetRow.addView(rightCol)
                presetRow.addView(overwriteBtn)
                presetRow.addView(exportBtn)
                presetRow.addView(deleteBtn)
                // Tap to load preset
                presetRow.setOnClickListener {
                    val json = prefs.getString("preset_$name", null) ?: return@setOnClickListener
                    val obj = org.json.JSONObject(json)

                    fun parseBands(arr: org.json.JSONArray): List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec> {
                        val out = mutableListOf<com.bearinmind.equalizer314.state.EqStateManager.BandSpec>()
                        for (i in 0 until arr.length()) {
                            val bj = arr.getJSONObject(i)
                            val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(bj.getString("filterType")) }
                                     catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                            out += com.bearinmind.equalizer314.state.EqStateManager.BandSpec(
                                frequency = bj.getDouble("frequency").toFloat(),
                                gain = bj.getDouble("gain").toFloat(),
                                q = bj.getDouble("q"),
                                filterType = ft,
                                enabled = if (bj.has("enabled")) bj.getBoolean("enabled") else true,
                            )
                        }
                        return out
                    }

                    val cseOn = obj.optBoolean("channelSideEqEnabled", false)
                    val bothBands = if (obj.has("bands")) parseBands(obj.getJSONArray("bands")) else emptyList()
                    val leftBands = if (obj.has("leftBands")) parseBands(obj.getJSONArray("leftBands")) else bothBands
                    val rightBands = if (obj.has("rightBands")) parseBands(obj.getJSONArray("rightBands")) else bothBands
                    val sharedBands = if (obj.has("sharedBands")) parseBands(obj.getJSONArray("sharedBands")) else null
                    stateManager.applyPresetEqs(cseOn, bothBands, leftBands, rightBands, sharedBands)
                    // Full-chain presets: apply MBC + limiter when present.
                    com.bearinmind.equalizer314.state.PresetChainIo.applyChain(
                        this, obj, eqPrefs, stateManager.eqService?.dynamicsManager)

                    eqGraphView.setParametricEqualizer(stateManager.parametricEq)
                    stateManager.eqPrefs.saveState(stateManager.parametricEq)
                    stateManager.persistLeftRightIfCse()
                    stateManager.initBandSlots()
                    bandToggleManager.setupToggles()
                    // Ghost curves + shared overlay follow the new preset too, else the old one lingers dotted.
                    stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
                    eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
                    if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
                    // Legacy presets without a preamp field load as 0.0, matching the picker row's "Preamp: 0.0".
                    stateManager.preampGainDb =
                        if (obj.has("preamp")) obj.getDouble("preamp").toFloat() else 0f
                    stateManager.eqPrefs.savePreampGain(stateManager.preampGainDb)
                    // Per-channel preamps ride the preset too (adam's report).
                    stateManager.preampLeftDb = obj.optDouble("preampLeft", 0.0).toFloat()
                    stateManager.preampRightDb = obj.optDouble("preampRight", 0.0).toFloat()
                    eqPrefs.savePreampLeft(stateManager.preampLeftDb)
                    eqPrefs.savePreampRight(stateManager.preampRightDb)
                    // Sync preamp slider/text to the loaded value; pref alone leaves stale UI + stale DP preamp.
                    preampSlider.value = stateManager.preampGainDb.coerceIn(-preampRange(), preampRange())
                    preampText.setText(String.format("%.1f", stateManager.preampGainDb))
                    // Persist the loaded preset's name for the notification and getPresetName() readers.
                    stateManager.eqPrefs.savePresetName(name)
                    // Sync the dropdown too — onPause re-writes savePresetName(presetDropdown.text) and would clobber the pref.
                    presetDropdown.setText(name, false)
                    // Broadcast so EqService rebuilds the notification even with DP off: MainActivity is unbound then.
                    sendBroadcast(
                        Intent(com.bearinmind.equalizer314.audio.EqService.ACTION_NOTIFICATION_REFRESH)
                            .setPackage(packageName)
                    )
                    if (stateManager.isProcessing) {
                        stateManager.eqService?.let { svc ->
                            svc.dynamicsManager.stop()
                            svc.dynamicsManager.start(stateManager.parametricEq)
                        }
                        // Propagate preamp (+ balance / channel gains) into DP's input-gain stage.
                        stateManager.pushEqUpdate()
                    }
                    refreshChannelPopoutDim()
                    // Close picker with animation
                    presetPickerOpen = false
                    eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                    eqControlsContainerLocal.alpha = 0f
                    eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                        presetPickerScroll.visibility = android.view.View.GONE
                        presetPickerScroll.alpha = 1f
                    }.start()
                    saveBtn.setBackgroundColor(graphBtnDimBg)
                    saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                    saveBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnDimContent)
                    android.widget.Toast.makeText(this, "Loaded \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                }
                userPresetsBody.addView(presetRow)
            }

            // ---- AutoEQ presets section ----
            val importedNames = eqPrefs.getImportedPresets()
            val autoEqTotal = autoEqDatabase.totalCount() + importedNames.size
            val autoEqBody = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            presetPickerContainer.addView(
                collapsibleSection("AUTOEQ PRESETS", "$autoEqTotal presets", "pickerAutoEqOpen", autoEqBody))
            presetPickerContainer.addView(autoEqBody)

            val autoEqSearch = android.widget.EditText(this).apply {
                hint = "Search AutoEQ..."
                setTextColor(if (isLightUi) 0xFF202020.toInt() else 0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
                textSize = 14f
                isSingleLine = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((1 * density).toInt(), 0xFF444444.toInt())
                    cornerRadius = 12 * density
                }
                val padH = (12 * density).toInt(); val padV = (10 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
            }
            autoEqBody.addView(autoEqSearch)
            val autoEqResults = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            autoEqBody.addView(autoEqResults)

            var pendingAutoEqEntries: List<com.bearinmind.equalizer314.autoeq.AutoEqEntry> = emptyList()
            var renderedAutoEqCount = 0
            var appendAutoEqBatch: (() -> Unit)? = null
            fun renderAutoEqRows(query: String) {
                autoEqResults.removeAllViews()
                val q = query.trim().lowercase()
                // Favorites first, then imported, then database matches (capped).
                val favs = eqPrefs.getFavoritePresets()
                val favKeys = favs.map { (n, s) -> "$n|$s" }.toHashSet()
                val entries = mutableListOf<com.bearinmind.equalizer314.autoeq.AutoEqEntry>()
                for ((favName, favSource) in favs) {
                    if (q.isNotEmpty() && !favName.lowercase().contains(q)) continue
                    val e = if (favSource == "Imported") {
                        if (importedNames.contains(favName))
                            com.bearinmind.equalizer314.autoeq.AutoEqEntry(favName, "Imported", "", "", "") else null
                    } else {
                        autoEqDatabase.search(favName).firstOrNull { it.name == favName && it.source == favSource }
                    }
                    if (e != null) entries.add(e)
                }
                importedNames
                    .filter { (q.isEmpty() || it.lowercase().contains(q)) && "$it|Imported" !in favKeys }
                    .forEach { entries.add(com.bearinmind.equalizer314.autoeq.AutoEqEntry(it, "Imported", "", "", "")) }
                entries.addAll(autoEqDatabase.search(query).filter { "${it.name}|${it.source}" !in favKeys })

                // All entries render, batched in as the picker scrolls near the bottom.
                pendingAutoEqEntries = entries
                renderedAutoEqCount = 0
                fun addBatch() {
                    val batch = pendingAutoEqEntries.drop(renderedAutoEqCount).take(60)
                    renderedAutoEqCount += batch.size
                    for (entry in batch) {
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 0, 0, (4 * density).toInt())
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0x00000000)
                            setStroke((1 * density).toInt(), 0xFF444444.toInt())
                            cornerRadius = 12 * density
                        }
                        val padH = (12 * density).toInt(); val padV = (10 * density).toInt()
                        setPadding(padH, padV, padH, padV)
                        isClickable = true; isFocusable = true
                    }
                    val nameCol = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    nameCol.addView(android.widget.TextView(this).apply {
                        text = entry.name
                        textSize = 14f
                        isSingleLine = true
                        setTextColor(if (isLightUi) 0xFF202020.toInt() else 0xFFE2E2E2.toInt())
                    })
                    nameCol.addView(android.widget.TextView(this).apply {
                        text = entry.source
                        textSize = 11f
                        isSingleLine = true
                        setTextColor(0xFF888888.toInt())
                    })
                    row.addView(nameCol)
                    val cacheKey = "${entry.source}|${entry.path.ifEmpty { entry.name }}"
                    val entryProfile = autoEqThumbCache.getOrPut(cacheKey) {
                        if (entry.source == "Imported") {
                            eqPrefs.getImportedPresetText(entry.name)
                                ?.let { com.bearinmind.equalizer314.autoeq.AutoEqParser.parse(it) }
                        } else {
                            autoEqDatabase.loadProfile(entry)
                        }
                    }
                    val rightCol = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (8 * density).toInt() }
                    }
                    rightCol.addView(AutoEqActivity.MiniEqView(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (48 * density).toInt(), (24 * density).toInt())
                        setProfile(entryProfile)
                    })
                    rightCol.addView(android.widget.TextView(this).apply {
                        text = "${entryProfile?.filters?.size ?: "?"} filters"
                        setTextColor(0xFF888888.toInt())
                        textSize = 10f
                        gravity = android.view.Gravity.CENTER
                    })
                    row.addView(rightCol)
                    if (entry.source == "Imported") {
                        row.addView(android.widget.TextView(this).apply {
                            text = "×"
                            setTextColor(0xFFEF9A9A.toInt())
                            textSize = 18f
                            gravity = android.view.Gravity.CENTER
                            val btnSize = (30 * density).toInt()
                            layoutParams = android.widget.LinearLayout.LayoutParams(btnSize, btnSize).apply {
                                marginStart = (8 * density).toInt()
                            }
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(0x00000000)
                                setStroke((1 * density).toInt(), 0xFF444444.toInt())
                                cornerRadius = 10 * density
                            }
                            isClickable = true; isFocusable = true
                            contentDescription = "Remove"
                            setOnClickListener {
                                showPickerDeleteDialog(entry.name) {
                                    eqPrefs.removeImportedPreset(entry.name)
                                    autoEqThumbCache.remove(cacheKey)
                                    refreshPresetPicker?.invoke()
                                }
                            }
                        })
                    }
                    val rippleBorderless = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleBorderless, true)
                    row.addView(android.widget.ImageButton(this).apply {
                        val btnSize = (28 * density).toInt()
                        layoutParams = android.widget.LinearLayout.LayoutParams(btnSize, btnSize).apply {
                            marginStart = (8 * density).toInt()
                        }
                        setBackgroundResource(rippleBorderless.resourceId)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        val pad = (4 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                        contentDescription = "Favorite"
                        imageTintList = null
                        setImageResource(
                            if (eqPrefs.isFavoritePreset(entry.name, entry.source)) R.drawable.ic_star_filled
                            else R.drawable.ic_star_outline
                        )
                        setOnClickListener {
                            if (eqPrefs.isFavoritePreset(entry.name, entry.source)) {
                                eqPrefs.removeFavoritePreset(entry.name, entry.source)
                            } else {
                                eqPrefs.addFavoritePreset(entry.name, entry.source)
                            }
                            renderAutoEqRows(query)
                        }
                    })
                    row.setOnClickListener {
                        if (entryProfile == null ||
                            (entryProfile.filters.isEmpty() && entryProfile.leftFilters.isEmpty())) {
                            android.widget.Toast.makeText(this, "Failed to load profile", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            applyParsedProfile(entryProfile, entry.name, entry.name, entry.source)
                        }
                    }
                    autoEqResults.addView(row)
                    }
                }
                appendAutoEqBatch = { addBatch() }
                addBatch()
            }
            renderAutoEqRows("")
            autoEqSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderAutoEqRows(s?.toString() ?: "")
                }
            })
            presetPickerScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val content = presetPickerScroll.getChildAt(0)
                if (content != null && renderedAutoEqCount < pendingAutoEqEntries.size &&
                    scrollY + presetPickerScroll.height >= content.height - (600 * density).toInt()) {
                    appendAutoEqBatch?.invoke()
                }
            }
        }

        // Bound the picker to the viewport below the graph so it scrolls internally instead of growing the page.
        fun boundPresetPickerHeight() {
            pageEq.scrollTo(0, 0)
            presetPickerScroll.post {
                var t = 0
                var v: android.view.View? = presetPickerScroll
                while (v != null && v !== pageEq) {
                    t += v.top
                    v = v.parent as? android.view.View
                }
                val avail = pageEq.height - t
                val lp = presetPickerScroll.layoutParams
                lp.height = if (avail > 0) avail
                            else android.view.ViewGroup.LayoutParams.MATCH_PARENT
                presetPickerScroll.layoutParams = lp
            }
        }

        saveBtn.setOnClickListener {
            presetPickerOpen = !presetPickerOpen
            if (presetPickerOpen) {
                populatePresetPicker()
                boundPresetPickerHeight()
                presetPickerScroll.visibility = android.view.View.VISIBLE
                presetPickerScroll.alpha = 0f
                presetPickerScroll.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                eqControlsContainerLocal.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    eqControlsContainerLocal.visibility = android.view.View.GONE
                    eqControlsContainerLocal.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(graphBtnLitBg)
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnLitContent)
            } else {
                eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                eqControlsContainerLocal.alpha = 0f
                eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    presetPickerScroll.visibility = android.view.View.GONE
                    presetPickerScroll.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(graphBtnDimBg)
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnDimContent)
            }
        }
        // Eye button: opens the view-options popout (ON/OFF visibility + per-band fill toggles).
        var bandPointsVisible = true
        var bandCurvesVisible = false
        var viewOptionsOpen = false
        val onOffBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandPointsOnOffBtn)
        val fillBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandFillToggle)
        val heatBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.gainHeatToggle)

        fun paintLit(btn: com.google.android.material.button.MaterialButton, lit: Boolean) {
            if (lit) {
                btn.setBackgroundColor(graphBtnLitBg)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
                btn.strokeWidth = (2 * vizDensity).toInt()
                btn.setTextColor(graphBtnLitContent)
                btn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnLitContent)
            } else {
                btn.setBackgroundColor(graphBtnDimBg)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                btn.strokeWidth = (1 * vizDensity).toInt()
                btn.setTextColor(graphBtnDimContent)
                btn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnDimContent)
            }
        }
        // Initial state: eye dim (popout closed), points on.
        paintLit(bandPtsBtn, false)
        bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnLitContent)
        onOffBtn.text = "ON"
        paintLit(onOffBtn, true)
        paintLit(fillBtn, false)
        eqGraphView.showGainHeat = eqPrefs.getGraphHeat()
        paintLit(heatBtn, eqGraphView.showGainHeat)
        closeViewOptionsPopoutInstant = {
            if (viewOptionsOpen) {
                viewOptionsOpen = false
                for (b in listOf(onOffBtn, fillBtn, heatBtn)) {
                    b.animate().cancel()
                    b.visibility = android.view.View.GONE
                    b.alpha = 1f; b.scaleX = 1f; b.scaleY = 1f; b.translationY = 0f
                }
                paintLit(bandPtsBtn, false)
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            }
        }

        bandPtsBtn.setOnClickListener {
            viewOptionsOpen = !viewOptionsOpen
            val offsetY = -(bandPtsBtn.height.toFloat() + gapPx)
            if (viewOptionsOpen) {
                closeChannelPopoutInstant?.invoke()
                onOffBtn.visibility = android.view.View.VISIBLE
                fillBtn.visibility = android.view.View.VISIBLE
                heatBtn.visibility = android.view.View.VISIBLE
                onOffBtn.bringToFront(); fillBtn.bringToFront(); heatBtn.bringToFront()
                onOffBtn.alpha = 0f; onOffBtn.scaleX = 0.3f; onOffBtn.scaleY = 0.3f; onOffBtn.translationY = offsetY
                fillBtn.alpha = 0f; fillBtn.scaleX = 0.3f; fillBtn.scaleY = 0.3f; fillBtn.translationY = offsetY
                heatBtn.alpha = 0f; heatBtn.scaleX = 0.3f; heatBtn.scaleY = 0.3f; heatBtn.translationY = offsetY
                onOffBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                fillBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                heatBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                paintLit(bandPtsBtn, true)
            } else {
                heatBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { heatBtn.visibility = android.view.View.GONE; heatBtn.translationY = 0f }.start()
                fillBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(40).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { fillBtn.visibility = android.view.View.GONE; fillBtn.translationY = 0f }.start()
                onOffBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(80).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { onOffBtn.visibility = android.view.View.GONE; onOffBtn.translationY = 0f }.start()
                paintLit(bandPtsBtn, false)
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            }
        }

        // ON/OFF — toggles the band-point dots (the eye's old job).
        onOffBtn.setOnClickListener {
            bandPointsVisible = !bandPointsVisible
            eqGraphView.showBandPoints = bandPointsVisible
            eqGraphView.invalidate()
            onOffBtn.text = if (bandPointsVisible) "ON" else "OFF"
            paintLit(onOffBtn, bandPointsVisible)
        }

        // Fill — toggles the per-band colored curves overlay (issue #40).
        fillBtn.setOnClickListener {
            bandCurvesVisible = !bandCurvesVisible
            eqGraphView.showBandCurves = bandCurvesVisible
            paintLit(fillBtn, bandCurvesVisible)
        }

        heatBtn.setOnClickListener {
            val on = !eqGraphView.showGainHeat
            eqGraphView.showGainHeat = on
            eqPrefs.saveGraphHeat(on)
            paintLit(heatBtn, on)
        }
        // Reset button: reset EQ to flat
        resetBtn.setOnClickListener {
            val density = resources.displayMetrics.density
            val dialogView = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            }
            val title = android.widget.TextView(this).apply {
                text = "Reset"
                setTextColor(0xFFE2E2E2.toInt())
                textSize = 20f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            val message = android.widget.TextView(this).apply {
                text = "Reset all values in this screen to their defaults?"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
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
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val resetDialogBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Reset"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt()
                setTextColor(0xFFEF9A9A.toInt())
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000)
                insetTop = 0; insetBottom = 0
            }
            val cancelBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Cancel"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt()
                setTextColor(0xFFDDDDDD.toInt())
                setBackgroundColor(0x00000000)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                insetTop = 0; insetBottom = 0
            }
            btnRow.addView(resetDialogBtn)
            btnRow.addView(cancelBtn)
            dialogView.addView(title)
            dialogView.addView(message)
            dialogView.addView(divider)
            dialogView.addView(btnRow)

            val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
                .setView(dialogView)
                .create()
            cancelBtn.setOnClickListener { dialog.dismiss() }
            resetDialogBtn.setOnClickListener {
                val eq = stateManager.parametricEq ?: return@setOnClickListener
                eq.clearBands()
                val defaultFreqs = com.bearinmind.equalizer314.dsp.ParametricEqualizer.logSpacedFrequencies(16)
                for (i in 0..3) {
                    eq.addBand(defaultFreqs[i], 0f, com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL)
                }
                eqGraphView.setParametricEqualizer(eq)
                stateManager.eqPrefs.saveState(eq)
                // Reset wipes the shared EQ — drop any stored L/R divergence so re-enabling CSE forks from the fresh defaults.
                stateManager.eqPrefs.clearLeftRightBands()
                stateManager.initBandSlots()
                bandToggleManager.setupToggles()
                if (stateManager.isProcessing) {
                    stateManager.eqService?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.start(eq)
                    }
                }
                android.widget.Toast.makeText(this, "EQ reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.show()
        }
        // Edit mode toggle — undo/redo pop out from edit button position
        var editMode = false
        editBtn.setOnClickListener {
            editMode = !editMode
            val d = resources.displayMetrics.density
            if (editMode) {
                val offsetY = -(editBtn.height.toFloat() + gapPx)

                // Show reset, undo, redo — all pop out from edit button.
                resetBtn.visibility = android.view.View.VISIBLE
                undoBtn.visibility = android.view.View.VISIBLE
                redoBtn.visibility = android.view.View.VISIBLE
                // Reset shares the EQ ON/OFF toggle's slot (left of Edit).
                resetBtn.bringToFront()
                undoBtn.bringToFront()
                redoBtn.bringToFront()
                eqPowerToggle.isClickable = false
                eqPowerToggle.animate().alpha(0f).setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                resetBtn.alpha = 0f; resetBtn.scaleX = 0.3f; resetBtn.scaleY = 0.3f; resetBtn.translationY = offsetY
                undoBtn.alpha = 0f; undoBtn.scaleX = 0.3f; undoBtn.scaleY = 0.3f; undoBtn.translationY = offsetY
                redoBtn.alpha = 0f; redoBtn.scaleX = 0.3f; redoBtn.scaleY = 0.3f; redoBtn.translationY = offsetY

                resetBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                undoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                redoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()

                editBtn.setBackgroundColor(graphBtnLitBg)
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnLitContent)
            } else {
                val offsetY = -(editBtn.height.toFloat() + gapPx)

                redoBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { redoBtn.visibility = android.view.View.GONE; redoBtn.translationY = 0f }.start()
                undoBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(40).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { undoBtn.visibility = android.view.View.GONE; undoBtn.translationY = 0f }.start()
                resetBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(80).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { resetBtn.visibility = android.view.View.GONE; resetBtn.translationY = 0f }.start()

                // Fade the EQ ON/OFF toggle back in as the popouts collapse.
                eqPowerToggle.animate().alpha(1f).setStartDelay(80).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction { eqPowerToggle.isClickable = true }.start()

                editBtn.setBackgroundColor(graphBtnDimBg)
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(graphBtnDimContent)
            }
        }

        // Undo/Redo — EQ state history
        val eqHistory = mutableListOf<String>()
        var historyIndex = -1
        fun saveEqState() {
            val eq = stateManager.parametricEq
            val json = org.json.JSONObject()
            val bands = org.json.JSONArray()
            for (b in eq.getAllBands()) {
                val bj = org.json.JSONObject()
                bj.put("frequency", b.frequency); bj.put("gain", b.gain)
                bj.put("q", b.q); bj.put("filterType", b.filterType.name)
                bj.put("enabled", b.enabled)
                bands.put(bj)
            }
            json.put("bands", bands)
            // Trim future states if we're not at the end
            while (eqHistory.size > historyIndex + 1) eqHistory.removeAt(eqHistory.size - 1)
            eqHistory.add(json.toString())
            historyIndex = eqHistory.size - 1
        }
        fun restoreEqState(jsonStr: String) {
            val eq = stateManager.parametricEq
            val obj = org.json.JSONObject(jsonStr)
            val bandsArr = obj.getJSONArray("bands")
            eq.clearBands()
            for (i in 0 until bandsArr.length()) {
                val bj = bandsArr.getJSONObject(i)
                val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(bj.getString("filterType")) }
                         catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                eq.addBand(bj.getDouble("frequency").toFloat(), bj.getDouble("gain").toFloat(), ft, bj.getDouble("q"))
                if (bj.has("enabled")) eq.setBandEnabled(i, bj.getBoolean("enabled"))
            }
            eqGraphView.setParametricEqualizer(eq)
            stateManager.eqPrefs.saveState(eq)
            stateManager.persistLeftRightIfCse()
            stateManager.initBandSlots()
            bandToggleManager.setupToggles()
            if (stateManager.isProcessing) {
                stateManager.eqService?.let { svc -> svc.dynamicsManager.stop(); svc.dynamicsManager.start(eq) }
            }
        }
        // Save initial state
        saveEqState()

        undoBtn.setOnClickListener {
            if (historyIndex > 0) {
                historyIndex--
                restoreEqState(eqHistory[historyIndex])
            }
        }
        redoBtn.setOnClickListener {
            if (historyIndex < eqHistory.size - 1) {
                historyIndex++
                restoreEqState(eqHistory[historyIndex])
            }
        }

        fun updateVizToggleStyle(active: Boolean) {
            if (active) {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(graphBtnLitBg)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
                vizToggle.strokeWidth = (2 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(graphBtnLitContent)
            } else {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(graphBtnDimBg)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                vizToggle.strokeWidth = (1 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(graphBtnDimContent)
            }
        }
        // Restore spectrum state from preferences
        if (eqPrefs.getSpectrumEnabled() &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
            updateVizToggleStyle(true)
        } else {
            updateVizToggleStyle(false)
        }
        vizToggle.setOnClickListener {
            if (visualizerHelper.isRunning) {
                visualizerHelper.stop()
                eqGraphView.spectrumRenderer = null
                eqGraphView.invalidate()
                updateVizToggleStyle(false)
                eqPrefs.saveSpectrumEnabled(false)
            } else {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                    return@setOnClickListener
                }
                visualizerHelper.start(eqGraphView)
                eqGraphView.spectrumRenderer = visualizerHelper.renderer
                updateVizToggleStyle(true)
                eqPrefs.saveSpectrumEnabled(true)
            }
        }
        powerButton.setOnClickListener {
            if (stateManager.isProcessing) stopProcessing() else startProcessing()
        }

        // EQ toggle — both the text button and the graph-header icon drive the same toggle.
        eqToggleButton.setOnClickListener { toggleEq() }
        eqPowerToggle.setOnClickListener { toggleEq() }

        // Preset dropdown
        presetDropdown.setOnItemClickListener { parent, _, position, _ ->
            val presetName = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            stateManager.loadPreset(presetName, eqGraphView)
            presetDropdown.setText(presetName, false)
            bandToggleManager.updateIcons()
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
        }

        // Graph callbacks
        eqGraphView.onBandSelectedListener = { bandIndex ->
            bandToggleManager.updateSelection(bandIndex)
            updateFilterTypeButtons(bandIndex)
            updateBandInputs(bandIndex)
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC && graphicController.updatePageForBand(bandIndex)) {
                graphicController.buildSliders(graphicController.targetCardHeight)
            }
            reorderToggleRows()
        }

        eqGraphView.onBandChangedListener = { bandIndex, _, _ ->
            // Throttle DP writes during drag — each ACTION_MOVE would trigger a full Pre-EQ rewrite on the audio thread.
            stateManager.pushEqUpdateThrottled()
            // Mirror "Both" edits to the other channel and redraw its ghost curve LIVE during the drag (issue #53).
            if (stateManager.activeChannel != EqStateManager.ActiveChannel.BOTH) {
                stateManager.syncBothBands()
                eqGraphView.invalidate()
            }
            updateBandInputs(bandIndex)
            // NOTE: don't rebuild the table per drag move — recreating all rows/inputs makes the drag stutter.
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.updateSliderValues()
        }

        eqGraphView.onBandDragEndListener = {
            stateManager.flushEqUpdate()
            // Sync the table to the dragged values now that the drag is done.
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
        }

        eqGraphView.onLongPressListener = { showPresetsBottomSheet() }

        // Band parameter sliders + text inputs
        setupBandSliderListeners()
        setupBandInputListeners()

        // DP band count slider
        dpBandCountSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val count = value.toInt()
            dpBandCountText.setText(count.toString())
            ParametricToDpConverter.setNumBands(count)
            eqPrefs.saveDpBandCount(count)
            stateManager.pushEqUpdate()
        }

        dpBandCountText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val count = dpBandCountText.text.toString().toIntOrNull()?.coerceIn(128, 1024) ?: 128
                dpBandCountText.setText(count.toString())
                dpBandCountSlider.value = count.toFloat()
                ParametricToDpConverter.setNumBands(count)
                eqPrefs.saveDpBandCount(count)
                stateManager.pushEqUpdate()
                dpBandCountText.clearFocus()
            }
            true
        }

        // Filter type buttons
        setupFilterTypeButtons()
        // Color swatches
        setupColorSwatches()

        // EQ mode selector: advanced modes clear the Simple flag, Simple sets it.
        modeParametricBtn.setOnClickListener { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.PARAMETRIC) }
        modeGraphicBtn.setOnClickListener { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.GRAPHIC) }
        modeTableBtn.setOnClickListener { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.TABLE) }
        modeSimpleBtn.setOnClickListener { eqPrefs.saveSimpleEqEnabled(true); switchEqUiMode(EqUiMode.SIMPLE) }

        // Settings controls
        setupSettingsListeners()
    }

    // ---- Settings ----

    /** Apply a preset-JSON state received over TV Mode (issues #35/#55) — the preset-load path minus the picker UI. */
    private fun applyRemotePresetJson(obj: org.json.JSONObject) {
        fun parseBands(arr: org.json.JSONArray): List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec> {
            val out = mutableListOf<com.bearinmind.equalizer314.state.EqStateManager.BandSpec>()
            for (i in 0 until arr.length()) {
                val bj = arr.getJSONObject(i)
                val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(bj.getString("filterType")) }
                         catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                out += com.bearinmind.equalizer314.state.EqStateManager.BandSpec(
                    frequency = bj.getDouble("frequency").toFloat(),
                    gain = bj.getDouble("gain").toFloat(),
                    q = bj.getDouble("q"),
                    filterType = ft,
                    enabled = if (bj.has("enabled")) bj.getBoolean("enabled") else true,
                )
            }
            return out
        }
        val cseOn = obj.optBoolean("channelSideEqEnabled", false)
        val bothBands = if (obj.has("bands")) parseBands(obj.getJSONArray("bands")) else emptyList()
        val leftBands = if (obj.has("leftBands")) parseBands(obj.getJSONArray("leftBands")) else bothBands
        val rightBands = if (obj.has("rightBands")) parseBands(obj.getJSONArray("rightBands")) else bothBands
        // Full-chain sync: MBC + limiter follow the peer when present.
        com.bearinmind.equalizer314.state.PresetChainIo.applyChain(
            this, obj, eqPrefs, stateManager.eqService?.dynamicsManager)

        // Structure signature (band count + types + enabled flags) — value-only syncs skip a rebuild.
        fun sigOfSpecs(bands: List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec>) =
            bands.joinToString(";") { "${it.filterType.name}${if (it.enabled) '1' else '0'}" }
        fun sigOfEq(eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer) =
            (0 until eq.getBandCount()).joinToString(";") { i ->
                val b = eq.getBand(i) ?: return@joinToString "?"
                "${b.filterType.name}${if (b.enabled) '1' else '0'}"
            }
        val currentCse = eqPrefs.getChannelSideEqEnabled()
        val (curL, curR) = stateManager.getChannelEqs()
        val structureChanged = cseOn != currentCse ||
            if (cseOn) sigOfSpecs(leftBands) != sigOfEq(curL) || sigOfSpecs(rightBands) != sigOfEq(curR)
            else sigOfSpecs(bothBands) != sigOfEq(stateManager.parametricEq)

        val sharedBands =
            if (obj.has("sharedBands")) parseBands(obj.getJSONArray("sharedBands")) else null
        stateManager.applyPresetEqs(cseOn, bothBands, leftBands, rightBands, sharedBands)
        eqGraphView.setParametricEqualizer(stateManager.parametricEq)
        stateManager.eqPrefs.saveState(stateManager.parametricEq)
        stateManager.persistLeftRightIfCse()
        if (structureChanged) {
            stateManager.initBandSlots()
            bandToggleManager.setupToggles()
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
        } else {
            eqGraphView.updateBandLevels()
        }
        stateManager.preampGainDb =
            if (obj.has("preamp")) obj.getDouble("preamp").toFloat() else 0f
        stateManager.eqPrefs.savePreampGain(stateManager.preampGainDb)
        stateManager.preampLeftDb = obj.optDouble("preampLeft", 0.0).toFloat()
        stateManager.preampRightDb = obj.optDouble("preampRight", 0.0).toFloat()
        eqPrefs.savePreampLeft(stateManager.preampLeftDb)
        eqPrefs.savePreampRight(stateManager.preampRightDb)
        preampSlider.value = stateManager.preampGainDb.coerceIn(-preampRange(), preampRange())
        preampText.setText(String.format("%.1f", stateManager.preampGainDb))
        stateManager.pushEqUpdate()
        stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
        eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
        refreshChannelPopoutDim()
        // Power follows the peer: the remote's power button drives this device's DP.
        if (obj.has("power")) {
            val wantPower = obj.getBoolean("power")
            if (wantPower != stateManager.isProcessing) {
                if (wantPower) startProcessing() else stopProcessing()
            }
        }
        obj.optJSONObject("nav")?.let { applyRemoteNav(it) }
        // Remote changes bypass the local UI paths that poke the notification.
        sendBroadcast(
            Intent(com.bearinmind.equalizer314.audio.EqService.ACTION_NOTIFICATION_REFRESH)
                .setPackage(packageName)
        )
        // While a remote is driving this device, keep the app-wide touch lock up.
        if (com.bearinmind.equalizer314.remote.TvRemoteHub.getMode(this) ==
            com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_SERVER) {
            com.bearinmind.equalizer314.remote.RemoteScrim.setActive(true)
        }
    }

    /** Screen-state follow (TV Mode): shadow the remote's nav — screen, UI mode, channel view, selected band. */
    private var lastRemoteNav: String? = null

    /** Screens the TV will follow the remote into (simple name → class). */
    private val remoteFollowScreens: Map<String, Class<out android.app.Activity>> = mapOf(
        "MainActivity" to MainActivity::class.java,
        "MbcActivity" to MbcActivity::class.java,
        "LimiterActivity" to LimiterActivity::class.java,
        "EnvironmentalReverbActivity" to EnvironmentalReverbActivity::class.java,
        "ChannelSideEqActivity" to ChannelSideEqActivity::class.java,
        "ExperimentalActivity" to ExperimentalActivity::class.java,
        "AudioOutputActivity" to AudioOutputActivity::class.java,
        "SpectrumControlActivity" to SpectrumControlActivity::class.java,
        "AutoEqActivity" to AutoEqActivity::class.java,
        "TargetCurveActivity" to TargetCurveActivity::class.java,
        "PresetsConversionsActivity" to PresetsConversionsActivity::class.java,
        "CustomPresetsActivity" to CustomPresetsActivity::class.java,
        "AudioEffectsPipelineActivity" to AudioEffectsPipelineActivity::class.java,
        "ChannelInputActivity" to ChannelInputActivity::class.java,
        "ConvertToApoActivity" to ConvertToApoActivity::class.java,
        "MeasurementSelectActivity" to MeasurementSelectActivity::class.java,
        "TargetSelectActivity" to TargetSelectActivity::class.java,
    )

    private fun applyRemoteNav(nav: org.json.JSONObject) {
        val navStr = nav.toString()
        val changed = navStr != lastRemoteNav
        lastRemoteNav = navStr
        // Follow the remote's screen (any whitelisted activity); only acts when nav changed and we're not already there.
        val targetScreen = nav.optString("screen", "")
        if (changed && targetScreen.isNotEmpty() &&
            targetScreen != com.bearinmind.equalizer314.remote.TvRemoteHub.topScreen) {
            remoteFollowScreens[targetScreen]?.let { cls ->
                try {
                    startActivity(
                        android.content.Intent(this, cls)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (_: Exception) {}
            }
        }
        // EQ UI mode (Parametric / Graphic / Table / Simple)
        try {
            val mode = EqUiMode.valueOf(nav.optString("uiMode", stateManager.currentEqUiMode.name))
            if (mode != stateManager.currentEqUiMode) switchEqUiMode(mode)
        } catch (_: Exception) {}
        // Channel view — only meaningful while Channel Side EQ is on.
        if (eqPrefs.getChannelSideEqEnabled()) {
            when (nav.optString("channelView", "")) {
                "BOTH_VIEW" -> if (!stateManager.bothViewActive) {
                    stateManager.enterBothView()
                    rebindActiveEq()
                    paintChannelButtonStyles()
                }
                "LEFT" -> if (stateManager.bothViewActive ||
                    stateManager.activeChannel != com.bearinmind.equalizer314.state.EqStateManager.ActiveChannel.LEFT) {
                    if (stateManager.bothViewActive) stateManager.exitBothView()
                    stateManager.setActiveChannel(com.bearinmind.equalizer314.state.EqStateManager.ActiveChannel.LEFT)
                    rebindActiveEq()
                    paintChannelButtonStyles()
                }
                "RIGHT" -> if (stateManager.bothViewActive ||
                    stateManager.activeChannel != com.bearinmind.equalizer314.state.EqStateManager.ActiveChannel.RIGHT) {
                    if (stateManager.bothViewActive) stateManager.exitBothView()
                    stateManager.setActiveChannel(com.bearinmind.equalizer314.state.EqStateManager.ActiveChannel.RIGHT)
                    rebindActiveEq()
                    paintChannelButtonStyles()
                }
            }
        }
        // Selected band highlight
        val sel = nav.optInt("selectedBand", -1)
        if (sel >= 0 && sel < stateManager.parametricEq.getBandCount() &&
            sel != stateManager.selectedBandIndex) {
            eqGraphView.setActiveBand(sel)
            bandToggleManager.updateSelection(sel)
        }
    }

    private val presetAutosaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val presetAutosaveRunnable = Runnable { autosaveLoadedPreset() }

    /** Writes edits back into the loaded pool preset so tweaks survive reconnects. */
    private fun autosaveLoadedPreset() {
        if (!eqPrefs.getPresetAutosave()) return
        if (System.currentTimeMillis() - stateManager.lastPresetApplyMs < 2500L) return
        val name = eqPrefs.getPresetName()
        if (name.isBlank()) return
        if (eqPrefs.getCustomPresetJson(name) == null) return
        eqPrefs.updateCustomPresetJson(name, buildCurrentPresetJson())
    }

    private fun buildCurrentPresetJson(): String {
        stateManager.eqPrefs.saveState(stateManager.parametricEq)
        stateManager.persistLeftRightIfCse()
        fun serialize(eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer): org.json.JSONArray {
            val arr = org.json.JSONArray()
            for (b in eq.getAllBands()) {
                arr.put(org.json.JSONObject().apply {
                    put("frequency", b.frequency)
                    put("gain", b.gain)
                    put("q", b.q)
                    put("filterType", b.filterType.name)
                    put("enabled", b.enabled)
                })
            }
            return arr
        }
        val json = org.json.JSONObject()
        json.put("preamp", stateManager.preampGainDb)
        val cseOn = eqPrefs.getChannelSideEqEnabled()
        json.put("channelSideEqEnabled", cseOn)
        if (cseOn) {
            val (lEq, rEq) = stateManager.getChannelEqs()
            json.put("leftBands", serialize(lEq))
            json.put("rightBands", serialize(rEq))
            // Shared "Both" layer + per-channel preamps (adam's report).
            json.put("sharedBands", serialize(stateManager.getSharedEqForPreset()))
            json.put("preampLeft", stateManager.preampLeftDb.toDouble())
            json.put("preampRight", stateManager.preampRightDb.toDouble())
            // Back-compat: also write the active EQ under the legacy "bands".
            json.put("bands", serialize(stateManager.parametricEq))
        } else {
            json.put("bands", serialize(stateManager.parametricEq))
        }
        // Full-chain presets: MBC + limiter ride along (applied when present).
        com.bearinmind.equalizer314.state.PresetChainIo.appendChain(json, eqPrefs)
        return json.toString()
    }

    private fun updateAutoEqStatus() {
        // AutoEQ status card lives in PresetsConversionsActivity — view may not exist here.
        val statusText = findViewById<TextView>(R.id.autoEqStatusText) ?: return
        val name = eqPrefs.getAutoEqName()
        if (!name.isNullOrBlank()) {
            val source = eqPrefs.getAutoEqSource() ?: ""
            statusText.text = "$name by $source"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = "Select or import a preset"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }

    private fun updateTargetStatus() {
        val statusText = findViewById<TextView>(R.id.targetStatusText) ?: return
        val name = eqPrefs.getSelectedTargetName()
        if (!name.isNullOrBlank()) {
            val type = eqPrefs.getSelectedTargetType() ?: ""
            statusText.text = if (type.isNotBlank()) "$name \u00B7 $type" else name
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = "Import a measurement and match to a specific target"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }

    private fun preampRange(): Float = 20f

    // EQ mode tabs (issue #77): visibility + order from prefs, wrapping to a second row past two enabled modes.
    private fun applyEqModeTabs() {
        val density = resources.displayMetrics.density
        val btnFor = mapOf(
            "parametric" to modeParametricBtn, "graphic" to modeGraphicBtn,
            "table" to modeTableBtn, "simple" to modeSimpleBtn)
        val group = findViewById<android.widget.LinearLayout>(R.id.modeSelectorGroup)
        val row1 = group.getChildAt(0) as android.widget.LinearLayout
        val row2 = if (group.childCount > 1) group.getChildAt(1) as android.widget.LinearLayout
        else android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            group.addView(this)
        }
        val order = eqPrefs.getEqModeOrder()
        for (btn in btnFor.values) (btn.parent as? android.view.ViewGroup)?.removeView(btn)
        row1.removeAllViews(); row2.removeAllViews()

        fun place(key: String, row: android.widget.LinearLayout, firstInRow: Boolean, visible: Boolean) {
            val btn = btnFor[key] ?: return
            btn.visibility = if (visible) View.VISIBLE else View.GONE
            (btn.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                marginStart = if (visible && !firstInRow) (4 * density).toInt() else 0
                marginEnd = 0
            }
            row.addView(btn)
        }
        val enabledKeys = order.filter { eqPrefs.getEqModeEnabled(it) }
        enabledKeys.forEachIndexed { i, key ->
            if (i < 2) place(key, row1, i == 0, true) else place(key, row2, i == 2, true)
        }
        order.filter { !eqPrefs.getEqModeEnabled(it) }.forEach { place(it, row1, false, false) }
        val twoRows = enabledKeys.size > 2
        row2.visibility = if (twoRows) View.VISIBLE else View.GONE
        // Base graph size: the fit measures against it and caps the graph at 2x.
        graphDesiredPx = ((if (twoRows) 246 else 288) * density).toInt()
        fitToViewport()
        val currentKey = when (stateManager.currentEqUiMode) {
            EqUiMode.PARAMETRIC -> "parametric"
            EqUiMode.GRAPHIC -> "graphic"
            EqUiMode.TABLE -> "table"
            EqUiMode.SIMPLE -> "simple"
        }
        if (!eqPrefs.getEqModeEnabled(currentKey)) {
            when (order.firstOrNull { eqPrefs.getEqModeEnabled(it) } ?: "parametric") {
                "graphic" -> { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.GRAPHIC) }
                "table" -> { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.TABLE) }
                "simple" -> { eqPrefs.saveSimpleEqEnabled(true); switchEqUiMode(EqUiMode.SIMPLE) }
                else -> { eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.PARAMETRIC) }
            }
        }
    }

    private var graphDesiredPx = 0
    private var viewportMaxHeight = 0
    private var fitting = false
    private var compactApplied = false

    /** Fits the EQ page: the graph takes what the two-row Parametric layout leaves (same size in every mode), so nothing else resizes on mode or band changes. */
    private fun fitToViewport() {
        if (fitting || graphDesiredPx <= 0 || !::eqGraphView.isInitialized) return
        val viewport = pageEq.height
        val width = pageEq.width
        if (viewport <= 0 || width <= 0) return
        val density = resources.displayMetrics.density
        // Ignore keyboard-sized shrinks (IME); smaller changes are insets/bottom bar settling and count.
        if (viewport > viewportMaxHeight || viewportMaxHeight - viewport < 100 * density) viewportMaxHeight = viewport
        val h = viewportMaxHeight
        val column = (pageEq as ScrollView).getChildAt(0) as? LinearLayout ?: return
        fitting = true
        try {
            fun measureColumn(): Int {
                column.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                return column.measuredHeight
            }
            val mode = stateManager.currentEqUiMode
            if (mode == EqUiMode.SIMPLE) {
                val bars = simpleEqController.bars ?: return
                val lp = bars.layoutParams ?: return
                val current = lp.height
                val desired = (256 * density).toInt()
                lp.height = desired
                val overflow = measureColumn() - h
                val target = (desired - overflow).coerceIn((160 * density).toInt(), desired)
                lp.height = target
                if (target != current) bars.requestLayout()
                return
            }
            // Reference block = two toggle rows + triangle + controls card (measured even when GONE); Table/Graphic content is sized to match it.
            val contentWidth = width - column.paddingLeft - column.paddingRight
            val wSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
            val uSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            bandToggleGroup.measure(wSpec, uSpec)
            parametricControlsCard.measure(wSpec, uSpec)
            val rowH = bandToggleGroup.measuredHeight
            val cardH = parametricControlsCard.measuredHeight
            val triangleH = findViewById<View>(R.id.triangleIndicatorContainer).layoutParams.height
            val gap8 = (8 * density).toInt()
            val refControls = 2 * rowH + triangleH + cardH + gap8

            if (mode == EqUiMode.TABLE) {
                val tableTarget = 2 * rowH + triangleH + cardH
                val lp = tableEqCard.layoutParams
                if (lp.height != tableTarget) { lp.height = tableTarget; tableEqCard.requestLayout() }
            } else if (mode == EqUiMode.GRAPHIC) {
                graphicController.targetCardHeight = cardH
                for (v in graphicController.cards) {
                    if (v.layoutParams.height != cardH) { v.layoutParams.height = cardH; v.requestLayout() }
                }
            }

            val graphLp = eqGraphView.layoutParams
            val graphCurrent = graphLp.height
            graphLp.height = graphDesiredPx
            val nonGraph = measureColumn() - graphDesiredPx
            // Swap the current mode's controls container for the reference block + preamp.
            val preampCard = findViewById<View>(R.id.preampCardBar)
            val preampBlock = preampCard.measuredHeight + ((preampCard.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0)
            val ref = nonGraph - eqControlsContainer.measuredHeight + refControls + preampBlock
            val graphMin = (130 * density).toInt()
            val graphTarget = (h - ref).coerceIn(graphMin, graphDesiredPx * 2)
            graphLp.height = graphTarget
            if (graphTarget != graphCurrent) eqGraphView.requestLayout()

            // Graph under 180dp → compact chips/tabs/card padding (~48dp back); 64dp hysteresis so it can't flip-flop.
            val short = ref + (180 * density).toInt() - h
            val wantCompact = when {
                short > 0 -> true
                short < -(64 * density) -> false
                else -> BandToggleManager.compact
            }
            if (wantCompact != compactApplied) {
                compactApplied = wantCompact
                BandToggleManager.compact = wantCompact
                applyParametricCompact(wantCompact)
                bandToggleManager.setupToggles()
            }
        } finally {
            fitting = false
        }
    }

    private fun applyParametricCompact(compact: Boolean) {
        val density = resources.displayMetrics.density
        val h = (16 * density).toInt()
        val v = ((if (compact) 8 else 16) * density).toInt()
        (parametricControlsCard as? android.view.ViewGroup)?.getChildAt(0)?.setPadding(h, v, h, v)
        findViewById<View>(R.id.filterTypeGroup)?.let {
            it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, ((if (compact) 4 else 8) * density).toInt())
        }
        val tabPad = ((if (compact) 4 else 7) * density).toInt()
        for (id in intArrayOf(R.id.modeParametricBtn, R.id.modeGraphicBtn, R.id.modeTableBtn, R.id.modeSimpleBtn)) {
            findViewById<View>(id)?.let { it.setPadding(it.paddingLeft, tabPad, it.paddingRight, tabPad) }
        }
    }

    private fun setupSpectrumControl() {
        // Spectrum Control + Light Theme moved into the UI & EQ Modes sub-screen.
        findViewById<android.view.View>(R.id.uiEqModesCard).setOnClickListener {
            startActivity(android.content.Intent(this, UiEqModesActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun applySpectrumSettings() {
        val ppoValues = intArrayOf(1, 2, 3, 6, 12, 24, 48, 96)
        val renderer = visualizerHelper.renderer
        val eqPrefs = stateManager.eqPrefs
        if (eqPrefs.getPpoEnabled()) {
            renderer.ppoSmoothing = ppoValues[eqPrefs.getPpoIndex().coerceIn(0, 7)]
        } else {
            renderer.ppoSmoothing = 0
        }
        renderer.setSpectrumColor(eqPrefs.getSpectrumColor())
        renderer.releaseAlpha = eqPrefs.getSpectrumRelease()
    }

    private fun setupSettingsListeners() {
        // Light Theme toggle moved to UiEqModesActivity.

        // Channel Side Options settings card retired — CSE now toggles via the power button in the graph's channel popout.

        // Presets & Conversions sub-screen (AutoEQ & Presets / Generate Custom EQ / Convert to APO).
        findViewById<View>(R.id.presetsConversionsCard).setOnClickListener {
            presetsConversionsLauncher.launch(Intent(this, PresetsConversionsActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Backup & Restore — whole-app export/import.
        findViewById<View>(R.id.backupRestoreCard).setOnClickListener {
            showBackupRestoreDialog()
        }

        // Audio Effects & Misc Settings — groups Channel Settings, Pipeline, Gain Reduction.
        findViewById<View>(R.id.audioMiscCard).setOnClickListener {
            startActivity(Intent(this, AudioMiscSettingsActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Experimental lock retired (see LegacyFeatures.kt) — card is always tappable.
        findViewById<ImageButton>(R.id.experimentalLockButton).visibility = View.GONE
        val experimentalCard = findViewById<View>(R.id.experimentalCard)
        experimentalCard.alpha = 1f
        experimentalCard.setOnClickListener {
            startActivity(Intent(this, ExperimentalActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Spectrum Control
        setupSpectrumControl()

        // Preamp slider
        preampSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            preampText.setText(String.format("%.1f", value))
            stateManager.setActivePreamp(value)
            persistPreamps()
            stateManager.pushEqUpdate()
            updateAutoGainOffsetText()
        }

        var preampJustDoubleTapped = false
        val preampDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                preampSlider.value = 0f
                preampText.setText(String.format("%.1f", 0f))
                stateManager.setActivePreamp(0f)
                persistPreamps()
                stateManager.pushEqUpdate()
                updateAutoGainOffsetText()
                preampJustDoubleTapped = true
                return true
            }
        })
        preampSlider.setOnTouchListener { _, event ->
            preampDetector.onTouchEvent(event)
            if (preampJustDoubleTapped) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    preampJustDoubleTapped = false
                }
                true  // swallow so Slider doesn't commit the tap position
            } else false
        }

        preampText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val gain = preampText.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(-preampRange(), preampRange()) ?: 0f
                preampText.setText(String.format("%.1f", gain))
                preampSlider.value = gain
                stateManager.setActivePreamp(gain)
                persistPreamps()
                stateManager.pushEqUpdate()
                updateAutoGainOffsetText()
                preampText.clearFocus()
            }
            true
        }

        // Auto-gain switch
        autoGainSwitch.setOnCheckedChangeListener { _, isChecked ->
            stateManager.autoGainEnabled = isChecked
            eqPrefs.saveAutoGainEnabled(isChecked)
            stateManager.pushEqUpdate()
            updateAutoGainOffsetText()
        }

        // Old inline limiter controls removed — now in LimiterActivity
    }

    private fun updateAutoGainOffsetText() {
        val offset = stateManager.getAutoGainOffset()
        autoGainOffsetText.text = String.format("Offset: %.1f dB", offset)
    }

    // ---- EQ UI Mode Switching ----

    /** Pixel-positions the graph-header overlay buttons from the graph width. */
    private fun relayoutGraphHeaderButtons() {
        if (!::eqGraphView.isInitialized) return
        val viewWidth = eqGraphView.width
        if (viewWidth <= 0) return
        val vizDensity = resources.displayMetrics.density
        val gapPx = (2 * vizDensity).toInt()
        val vPadPx = 80
        val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
        val btnTop = gapPx
        val btnHeight = vPadPx - 2 * gapPx
        val specWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)
        val specLeft = gridLine10k + gapPx

        fun reposition(btn: View, leftMargin: Int, topMargin: Int = btnTop) {
            val lp = btn.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
            lp.width = specWidth; lp.height = btnHeight
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.leftMargin = leftMargin; lp.topMargin = topMargin
            btn.layoutParams = lp
        }

        val vizToggle = findViewById<View>(R.id.visualizerToggle)
        val editBtn = findViewById<View>(R.id.editButton)
        val resetBtn = findViewById<View>(R.id.resetButton)
        val undoBtn = findViewById<View>(R.id.undoButton)
        val redoBtn = findViewById<View>(R.id.redoButton)
        val bandPtsBtn = findViewById<View>(R.id.bandPointsToggle)
        val saveBtn = findViewById<View>(R.id.savePresetButton)

        val editLeftPx = (specLeft - gapPx - specWidth).coerceAtLeast(gapPx)
        val resetLeftPx = (specLeft - 2 * (gapPx + specWidth)).coerceAtLeast(gapPx)
        val row2Top = btnTop + btnHeight + gapPx

        reposition(vizToggle, specLeft)
        reposition(editBtn, editLeftPx)
        reposition(resetBtn, resetLeftPx)
        // EQ on/off toggle shares the reset slot (left of edit).
        reposition(findViewById(R.id.eqPowerToggle), resetLeftPx)
        // Undo sits directly below reset; redo sits directly below edit
        reposition(undoBtn, resetLeftPx, row2Top)
        reposition(redoBtn, editLeftPx, row2Top)
        reposition(bandPtsBtn, gapPx)
        val saveLeftPx = gapPx + specWidth + gapPx
        reposition(saveBtn, saveLeftPx)
        // View-options popout: ON/OFF below the eye, fill toggle below save.
        reposition(findViewById(R.id.bandPointsOnOffBtn), gapPx, row2Top)
        reposition(findViewById(R.id.bandFillToggle), saveLeftPx, row2Top)

        // Alt-route button: after save on row 1
        val altRouteLeftPx = saveLeftPx + specWidth + gapPx
        reposition(findViewById(R.id.altRouteButton), altRouteLeftPx)
        // Settings gear: right of alt-route on row 1 (popout member)
        val settingsLeftPx = altRouteLeftPx + specWidth + gapPx
        reposition(findViewById(R.id.settingsGearButton), settingsLeftPx)
        // L / R popout on row 2 — L below alt-route, R below settings
        reposition(findViewById(R.id.channelLButton), altRouteLeftPx, row2Top)
        reposition(findViewById(R.id.channelRButton), settingsLeftPx, row2Top)
        // "Both" one cell left of L, heat toggle in the eye popout's third cell.
        reposition(findViewById(R.id.channelBothButton),
            (altRouteLeftPx - specWidth - gapPx).coerceAtLeast(0), row2Top)
        reposition(findViewById(R.id.gainHeatToggle), gapPx + (specWidth + gapPx) * 2, row2Top)

        // Keep the mini L/R badge glued to the alt-route button.
        badgeAnchorAltRouteLeft = altRouteLeftPx
        badgeAnchorSpecWidth = specWidth
        badgeAnchorBtnTop = btnTop
        repositionChannelBadge(findViewById(R.id.altRouteChannelBadge))
    }

    private fun switchEqUiMode(mode: EqUiMode) {
        // TV Mode nav sync — hub debounces 150ms, so state is read post-switch.
        com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        // Clean up table mode bands when leaving
        if (stateManager.currentEqUiMode == EqUiMode.TABLE && mode != EqUiMode.TABLE) {
            tableController.cleanup()
        }
        // Save simple EQ gains and restore the advanced EQ when leaving SIMPLE mode
        if (stateManager.currentEqUiMode == EqUiMode.SIMPLE && mode != EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
            // Restore the advanced EQ state that was saved when entering SIMPLE mode
            val backup = eqPrefs.getAdvancedEqBackup()
            if (backup != null) {
                val eq = stateManager.parametricEq
                val bandsJson = org.json.JSONArray(backup)
                eq.clearBands()
                for (i in 0 until bandsJson.length()) {
                    val obj = bandsJson.getJSONObject(i)
                    val ft = try {
                        com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(obj.getString("filterType"))
                    } catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                    eq.addBand(obj.getDouble("frequency").toFloat(), obj.getDouble("gain").toFloat(), ft, obj.getDouble("q"))
                    if (obj.has("enabled")) eq.setBandEnabled(i, obj.getBoolean("enabled"))
                }
                // Restore band slots
                stateManager.initBandSlots()
                eqGraphView.setParametricEqualizer(eq)
                eqGraphView.updateBandLevels()
                stateManager.pushEqUpdate()
                // Refreshes the DP-band overlay — drawDpBands() reads cached dpCenterFrequencies/dpGains still holding the Simple EQ.
            }
        }
        // Save the advanced EQ before entering SIMPLE.
        if (mode == EqUiMode.SIMPLE && stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
            val eq = stateManager.parametricEq
            val isAlreadySimpleConfig = eq.getBandCount() == com.bearinmind.equalizer314.ui.SimpleEqController.FREQUENCIES.size &&
                (0 until eq.getBandCount()).all { i ->
                    val band = eq.getBand(i)
                    band != null &&
                    band.filterType == com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL &&
                    kotlin.math.abs(band.frequency - com.bearinmind.equalizer314.ui.SimpleEqController.FREQUENCIES[i]) < 0.5f
                }
            if (!isAlreadySimpleConfig) {
                val bandsJson = org.json.JSONArray()
                for (i in 0 until eq.getBandCount()) {
                    val band = eq.getBand(i) ?: continue
                    bandsJson.put(org.json.JSONObject().apply {
                        put("frequency", band.frequency.toDouble())
                        put("gain", band.gain.toDouble())
                        put("filterType", band.filterType.name)
                        put("q", band.q)
                        put("enabled", band.enabled)
                    })
                }
                eqPrefs.saveAdvancedEqBackup(bandsJson.toString())
            }
        }
        stateManager.currentEqUiMode = mode
        eqGraphView.eqUiMode = mode
        if (mode != EqUiMode.SIMPLE) eqPrefs.saveEqUiMode(mode.name)
        updateModeSelectorButtons()

        // In non-SIMPLE modes, ensure standard views are visible and simple container hidden.
        if (mode != EqUiMode.SIMPLE) {
            modeSelectorGroup.visibility = View.VISIBLE
            graphCardView.visibility = View.VISIBLE
            eqControlsContainer.visibility = View.VISIBLE
            simpleEqContainer.visibility = View.GONE

            // Ensure advanced preset picker is closed when returning from SIMPLE
            findViewById<View>(R.id.presetPickerScroll).apply {
                animate().cancel()
                visibility = View.GONE
                alpha = 1f
            }

            val preampCard = findViewById<View>(R.id.preampCardBar)
            if (preampCard.parent !== eqControlsContainer) {
                (preampCard.parent as? android.view.ViewGroup)?.removeView(preampCard)
                eqControlsContainer.addView(preampCard)
                // Restore original XML margins (topMargin=8dp, bottomMargin=0dp)
                (preampCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = 0
                }
            }

            // Re-sync band toggles and graph after returning from SIMPLE mode (it replaces the EQ with 10 fixed bands).
            bandToggleManager.setupToggles()
            eqGraphView.updateBandLevels()

            // Re-position the graph overlay buttons once the card has its real width again.
            eqGraphView.doOnLayout { relayoutGraphHeaderButtons() }
        }

        when (mode) {
            EqUiMode.PARAMETRIC -> {
                // Clear any visual translation on the actual preamp card from table mode
                findViewById<View>(R.id.preampCardBar).translationY = 0f
                parametricControlsCard.visibility = View.VISIBLE
                graphicScrollView.visibility = View.GONE
                filterTypeGroup.visibility = View.VISIBLE
                hzControlRow.visibility = View.VISIBLE
                qControlGroup.visibility = View.VISIBLE
                bandInputGroup.visibility = View.VISIBLE
                bandQInputLayout.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                // bandToggleGroup2 visibility managed by BandToggleManager.updateRow2Visibility()
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.VISIBLE
                tableEqCard.visibility = View.GONE
                bandToggleManager.setupToggles()
                // Always have a band selected
                if (stateManager.parametricEq.getBandCount() > 0) {
                    val band = stateManager.selectedBandIndex ?: 0
                    stateManager.selectedBandIndex = band
                    eqGraphView.setActiveBand(band)
                    updateFilterTypeButtons(band)
                    updateBandInputs(band)
                }
                reorderToggleRows(animate = false)
            }
            EqUiMode.GRAPHIC -> {
                // Clear any visual translation on the actual preamp card from table mode
                findViewById<View>(R.id.preampCardBar).translationY = 0f
                parametricControlsCard.measure(
                    View.MeasureSpec.makeMeasureSpec(parametricControlsCard.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                graphicController.targetCardHeight = parametricControlsCard.measuredHeight
                parametricControlsCard.visibility = View.GONE
                graphicScrollView.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.INVISIBLE
                tableEqCard.visibility = View.GONE
                bandToggleManager.setupToggles()
                graphicController.buildSliders(graphicController.targetCardHeight)
                reorderToggleRows(animate = false)
            }
            EqUiMode.TABLE -> {
                parametricControlsCard.visibility = View.GONE
                graphicScrollView.visibility = View.GONE
                bandToggleGroup.visibility = View.GONE
                bandToggleGroup2.visibility = View.GONE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.GONE
                tableEqCard.visibility = View.VISIBLE

                // The fit sizes the table card to the two-row Parametric controls block; re-run after layout for cold start.
                fitToViewport()
                pageEq.post { if (stateManager.currentEqUiMode == EqUiMode.TABLE) fitToViewport() }

                tableEqCard.translationY = 0f
                findViewById<View>(R.id.preampCardBar).translationY = 0f

                tableController.buildTable()
            }
            EqUiMode.SIMPLE -> {
                // Keep the mode selector visible so Simple is a true peer of the other modes; only the graph + advanced controls hide.
                modeSelectorGroup.visibility = View.VISIBLE
                graphCardView.visibility = View.GONE
                eqControlsContainer.visibility = View.GONE

                // Close advanced preset picker if it was left open
                findViewById<View>(R.id.presetPickerScroll).apply {
                    animate().cancel()
                    visibility = View.GONE
                    alpha = 1f
                }

                // Match the controls overlay's 8dp topMargin from PARAMETRIC/GRAPHIC.
                val overlay = simpleEqContainer.parent as? View
                (overlay?.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                    (8 * resources.displayMetrics.density).toInt()
                overlay?.requestLayout()

                // Content starts at the top of pageEq here; rootLayout already handles the status-bar inset.

                // Scroll to top so header starts at correct position
                (pageEq as android.widget.ScrollView).scrollTo(0, 0)

                // Show simple 10-band EQ
                simpleEqContainer.visibility = View.VISIBLE
                simpleEqController.configureParametricEq()
                simpleEqController.buildSliders()

                // Reparent the preamp card into simpleEqContainer as the LAST child (below bars/preset area).
                val preampCard = findViewById<View>(R.id.preampCardBar)
                (preampCard.parent as? android.view.ViewGroup)?.removeView(preampCard)
                simpleEqContainer.addView(preampCard, simpleEqContainer.childCount)
                preampCard.translationY = 0f
                // topMargin 0 so the bars card's bottom margin isn't doubled; the page's bottom padding supplies the gap below.
                (preampCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = 0
                    bottomMargin = 0
                }
            }
        }
        // Re-push EQ so the DP manager re-evaluates the conversion path for the new mode.
        stateManager.pushEqUpdate()
        // Refresh the red experimental-DP overlay so it shows the new path (feature-aware ↔ direct) the moment the mode changes.
        eqGraphView.invalidate()
    }

    private fun reorderToggleRows(animate: Boolean = true) {
        if (stateManager.currentEqUiMode == EqUiMode.TABLE || stateManager.currentEqUiMode == EqUiMode.SIMPLE) return
        val parent = findViewById<LinearLayout>(R.id.eqControlsContainer)
        val triContainer = findViewById<View>(R.id.triangleIndicatorContainer)

        // Determine which page the selected band is on
        val selectedBand = stateManager.selectedBandIndex ?: 0
        val displayPos = stateManager.displayToBandIndex.indexOf(selectedBand).let { if (it < 0) 0 else it }
        // Expanded mode (issue #31) can have 3+ rows — no active/inactive page swap; row 1 stays on top.
        val activePage = if (EqStateManager.MAX_BANDS > 16) 0 else displayPos / 8
        val activeRow = if (activePage == 0) bandToggleGroup else bandToggleGroup2
        val inactiveRow = if (activePage == 0) bandToggleGroup2 else bandToggleGroup

        // Check if both rows are already in correct positions — use index 0 as base since controls container starts with toggle groups
        val graphIdx = -1  // graph is outside eqControlsContainer
        val currentActiveIdx = parent.indexOfChild(activeRow)
        val controlsView = when (stateManager.currentEqUiMode) {
            EqUiMode.PARAMETRIC -> parametricControlsCard
            EqUiMode.GRAPHIC -> graphicScrollView
            else -> null
        }
        val expectedInactiveIdx = if (controlsView != null) parent.indexOfChild(controlsView) + 1 else graphIdx + 3
        val currentInactiveIdx = parent.indexOfChild(inactiveRow)

        // Keep extra-rows scroll + "+" directly below the inactive (bottom) row so "+" sits under bands 9-16, not 1-8.
        fun positionExtraBandViews() {
            if (EqStateManager.MAX_BANDS <= 16) return
            val idx = parent.indexOfChild(inactiveRow)
            if (idx < 0) return
            // Already directly below the inactive row → leave it (avoids churn / resetting the scroll position on every selection).
            if (parent.indexOfChild(bandToggleExtraScroll) == idx + 1) return
            parent.removeView(bandToggleExtraScroll)
            val idx2 = parent.indexOfChild(inactiveRow)
            parent.addView(bandToggleExtraScroll, idx2 + 1)
        }

        if (currentActiveIdx == graphIdx + 1 && currentInactiveIdx == expectedInactiveIdx) {
            positionExtraBandViews()
            return
        }

        // Animate the transition
        if (animate) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            android.transition.TransitionManager.beginDelayedTransition(parent, transition)
        }

        // Remove movable views
        parent.removeView(bandToggleGroup)
        parent.removeView(bandToggleGroup2)
        parent.removeView(triContainer)

        // Active row at top of controls container
        (activeRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        parent.addView(activeRow, 0)

        // Triangle indicator after active row
        parent.addView(triContainer, 1)

        // Inactive row after controls
        (inactiveRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        if (controlsView != null) {
            val controlsIdx = parent.indexOfChild(controlsView)
            parent.addView(inactiveRow, controlsIdx + 1)
        } else {
            parent.addView(inactiveRow, 2)
        }
        positionExtraBandViews()
    }

    private fun updateModeSelectorButtons() {
        val buttons = listOf(
            modeParametricBtn to EqUiMode.PARAMETRIC,
            modeGraphicBtn to EqUiMode.GRAPHIC,
            modeTableBtn to EqUiMode.TABLE,
            modeSimpleBtn to EqUiMode.SIMPLE
        )
        for ((btn, mode) in buttons) {
            if (mode == stateManager.currentEqUiMode) {
                btn.setBackgroundColor(getColor(R.color.filter_active))
                btn.setTextColor(getColor(R.color.filter_active_text))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_active))
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.setTextColor(getColor(R.color.filter_inactive_text))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_outline))
            }
        }
    }

    // ---- Band Parameter Sliders + Text Inputs ----

    private fun hzToSlider(hz: Float): Float {
        val logHz = kotlin.math.log10(hz.coerceIn(10f, 20000f))
        return ((logHz - hzLogMin) / (hzLogMax - hzLogMin) * 1000f)
    }

    private fun sliderToHz(pos: Float): Float {
        val logHz = hzLogMin + (pos / 1000f) * (hzLogMax - hzLogMin)
        return (10.0).pow(logHz.toDouble()).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBandSliderListeners() {
        bandHzSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            val hz = sliderToHz(value)
            isUpdatingInputs = true
            bandHzInput.setText(formatHzValue(hz))
            isUpdatingInputs = false
            applyBandHz(hz)
        }

        bandDbSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandDbInput.setText(String.format("%.1f", value))
            isUpdatingInputs = false
            applyBandDb(value)
        }

        qSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandQInput.setText(String.format("%.2f", value))
            isUpdatingInputs = false
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addOnChangeListener
            eqGraphView.setQ(bandIndex, value.toDouble())
            stateManager.pushEqUpdate()
        }

        // Double-tap to reset sliders to default values
        addDoubleTapReset(bandHzSlider) {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            val defaults = stateManager.allDefaultFrequencies
            val slot = stateManager.bandSlots.getOrNull(bandIndex) ?: bandIndex
            val defaultHz = if (slot < defaults.size) defaults[slot] else 1000f
            bandHzSlider.value = hzToSlider(defaultHz)
            bandHzInput.setText(formatHzValue(defaultHz))
            applyBandHz(defaultHz)
        }

        addDoubleTapReset(bandDbSlider) {
            bandDbSlider.value = 0f
            bandDbInput.setText("0.0")
            applyBandDb(0f)
        }

        addDoubleTapReset(qSlider) {
            val defaultQ = 0.71f
            qSlider.value = defaultQ
            bandQInput.setText(String.format("%.2f", defaultQ))
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            eqGraphView.setQ(bandIndex, defaultQ.toDouble())
            stateManager.pushEqUpdate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addDoubleTapReset(slider: Slider, onReset: () -> Unit) {
        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { v, event ->
            if (consumeUntilUp) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    consumeUntilUp = false
                }
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    isUpdatingInputs = true
                    onReset()
                    isUpdatingInputs = false
                    eqGraphView.invalidate()
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            false
        }
    }

    private fun setupBandInputListeners() {
        val applyOnAction = android.view.inputmethod.EditorInfo.IME_ACTION_DONE

        bandHzInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandHzFromInput(); true } else false
        }
        bandHzInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandHzFromInput()
        }

        bandDbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandDbFromInput(); true } else false
        }
        bandDbInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandDbFromInput()
        }

        bandQInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandQFromInput(); true } else false
        }
        bandQInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandQFromInput()
        }
    }

    private fun applyBandHzFromInput() {
        if (isUpdatingInputs) return
        val hz = bandHzInput.text.toString().replace(',', '.').toFloatOrNull() ?: return
        val clamped = hz.coerceIn(10f, 20000f)
        isUpdatingInputs = true
        bandHzSlider.value = hzToSlider(clamped)
        isUpdatingInputs = false
        applyBandHz(clamped)
    }

    private fun applyBandDbFromInput() {
        if (isUpdatingInputs) return
        val db = bandDbInput.text.toString().replace(',', '.').toFloatOrNull() ?: return
        val clamped = db.coerceIn(-20f, 20f)
        isUpdatingInputs = true
        bandDbSlider.value = clamped
        isUpdatingInputs = false
        applyBandDb(clamped)
    }

    private fun applyBandQFromInput() {
        if (isUpdatingInputs) return
        val q = bandQInput.text.toString().replace(',', '.').toDoubleOrNull() ?: return
        val clamped = q.coerceIn(0.1, 12.0)
        isUpdatingInputs = true
        qSlider.value = clamped.toFloat()
        isUpdatingInputs = false
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        eqGraphView.setQ(bandIndex, clamped)
        stateManager.pushEqUpdate()
    }

    private fun applyBandHz(hz: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        stateManager.parametricEq.updateBand(bandIndex, hz, band.gain, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        stateManager.pushEqUpdate()
    }

    private fun applyBandDb(db: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val ftNow = band.filterType
        val gainless = ftNow == BiquadFilter.FilterType.LOW_PASS ||
                       ftNow == BiquadFilter.FilterType.HIGH_PASS ||
                       ftNow == BiquadFilter.FilterType.LOW_PASS_1 ||
                       ftNow == BiquadFilter.FilterType.HIGH_PASS_1 ||
                       ftNow == BiquadFilter.FilterType.BAND_PASS ||
                       ftNow == BiquadFilter.FilterType.NOTCH ||
                       ftNow == BiquadFilter.FilterType.ALL_PASS
        val effectiveDb = if (gainless) 0f else db
        stateManager.parametricEq.updateBand(bandIndex, band.frequency, effectiveDb, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        stateManager.pushEqUpdate()
    }

    private fun showPresetsBottomSheet() {
        val density = resources.displayMetrics.density
        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        for (presetName in presets) {
            val item = TextView(this).apply {
                text = presetName
                textSize = 16f
                setTextColor(0xFFE2E2E2.toInt())
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                setOnClickListener {
                    stateManager.loadPreset(presetName, eqGraphView)
                    presetDropdown.setText(presetName, false)
                    bandToggleManager.updateIcons()
                    if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
                    if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
                    bottomSheet.dismiss()
                }
            }
            sheetLayout.addView(item)
        }
        bottomSheet.setContentView(sheetLayout)
        bottomSheet.show()
    }

    private fun formatHzValue(hz: Float): String {
        return if (hz >= 1000) String.format("%.0f", hz) else String.format("%.1f", hz)
    }

    private fun updateBandInputs(bandIndex: Int?) {
        isUpdatingInputs = true
        val idx = bandIndex ?: stateManager.selectedBandIndex ?: 0
        val band = stateManager.parametricEq.getBand(idx)
        if (band != null) {
            bandHzInput.setText(formatHzValue(band.frequency))
            bandDbInput.setText(String.format("%.1f", band.gain))
            bandQInput.setText(String.format("%.2f", band.q))
            bandHzSlider.value = hzToSlider(band.frequency)
            bandDbSlider.value = band.gain.coerceIn(-20f, 20f)
            qSlider.value = band.q.toFloat().coerceIn(0.1f, 12f)

            // dB slider / input are disabled for every gainless filter type: low / high pass at either order, plus BP / NO / AP.
            val ft = band.filterType
            val gainless = ft == BiquadFilter.FilterType.LOW_PASS ||
                           ft == BiquadFilter.FilterType.HIGH_PASS ||
                           ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                           ft == BiquadFilter.FilterType.HIGH_PASS_1 ||
                           ft == BiquadFilter.FilterType.BAND_PASS ||
                           ft == BiquadFilter.FilterType.NOTCH ||
                           ft == BiquadFilter.FilterType.ALL_PASS
            bandDbSlider.isEnabled = !gainless
            bandDbInput.isEnabled = !gainless
            bandDbSlider.alpha = if (gainless) 0.3f else 1f
            bandDbInput.alpha = if (gainless) 0.3f else 1f

            // Q slider / input don't apply to 1st-order variants — they collapse to a 6 dB/oct slope with no Q term.
            val is1st = ft == BiquadFilter.FilterType.LOW_SHELF_1 ||
                        ft == BiquadFilter.FilterType.HIGH_SHELF_1 ||
                        ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                        ft == BiquadFilter.FilterType.HIGH_PASS_1
            qSlider.isEnabled = !is1st
            bandQInput.isEnabled = !is1st
            qSlider.alpha = if (is1st) 0.3f else 1f
            bandQInput.alpha = if (is1st) 0.3f else 1f
        } else {
            bandHzInput.setText("1000")
            bandDbInput.setText("0.0")
            bandQInput.setText("0.71")
            bandHzSlider.value = 500f
            bandDbSlider.value = 0f
            qSlider.value = 0.71f
            bandDbSlider.isEnabled = true
            bandDbInput.isEnabled = true
            bandDbSlider.alpha = 1f
            bandDbInput.alpha = 1f
            qSlider.isEnabled = true
            bandQInput.isEnabled = true
            qSlider.alpha = 1f
            bandQInput.alpha = 1f
        }
        updateColorSwatches(bandIndex)
        isUpdatingInputs = false
    }

    private fun setupColorSwatches() {
        colorSwatchRow.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (22 * density).toInt()

        for ((color, _) in TableEqController.BAND_COLORS) {
            val isNone = color == 0xFF333333.toInt()
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val swatch: View = if (isNone) {
                TextView(this).apply {
                    text = "\u2014"
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF404040.toInt())
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            }
            swatch.setOnClickListener {
                val bandIndex = stateManager.selectedBandIndex ?: return@setOnClickListener
                val slotIdx = if (bandIndex < stateManager.bandSlots.size) stateManager.bandSlots[bandIndex] else return@setOnClickListener
                if (isNone) {
                    stateManager.bandColors.remove(slotIdx)
                } else {
                    stateManager.bandColors[slotIdx] = color
                }
                stateManager.saveState()
                eqGraphView.setBandColors(stateManager.bandColors)
                eqGraphView.invalidate()
                bandToggleManager.updateSelection(stateManager.selectedBandIndex)
                updateColorSwatches(bandIndex)
            }
            wrapper.addView(swatch)
            colorSwatchRow.addView(wrapper)
        }
    }

    private fun updateColorSwatches(bandIndex: Int?) {
        val idx = bandIndex ?: stateManager.selectedBandIndex ?: return
        val slotIdx = if (idx < stateManager.bandSlots.size) stateManager.bandSlots[idx] else -1
        val currentColor = if (slotIdx >= 0) stateManager.bandColors[slotIdx] else null
        val density = resources.displayMetrics.density

        for (i in 0 until colorSwatchRow.childCount) {
            val wrapper = colorSwatchRow.getChildAt(i) as? FrameLayout ?: continue
            val swatch = wrapper.getChildAt(0) ?: continue
            val bg = swatch.background as? android.graphics.drawable.GradientDrawable ?: continue

            val swatchColor = TableEqController.BAND_COLORS[i].first
            val isNone = swatchColor == 0xFF333333.toInt()
            val isSelected = if (isNone) currentColor == null else currentColor == swatchColor

            if (isSelected) {
                bg.setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            } else {
                bg.setStroke((1 * density).toInt(), 0xFF666666.toInt())
            }
        }
    }

    // ---- Processing Control ----

    private fun startProcessing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "DynamicsProcessing requires Android 9+", Toast.LENGTH_LONG).show()
            return
        }

        // POST_NOTIFICATIONS only gates *displaying* the FGS notification — the service runs fine without it on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !notificationPermissionRequested
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            // fall through — power-on continues regardless of grant result
        }

        // Start animation first, then do heavy work after a frame so animation plays smoothly
        showPowerSnackbar(true)
        animatePowerFab(true)

        powerFab.postDelayed({
            EqService.start(this)
            if (stateManager.serviceBound) {
                doStartEq()
            } else {
                stateManager.pendingStartEq = true
                val intent = Intent(this, EqService::class.java)
                bindService(intent, stateManager.serviceConnection, BIND_AUTO_CREATE)
            }
        }, 280)
    }

    private fun doStartEq() {
        stateManager.doStartEq { on -> animatePowerFab(on) }
    }

    private fun stopProcessing() {
        showPowerSnackbar(false)
        animatePowerFab(false)
        powerFab.postDelayed({
            stateManager.stopProcessing { on -> animatePowerFab(on) }
        }, 280)
    }

    private fun showPowerSnackbar(on: Boolean) {
        eqPrefs.savePowerState(on)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this, on)
        val message = if (on) "DynamicsProcessing Start" else "DynamicsProcessing Stop"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---- UI Updates ----

    private var powerAnimator: android.animation.ValueAnimator? = null

    private fun animatePowerFab(on: Boolean) {
        // BottomNavHelper.updatePowerFab handles the animation — just update the text label.
        powerButton.text = if (on) "ON" else "OFF"
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val a = ((from shr 24 and 0xFF) * inv + (to shr 24 and 0xFF) * ratio).toInt()
        val r = ((from shr 16 and 0xFF) * inv + (to shr 16 and 0xFF) * ratio).toInt()
        val g = ((from shr 8 and 0xFF) * inv + (to shr 8 and 0xFF) * ratio).toInt()
        val b = ((from and 0xFF) * inv + (to and 0xFF) * ratio).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun updatePowerUI() {
        powerButton.text = if (stateManager.isProcessing) "ON" else "OFF"
    }

    private fun updateBottomBarHighlight(isEqPage: Boolean) {
        val screen = if (isEqPage) com.bearinmind.equalizer314.ui.NavScreen.EQ else com.bearinmind.equalizer314.ui.NavScreen.SETTINGS
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateHighlight(this, screen)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
    }

    /** Flip the EQ on/off (text button + graph-header icon). */
    private fun toggleEq() {
        val eq = stateManager.parametricEq
        eq.isEnabled = !eq.isEnabled
        // Persist immediately so the bottom-nav status label (which reads the eqEnabled pref) and the next cold start agree.
        eqPrefs.saveEqEnabled(eq.isEnabled)
        updateEqToggleUI()
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
        if (stateManager.isProcessing) {
            stateManager.eqService?.setEqEnabled(eq.isEnabled)
        }
    }

    private fun updateEqToggleUI() {
        val enabled = stateManager.parametricEq.isEnabled
        eqToggleButton.text = if (enabled) "EQ: ON" else "EQ: OFF"
        if (::eqPowerToggle.isInitialized) applyEqToggleVisual(enabled)
    }

    /** Graph-header EQ toggle styling: "ON"/"OFF" text, lit (filled bg + brighter stroke) when on, dim outlined when off. */
    private fun applyEqToggleVisual(enabled: Boolean) {
        if (!::eqPowerToggle.isInitialized) return
        val d = resources.displayMetrics.density
        eqPowerToggle.text = if (enabled) "ON" else "OFF"
        if (enabled) {
            eqPowerToggle.setBackgroundColor(graphBtnLitBg)
            eqPowerToggle.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
            eqPowerToggle.strokeWidth = (2 * d).toInt()
            eqPowerToggle.setTextColor(graphBtnLitContent)
        } else {
            eqPowerToggle.setBackgroundColor(graphBtnDimBg)
            eqPowerToggle.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
            eqPowerToggle.strokeWidth = (1 * d).toInt()
            eqPowerToggle.setTextColor(if (isLightUi) 0xFF8B8B8B.toInt() else 0xFF777777.toInt())
        }
    }

    // ---- Filter Type Buttons ----

    private fun setupFilterTypeButtons() {
        filterTypeGroup.removeAllViews()

        // PEAK — first tap applies BELL (PK); a second tap in the peak family opens the PK/BP/NO dropdown.
        val peakBtn = buildFilterTypeButton(
            label = "PEAK",
            defaultSubtitle = "",
            weightedWidth = true,
        )
        peakBtn.setOnClickListener { anchor ->
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            val band = stateManager.parametricEq.getBand(bandIndex)
            val inFamily = band != null && band.enabled && isPeakFamily(band.filterType)
            if (inFamily) {
                showPeakPopup(anchor, bandIndex)
            } else {
                applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.BELL)
            }
        }
        filterTypeGroup.addView(peakBtn)

        // Shelves & passes — each has a 2-tap 12 dB / 6 dB slope popup.
        val shelfPassTypes = listOf(
            "LSHELF" to BiquadFilter.FilterType.LOW_SHELF,
            "HSHELF" to BiquadFilter.FilterType.HIGH_SHELF,
            "LPF" to BiquadFilter.FilterType.LOW_PASS,
            "HPF" to BiquadFilter.FilterType.HIGH_PASS,
        )
        for ((label, type) in shelfPassTypes) {
            val btn = buildFilterTypeButton(
                label = label,
                defaultSubtitle = "12 dB",
                weightedWidth = true,
            )
            btn.setOnClickListener { anchor ->
                val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
                val band = stateManager.parametricEq.getBand(bandIndex)
                val alreadyActive = band != null &&
                    band.enabled &&
                    filterTypeFamily(band.filterType) == type
                if (alreadyActive) {
                    showSlopePopup(anchor, bandIndex, type)
                } else {
                    applyFilterTypeToBand(bandIndex, type)
                }
            }
            filterTypeGroup.addView(btn)
        }

        // BYPASS — tied 1:1 to APO's `AP` token: sets ALL_PASS (flat magnitude on-device, exports as `Filter N: ON AP ...`).
        val bypassBtn = buildFilterTypeButton(
            label = "BYPASS",
            defaultSubtitle = "",
            weightedWidth = true,
        )
        bypassBtn.setOnClickListener {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.ALL_PASS)
        }
        filterTypeGroup.addView(bypassBtn)
    }

    // Per-band L/Both/R picker popup (showBandChannelPopup / onBandChannelPicked) retired — see LegacyFeatures.kt.

    private fun updateFilterTypeButtons(bandIndex: Int?) {
        // TV Mode nav sync — fires on band selection changes.
        com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        if (bandIndex == null) return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val currentType = band.filterType
        val currentFamily = filterTypeFamily(currentType)
        val currentIs1st = currentType == BiquadFilter.FilterType.LOW_SHELF_1 ||
                           currentType == BiquadFilter.FilterType.HIGH_SHELF_1 ||
                           currentType == BiquadFilter.FilterType.LOW_PASS_1 ||
                           currentType == BiquadFilter.FilterType.HIGH_PASS_1
        val bandEnabled = band.enabled

        // Single row: order matches how buttons are added in setupFilterTypeButtons: PEAK, LSHELF, HSHELF, LPF, HPF, BYPASS.
        data class Entry(val btn: MaterialButton, val label: String, val role: Role)
        val roles = listOf(Role.PEAK, Role.SHELF_PASS, Role.SHELF_PASS, Role.SHELF_PASS, Role.SHELF_PASS, Role.BYPASS)
        val labels = listOf("PEAK", "LSHELF", "HSHELF", "LPF", "HPF", "BYPASS")
        val typesForHighlight = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS,
            BiquadFilter.FilterType.BELL,        // unused for BYPASS
        )
        val entries = mutableListOf<Entry>()
        for (i in 0 until filterTypeGroup.childCount) {
            val btn = filterTypeGroup.getChildAt(i) as? MaterialButton ?: continue
            if (i >= roles.size) break
            entries += Entry(btn, labels[i], roles[i])
        }

        val inPeakFam = isPeakFamily(currentType)
        val isAllPass = currentType == BiquadFilter.FilterType.ALL_PASS

        for ((i, e) in entries.withIndex()) {
            val buttonType = typesForHighlight[i]
            val sameFamily = filterTypeFamily(buttonType) == currentFamily
            val isActive = when (e.role) {
                Role.PEAK -> bandEnabled && inPeakFam
                Role.SHELF_PASS -> bandEnabled && sameFamily
                // BYPASS is tied to AP: highlighted when band is ALL_PASS OR when band is disabled (legacy presets).
                Role.BYPASS -> !bandEnabled || isAllPass
            }

            if (isActive) {
                e.btn.setBackgroundColor(getColor(R.color.filter_active))
                e.btn.setTextColor(getColor(R.color.filter_active_text))
            } else {
                e.btn.setBackgroundColor(0x00000000)
                e.btn.setTextColor(getColor(R.color.filter_inactive_text))
                e.btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_outline))
            }

            // Subtitle: PEAK blank (sub-type replaces the primary label); shelves/passes show current slope if active else "12 dB".
            val primary: String = when (e.role) {
                Role.PEAK -> peakButtonLabel(currentType, bandEnabled)
                else -> e.label
            }
            val subtitle: String = when (e.role) {
                Role.PEAK -> ""
                Role.SHELF_PASS -> when {
                    sameFamily -> if (currentIs1st) "6 dB" else "12 dB"
                    else -> "12 dB"
                }
                Role.BYPASS -> ""
            }
            e.btn.text = buildFilterButtonText(primary, subtitle)
        }
    }

    /** PEAK button primary label. */
    private fun peakButtonLabel(current: BiquadFilter.FilterType, bandEnabled: Boolean): String = when {
        !bandEnabled -> "PEAK"
        current == BiquadFilter.FilterType.BAND_PASS -> "B. PASS"
        current == BiquadFilter.FilterType.NOTCH -> "NOTCH"
        else -> "PEAK"
    }

    private enum class Role { PEAK, SHELF_PASS, BYPASS }

    /** PEAK dropdown membership: plain bell + gainless Fc+Q specials (BP / NO). */
    private fun isPeakFamily(t: BiquadFilter.FilterType): Boolean = when (t) {
        BiquadFilter.FilterType.BELL,
        BiquadFilter.FilterType.BAND_PASS,
        BiquadFilter.FilterType.NOTCH -> true
        else -> false
    }


    /** Collapse 1st/2nd-order variants into one "family" key so highlighting treats LShelf / LShelf (6 dB) as the same button. */
    private fun filterTypeFamily(t: BiquadFilter.FilterType): BiquadFilter.FilterType = when (t) {
        BiquadFilter.FilterType.LOW_SHELF_1 -> BiquadFilter.FilterType.LOW_SHELF
        BiquadFilter.FilterType.HIGH_SHELF_1 -> BiquadFilter.FilterType.HIGH_SHELF
        BiquadFilter.FilterType.LOW_PASS_1 -> BiquadFilter.FilterType.LOW_PASS
        BiquadFilter.FilterType.HIGH_PASS_1 -> BiquadFilter.FilterType.HIGH_PASS
        else -> t
    }

    /** Given a 2nd-order filter family button, return the matching 1st-order type. */
    private fun oneOrderVariant(family: BiquadFilter.FilterType): BiquadFilter.FilterType? = when (family) {
        BiquadFilter.FilterType.LOW_SHELF -> BiquadFilter.FilterType.LOW_SHELF_1
        BiquadFilter.FilterType.HIGH_SHELF -> BiquadFilter.FilterType.HIGH_SHELF_1
        BiquadFilter.FilterType.LOW_PASS -> BiquadFilter.FilterType.LOW_PASS_1
        BiquadFilter.FilterType.HIGH_PASS -> BiquadFilter.FilterType.HIGH_PASS_1
        else -> null
    }

    /** Filter-button label: two-line with subtitle ("LSHELF\n12 dB"). */
    private fun buildFilterButtonText(primary: String, subtitle: String): CharSequence {
        val full = if (subtitle.isEmpty()) primary else "$primary\n$subtitle"
        val span = android.text.SpannableString(full)
        val shrink = when {
            primary.length >= 8 -> 0.7f
            else -> 1f
        }
        if (shrink < 1f) {
            span.setSpan(
                android.text.style.RelativeSizeSpan(shrink),
                0, primary.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }

    /** Apply a filter type to a band and refresh every dependent surface (graph, toggles, inputs, DP pipeline, saved state). */
    private fun applyFilterTypeToBand(bandIndex: Int, newType: BiquadFilter.FilterType) {
        stateManager.parametricEq.setBandEnabled(bandIndex, true)
        eqGraphView.setFilterType(bandIndex, newType)
        updateFilterTypeButtons(bandIndex)
        updateBandInputs(bandIndex)
        bandToggleManager.updateIcons()
        bandToggleManager.updateSelection(bandIndex)
        stateManager.pushEqUpdate()
        stateManager.eqPrefs.saveState(stateManager.parametricEq, stateManager.bandSlots)
        stateManager.persistLeftRightIfCse()
    }

    /** Filter-type button factory. */
    private fun buildFilterTypeButton(
        label: String,
        defaultSubtitle: String,
        weightedWidth: Boolean,
        fixedWidthPx: Int = 0,
    ): MaterialButton {
        val density = resources.displayMetrics.density
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = null
            text = buildFilterButtonText(label, defaultSubtitle)
            textSize = 11f
            cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius)
            isSingleLine = false
            maxLines = 2
            // Explicit centering on both axes so primary + subtitle stay centered regardless of label length or subtitle state.
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            // Drop font ascender/descender padding so two-line text measures exactly 2x line height and centers cleanly.
            includeFontPadding = false
            layoutParams = if (weightedWidth) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(fixedWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            }.apply { setMargins(3, 0, 3, 0) }
            val vertPad = (6 * density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0
            insetBottom = 0
            // Common minimum height so every button in the row is the same size, regardless of primary-label shrink factor.
            minimumHeight = (42 * density).toInt()
            minHeight = (42 * density).toInt()
        }
    }

    /** PEAK dropdown (PK / BP / NO) below the button, styled like showSlopePopup. */
    private fun showPeakPopup(anchor: View, bandIndex: Int) {
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val current = band.filterType
        val density = resources.displayMetrics.density
        val cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = getColor(R.color.filter_outline)
        val activeBg = getColor(R.color.filter_active)
        val activeTx = getColor(R.color.filter_active_text)
        val inactiveTx = getColor(R.color.filter_inactive_text)
        val bgColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = android.widget.PopupWindow(
            container,
            anchor.width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, type: BiquadFilter.FilterType) {
            val isActive = current == type
            val item = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    applyFilterTypeToBand(bandIndex, type)
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
            }
            container.addView(item)
        }

        fun addDivider() {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt(),
                )
                setBackgroundColor(outlineColor)
            }
            container.addView(divider)
        }

        // PK at top so the user can return from BP/NO to a plain bell without round-tripping through another family.
        addItem("PK", BiquadFilter.FilterType.BELL)
        addDivider()
        addItem("BP", BiquadFilter.FilterType.BAND_PASS)
        addDivider()
        addItem("NO", BiquadFilter.FilterType.NOTCH)

        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }

    /** Outlined popup below the tapped filter button (same width/outline/surface). */
    private fun showSlopePopup(
        anchor: View,
        bandIndex: Int,
        family2nd: BiquadFilter.FilterType,
    ) {
        val family1st = oneOrderVariant(family2nd) ?: return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val currentIs1st = band.filterType == family1st
        val density = resources.displayMetrics.density
        val cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = getColor(R.color.filter_outline)
        val activeBg = getColor(R.color.filter_active)
        val activeTx = getColor(R.color.filter_active_text)
        val inactiveTx = getColor(R.color.filter_inactive_text)
        // Match the parametric controls card's fill so the popup reads as an extension of the same surface.
        val bgColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = android.widget.PopupWindow(
            container,
            anchor.width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, isActive: Boolean, onTap: () -> Unit) {
            val item = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    onTap()
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
                setBackgroundResource(0)
                if (isActive) setBackgroundColor(activeBg)
            }
            container.addView(item)
        }

        addItem("12 dB", !currentIs1st) { applyFilterTypeToBand(bandIndex, family2nd) }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt(),
            )
            setBackgroundColor(outlineColor)
        }
        container.addView(divider)
        addItem("6 dB", currentIs1st) { applyFilterTypeToBand(bandIndex, family1st) }

        // Drop it directly under the button with a tiny gap.
        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        if (!stateManager.serviceBound) {
            val intent = Intent(this, EqService::class.java)
            bindService(intent, stateManager.serviceConnection, 0)
        }
    }

    /** Geometry cached on first layout so `repositionChannelBadge` can re-anchor the badge when its text changes. */
    private var badgeAnchorAltRouteLeft: Int = 0
    private var badgeAnchorSpecWidth: Int = 0
    private var badgeAnchorBtnTop: Int = 0

    /** Position the L/R badge in the split icon's top-right. */
    private fun repositionChannelBadge(badge: android.widget.TextView) {
        val density = resources.displayMetrics.density
        badge.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val lp = badge.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = badgeAnchorAltRouteLeft + badgeAnchorSpecWidth - badge.measuredWidth - (6 * density).toInt()
        lp.topMargin = badgeAnchorBtnTop + (5 * density).toInt()
        badge.layoutParams = lp
    }

    /** Paint L/R button bg/stroke/text per active channel. */
    private fun paintChannelButtonStyles() {
        // TV Mode nav sync — fires on L / R / Both view changes.
        com.bearinmind.equalizer314.remote.TvRemoteHub.onLocalEqChanged()
        val enabled = eqPrefs.getChannelSideEqEnabled()
        val lBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelLButton) ?: return
        val rBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelRButton) ?: return
        val bothBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelBothButton)
        val density = resources.displayMetrics.density
        // The "L"/"R" glyphs read lighter than the icon buttons.
        val lrDim = if (isLightUi) 0xFF2E2E2E.toInt() else 0xFF888888.toInt()
        fun paint(btn: com.google.android.material.button.MaterialButton, pressed: Boolean) {
            if (pressed) {
                btn.setBackgroundColor(graphBtnLitBg)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnLitStroke)
                btn.strokeWidth = (2 * density).toInt()
                btn.setTextColor(graphBtnLitContent)
            } else {
                btn.setBackgroundColor(graphBtnDimBg)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(graphBtnDimStroke)
                btn.strokeWidth = (1 * density).toInt()
                btn.setTextColor(lrDim)
            }
        }
        val badge = findViewById<android.widget.TextView>(R.id.altRouteChannelBadge)
        val altRouteBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.altRouteButton)
        // Power button mirrors the CSE state: lit while Channel Side EQ is on, dim while off.
        findViewById<com.google.android.material.button.MaterialButton>(R.id.settingsGearButton)?.let { pwr ->
            paint(pwr, enabled)
            pwr.iconTint = android.content.res.ColorStateList.valueOf(
                if (enabled) graphBtnLitContent else graphBtnDimContent
            )
        }
        if (!enabled) {
            paint(lBtn, false); paint(rBtn, false)
            bothBtn?.let { paint(it, false) }
            badge?.visibility = View.GONE
            altRouteBtn?.setIconResource(R.drawable.ic_alt_route_right)
            return
        }
        val active = stateManager.activeChannel
        val bothView = stateManager.bothViewActive
        paint(lBtn, !bothView && active == EqStateManager.ActiveChannel.LEFT)
        paint(rBtn, !bothView && active == EqStateManager.ActiveChannel.RIGHT)
        // "Both" lights up while the Both edit view is active.
        bothBtn?.let { paint(it, bothView) }
        badge?.let {
            when {
                bothView -> { it.text = "B"; it.visibility = View.VISIBLE }
                active == EqStateManager.ActiveChannel.LEFT -> { it.text = "L"; it.visibility = View.VISIBLE }
                active == EqStateManager.ActiveChannel.RIGHT -> { it.text = "R"; it.visibility = View.VISIBLE }
                else -> it.visibility = View.GONE
            }
            // Re-measure and re-anchor the badge so a subtle width change between "L" and "R" doesn't shove it past the button edge.
            if (it.visibility == View.VISIBLE) repositionChannelBadge(it)
        }
        // Swap the split-arrow for the single-branch icon in L/R mode.
        altRouteBtn?.setIconResource(
            if (active == EqStateManager.ActiveChannel.BOTH)
                R.drawable.ic_alt_route_right
            else
                R.drawable.ic_alt_route_right_solid
        )
    }

    /** L/R popout visual state: CSE off = both dim outlined; CSE on = full alpha, active gets the filled "pressed" style. */
    private fun refreshChannelPopoutDim() {
        paintChannelButtonStyles()
        // Dim via COLOR only, never view alpha — a translucent button lets the graph's band badges render straight through it.
        val lBtn = findViewById<View>(R.id.channelLButton) ?: return
        val rBtn = findViewById<View>(R.id.channelRButton) ?: return
        lBtn.alpha = 1.0f; rBtn.alpha = 1.0f
        findViewById<View>(R.id.channelBothButton)?.alpha = 1.0f
    }

    /** Persist all three preamp values (shared + per-side). */
    private fun persistPreamps() {
        eqPrefs.savePreampGain(stateManager.preampGainDb)
        eqPrefs.savePreampLeft(stateManager.preampLeftDb)
        eqPrefs.savePreampRight(stateManager.preampRightDb)
    }

    /** Point the preamp slider/text at the active view's preamp value. */
    private fun syncPreampUi() {
        val v = stateManager.getActivePreamp()
        preampSlider.value = v.coerceIn(-preampRange(), preampRange())
        preampText.setText(String.format("%.1f", v))
    }

    /** Rebind graph + toggles + inputs after the active EQ reference changes (CSE on/off, L/R switch). */
    private fun rebindActiveEq() {
        val eq = stateManager.parametricEq
        eqGraphView.setParametricEqualizer(eq)
        syncPreampUi()
        // Dotted ghost curves: other channel in L/R views, both channels in the Both view (issue #53).
        stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
        // Shared "Both" layer summed into the drawn curves.
        eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
        // Each channel keeps its own slot list — refresh the labels.
        eqGraphView.setBandSlotLabels(stateManager.bandSlots)
        eqGraphView.updateBandLevels()
        stateManager.pushEqUpdate()

        val count = eq.getBandCount()
        if (count > 0) {
            val idx = (stateManager.selectedBandIndex ?: 0).coerceIn(0, count - 1)
            stateManager.selectedBandIndex = idx
            eqGraphView.setActiveBand(idx)
            updateFilterTypeButtons(idx)
            updateBandInputs(idx)
        }
        bandToggleManager.setupToggles()
        if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
        refreshChannelPopoutDim()
    }

    override fun onResume() {
        super.onResume()
        updateDevicePresetStatus()
        // "Add more EQ bands" cap lowered while away: drop bands beyond it, then refresh toggles + graph (issue #31).
        if (stateManager.enforceBandCap()) {
            rebindActiveEq()                 // rebuilds toggles + graph + DP
            reorderToggleRows(animate = false)
        } else if (lastAppliedMaxBands != EqStateManager.MAX_BANDS) {
            // Cap toggled (raised or lowered) with no trim needed — rebuild so the extra-rows scroll appears/disappears correctly.
            bandToggleManager.setupToggles()
            reorderToggleRows(animate = false)
        }
        lastAppliedMaxBands = EqStateManager.MAX_BANDS
        // Sync limiter mirror fields: LimiterActivity writes prefs directly.
        stateManager.limiterEnabled     = eqPrefs.getLimiterEnabled()
        stateManager.limiterAttackMs    = eqPrefs.getLimiterAttack()
        stateManager.limiterReleaseMs   = eqPrefs.getLimiterRelease()
        stateManager.limiterRatio       = eqPrefs.getLimiterRatio()
        stateManager.limiterThresholdDb = eqPrefs.getLimiterThreshold()
        stateManager.limiterPostGainDb  = eqPrefs.getLimiterPostGain()
        // Apply spectrum settings (may have changed in SpectrumControlActivity)
        applySpectrumSettings()
        // Sync CSE state — the switch lives in ChannelSideEqActivity, so the pref may have flipped while away.
        val cseOnNow = eqPrefs.getChannelSideEqEnabled()
        val cseOnInState = stateManager.activeChannel != EqStateManager.ActiveChannel.BOTH
        if (cseOnNow != cseOnInState) {
            stateManager.setChannelSideEqEnabled(cseOnNow)
            rebindActiveEq()
            paintChannelButtonStyles()
        } else {
            refreshChannelPopoutDim()
        }
        // Gain Reduction switch moved to AudioMiscSettingsActivity (pref-only there) — apply on return.
        autoGainSwitch.isChecked = eqPrefs.getAutoGainEnabled()
        // EQ mode tabs may have changed in the Change EQ Modes dialog (issue #77).
        applyEqModeTabs()
        // Keep the dotted ghost curves in sync on every resume, incl. cold start with CSE already on (issue #53).
        stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
        syncGraphToDrivingAppPreset()
        eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
        val savedPower = eqPrefs.getPowerState()
        com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, savedPower)
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
            // A Disable-EQ binding keeps power on with the DP detached — show the real state.
            if (savedPower && !stateManager.isProcessing && stateManager.eqService!!.isDisabledByDevice) {
                com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, false)
            }
            // Safety net: restore the EQ after an app-switch dropout even if the watchdog hasn't ticked.
            stateManager.eqService?.let { svc ->
                if (svc.dynamicsManager.isActive && svc.dynamicsManager.hasLostControl()) {
                    svc.requestWatchdogCheck()
                }
            }
        } else {
            stateManager.isProcessing = savedPower
        }
        // Restore EQ/Settings page highlight
        val isEqPage = findViewById<View>(R.id.pageEq).visibility == View.VISIBLE
        updateBottomBarHighlight(isEqPage)
        // Restart visualizer if it was enabled (may have been stopped in onPause)
        if (eqPrefs.getSpectrumEnabled() && !visualizerHelper.isRunning &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
        }
        // Refresh ON/OFF status under nav icons
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
        updateAutoEqStatus()
        updateTargetStatus()
    }

    override fun onPause() {
        super.onPause()
        // Release visualizer so other activities can use session 0
        visualizerHelper.stop()
        eqGraphView.spectrumRenderer = null
        // Save simple EQ gains if in simple mode
        if (stateManager.currentEqUiMode == EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
        }
        stateManager.saveState()
        eqPrefs.savePresetName(presetDropdown.text.toString())
    }

    override fun onStop() {
        super.onStop()
        // onStop runs more reliably than onPause on abrupt teardown — persist here too.
        if (stateManager.currentEqUiMode == EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
        }
        stateManager.saveState()
        if (stateManager.serviceBound) {
            try { unbindService(stateManager.serviceConnection) } catch (_: Exception) {}
            stateManager.serviceBound = false
        }
    }

    override fun onDestroy() {
        visualizerHelper.stop()
        try { unregisterReceiver(eqStoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(eqStartedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(statusRefreshReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(sessionsChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(dpRecycledReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // Don't retry startProcessing() — the original call already proceeded.
            if (grantResults.isNotEmpty()
                && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Notifications disabled — EQ will run without the Turn Off notification.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
            val vizBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.visualizerToggle)
            val d = resources.displayMetrics.density
            vizBtn.alpha = 1.0f
            vizBtn.setBackgroundColor(0xFF555555.toInt())
            vizBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            vizBtn.strokeWidth = (2 * d).toInt()
            vizBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // On focus the graph has its final width — re-assert header-button positions.
        if (hasFocus && ::eqGraphView.isInitialized && ::stateManager.isInitialized &&
            stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
            eqGraphView.post { relayoutGraphHeaderButtons() }
        }
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        // Which page is open — restored in onCreate after a recreation.
        outState.putBoolean(
            "onSettingsPage",
            ::pageSettings.isInitialized && pageSettings.visibility == View.VISIBLE
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
        } else {
            stateManager.isProcessing = false
        }
        if (intent?.getBooleanExtra("showSettings", false) == true) {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        } else {
            pageEq.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
            updateBottomBarHighlight(isEqPage = true)
        }
    }
}
