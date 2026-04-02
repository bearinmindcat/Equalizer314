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

    // UI controllers
    private lateinit var graphicController: GraphicEqController
    private lateinit var tableController: TableEqController
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
    private lateinit var powerFab: com.google.android.material.floatingactionbutton.FloatingActionButton
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

        initViews()
        initControllers()
        initEQ()
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
        switchEqUiMode(savedMode)
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
        preampSlider = findViewById(R.id.preampSlider)
        preampText = findViewById(R.id.preampText)
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

        // Init settings controls from saved state
        preampSlider.value = stateManager.preampGainDb.coerceIn(-12f, 12f)
        preampText.setText(String.format("%.1f", stateManager.preampGainDb))
        autoGainSwitch.isChecked = stateManager.autoGainEnabled
        updateAutoGainOffsetText()

        updateBottomBarHighlight(isEqPage = true)
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
        // Set icon tints based on saved enabled state
        navMbcBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (eqPrefs.getMbcEnabled()) com.google.android.material.color.MaterialColors.getColor(navMbcBtn, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
        )
        navLimiterBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (eqPrefs.getLimiterEnabled()) com.google.android.material.color.MaterialColors.getColor(navLimiterBtn, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
        )
        navMbcBtn.setOnClickListener {
            startActivity(Intent(this, MbcActivity::class.java))
            overridePendingTransition(0, 0)
        }
        navLimiterBtn.setOnClickListener {
            startActivity(Intent(this, LimiterActivity::class.java))
            overridePendingTransition(0, 0)
        }
        powerFab.setOnClickListener {
            if (stateManager.isProcessing) stopProcessing() else startProcessing()
        }

        // Visualizer toggle — positioned exactly between grid lines with 2dp gap
        val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.visualizerToggle)
        val vizDensity = resources.displayMetrics.density
        val gapPx = (2 * vizDensity).toInt()
        eqGraphView.post {
            val viewWidth = eqGraphView.width
            val vPadPx = 80  // +12 dB grid line y position
            // 10kHz vertical grid line: x = viewWidth * (log10(10000)-log10(10))/(log10(20000)-log10(10))
            val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
            // Right grid line = viewWidth
            // Button bounds: 2dp inside from each grid line
            val btnLeft = gridLine10k + gapPx
            val btnTop = gapPx
            val btnRight = viewWidth - gapPx
            val btnBottom = vPadPx - gapPx
            val btnWidth = btnRight - btnLeft
            val btnHeight = btnBottom - btnTop
            val lp = vizToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = btnWidth
            lp.height = btnHeight
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.leftMargin = btnLeft
            lp.topMargin = btnTop
            lp.rightMargin = 0
            vizToggle.layoutParams = lp
            vizToggle.minimumWidth = 0
            vizToggle.minimumHeight = 0
            vizToggle.setPadding(0, 0, 0, 0)
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

    private fun setupSettingsListeners() {
        // MBC card (settings page)
        findViewById<View>(R.id.mbcCard).setOnClickListener {
            startActivity(Intent(this, MbcActivity::class.java))
            overridePendingTransition(0, 0)
        }
        // Limiter card (settings page)
        findViewById<View>(R.id.limiterCard).setOnClickListener {
            startActivity(Intent(this, LimiterActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.experimentalCard).setOnClickListener {
            startActivity(Intent(this, ExperimentalActivity::class.java))
        }
        // AutoEQ card (settings page)
        findViewById<View>(R.id.autoEqCard).setOnClickListener {
            autoEqLauncher.launch(Intent(this, AutoEqActivity::class.java))
            overridePendingTransition(0, 0)
        }
        // Target card (settings page) — opens Target Curve screen
        findViewById<View>(R.id.targetCard).setOnClickListener {
            targetCurveLauncher.launch(Intent(this, TargetCurveActivity::class.java))
            overridePendingTransition(0, 0)
        }

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
        stateManager.currentEqUiMode = mode
        eqGraphView.eqUiMode = mode
        eqPrefs.saveEqUiMode(mode.name)
        updateModeSelectorButtons()

        when (mode) {
            EqUiMode.PARAMETRIC -> {
                tableEqCard.setOnTouchListener(null)
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

                // Calculate available height: page height minus graph, mode selector, and padding
                val pageScrollView = pageEq as ScrollView
                pageScrollView.scrollTo(0, 0)
                pageScrollView.post {
                    val pageH = pageScrollView.height
                    val contentLayout = pageScrollView.getChildAt(0) as LinearLayout
                    val padding = contentLayout.paddingTop + contentLayout.paddingBottom

                    // Measure visible elements above table card
                    val modeSelector = findViewById<View>(R.id.modeSelectorGroup)
                    val graphCard = (eqGraphView.parent as? View)?.parent as? View ?: eqGraphView.parent as View // the MaterialCardView wrapping the graph
                    val modeSelectorH = modeSelector.height + (modeSelector.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin + it.bottomMargin } .let { it ?: 0 }
                    val graphCardH = graphCard.height + (graphCard.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin + it.bottomMargin } .let { it ?: 0 }
                    val tableMargin = (tableEqCard.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0

                    val tableHeight = pageH - padding - modeSelectorH - graphCardH - tableMargin
                    tableEqCard.layoutParams = tableEqCard.layoutParams.apply { height = tableHeight }
                }

                // Let table card's inner ScrollView handle touches, block outer scroll
                tableEqCard.setOnTouchListener { v, event ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }

                tableController.buildTable()
            }
        }
        eqGraphView.invalidate()
    }

    private fun reorderToggleRows(animate: Boolean = true) {
        if (stateManager.currentEqUiMode == EqUiMode.TABLE) return
        val parent = (pageEq as ScrollView).getChildAt(0) as LinearLayout
        val triContainer = findViewById<View>(R.id.triangleIndicatorContainer)

        // Determine which page the selected band is on
        val selectedBand = stateManager.selectedBandIndex ?: 0
        val displayPos = stateManager.displayToBandIndex.indexOf(selectedBand).let { if (it < 0) 0 else it }
        val activePage = displayPos / 8
        val activeRow = if (activePage == 0) bandToggleGroup else bandToggleGroup2
        val inactiveRow = if (activePage == 0) bandToggleGroup2 else bandToggleGroup

        // Check if both rows are already in correct positions
        val graphCard = (eqGraphView.parent as? View)?.parent as? View ?: eqGraphView.parent as View
        val graphIdx = parent.indexOfChild(graphCard)
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

        // Re-find graph index after removals
        val newGraphIdx = parent.indexOfChild(graphCard)

        // Active row right after graph (no extra margin — graph's marginBottom=8dp provides gap)
        (activeRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        parent.addView(activeRow, newGraphIdx + 1)

        // Triangle indicator after active row
        parent.addView(triContainer, newGraphIdx + 2)

        // Inactive row after controls (no extra margin — controls' marginBottom=8dp provides gap)
        (inactiveRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        if (controlsView != null) {
            val controlsIdx = parent.indexOfChild(controlsView)
            parent.addView(inactiveRow, controlsIdx + 1)
        } else {
            parent.addView(inactiveRow, newGraphIdx + 3)
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

        animatePowerFab(true)
        EqService.start(this)

        if (stateManager.serviceBound) {
            doStartEq()
        } else {
            stateManager.pendingStartEq = true
            val intent = Intent(this, EqService::class.java)
            bindService(intent, stateManager.serviceConnection, BIND_AUTO_CREATE)
        }

        showPowerSnackbar(true)
    }

    private fun doStartEq() {
        stateManager.doStartEq { on -> animatePowerFab(on) }
    }

    private fun stopProcessing() {
        stateManager.stopProcessing { on -> animatePowerFab(on) }
        showPowerSnackbar(false)
    }

    private fun showPowerSnackbar(on: Boolean) {
        val message = if (on) "Equalizer314 is On" else "Equalizer314 is Off"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---- UI Updates ----

    private var powerAnimator: android.animation.ValueAnimator? = null

    private fun animatePowerFab(on: Boolean) {
        powerAnimator?.cancel()

        val fromBg = (powerFab.backgroundTintList?.defaultColor ?: 0xFF2A2A2A.toInt())
        val fromIcon = (powerFab.imageTintList?.defaultColor ?: 0xFF555555.toInt())
        val toBg = if (on) 0xFFFFFFFF.toInt() else 0xFF2A2A2A.toInt()
        val toIcon = if (on) 0xFF000000.toInt() else 0xFF555555.toInt()

        powerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val bg = blendColor(fromBg, toBg, f)
                val icon = blendColor(fromIcon, toIcon, f)
                powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
                powerFab.imageTintList = android.content.res.ColorStateList.valueOf(icon)
            }
            start()
        }

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
        if (stateManager.isProcessing) {
            powerButton.text = "ON"
            powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
        } else {
            powerButton.text = "OFF"
            powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
            powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
        }
    }

    private fun updateBottomBarHighlight(isEqPage: Boolean) {
        val activeColor = 0xFFDDDDDD.toInt()
        // EQ icon always bright
        navPresetsButton.setColorFilter(activeColor)
        // Settings icon highlights when on settings page
        navSettingsButton.setColorFilter(if (isEqPage) 0xFF666666.toInt() else activeColor)
        // MBC and Limiter — clear color filter so imageTintList takes effect
        findViewById<ImageButton>(R.id.navMbcButton).clearColorFilter()
        findViewById<ImageButton>(R.id.navLimiterButton).clearColorFilter()
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
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
        } else {
            stateManager.isProcessing = false
        }
        // Set power FAB immediately (no animation) to avoid flicker
        if (stateManager.isProcessing) {
            powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
        } else {
            powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
            powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
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
        // Refresh MBC/Limiter nav icon tints
        val navMbc = findViewById<ImageButton>(R.id.navMbcButton)
        val navLimiter = findViewById<ImageButton>(R.id.navLimiterButton)
        navMbc.imageTintList = android.content.res.ColorStateList.valueOf(
            if (eqPrefs.getMbcEnabled()) com.google.android.material.color.MaterialColors.getColor(navMbc, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
        )
        navLimiter.imageTintList = android.content.res.ColorStateList.valueOf(
            if (eqPrefs.getLimiterEnabled()) com.google.android.material.color.MaterialColors.getColor(navLimiter, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
        )
        updateAutoEqStatus()
        updateTargetStatus()
    }

    override fun onPause() {
        super.onPause()
        // Release visualizer so other activities can use session 0
        visualizerHelper.stop()
        eqGraphView.spectrumRenderer = null
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
        }
    }
}
