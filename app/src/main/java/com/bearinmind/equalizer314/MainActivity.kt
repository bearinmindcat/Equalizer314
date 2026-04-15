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
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Equalizer314"
    }

    // State manager
    private lateinit var stateManager: EqStateManager
    private lateinit var eqPrefs: EqPreferencesManager

    private var pendingExportText: String? = null
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

    // UI controllers
    private lateinit var graphicController: GraphicEqController
    private lateinit var tableController: TableEqController
    private lateinit var simpleEqController: SimpleEqController
    private lateinit var bandToggleManager: BandToggleManager
    private val visualizerHelper = com.bearinmind.equalizer314.audio.VisualizerHelper()

    private fun reloadEqFromPrefs() {
        stateManager.initEq(eqGraphView)
        stateManager.preampGainDb = eqPrefs.getPreampGain()
        preampSlider.value = stateManager.preampGainDb.coerceIn(-12f, 12f)
        preampText.setText(String.format("%.1f", stateManager.preampGainDb))
        eqGraphView.updateBandLevels()
        bandToggleManager.setupToggles()
        stateManager.updateDpBandVisualization(eqGraphView)
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
        if (result.resultCode == RESULT_OK) reloadEqFromPrefs()
    }

    private val targetCurveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) reloadEqFromPrefs()
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
            // Apply profile
            val eq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
            eq.clearBands()
            for (filter in profile.filters) {
                val filterType = when (filter.filterType) {
                    "LSC" -> com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.LOW_SHELF
                    "HSC" -> com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.HIGH_SHELF
                    else -> com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL
                }
                eq.addBand(filter.frequency, filter.gain, filterType, filter.q.toDouble())
            }
            eq.isEnabled = true
            val slots = (0 until eq.getBandCount()).toList()
            eqPrefs.saveState(eq, slots)
            eqPrefs.savePreampGain(profile.preampDb)
            eqPrefs.savePresetName("APO Import")
            eqPrefs.saveAutoEqName("")
            eqPrefs.saveAutoEqSource("")
            reloadEqFromPrefs()
            android.widget.Toast.makeText(this, "Applied ${profile.filters.size} filters", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Views
    private lateinit var eqGraphView: EqGraphView
    private lateinit var eqToggleButton: MaterialButton
    private lateinit var presetDropdown: MaterialAutoCompleteTextView
    private lateinit var filterTypeGroup: LinearLayout
    private lateinit var qSlider: Slider
    private lateinit var qValueText: TextView
    private lateinit var qControlGroup: View
    private lateinit var powerButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var modeDescriptionText: TextView
    private lateinit var bandToggleGroup: LinearLayout
    private lateinit var bandToggleGroup2: LinearLayout
    private lateinit var triangleIndicator: View
    private lateinit var bandInputGroup: View
    private lateinit var pageEq: View
    private lateinit var pageSettings: View
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

    // Listen for EQ stopped from notification "Turn Off" button
    private val eqStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stateManager.isProcessing = false
            stateManager.eqService = null
            if (stateManager.serviceBound) {
                try { unbindService(stateManager.serviceConnection) } catch (_: Exception) {}
                stateManager.serviceBound = false
            }
            animatePowerFab(false)
            showPowerSnackbar(false)
        }
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

        // On fresh app launch, reset power state — user must explicitly turn on
        if (savedInstanceState == null) {
            eqPrefs.savePowerState(false)
            stateManager.pendingStartEq = false
        }

        initViews()
        initControllers()
        initEQ()
        syncPreampUI()
        setupListeners()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                eqStoppedReceiver,
                IntentFilter(EqService.ACTION_EQ_STOPPED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                eqStoppedReceiver,
                IntentFilter(EqService.ACTION_EQ_STOPPED)
            )
        }

        val savedMode = try { EqUiMode.valueOf(eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
        val effectiveMode = if (eqPrefs.getSimpleEqEnabled()) EqUiMode.SIMPLE else savedMode
        switchEqUiMode(effectiveMode)
        // Ensure rows are properly ordered after views are laid out
        pageEq.post { reorderToggleRows(animate = false) }
    }

    private fun initViews() {
        eqGraphView = findViewById(R.id.eqGraphView)
        eqToggleButton = findViewById(R.id.eqToggleButton)
        presetDropdown = findViewById(R.id.presetSpinner)
        filterTypeGroup = findViewById(R.id.filterTypeGroup)
        qSlider = findViewById(R.id.qSlider)
        qValueText = findViewById(R.id.qValueText)
        qControlGroup = findViewById(R.id.qControlGroup)
        powerButton = findViewById(R.id.powerButton)
        statusText = findViewById(R.id.statusText)
        modeDescriptionText = findViewById(R.id.modeDescriptionText)
        bandToggleGroup = findViewById(R.id.bandToggleGroup)
        bandToggleGroup2 = findViewById(R.id.bandToggleGroup2)
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

        eqGraphView.showDpBands = true
        eqGraphView.showSaturationCurve = false

        updateBottomBarHighlight(isEqPage = true)
    }

    /** Called after initEQ() to sync preamp UI with restored state */
    private fun syncPreampUI() {
        preampSlider.value = stateManager.preampGainDb.coerceIn(-12f, 12f)
        preampText.setText(String.format("%.1f", stateManager.preampGainDb))
        autoGainSwitch.isChecked = stateManager.autoGainEnabled
        updateAutoGainOffsetText()
    }

    private fun initControllers() {
        val onEqChanged = {
            stateManager.pushEqUpdate()
            stateManager.updateDpBandVisualization(eqGraphView)
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
            this, bandToggleGroup, bandToggleGroup2, triangleIndicator, eqGraphView, stateManager,
            onEqChanged, onBandCountChanged, onBandSelected
        )

        // Wire state manager callbacks
        stateManager.onProcessingChanged = { active ->
            animatePowerFab(active)
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
        stateManager.updateDpBandVisualization(eqGraphView)

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
        val undoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.undoButton)
        val redoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.redoButton)
        val bandPtsBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandPointsToggle)
        val saveBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.savePresetButton)
        val vizDensity = resources.displayMetrics.density
        val gapPx = (2 * vizDensity).toInt()
        eqGraphView.post {
            val viewWidth = eqGraphView.width
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

            // Save preset button: right next to eye button
            val saveLp = saveBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            saveLp.width = specWidth
            saveLp.height = btnHeight
            saveLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            saveLp.leftMargin = gapPx + specWidth + gapPx
            saveLp.topMargin = btnTop
            saveBtn.layoutParams = saveLp
            saveBtn.minimumWidth = 0; saveBtn.minimumHeight = 0
            saveBtn.setPadding(0, 0, 0, 0)
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

            // "+" button at top — exact copy of the band add button style, full width
            val saveCurrentBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
                cornerRadius = (12 * density).toInt()
                textSize = 11f
                val vertPad = (6 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                insetTop = 0; insetBottom = 0
                minWidth = 0; minimumWidth = 0
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0x00000000)
                setTextColor(0xFF888888.toInt())
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
            }
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
                        val eq = stateManager.parametricEq
                        stateManager.eqPrefs.saveState(eq)
                        val json = org.json.JSONObject()
                        json.put("preamp", stateManager.preampGainDb)
                        val bands = org.json.JSONArray()
                        for (b in eq.getAllBands()) {
                            val bj = org.json.JSONObject()
                            bj.put("frequency", b.frequency)
                            bj.put("gain", b.gain)
                            bj.put("q", b.q)
                            bj.put("filterType", b.filterType.name)
                            bj.put("enabled", b.enabled)
                            bands.put(bj)
                        }
                        json.put("bands", bands)
                        prefs.edit()
                            .putString("preset_$name", json.toString())
                            .putStringSet("preset_names", (presetNames.toMutableSet() + name))
                            .apply()
                        populatePresetPicker()
                        android.widget.Toast.makeText(this, "Saved \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                dialog.show()
            }
            presetPickerContainer.addView(saveCurrentBtn)

            // List saved presets — styled like (+) band buttons
            for (name in presetNames) {
                // Parse preset data for thumbnail
                val presetJson = prefs.getString("preset_$name", null)
                val bandCount = try {
                    org.json.JSONObject(presetJson ?: "{}").getJSONArray("bands").length()
                } catch (_: Exception) { 0 }

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

                // Mini EQ curve thumbnail
                val thumbW = (48 * density).toInt()
                val thumbH = (24 * density).toInt()
                val thumbnail = object : android.view.View(this) {
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        super.onDraw(canvas)
                        val w = width.toFloat(); val h = height.toFloat()
                        if (w <= 0 || h <= 0 || presetJson == null) return
                        try {
                            val obj = org.json.JSONObject(presetJson)
                            val bands = obj.getJSONArray("bands")
                            val eq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
                            eq.clearBands()
                            for (i in 0 until bands.length()) {
                                val b = bands.getJSONObject(i)
                                val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(b.getString("filterType")) }
                                         catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                                eq.addBand(b.getDouble("frequency").toFloat(), b.getDouble("gain").toFloat(), ft, b.getDouble("q"))
                            }
                            val path = android.graphics.Path()
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = 0xFFAAAAAA.toInt(); strokeWidth = 0.5f * density; style = android.graphics.Paint.Style.STROKE
                            }
                            val gridPaint = android.graphics.Paint().apply { color = 0xFF6A6A6A.toInt(); strokeWidth = 1f }
                            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
                            canvas.drawLine(0f, 0f, 0f, h, gridPaint)
                            val maxDb = 15f; val steps = 50
                            for (s in 0..steps) {
                                val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
                                val freq = 10f.pow(logF)
                                val db = eq.getFrequencyResponse(freq)
                                val x = w * s / steps; val y = (h / 2f - (db / maxDb) * (h / 2f)).coerceIn(0f, h)
                                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            canvas.drawPath(path, paint)
                        } catch (_: Exception) {}
                    }
                }.apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(thumbW, thumbH)
                }

                // Left side: preset name
                val nameText = android.widget.TextView(this).apply {
                    text = name
                    setTextColor(0xFFE2E2E2.toInt())
                    textSize = 14f
                    isSingleLine = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER_VERTICAL
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
                    icon = resources.getDrawable(R.drawable.ic_export, theme)
                    iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    iconSize = (18 * density).toInt()
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                exportBtn.setOnClickListener {
                    val presetJson = prefs.getString("preset_$name", null) ?: return@setOnClickListener
                    val obj = org.json.JSONObject(presetJson)
                    val sb = StringBuilder()
                    val preamp = obj.optDouble("preamp", 0.0)
                    sb.append("Preamp: ${String.format("%.1f", preamp)} dB\n")
                    val bands = obj.getJSONArray("bands")
                    for (i in 0 until bands.length()) {
                        val b = bands.getJSONObject(i)
                        val ft = b.getString("filterType")
                        val apoType = when (ft) { "LOW_SHELF" -> "LSC"; "HIGH_SHELF" -> "HSC"; else -> "PK" }
                        sb.append("Filter ${i + 1}: ON $apoType Fc ${b.getDouble("frequency").toInt()} Hz Gain ${String.format("%.1f", b.getDouble("gain"))} dB Q ${String.format("%.2f", b.getDouble("q"))}\n")
                    }
                    pendingExportText = sb.toString()
                    val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TITLE, "${name}.txt")
                    }
                    presetExportLauncher.launch(intent)
                }

                rightCol.addView(thumbnail)
                rightCol.addView(filtersText)
                presetRow.addView(nameText)
                presetRow.addView(rightCol)
                presetRow.addView(exportBtn)
                presetRow.addView(deleteBtn)
                // Tap to load preset
                presetRow.setOnClickListener {
                    val json = prefs.getString("preset_$name", null) ?: return@setOnClickListener
                    val obj = org.json.JSONObject(json)
                    val eq = stateManager.parametricEq ?: return@setOnClickListener
                    eq.clearBands()
                    val bandsArr = obj.getJSONArray("bands")
                    for (i in 0 until bandsArr.length()) {
                        val bj = bandsArr.getJSONObject(i)
                        val ft = try { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(bj.getString("filterType")) }
                                 catch (_: Exception) { com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL }
                        eq.addBand(bj.getDouble("frequency").toFloat(), bj.getDouble("gain").toFloat(), ft, bj.getDouble("q"))
                        if (bj.has("enabled")) eq.setBandEnabled(i, bj.getBoolean("enabled"))
                    }
                    eqGraphView.setParametricEqualizer(eq)
                    stateManager.eqPrefs.saveState(eq)
                    stateManager.initBandSlots()
                    bandToggleManager.setupToggles()
                    if (obj.has("preamp")) {
                        stateManager.preampGainDb = obj.getDouble("preamp").toFloat()
                        stateManager.eqPrefs.savePreampGain(stateManager.preampGainDb)
                    }
                    if (stateManager.isProcessing) {
                        stateManager.eqService?.let { svc -> svc.dynamicsManager.stop(); svc.dynamicsManager.start(eq) }
                    }
                    // Close picker with animation
                    presetPickerOpen = false
                    eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                    eqControlsContainerLocal.alpha = 0f
                    eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                        presetPickerScroll.visibility = android.view.View.GONE
                        presetPickerScroll.alpha = 1f
                    }.start()
                    saveBtn.setBackgroundColor(0x00000000)
                    saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    android.widget.Toast.makeText(this, "Loaded \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                }
                presetPickerContainer.addView(presetRow)
            }
        }

        saveBtn.setOnClickListener {
            presetPickerOpen = !presetPickerOpen
            if (presetPickerOpen) {
                populatePresetPicker()
                presetPickerScroll.visibility = android.view.View.VISIBLE
                presetPickerScroll.alpha = 0f
                presetPickerScroll.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                eqControlsContainerLocal.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    eqControlsContainerLocal.visibility = android.view.View.GONE
                    eqControlsContainerLocal.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(0xFF555555.toInt())
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                eqControlsContainerLocal.alpha = 0f
                eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    presetPickerScroll.visibility = android.view.View.GONE
                    presetPickerScroll.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(0x00000000)
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        // Band points toggle: active by default (points shown)
        var bandPointsVisible = true
        bandPtsBtn.setBackgroundColor(0xFF555555.toInt())
        bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
        bandPtsBtn.strokeWidth = (2 * vizDensity).toInt()
        bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
        bandPtsBtn.setOnClickListener {
            bandPointsVisible = !bandPointsVisible
            eqGraphView.showBandPoints = bandPointsVisible
            eqGraphView.invalidate()
            if (bandPointsVisible) {
                bandPtsBtn.setIconResource(R.drawable.ic_visibility)
                bandPtsBtn.setBackgroundColor(0xFF555555.toInt())
                bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                bandPtsBtn.strokeWidth = (2 * vizDensity).toInt()
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                bandPtsBtn.setIconResource(R.drawable.ic_visibility_off)
                bandPtsBtn.setBackgroundColor(0x00000000)
                bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                bandPtsBtn.strokeWidth = (1 * vizDensity).toInt()
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
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

                // Show reset, undo, redo — all pop out from edit button
                resetBtn.visibility = android.view.View.VISIBLE
                undoBtn.visibility = android.view.View.VISIBLE
                redoBtn.visibility = android.view.View.VISIBLE
                resetBtn.alpha = 0f; resetBtn.scaleX = 0.3f; resetBtn.scaleY = 0.3f; resetBtn.translationY = offsetY
                undoBtn.alpha = 0f; undoBtn.scaleX = 0.3f; undoBtn.scaleY = 0.3f; undoBtn.translationY = offsetY
                redoBtn.alpha = 0f; redoBtn.scaleX = 0.3f; redoBtn.scaleY = 0.3f; redoBtn.translationY = offsetY

                resetBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                undoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                redoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()

                editBtn.setBackgroundColor(0xFF555555.toInt())
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
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

                editBtn.setBackgroundColor(0x00000000)
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
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
                vizToggle.setBackgroundColor(0xFF555555.toInt())
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                vizToggle.strokeWidth = (2 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(0x00000000)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                vizToggle.strokeWidth = (1 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
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

        // EQ toggle
        eqToggleButton.setOnClickListener {
            val eq = stateManager.parametricEq
            eq.isEnabled = !eq.isEnabled
            updateEqToggleUI()
            if (stateManager.isProcessing) {
                stateManager.eqService?.setEqEnabled(eq.isEnabled)
            }
        }

        // Preset dropdown
        presetDropdown.setOnItemClickListener { parent, _, position, _ ->
            val presetName = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            stateManager.loadPreset(presetName, eqGraphView)
            presetDropdown.setText(presetName, false)
            bandToggleManager.updateIcons()
            stateManager.updateDpBandVisualization(eqGraphView)
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
            stateManager.pushEqUpdate()
            updateBandInputs(bandIndex)
            stateManager.updateDpBandVisualization(eqGraphView)
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.updateSliderValues()
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
            stateManager.updateDpBandVisualization(eqGraphView)
            stateManager.pushEqUpdate()
        }

        dpBandCountText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val count = dpBandCountText.text.toString().toIntOrNull()?.coerceIn(128, 1024) ?: 128
                dpBandCountText.setText(count.toString())
                dpBandCountSlider.value = count.toFloat()
                ParametricToDpConverter.setNumBands(count)
                eqPrefs.saveDpBandCount(count)
                stateManager.updateDpBandVisualization(eqGraphView)
                stateManager.pushEqUpdate()
                dpBandCountText.clearFocus()
            }
            true
        }

        // Filter type buttons
        setupFilterTypeButtons()
        // Color swatches
        setupColorSwatches()

        // EQ mode selector
        modeParametricBtn.setOnClickListener { switchEqUiMode(EqUiMode.PARAMETRIC) }
        modeGraphicBtn.setOnClickListener { switchEqUiMode(EqUiMode.GRAPHIC) }
        modeTableBtn.setOnClickListener { switchEqUiMode(EqUiMode.TABLE) }

        // Settings controls
        setupSettingsListeners()
    }

    // ---- Settings ----

    private fun updateAutoEqStatus() {
        val name = eqPrefs.getAutoEqName()
        val statusText = findViewById<TextView>(R.id.autoEqStatusText)
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
        val name = eqPrefs.getSelectedTargetName()
        val statusText = findViewById<TextView>(R.id.targetStatusText)
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

    private fun setupSpectrumControl() {
        findViewById<android.view.View>(R.id.spectrumControlCard).setOnClickListener {
            startActivity(android.content.Intent(this, SpectrumControlActivity::class.java))
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
        findViewById<View>(R.id.experimentalCard).setOnClickListener {
            startActivity(Intent(this, ExperimentalActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // AutoEQ card (settings page)
        findViewById<View>(R.id.autoEqCard).setOnClickListener {
            autoEqLauncher.launch(Intent(this, AutoEqActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // Target card (settings page) — opens Target Curve screen
        findViewById<View>(R.id.targetCard).setOnClickListener {
            targetCurveLauncher.launch(Intent(this, TargetCurveActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Spectrum Control
        setupSpectrumControl()

        // Preamp slider
        preampSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            preampText.setText(String.format("%.1f", value))
            stateManager.preampGainDb = value
            eqPrefs.savePreampGain(value)
            stateManager.pushEqUpdate()
            updateAutoGainOffsetText()
        }

        preampText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val gain = preampText.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
                preampText.setText(String.format("%.1f", gain))
                preampSlider.value = gain
                stateManager.preampGainDb = gain
                eqPrefs.savePreampGain(gain)
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

    private fun switchEqUiMode(mode: EqUiMode) {
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
            }
        }
        // Save the advanced EQ state before entering SIMPLE mode
        if (mode == EqUiMode.SIMPLE && stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
            val eq = stateManager.parametricEq
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
        stateManager.currentEqUiMode = mode
        eqGraphView.eqUiMode = mode
        if (mode != EqUiMode.SIMPLE) eqPrefs.saveEqUiMode(mode.name)
        updateModeSelectorButtons()

        // In non-SIMPLE modes, ensure standard views are visible and simple container hidden.
        // Also reparent the preamp card back into eqControlsContainer if it was moved.
        if (mode != EqUiMode.SIMPLE) {
            modeSelectorGroup.visibility = View.VISIBLE
            graphCardView.visibility = View.VISIBLE
            eqControlsContainer.visibility = View.VISIBLE
            simpleEqContainer.visibility = View.GONE

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

            // Re-sync band toggles and graph after returning from SIMPLE mode
            // (SIMPLE mode replaces the EQ with 10 fixed bands, so we need to
            // refresh everything after restoring the advanced EQ state)
            bandToggleManager.setupToggles()
            eqGraphView.updateBandLevels()

            // Re-position the graph overlay buttons (visibility, save, reset, edit,
            // spectrum). They were positioned via eqGraphView.post{} at startup, but
            // when the graph card was GONE in SIMPLE mode, the view's width was 0.
            // Now that it's VISIBLE again, we need to re-layout after the view has
            // its real width.
            eqGraphView.post {
                val viewWidth = eqGraphView.width
                if (viewWidth <= 0) return@post
                val vizDensity = resources.displayMetrics.density
                val gapPx = (2 * vizDensity).toInt()
                val vPadPx = 80
                val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
                val btnTop = gapPx
                val btnHeight = vPadPx - 2 * gapPx
                val specWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)
                val specLeft = gridLine10k + gapPx

                fun reposition(btn: View, leftMargin: Int) {
                    val lp = btn.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
                    lp.width = specWidth; lp.height = btnHeight
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    lp.leftMargin = leftMargin; lp.topMargin = btnTop
                    btn.layoutParams = lp
                }

                val vizToggle = findViewById<View>(R.id.visualizerToggle)
                val editBtn = findViewById<View>(R.id.editButton)
                val resetBtn = findViewById<View>(R.id.resetButton)
                val bandPtsBtn = findViewById<View>(R.id.bandPointsToggle)
                val saveBtn = findViewById<View>(R.id.savePresetButton)

                reposition(vizToggle, specLeft)
                reposition(editBtn, (specLeft - gapPx - specWidth).coerceAtLeast(gapPx))
                reposition(resetBtn, (specLeft - 2 * (gapPx + specWidth)).coerceAtLeast(gapPx))
                reposition(bandPtsBtn, gapPx)
                reposition(saveBtn, gapPx + specWidth + gapPx)
            }
        }

        when (mode) {
            EqUiMode.PARAMETRIC -> {
                tableEqCard.setOnTouchListener(null)
                // Restore preamp margin (this also accidentally sets the controls
                // FrameLayout's topMargin to 8dp — that's what positions the table
                // card; do NOT remove this line)
                val contentLayout0 = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                val preampCard0 = contentLayout0.getChildAt(contentLayout0.childCount - 1)
                (preampCard0.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * resources.displayMetrics.density).toInt()
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
                tableEqCard.setOnTouchListener(null)
                // Restore preamp margin (this also accidentally sets the controls
                // FrameLayout's topMargin to 8dp — that's what positions the table
                // card; do NOT remove this line)
                val contentLayoutG = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                val preampCardG = contentLayoutG.getChildAt(contentLayoutG.childCount - 1)
                (preampCardG.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * resources.displayMetrics.density).toInt()
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

                val density = resources.displayMetrics.density

                // Compute and apply the table card height + preamp translationY.
                // Wrapped in a closure so we can run it both synchronously (best effort
                // on cold start) and after the first layout pass (which corrects any
                // cold-start measurement errors when the views haven't been laid out
                // yet and parametricControlsCard.width is still 0).
                val applyTableSizing = {
                    // Use the actual outer LinearLayout's content width when available,
                    // falling back to (screen width - 32dp parent padding) on cold start.
                    val outerLayout = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                    val effectiveWidth = if (outerLayout.width > 0) {
                        outerLayout.width - outerLayout.paddingLeft - outerLayout.paddingRight
                    } else {
                        resources.displayMetrics.widthPixels - (32 * density).toInt()
                    }
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(effectiveWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

                    bandToggleGroup.measure(widthSpec, heightSpec)
                    parametricControlsCard.measure(widthSpec, heightSpec)

                    // Use bandToggleGroup.measuredHeight TWICE (for both row 1 and row 2)
                    // so the table card stays a constant size regardless of band count.
                    // Hardcode triangleContainer to 8dp because measure() returns 10dp
                    // (the inner triangleIndicator child's height) instead of the 8dp
                    // layout_height the parent uses in PARAM mode.
                    var targetHeight = bandToggleGroup.measuredHeight +
                        (8 * density).toInt() +
                        parametricControlsCard.measuredHeight +
                        bandToggleGroup.measuredHeight
                    // Add bottom margin from parametric card (8dp)
                    targetHeight += (8 * density).toInt()

                    val currentLp = tableEqCard.layoutParams
                    if (currentLp.height != targetHeight) {
                        tableEqCard.layoutParams = currentLp.apply { height = targetHeight }
                    }
                }

                // Synchronous best-effort
                applyTableSizing()

                // Re-run after the first layout pass — fixes cold-start case where the
                // synchronous measurements use stale (zero) widths and the buttons in
                // bandToggleGroup haven't been measured yet.
                pageEq.post {
                    if (stateManager.currentEqUiMode == EqUiMode.TABLE) applyTableSizing()
                }

                // Let table card's inner ScrollView handle touches, block outer scroll
                tableEqCard.setOnTouchListener { v, event ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }

                // Lock the table card at zero translation (defensive against any
                // leftover translationY from previous frames or animations).
                tableEqCard.translationY = 0f

                // Move ONLY the actual preamp card visually up by 7dp so it sits at
                // the same Y as in parametric/graphic mode. translationY does not
                // affect layout — the table card stays put.
                findViewById<View>(R.id.preampCardBar).translationY = -(7 * density)

                tableController.buildTable()
            }
            EqUiMode.SIMPLE -> {
                // Hide standard EQ UI
                modeSelectorGroup.visibility = View.GONE
                graphCardView.visibility = View.GONE
                eqControlsContainer.visibility = View.GONE

                // Ensure the controls overlay FrameLayout has the same topMargin
                // as it does in PARAMETRIC/GRAPHIC mode (the "Restore preamp margin"
                // code sets it to 8dp there, but doesn't run in SIMPLE mode).
                val overlay = simpleEqContainer.parent as? View
                (overlay?.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                    (8 * resources.displayMetrics.density).toInt()
                overlay?.requestLayout()

                // In SIMPLE mode the mode selector + graph card are GONE, so the
                // content starts right at the top of pageEq. MBC/Limiter activities
                // handle this with fitsSystemWindows on their root, but here we need
                // to manually offset. Set the outer LinearLayout's paddingTop to 0
                // (the rootLayout already handles the status bar inset) — this is
                // already 0dp from XML, no change needed.

                // Scroll to top so header starts at correct position
                (pageEq as android.widget.ScrollView).scrollTo(0, 0)

                // Show simple 10-band EQ
                simpleEqContainer.visibility = View.VISIBLE
                simpleEqController.configureParametricEq()
                simpleEqController.buildSliders()

                // Reparent the existing preamp card from eqControlsContainer into
                // simpleEqContainer (between the bars card and the undo/redo/reset
                // card). This reuses the exact same preamp card used in
                // parametric/graphic/table modes.
                val preampCard = findViewById<View>(R.id.preampCardBar)
                (preampCard.parent as? android.view.ViewGroup)?.removeView(preampCard)
                // Insert at index 3: after header (0), mini graph (1), bars card (2),
                // before undo/redo/reset controls card (3→4)
                simpleEqContainer.addView(preampCard, 3)
                preampCard.translationY = 0f
                // Set consistent 8dp bottom margin (remove the XML topMargin=8dp to
                // avoid double-spacing since bars card already has bottomMargin=8dp)
                (preampCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = 0
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
        }
        eqGraphView.invalidate()
    }

    private fun reorderToggleRows(animate: Boolean = true) {
        if (stateManager.currentEqUiMode == EqUiMode.TABLE || stateManager.currentEqUiMode == EqUiMode.SIMPLE) return
        val parent = findViewById<LinearLayout>(R.id.eqControlsContainer)
        val triContainer = findViewById<View>(R.id.triangleIndicatorContainer)

        // Determine which page the selected band is on
        val selectedBand = stateManager.selectedBandIndex ?: 0
        val displayPos = stateManager.displayToBandIndex.indexOf(selectedBand).let { if (it < 0) 0 else it }
        val activePage = displayPos / 8
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
        if (currentActiveIdx == graphIdx + 1 && currentInactiveIdx == expectedInactiveIdx) return

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
    }

    private fun updateModeSelectorButtons() {
        val buttons = listOf(
            modeParametricBtn to EqUiMode.PARAMETRIC,
            modeGraphicBtn to EqUiMode.GRAPHIC,
            modeTableBtn to EqUiMode.TABLE
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
            stateManager.updateDpBandVisualization(eqGraphView)
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
            stateManager.updateDpBandVisualization(eqGraphView)
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
        val hz = bandHzInput.text.toString().toFloatOrNull() ?: return
        val clamped = hz.coerceIn(10f, 20000f)
        isUpdatingInputs = true
        bandHzSlider.value = hzToSlider(clamped)
        isUpdatingInputs = false
        applyBandHz(clamped)
    }

    private fun applyBandDbFromInput() {
        if (isUpdatingInputs) return
        val db = bandDbInput.text.toString().toFloatOrNull() ?: return
        val clamped = db.coerceIn(-12f, 12f)
        isUpdatingInputs = true
        bandDbSlider.value = clamped
        isUpdatingInputs = false
        applyBandDb(clamped)
    }

    private fun applyBandQFromInput() {
        if (isUpdatingInputs) return
        val q = bandQInput.text.toString().toDoubleOrNull() ?: return
        val clamped = q.coerceIn(0.1, 12.0)
        isUpdatingInputs = true
        qSlider.value = clamped.toFloat()
        isUpdatingInputs = false
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        eqGraphView.setQ(bandIndex, clamped)
        stateManager.pushEqUpdate()
        stateManager.updateDpBandVisualization(eqGraphView)
    }

    private fun applyBandHz(hz: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        stateManager.parametricEq.updateBand(bandIndex, hz, band.gain, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        stateManager.pushEqUpdate()
        stateManager.updateDpBandVisualization(eqGraphView)
    }

    private fun applyBandDb(db: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val effectiveDb = if (band.filterType == BiquadFilter.FilterType.LOW_PASS || band.filterType == BiquadFilter.FilterType.HIGH_PASS) 0f else db
        stateManager.parametricEq.updateBand(bandIndex, band.frequency, effectiveDb, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        stateManager.pushEqUpdate()
        stateManager.updateDpBandVisualization(eqGraphView)
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
            bandDbSlider.value = band.gain.coerceIn(-12f, 12f)
            qSlider.value = band.q.toFloat().coerceIn(0.1f, 12f)

            val isLpHp = band.filterType == BiquadFilter.FilterType.LOW_PASS || band.filterType == BiquadFilter.FilterType.HIGH_PASS
            bandDbSlider.isEnabled = !isLpHp
            bandDbInput.isEnabled = !isLpHp
            bandDbSlider.alpha = if (isLpHp) 0.3f else 1f
            bandDbInput.alpha = if (isLpHp) 0.3f else 1f
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
                        setColor(0xFF333333.toInt())
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
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
        // Don't duplicate — BottomNavHelper.updatePowerFab handles the full animation
        // Just update the text label
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

    private fun updateEqToggleUI() {
        val enabled = stateManager.parametricEq.isEnabled
        eqToggleButton.text = if (enabled) "EQ: ON" else "EQ: OFF"
    }

    // ---- Filter Type Buttons ----

    private fun setupFilterTypeButtons() {
        val filterTypes = listOf(
            "PEAK" to BiquadFilter.FilterType.BELL,
            "LSHELF" to BiquadFilter.FilterType.LOW_SHELF,
            "HSHELF" to BiquadFilter.FilterType.HIGH_SHELF,
            "LPF" to BiquadFilter.FilterType.LOW_PASS,
            "HPF" to BiquadFilter.FilterType.HIGH_PASS
        )

        filterTypeGroup.removeAllViews()
        for ((label, type) in filterTypes) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                icon = null
                text = label
                textSize = 11f
                cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(3, 0, 3, 0)
                }
                val vertPad = (6 * resources.displayMetrics.density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                insetTop = 0
                insetBottom = 0

                setOnClickListener {
                    val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
                    stateManager.parametricEq.setBandEnabled(bandIndex, true)
                    eqGraphView.setFilterType(bandIndex, type)
                    updateFilterTypeButtons(bandIndex)
                    updateBandInputs(bandIndex)
                    bandToggleManager.updateIcons()
                    bandToggleManager.updateSelection(bandIndex)
                    stateManager.updateDpBandVisualization(eqGraphView)
                    stateManager.pushEqUpdate()
                }
            }
            filterTypeGroup.addView(btn)
        }

        // BYPASS button
        val bypassBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = null
            text = "BYPASS"
            textSize = 11f
            cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(3, 0, 3, 0)
            }
            val vertPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0
            insetBottom = 0

            setOnClickListener {
                val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
                stateManager.parametricEq.setBandEnabled(bandIndex, false)
                updateFilterTypeButtons(bandIndex)
                bandToggleManager.updateIcons()
                bandToggleManager.updateSelection(bandIndex)
                eqGraphView.updateBandLevels()
                eqGraphView.invalidate()
                stateManager.updateDpBandVisualization(eqGraphView)
                stateManager.pushEqUpdate()
            }
        }
        filterTypeGroup.addView(bypassBtn)
    }

    private fun updateFilterTypeButtons(bandIndex: Int?) {
        if (bandIndex == null) return
        val band = stateManager.parametricEq.getBand(bandIndex) ?: return
        val currentType = band.filterType
        val bandEnabled = band.enabled

        val filterTypes = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS
        )

        for (i in 0 until filterTypeGroup.childCount) {
            val btn = filterTypeGroup.getChildAt(i) as? MaterialButton ?: continue
            val isBypass = i == filterTypes.size
            val isActive = if (isBypass) !bandEnabled else (bandEnabled && i < filterTypes.size && filterTypes[i] == currentType)

            if (isActive) {
                btn.setBackgroundColor(getColor(R.color.filter_active))
                btn.setTextColor(getColor(R.color.filter_active_text))
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.setTextColor(getColor(R.color.filter_inactive_text))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_outline))
            }
        }
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        if (!stateManager.serviceBound) {
            val intent = Intent(this, EqService::class.java)
            bindService(intent, stateManager.serviceConnection, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // Apply spectrum settings (may have changed in SpectrumControlActivity)
        applySpectrumSettings()
        // Set FAB from saved power state — instant, no animation
        val savedPower = eqPrefs.getPowerState()
        com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, savedPower)
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
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

        // Check if Simple EQ was toggled in experimental settings
        val simpleEqEnabled = eqPrefs.getSimpleEqEnabled()
        if (simpleEqEnabled && stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
            switchEqUiMode(EqUiMode.SIMPLE)
        } else if (!simpleEqEnabled && stateManager.currentEqUiMode == EqUiMode.SIMPLE) {
            val fallback = try { EqUiMode.valueOf(eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
            switchEqUiMode(fallback)
        }
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
        if (stateManager.serviceBound) {
            try { unbindService(stateManager.serviceConnection) } catch (_: Exception) {}
            stateManager.serviceBound = false
        }
    }

    override fun onDestroy() {
        visualizerHelper.stop()
        try { unregisterReceiver(eqStoppedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startProcessing()
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
        } else {
            stateManager.isProcessing = false
        }
        // Check if we should show settings page
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
