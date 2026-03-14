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
import com.google.android.material.button.MaterialButton
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

    // EQ UI mode
    private lateinit var modeParametricBtn: MaterialButton
    private lateinit var modeGraphicBtn: MaterialButton
    private lateinit var modeTableBtn: MaterialButton
    private lateinit var parametricControlsCard: View
    private lateinit var hzControlRow: View
    private lateinit var typedEqTableCard: View
    private lateinit var typedEqRowContainer: LinearLayout
    private lateinit var graphicSlidersContainer: LinearLayout

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

        registerReceiver(
            eqStoppedReceiver,
            IntentFilter(EqService.ACTION_EQ_STOPPED),
            RECEIVER_NOT_EXPORTED
        )

        val savedMode = try { EqUiMode.valueOf(eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
        switchEqUiMode(savedMode)
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
        typedEqTableCard = findViewById(R.id.typedEqTableCard)
        typedEqRowContainer = findViewById(R.id.typedEqRowContainer)
        graphicSlidersContainer = findViewById(R.id.graphicSlidersContainer)

        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presets)
        presetDropdown.setAdapter(adapter)
        presetDropdown.setText("Flat", false)

        val savedBandCount = eqPrefs.getDpBandCount()
        ParametricToDpConverter.setNumBands(savedBandCount)
        dpBandCountSlider.value = savedBandCount.toFloat()
        dpBandCountText.setText(savedBandCount.toString())

        dpBandCountGroup.visibility = View.VISIBLE
        eqGraphView.showDpBands = true
        eqGraphView.showSaturationCurve = false

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
            bandToggleManager.setupToggles()
            setupFilterTypeButtons()
            if (stateManager.currentEqUiMode == EqUiMode.TABLE) tableController.buildTable()
            if (stateManager.currentEqUiMode == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
        }

        val onBandSelected = { bandIndex: Int? ->
            updateFilterTypeButtons(bandIndex)
            updateBandInputs(bandIndex)
        }

        graphicController = GraphicEqController(
            this, graphicSlidersContainer, eqGraphView, stateManager,
            onEqChanged, onBandCountChanged
        )

        tableController = TableEqController(
            this, typedEqRowContainer, eqGraphView, stateManager, onEqChanged
        )

        bandToggleManager = BandToggleManager(
            this, bandToggleGroup, triangleIndicator, eqGraphView, stateManager,
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
        powerFab.setOnClickListener {
            if (stateManager.isProcessing) stopProcessing() else startProcessing()
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
                val count = dpBandCountText.text.toString().toIntOrNull()?.coerceIn(5, 128) ?: 31
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

        // EQ mode selector
        modeParametricBtn.setOnClickListener { switchEqUiMode(EqUiMode.PARAMETRIC) }
        modeGraphicBtn.setOnClickListener { switchEqUiMode(EqUiMode.GRAPHIC) }
        modeTableBtn.setOnClickListener { switchEqUiMode(EqUiMode.TABLE) }
    }

    // ---- EQ UI Mode Switching ----

    private fun switchEqUiMode(mode: EqUiMode) {
        stateManager.currentEqUiMode = mode
        eqGraphView.eqUiMode = mode
        eqPrefs.saveEqUiMode(mode.name)
        updateModeSelectorButtons()

        when (mode) {
            EqUiMode.PARAMETRIC -> {
                parametricControlsCard.visibility = View.VISIBLE
                graphicSlidersContainer.visibility = View.GONE
                filterTypeGroup.visibility = View.VISIBLE
                hzControlRow.visibility = View.VISIBLE
                qControlGroup.visibility = View.VISIBLE
                bandInputGroup.visibility = View.VISIBLE
                bandQInputLayout.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.VISIBLE
                typedEqTableCard.visibility = View.GONE
                bandToggleManager.setupToggles()
            }
            EqUiMode.GRAPHIC -> {
                parametricControlsCard.measure(
                    View.MeasureSpec.makeMeasureSpec(parametricControlsCard.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                graphicController.targetCardHeight = parametricControlsCard.measuredHeight
                parametricControlsCard.visibility = View.GONE
                graphicSlidersContainer.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.INVISIBLE
                typedEqTableCard.visibility = View.GONE
                bandToggleManager.setupToggles()
                graphicController.buildSliders(graphicController.targetCardHeight)
            }
            EqUiMode.TABLE -> {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(
                    parametricControlsCard.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                parametricControlsCard.measure(widthSpec, heightSpec)
                bandToggleGroup.measure(widthSpec, heightSpec)
                val triangleContainer = findViewById<View>(R.id.triangleIndicatorContainer)
                triangleContainer.measure(widthSpec, heightSpec)

                val paraH = if (parametricControlsCard.height > 0) parametricControlsCard.height else parametricControlsCard.measuredHeight
                val toggleH = if (bandToggleGroup.height > 0) bandToggleGroup.height else bandToggleGroup.measuredHeight
                val triH = if (triangleContainer.height > 0) triangleContainer.height else triangleContainer.measuredHeight

                val toggleParams = bandToggleGroup.layoutParams as? LinearLayout.LayoutParams
                val triParams = triangleContainer.layoutParams as? LinearLayout.LayoutParams
                val paraParams = parametricControlsCard.layoutParams as? LinearLayout.LayoutParams
                val toggleMargins = (toggleParams?.topMargin ?: 0) + (toggleParams?.bottomMargin ?: 0)
                val triMargins = (triParams?.topMargin ?: 0) + (triParams?.bottomMargin ?: 0)
                val paraMargins = (paraParams?.topMargin ?: 0) + (paraParams?.bottomMargin ?: 0)
                val tableParams = typedEqTableCard.layoutParams as? LinearLayout.LayoutParams
                val tableMargins = (tableParams?.topMargin ?: 0) + (tableParams?.bottomMargin ?: 0)
                val totalHeight = (toggleH + toggleMargins) + (triH + triMargins) + (paraH + paraMargins) - tableMargins

                parametricControlsCard.visibility = View.GONE
                graphicSlidersContainer.visibility = View.GONE
                bandToggleGroup.visibility = View.GONE
                triangleContainer.visibility = View.GONE
                typedEqTableCard.visibility = View.VISIBLE
                typedEqTableCard.layoutParams = typedEqTableCard.layoutParams.apply { height = totalHeight }
                tableController.buildTable()
            }
        }
        eqGraphView.invalidate()
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
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                isUpdatingInputs = true
                onReset()
                isUpdatingInputs = false
                eqGraphView.invalidate()
                return true
            }
        })
        slider.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            false // let slider handle normal drags
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
        isUpdatingInputs = false
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
        val inactiveColor = 0xFF666666.toInt()
        navPresetsButton.setColorFilter(if (isEqPage) activeColor else inactiveColor)
        navSettingsButton.setColorFilter(if (isEqPage) inactiveColor else activeColor)
    }

    private fun updateEqToggleUI() {
        val enabled = stateManager.parametricEq.isEnabled
        eqToggleButton.text = if (enabled) "EQ: ON" else "EQ: OFF"
    }

    // ---- Filter Type Buttons ----

    private fun setupFilterTypeButtons() {
        val filterTypes = listOf(
            R.drawable.ic_filter_bell to BiquadFilter.FilterType.BELL,
            R.drawable.ic_filter_low_shelf to BiquadFilter.FilterType.LOW_SHELF,
            R.drawable.ic_filter_high_shelf to BiquadFilter.FilterType.HIGH_SHELF,
            R.drawable.ic_filter_low_pass to BiquadFilter.FilterType.LOW_PASS,
            R.drawable.ic_filter_high_pass to BiquadFilter.FilterType.HIGH_PASS
        )

        filterTypeGroup.removeAllViews()
        for ((iconRes, type) in filterTypes) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconSize = (28 * resources.displayMetrics.density).toInt()
                iconPadding = 0
                text = ""
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
                    eqGraphView.setFilterType(bandIndex, type)
                    updateFilterTypeButtons(bandIndex)
                    updateBandInputs(bandIndex)
                    bandToggleManager.updateIcons()
                    stateManager.updateDpBandVisualization(eqGraphView)
                    stateManager.pushEqUpdate()
                }
            }
            filterTypeGroup.addView(btn)
        }
    }

    private fun updateFilterTypeButtons(bandIndex: Int?) {
        if (bandIndex == null) return
        val currentType = stateManager.parametricEq.getBand(bandIndex)?.filterType ?: return

        val filterTypes = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS
        )

        for (i in 0 until filterTypeGroup.childCount) {
            val btn = filterTypeGroup.getChildAt(i) as? MaterialButton ?: continue
            if (i < filterTypes.size && filterTypes[i] == currentType) {
                btn.setBackgroundColor(getColor(R.color.filter_active))
                btn.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_active_text))
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_inactive_text))
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
        updatePowerUI()
    }

    override fun onPause() {
        super.onPause()
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
        try { unregisterReceiver(eqStoppedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startProcessing()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (stateManager.serviceBound && stateManager.eqService != null) {
            stateManager.isProcessing = stateManager.eqService!!.dynamicsManager.isActive
        } else {
            stateManager.isProcessing = false
        }
        updatePowerUI()
    }
}
