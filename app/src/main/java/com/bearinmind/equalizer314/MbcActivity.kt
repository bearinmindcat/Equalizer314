package com.bearinmind.equalizer314

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.EqGraphView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MbcActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_BAND_COUNT = 3
        const val MIN_BAND_COUNT = 1
        const val MAX_BAND_COUNT = 6
        // Default crossover frequencies for up to 6 bands (5 crossovers)
        val BAND_COLORS = intArrayOf(
            0xFFE57373.toInt(),  // red
            0xFF81C784.toInt(),  // green
            0xFF64B5F6.toInt(),  // blue
            0xFFFFD54F.toInt(),  // yellow
            0xFFBA68C8.toInt(),  // purple
            0xFF4DD0E1.toInt()   // cyan
        )
        val DEFAULT_CROSSOVERS_BY_COUNT = mapOf(
            3 to floatArrayOf(200f, 4000f),
            4 to floatArrayOf(120f, 1000f, 8000f),
            5 to floatArrayOf(80f, 400f, 2000f, 10000f),
            6 to floatArrayOf(60f, 200f, 800f, 3500f, 12000f)
        )
    }

    private lateinit var eqPrefs: EqPreferencesManager

    // Views
    private lateinit var graphView: EqGraphView
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var bandTabs: LinearLayout
    private lateinit var bandTitle: TextView
    private lateinit var bandSwitch: MaterialSwitch
    private lateinit var cutoffSlider: Slider
    private lateinit var cutoffText: EditText
    private lateinit var attackSlider: Slider
    private lateinit var attackText: EditText
    private lateinit var releaseSlider: Slider
    private lateinit var releaseText: EditText
    private lateinit var ratioSlider: Slider
    private lateinit var ratioText: EditText
    private lateinit var thresholdSlider: Slider
    private lateinit var thresholdText: EditText
    private lateinit var kneeSlider: Slider
    private lateinit var kneeText: EditText
    private lateinit var rangeSlider: Slider
    private lateinit var rangeText: EditText
    private lateinit var noiseGateSlider: Slider
    private lateinit var noiseGateText: EditText
    private lateinit var expanderSlider: Slider
    private lateinit var expanderText: EditText
    private lateinit var preGainSlider: Slider
    private lateinit var preGainText: EditText
    private lateinit var postGainSlider: Slider
    private lateinit var postGainText: EditText
    private lateinit var bandColorBox: TextView
    private lateinit var compressorCurve: com.bearinmind.equalizer314.ui.CompressorCurveView
    private lateinit var gateCurve: com.bearinmind.equalizer314.ui.GateCurveView
    private lateinit var attackReleaseView: com.bearinmind.equalizer314.ui.AttackReleaseView

    private val mbcBandColors = mutableMapOf<Int, Int>() // band index → color
    private var selectedBand = 0
    private var bandCount = DEFAULT_BAND_COUNT
    private var isUpdating = false
    private var isAnimating = false

    // Per-band data
    data class MbcBandData(
        var enabled: Boolean = true,
        var cutoff: Float = 1000f,
        var attack: Float = 1f,
        var release: Float = 60f,
        var ratio: Float = 10f,
        var threshold: Float = -2f,
        var kneeWidth: Float = 3.5f,
        var noiseGateThreshold: Float = -90f,
        var expanderRatio: Float = 1f,
        var preGain: Float = 0f,
        var postGain: Float = 0f,
        var range: Float = -12f
    )

    private val bands = mutableListOf<MbcBandData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_mbc)

        val root = findViewById<android.view.View>(R.id.mbcRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        eqPrefs = EqPreferencesManager(this)
        initViews()
        loadState()
        setupMbcGraph()
        buildBandTabs()
        selectBand(0)
        setupListeners()
    }

    private fun initViews() {
        findViewById<android.widget.ImageButton>(R.id.mbcBackButton).setOnClickListener { finish() }

        // Graph with MBC band visualization
        graphView = findViewById(R.id.mbcGraphView)
        graphView.showDpBands = false
        graphView.showSaturationCurve = false
        graphView.showBandPoints = false
        val eq = ParametricEqualizer()
        eqPrefs.restoreState(eq)
        graphView.setParametricEqualizer(eq)

        masterSwitch = findViewById(R.id.mbcMasterSwitch)
        bandTabs = findViewById(R.id.mbcBandTabs)
        bandTitle = findViewById(R.id.mbcBandTitle)
        bandColorBox = findViewById(R.id.mbcBandColorBox)
        bandSwitch = findViewById(R.id.mbcBandSwitch)
        bandColorBox.setOnClickListener { showMbcColorPicker() }
        cutoffSlider = findViewById(R.id.mbcCutoffSlider)
        cutoffText = findViewById(R.id.mbcCutoffText)
        attackSlider = findViewById(R.id.mbcAttackSlider)
        attackText = findViewById(R.id.mbcAttackText)
        releaseSlider = findViewById(R.id.mbcReleaseSlider)
        releaseText = findViewById(R.id.mbcReleaseText)
        ratioSlider = findViewById(R.id.mbcRatioSlider)
        ratioText = findViewById(R.id.mbcRatioText)
        thresholdSlider = findViewById(R.id.mbcThresholdSlider)
        thresholdText = findViewById(R.id.mbcThresholdText)
        rangeSlider = findViewById(R.id.mbcRangeSlider)
        rangeText = findViewById(R.id.mbcRangeText)
        kneeSlider = findViewById(R.id.mbcKneeSlider)
        kneeText = findViewById(R.id.mbcKneeText)
        noiseGateSlider = findViewById(R.id.mbcNoiseGateSlider)
        noiseGateText = findViewById(R.id.mbcNoiseGateText)
        expanderSlider = findViewById(R.id.mbcExpanderSlider)
        expanderText = findViewById(R.id.mbcExpanderText)
        preGainSlider = findViewById(R.id.mbcPreGainSlider)
        preGainText = findViewById(R.id.mbcPreGainText)
        postGainSlider = findViewById(R.id.mbcPostGainSlider)
        postGainText = findViewById(R.id.mbcPostGainText)
        compressorCurve = findViewById(R.id.mbcCompressorCurve)
        gateCurve = findViewById(R.id.mbcGateCurve)
        attackReleaseView = findViewById(R.id.mbcAttackReleaseView)
    }

    private fun loadState() {
        masterSwitch.isChecked = eqPrefs.getMbcEnabled()
        bandCount = eqPrefs.getMbcBandCount().coerceIn(MIN_BAND_COUNT, MAX_BAND_COUNT)

        bands.clear()
        val defaultFreqs = logSpacedFrequencies(bandCount)
        for (i in 0 until bandCount) {
            bands.add(MbcBandData(
                enabled = eqPrefs.getMbcBandEnabled(i),
                cutoff = eqPrefs.getMbcBandCutoff(i, defaultFreqs[i]),
                attack = eqPrefs.getMbcBandAttack(i),
                release = eqPrefs.getMbcBandRelease(i),
                ratio = eqPrefs.getMbcBandRatio(i),
                threshold = eqPrefs.getMbcBandThreshold(i),
                kneeWidth = eqPrefs.getMbcBandKnee(i),
                noiseGateThreshold = eqPrefs.getMbcBandNoiseGate(i),
                expanderRatio = eqPrefs.getMbcBandExpander(i),
                preGain = eqPrefs.getMbcBandPreGain(i),
                postGain = eqPrefs.getMbcBandPostGain(i),
                range = eqPrefs.getMbcBandRange(i)
            ))
        }
    }

    private var crossoverFreqs = floatArrayOf()

    private fun setupMbcGraph() {
        // Restore crossovers from prefs or use defaults for current band count
        val defaults = DEFAULT_CROSSOVERS_BY_COUNT[bandCount]
            ?: logSpacedCrossovers(bandCount)
        crossoverFreqs = FloatArray(bandCount - 1) { i ->
            eqPrefs.getMbcCrossover(i, defaults.getOrElse(i) { 1000f })
        }

        graphView.mbcCrossovers = crossoverFreqs
        graphView.mbcBandColors = null // no color coding
        graphView.mbcSelectedBand = selectedBand

        // Initialize band gains from preGain (input level before compression)
        graphView.mbcBandGains = FloatArray(bandCount) { bands[it].preGain }
        graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }

        graphView.onMbcBandSelected = { bandIndex ->
            selectBand(bandIndex)
        }

        graphView.onMbcCrossoverChanged = { index, freq ->
            crossoverFreqs[index] = freq
            eqPrefs.saveMbcCrossover(index, freq)
            bands[index].cutoff = freq
            saveBand(index)
        }

        graphView.onMbcBandGainChanged = { bandIndex, gain ->
            val snapped = Math.round(gain * 10f) / 10f
            bands[bandIndex].preGain = snapped
            saveBand(bandIndex)
            // Sync preGain slider if this is the currently selected band
            if (bandIndex == selectedBand) {
                isUpdating = true
                preGainSlider.value = snapped.coerceIn(-12f, 12f)
                preGainText.setText(String.format("%.1f", snapped))
                isUpdating = false
            }
        }

        graphView.onMbcBandRangeChanged = { bandIndex, range ->
            val snapped = Math.round(range * 10f) / 10f
            bands[bandIndex].range = snapped
            saveBand(bandIndex)
            if (bandIndex == selectedBand) {
                isUpdating = true
                rangeSlider.value = snapped.coerceIn(-12f, 0f)
                rangeText.setText(String.format("%.1f", snapped))
                isUpdating = false
            }
        }
    }

    private fun createBandButton(index: Int): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "${index + 1}"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            cornerRadius = (12 * resources.displayMetrics.density).toInt()
            textSize = 11f
            val vertPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            gravity = Gravity.CENTER
            rippleColor = ColorStateList.valueOf(0x33AAAAAA)
            setOnClickListener { selectBand(index) }
            setOnLongClickListener {
                if (bandCount > MIN_BAND_COUNT) removeBand(index)
                true
            }
        }
    }

    private fun createAddButton(): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            cornerRadius = (12 * resources.displayMetrics.density).toInt()
            textSize = 11f
            val vertPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0
            gravity = Gravity.CENTER
            setBackgroundColor(0x00000000)
            setTextColor(0xFF888888.toInt())
            strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            setOnClickListener { addBand() }
        }
    }

    private fun buildBandTabs() {
        bandTabs.removeAllViews()
        for (i in 0 until bandCount) {
            bandTabs.addView(createBandButton(i))
        }
        if (bandCount < MAX_BAND_COUNT) {
            bandTabs.addView(createAddButton())
        }
        updateTabHighlight()
    }

    private fun selectBand(index: Int) {
        selectedBand = index
        graphView.mbcSelectedBand = index
        graphView.invalidate()
        updateTabHighlight()
        loadBandToUI()
    }

    private fun updateTabHighlight() {
        for (i in 0 until bandTabs.childCount) {
            val btn = bandTabs.getChildAt(i) as? MaterialButton ?: continue
            if (btn.text == "+") continue
            if (i == selectedBand) {
                btn.setBackgroundColor(0xFFFFFFFF.toInt())
                btn.setTextColor(0xFF000000.toInt())
                btn.strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.setTextColor(0xFFBBBBBB.toInt())
                btn.strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
            }
        }
    }

    private fun loadBandToUI() {
        isUpdating = true
        val b = bands[selectedBand]
        bandTitle.text = "Band ${selectedBand + 1}"
        updateMbcColorBox()
        bandSwitch.isChecked = b.enabled
        cutoffSlider.value = b.cutoff.coerceIn(20f, 20000f)
        cutoffText.setText(b.cutoff.toInt().toString())
        attackSlider.value = b.attack.coerceIn(0.01f, 500f)
        attackText.setText(String.format("%.2f", b.attack))
        releaseSlider.value = b.release.coerceIn(1f, 5000f)
        releaseText.setText(String.format("%.0f", b.release))
        ratioSlider.value = ratioToSlider(b.ratio)
        ratioText.setText(String.format("%.2f", b.ratio))
        thresholdSlider.value = b.threshold.coerceIn(-60f, 0f)
        thresholdText.setText(String.format("%.1f", b.threshold))
        rangeSlider.value = b.range.coerceIn(-12f, 0f)
        rangeText.setText(String.format("%.1f", b.range))
        kneeSlider.value = b.kneeWidth.coerceIn(0.01f, 24f)
        kneeText.setText(String.format("%.2f", b.kneeWidth))
        noiseGateSlider.value = b.noiseGateThreshold.coerceIn(-90f, 0f)
        noiseGateText.setText(String.format("%.0f", b.noiseGateThreshold))
        expanderSlider.value = b.expanderRatio.coerceIn(1f, 50f)
        expanderText.setText(String.format("%.2f", b.expanderRatio))
        preGainSlider.value = b.preGain.coerceIn(-12f, 12f)
        preGainText.setText(String.format("%.1f", b.preGain))
        postGainSlider.value = b.postGain.coerceIn(-30f, 30f)
        postGainText.setText(String.format("%.1f", b.postGain))
        // Sync compressor curve
        compressorCurve.selectedBand = selectedBand
        compressorCurve.threshold = b.threshold
        compressorCurve.ratio = b.ratio
        compressorCurve.kneeWidth = b.kneeWidth
        gateCurve.selectedBand = selectedBand
        gateCurve.gateThreshold = b.noiseGateThreshold
        gateCurve.expanderRatio = b.expanderRatio
        attackReleaseView.attackMs = b.attack
        attackReleaseView.releaseMs = b.release
        isUpdating = false
    }

    private fun setupListeners() {
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            eqPrefs.saveMbcEnabled(checked)
        }

        bandSwitch.setOnCheckedChangeListener { _, checked ->
            if (isUpdating) return@setOnCheckedChangeListener
            bands[selectedBand].enabled = checked
            saveBand(selectedBand)
        }

        setupSlider(cutoffSlider, cutoffText, 20f, 20000f, "%.0f") { bands[selectedBand].cutoff = it }
        setupSlider(attackSlider, attackText, 0.01f, 500f, "%.2f") {
            bands[selectedBand].attack = it
            attackReleaseView.attackMs = it
        }
        setupSlider(releaseSlider, releaseText, 1f, 5000f, "%.0f") {
            bands[selectedBand].release = it
            attackReleaseView.releaseMs = it
        }
        // Ratio slider: exponential mapping (slider 0-100 → ratio 1-50)
        ratioSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdating) return@addOnChangeListener
            val ratio = sliderToRatio(value)
            ratioText.setText(String.format("%.2f", ratio))
            bands[selectedBand].ratio = ratio
            compressorCurve.ratio = ratio
            saveBand(selectedBand)
        }
        ratioText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val v = ratioText.text.toString().toFloatOrNull()?.coerceIn(1f, 50f) ?: 1f
                ratioText.setText(String.format("%.2f", v))
                ratioSlider.value = ratioToSlider(v)
                bands[selectedBand].ratio = v
                compressorCurve.ratio = v
                saveBand(selectedBand)
                ratioText.clearFocus()
            }
            true
        }
        setupSlider(thresholdSlider, thresholdText, -60f, 0f, "%.1f") {
            bands[selectedBand].threshold = it
            compressorCurve.threshold = it
        }
        setupSlider(rangeSlider, rangeText, -12f, 0f, "%.1f") {
            bands[selectedBand].range = it
            graphView.mbcBandRanges?.let { ranges ->
                ranges[selectedBand] = it
                graphView.invalidate()
            }
        }
        kneeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { compressorCurve.showKneeZoom = true }
            override fun onStopTrackingTouch(slider: Slider) { compressorCurve.showKneeZoom = false }
        })
        setupSlider(kneeSlider, kneeText, 0.01f, 24f, "%.2f") {
            bands[selectedBand].kneeWidth = it
            compressorCurve.kneeWidth = it
        }
        setupSlider(noiseGateSlider, noiseGateText, -90f, 0f, "%.0f") {
            bands[selectedBand].noiseGateThreshold = it
            gateCurve.gateThreshold = it
        }
        setupSlider(expanderSlider, expanderText, 1f, 50f, "%.2f") {
            bands[selectedBand].expanderRatio = it
            gateCurve.expanderRatio = it
        }
        setupSlider(preGainSlider, preGainText, -12f, 12f, "%.1f") {
            bands[selectedBand].preGain = it
            graphView.mbcBandGains?.let { gains ->
                gains[selectedBand] = it
                graphView.invalidate()
            }
        }
        setupSlider(postGainSlider, postGainText, -30f, 30f, "%.1f") { bands[selectedBand].postGain = it }

        // Compressor curve callbacks — sync sliders when dragging on the curve
        compressorCurve.onThresholdChanged = { value ->
            bands[selectedBand].threshold = value
            saveBand(selectedBand)
            isUpdating = true
            thresholdSlider.value = value.coerceIn(-60f, 0f)
            thresholdText.setText(String.format("%.1f", value))
            isUpdating = false
        }
        compressorCurve.onRatioChanged = { value ->
            bands[selectedBand].ratio = value
            saveBand(selectedBand)
            isUpdating = true
            ratioSlider.value = ratioToSlider(value)
            ratioText.setText(String.format("%.2f", value))
            isUpdating = false
        }

        // Gate callbacks — sync sliders when dragging on the gate graph
        gateCurve.onGateThresholdChanged = { value ->
            bands[selectedBand].noiseGateThreshold = value
            saveBand(selectedBand)
            isUpdating = true
            noiseGateSlider.value = value.coerceIn(-90f, 0f)
            noiseGateText.setText(String.format("%.0f", value))
            isUpdating = false
        }
        gateCurve.onExpanderRatioChanged = { value ->
            bands[selectedBand].expanderRatio = value
            saveBand(selectedBand)
            isUpdating = true
            expanderSlider.value = value.coerceIn(1f, 50f)
            expanderText.setText(String.format("%.2f", value))
            isUpdating = false
        }

        // Attack/Release envelope callbacks — sync sliders when dragging lines
        attackReleaseView.onAttackChanged = { value ->
            bands[selectedBand].attack = value
            saveBand(selectedBand)
            isUpdating = true
            attackSlider.value = value.coerceIn(0.01f, 500f)
            attackText.setText(String.format("%.2f", value))
            isUpdating = false
        }
        attackReleaseView.onReleaseChanged = { value ->
            bands[selectedBand].release = value
            saveBand(selectedBand)
            isUpdating = true
            releaseSlider.value = value.coerceIn(1f, 5000f)
            releaseText.setText(String.format("%.0f", value))
            isUpdating = false
        }
    }

    private fun setupSlider(
        slider: Slider, textField: EditText,
        min: Float, max: Float, fmt: String,
        onValue: (Float) -> Unit
    ) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdating) return@addOnChangeListener
            textField.setText(String.format(fmt, value))
            onValue(value)
            saveBand(selectedBand)
        }

        textField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val v = textField.text.toString().toFloatOrNull()?.coerceIn(min, max) ?: min
                textField.setText(String.format(fmt, v))
                slider.value = v
                onValue(v)
                saveBand(selectedBand)
                textField.clearFocus()
            }
            true
        }
    }

    private fun saveBand(index: Int) {
        val b = bands[index]
        eqPrefs.saveMbcBand(index, b.enabled, b.cutoff, b.attack, b.release, b.ratio,
            b.threshold, b.kneeWidth, b.noiseGateThreshold, b.expanderRatio, b.preGain, b.postGain, b.range)
    }

    override fun onPause() {
        super.onPause()
        eqPrefs.saveMbcEnabled(masterSwitch.isChecked)
        for (i in bands.indices) saveBand(i)
    }

    private fun addBand() {
        if (bandCount >= MAX_BAND_COUNT) return

        val oldBandCount = bandCount
        bandCount++
        eqPrefs.saveMbcBandCount(bandCount)

        // Add new band with defaults
        val defaultFreqs = logSpacedFrequencies(bandCount)
        bands.add(MbcBandData(cutoff = defaultFreqs.last()))
        saveBand(bandCount - 1)

        // Recompute crossovers
        val defaults = DEFAULT_CROSSOVERS_BY_COUNT[bandCount] ?: logSpacedCrossovers(bandCount)
        crossoverFreqs = FloatArray(bandCount - 1) { i ->
            if (i < oldBandCount - 1) crossoverFreqs.getOrElse(i) { defaults[i] }
            else defaults.getOrElse(i) { 1000f }
        }
        for (i in crossoverFreqs.indices) eqPrefs.saveMbcCrossover(i, crossoverFreqs[i])

        // Update graph
        graphView.mbcCrossovers = crossoverFreqs
        graphView.mbcBandGains = FloatArray(bandCount) { bands[it].preGain }
        graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }
        graphView.invalidate()

        if (isAnimating) {
            buildBandTabs()
            selectBand(bandCount - 1)
            return
        }

        // Lock all existing buttons (including "+") to explicit widths
        val capturedHeight = (0 until bandTabs.childCount)
            .mapNotNull { bandTabs.getChildAt(it)?.height?.takeIf { h -> h > 0 } }
            .firstOrNull() ?: 0
        val buttonWidths = mutableListOf<Int>()
        for (i in 0 until bandTabs.childCount) {
            val child = bandTabs.getChildAt(i) ?: continue
            val clp = child.layoutParams as LinearLayout.LayoutParams
            buttonWidths.add(child.width)
            if (capturedHeight > 0) clp.height = capturedHeight
            clp.weight = 0f; clp.width = child.width
            child.layoutParams = clp
        }

        // Insert new band button BEFORE the "+", at width 0
        val addBtnIdx = oldBandCount // where "+" currently is
        val existingAddBtn = bandTabs.getChildAt(addBtnIdx)
        val existingAddLp = existingAddBtn?.layoutParams as? LinearLayout.LayoutParams
        val addBtnStartWidth = buttonWidths.getOrElse(addBtnIdx) { 0 }

        val newBtn = createBandButton(oldBandCount)
        val newLp = newBtn.layoutParams as LinearLayout.LayoutParams
        if (capturedHeight > 0) newLp.height = capturedHeight
        newLp.weight = 0f; newLp.width = 0
        newBtn.alpha = 0f
        bandTabs.addView(newBtn, addBtnIdx) // "+" shifts to addBtnIdx + 1

        // Target widths: all buttons share equally
        val totalWidth = buttonWidths.sum()
        val atMax = bandCount >= MAX_BAND_COUNT
        val newTotalButtons = bandCount + if (atMax) 0 else 1
        val targetWidth = totalWidth / newTotalButtons

        isAnimating = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                // Existing band buttons shrink toward target
                for (i in 0 until addBtnIdx) {
                    val child = bandTabs.getChildAt(i) ?: continue
                    val clp = child.layoutParams as LinearLayout.LayoutParams
                    clp.width = (buttonWidths[i] + (targetWidth - buttonWidths[i]) * f).toInt()
                    child.requestLayout()
                }
                // New band button grows in
                newLp.width = (targetWidth * f).toInt(); newBtn.alpha = f; newBtn.requestLayout()
                // "+" resizes: shrinks to target (or to 0 if at max)
                if (existingAddBtn != null && existingAddLp != null) {
                    val addTarget = if (atMax) 0 else targetWidth
                    existingAddLp.width = (addBtnStartWidth + (addTarget - addBtnStartWidth) * f).toInt()
                    if (atMax) existingAddBtn.alpha = 1f - f
                    existingAddBtn.requestLayout()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                    if (atMax && existingAddBtn != null) bandTabs.removeView(existingAddBtn)
                    for (i in 0 until bandTabs.childCount) {
                        val child = bandTabs.getChildAt(i) ?: continue
                        val clp = child.layoutParams as LinearLayout.LayoutParams
                        clp.weight = 1f; clp.width = 0
                        clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                        child.alpha = 1f; child.requestLayout()
                    }
                    updateTabHighlight()
                    selectBand(bandCount - 1)
                }
            })
            start()
        }
        selectBand(bandCount - 1)
    }

    private fun removeBand(index: Int) {
        if (bandCount <= MIN_BAND_COUNT) return

        if (isAnimating) {
            performRemoveBand(index)
            return
        }

        // Lock all buttons to explicit widths
        val capturedHeight = (0 until bandTabs.childCount)
            .mapNotNull { bandTabs.getChildAt(it)?.height?.takeIf { h -> h > 0 } }
            .firstOrNull() ?: 0
        val buttonWidths = mutableListOf<Int>()
        for (i in 0 until bandTabs.childCount) {
            val child = bandTabs.getChildAt(i) ?: continue
            val clp = child.layoutParams as LinearLayout.LayoutParams
            buttonWidths.add(child.width)
            if (capturedHeight > 0) clp.height = capturedHeight
            clp.weight = 0f; clp.width = child.width
            child.layoutParams = clp
        }

        val removingBtn = bandTabs.getChildAt(index) ?: run { performRemoveBand(index); return }
        val removeWidth = buttonWidths.getOrElse(index) { 0 }

        // If was at max (no "+"), add one at the end at width 0
        val wasAtMax = bandCount == MAX_BAND_COUNT
        if (wasAtMax) {
            val addBtn = createAddButton()
            val addLp = addBtn.layoutParams as LinearLayout.LayoutParams
            if (capturedHeight > 0) addLp.height = capturedHeight
            addLp.weight = 0f; addLp.width = 0
            addBtn.alpha = 0f
            bandTabs.addView(addBtn)
            buttonWidths.add(0)
        }

        // Target width: remaining buttons (including "+") share equally
        val newBandCount = bandCount - 1
        val totalWidth = buttonWidths.sum()
        val newTotalButtons = newBandCount + 1 // remaining bands + "+"
        val targetWidth = totalWidth / newTotalButtons

        isAnimating = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                for (i in 0 until bandTabs.childCount) {
                    val child = bandTabs.getChildAt(i) ?: continue
                    val clp = child.layoutParams as LinearLayout.LayoutParams
                    if (child === removingBtn) {
                        clp.width = (removeWidth * (1f - f)).toInt()
                        child.alpha = 1f - f
                    } else {
                        val startW = buttonWidths.getOrElse(i) { 0 }
                        clp.width = (startW + (targetWidth - startW) * f).toInt()
                        if (wasAtMax && startW == 0) child.alpha = f
                    }
                    child.requestLayout()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                    bandTabs.removeView(removingBtn)
                    for (i in 0 until bandTabs.childCount) {
                        val child = bandTabs.getChildAt(i) ?: continue
                        val clp = child.layoutParams as LinearLayout.LayoutParams
                        clp.weight = 1f; clp.width = 0
                        clp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                        child.alpha = 1f; child.requestLayout()
                    }
                    performRemoveBand(index)
                }
            })
            start()
        }
    }

    private fun performRemoveBand(index: Int) {
        bands.removeAt(index)
        bandCount--
        eqPrefs.saveMbcBandCount(bandCount)

        // Recompute crossovers
        if (bandCount > 1) {
            val defaults = DEFAULT_CROSSOVERS_BY_COUNT[bandCount] ?: logSpacedCrossovers(bandCount)
            val oldCrossovers = crossoverFreqs.toList()
            crossoverFreqs = FloatArray(bandCount - 1) { i ->
                // Try to keep existing crossovers, skip the removed one
                val srcIdx = if (i < index) i else i + 1
                oldCrossovers.getOrElse(srcIdx) { defaults.getOrElse(i) { 1000f } }
            }
        } else {
            crossoverFreqs = floatArrayOf()
        }
        for (i in crossoverFreqs.indices) eqPrefs.saveMbcCrossover(i, crossoverFreqs[i])

        // Update graph
        graphView.mbcCrossovers = crossoverFreqs
        graphView.mbcBandGains = FloatArray(bandCount) { bands[it].preGain }
        graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }
        graphView.invalidate()

        // Adjust selection
        if (selectedBand >= bandCount) selectedBand = bandCount - 1
        buildBandTabs()
        selectBand(selectedBand)
    }

    private fun updateMbcColorBox() {
        val density = resources.displayMetrics.density
        val color = mbcBandColors[selectedBand]
        val boxColor = color ?: 0xFF333333.toInt()
        bandColorBox.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(boxColor)
            cornerRadius = 6 * density
            setStroke((1 * density).toInt(), 0xFF666666.toInt())
        }
        if (color == null) {
            bandColorBox.text = "\u2014"
            bandColorBox.setTextColor(0xFFAAAAAA.toInt())
        } else {
            bandColorBox.text = ""
        }
    }

    private fun showMbcColorPicker() {
        val density = resources.displayMetrics.density
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "Band Color"
            textSize = 16f
            setTextColor(0xFFE2E2E2.toInt())
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        sheetLayout.addView(title)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        for ((color, _) in com.bearinmind.equalizer314.ui.TableEqController.BAND_COLORS) {
            val isNone = color == 0xFF333333.toInt()
            val size = (32 * density).toInt()
            val swatch = if (isNone) {
                TextView(this).apply {
                    text = "\u2014"
                    textSize = 16f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF333333.toInt())
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            }
            swatch.setOnClickListener {
                if (isNone) {
                    mbcBandColors.remove(selectedBand)
                } else {
                    mbcBandColors[selectedBand] = color
                }
                updateMbcColorBox()
                bottomSheet.dismiss()
            }
            grid.addView(swatch)
        }

        sheetLayout.addView(grid)
        bottomSheet.setContentView(sheetLayout)
        bottomSheet.show()
    }

    private fun logSpacedFrequencies(count: Int): FloatArray {
        val logMin = kotlin.math.log10(20f)
        val logMax = kotlin.math.log10(20000f)
        return FloatArray(count) { i ->
            val frac = i.toFloat() / (count - 1).coerceAtLeast(1)
            Math.pow(10.0, (logMin + frac * (logMax - logMin)).toDouble()).toFloat()
        }
    }

    // Ratio mapping: slider tracks the curve's visual position on the graph
    // At a reference input 10 dB above threshold:
    //   output = T + 10/R  → ranges from T+10 (R=1) to T (R=∞)
    // Slider 0 = no compression (R=1), slider 100 = max compression (R=50)
    // Map slider linearly to the output position, then derive ratio from that
    private fun sliderToRatio(sliderValue: Float): Float {
        val norm = (sliderValue / 100f).coerceIn(0f, 1f)
        // norm=0 → output=T+10 (R=1), norm=1 → output≈T (R=50)
        // output = T + 10*(1-norm), so R = 10 / (output - T) = 10 / (10*(1-norm)) = 1/(1-norm)
        // But cap at 50
        val denom = (1f - norm).coerceAtLeast(1f / 50f)
        return (1f / denom).coerceIn(1f, 50f)
    }

    private fun ratioToSlider(ratio: Float): Float {
        // R = 1/(1-norm) → norm = 1 - 1/R
        val norm = (1f - 1f / ratio.coerceIn(1f, 50f)).coerceIn(0f, 1f)
        return (norm * 100f).coerceIn(0f, 100f)
    }

    private fun logSpacedCrossovers(bandCount: Int): FloatArray {
        // Generate bandCount-1 crossover frequencies log-spaced between 40Hz and 16kHz
        val logMin = kotlin.math.log10(40f)
        val logMax = kotlin.math.log10(16000f)
        return FloatArray(bandCount - 1) { i ->
            val frac = (i + 1).toFloat() / bandCount
            Math.pow(10.0, (logMin + frac * (logMax - logMin)).toDouble()).toFloat()
        }
    }
}
