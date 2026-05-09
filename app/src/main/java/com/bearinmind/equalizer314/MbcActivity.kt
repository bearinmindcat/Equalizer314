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
            3 to floatArrayOf(200f, 2000f),
            4 to floatArrayOf(200f, 2000f, 5000f),
            5 to floatArrayOf(200f, 2000f, 5000f, 7000f),
            6 to floatArrayOf(200f, 2000f, 5000f, 7000f, 10000f)
        )
        // Default cutoff frequencies per band index
        val DEFAULT_CUTOFFS = floatArrayOf(200f, 700f, 2000f, 5000f, 7000f, 10000f)
        // Default range values per band index
        val DEFAULT_RANGES = floatArrayOf(-4f, -8f, -6f, -6f, -6f, -6f)
    }

    private lateinit var eqPrefs: EqPreferencesManager

    // Service binding
    private var eqService: com.bearinmind.equalizer314.audio.EqService? = null
    private var serviceBound = false
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            eqService = (binder as com.bearinmind.equalizer314.audio.EqService.EqBinder).service
            serviceBound = true
            // Check DP state BEFORE pushMbcToService (which can start DP)
            val wasActive = eqService?.dynamicsManager?.isActive == true
            // Don't pushMbcToService on screen entry — causes audio dropout from DP recreation
            // MBC settings are already applied from when DP was started
            com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this@MbcActivity, wasActive)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            eqService = null
            serviceBound = false
        }
    }

    // Visualizer
    private val visualizerHelper = com.bearinmind.equalizer314.audio.VisualizerHelper()

    // Views
    private lateinit var graphView: EqGraphView
    private lateinit var grTraceView: com.bearinmind.equalizer314.ui.GrTraceView
    private var isGrTraceMode = false
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
    // RANGE FEATURE COMMENTED OUT — range UI hidden, data still saved/loaded
    // private lateinit var rangeSlider: Slider
    // private lateinit var rangeText: EditText
    // private lateinit var rangeHint: android.widget.TextView
    // private lateinit var rangeHintCard: android.view.View
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
    private var mbcNavIconRef: android.widget.ImageButton? = null

    private fun updateNavIconTint(icon: android.widget.ImageButton, enabled: Boolean) {
        val tint = if (enabled)
            com.google.android.material.color.MaterialColors.getColor(icon, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
        else
            0xFF555555.toInt()
        icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }
    private var isAnimating = false

    // Per-band data
    data class MbcBandData(
        var enabled: Boolean = true,
        var cutoff: Float = 1000f,
        var attack: Float = 1f,
        var release: Float = 100f,
        var ratio: Float = 2f,
        var threshold: Float = 0f,
        var kneeWidth: Float = 8f,
        var noiseGateThreshold: Float = -60f,
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

        // Initialize first 3 band colors with picker colors (blue, green, red)
        // Must happen BEFORE setupMbcGraph/selectBand so colors are available
        val defaultColors = intArrayOf(
            0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(), 0xFFEF9A9A.toInt()
        )
        for (i in 0 until defaultColors.size.coerceAtMost(bandCount)) {
            if (!mbcBandColors.containsKey(i)) {
                mbcBandColors[i] = defaultColors[i]
            }
        }

        setupMbcGraph()
        buildBandTabs()
        selectBand(0)
        setupListeners()
        startVisualizer()

        // Bind to EqService to push MBC settings
        val intent = android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
        bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)

        // GR trace view + graph mode toggles
        grTraceView = findViewById(R.id.mbcGrTraceView)
        grTraceView.updateNumBands(bandCount)
        grTraceView.customBandColors = IntArray(bandCount) { mbcBandColors[it] ?: 0 }
        // Sync initial thresholds to the GR trace view
        for (b in bands.indices) grTraceView.setThreshold(b, bands[b].threshold)
        // Wire threshold drag callback
        grTraceView.onThresholdChanged = { bandIndex, thresholdDb ->
            bands[bandIndex].threshold = thresholdDb
            saveBand(bandIndex)
            // Switch to the dragged band if different
            if (bandIndex != selectedBand) selectBand(bandIndex)
            // Always sync threshold slider + compressor curve
            isUpdating = true
            thresholdSlider.value = thresholdDb.coerceIn(-60f, 0f)
            thresholdText.setText(String.format("%.1f", thresholdDb))
            compressorCurve.threshold = thresholdDb
            gateCurve.compressorThreshold = thresholdDb
            isUpdating = false
        }
        // Wire crossover drag on GR trace view
        grTraceView.onCrossoverChanged = { index, freq ->
            crossoverFreqs[index] = freq
            eqPrefs.saveMbcCrossover(index, freq)
            bands[index].cutoff = freq
            saveBand(index)
            // Sync to main graph
            graphView.mbcCrossovers = crossoverFreqs.copyOf()
            graphView.invalidate()
            // Sync cutoff slider if selected band matches
            if (index == selectedBand || index + 1 == selectedBand) {
                val b = bands[selectedBand]
                val cutoffVal = if (selectedBand < crossoverFreqs.size) crossoverFreqs[selectedBand] else b.cutoff
                isUpdating = true
                cutoffSlider.value = freqToSlider(cutoffVal)
                cutoffText.setText(cutoffVal.toInt().toString())
                isUpdating = false
            }
        }
        val freqBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.mbcGraphModeFreq)
        val timeBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.mbcGraphModeTime)
        val toggleGroup = findViewById<android.widget.LinearLayout>(R.id.mbcGraphModeToggleGroup)
        val density = resources.displayMetrics.density

        // Position toggle icons — same width as spectrum button (right side)
        val gapPx = (2 * density).toInt()
        val vPadPx = 80
        graphView.post {
            val viewWidth = graphView.width
            val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
            // Spectrum button width = space between 10kHz line and right edge
            val specBtnWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)
            val btnHeight = vPadPx - 2 * gapPx

            for (btn in listOf(freqBtn, timeBtn)) {
                val lp = btn.layoutParams as android.widget.LinearLayout.LayoutParams
                lp.width = specBtnWidth
                lp.height = btnHeight
                btn.layoutParams = lp
                btn.minimumWidth = 0
                btn.minimumHeight = 0
                btn.setPadding(0, 0, 0, 0)
            }
            val groupLp = toggleGroup.layoutParams as android.widget.FrameLayout.LayoutParams
            groupLp.topMargin = gapPx
            groupLp.leftMargin = gapPx
            toggleGroup.layoutParams = groupLp

            // Force redraw after sibling layout changes (software layer may not auto-invalidate)
            graphView.invalidate()
        }
        fun updateGraphToggleStyle(freqActive: Boolean) {
            if (freqActive) {
                freqBtn.alpha = 1f; freqBtn.setBackgroundColor(0xFF555555.toInt())
                freqBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                freqBtn.strokeWidth = (2 * density).toInt()
                freqBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
                timeBtn.alpha = 1f; timeBtn.setBackgroundColor(0x00000000)
                timeBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                timeBtn.strokeWidth = (1 * density).toInt()
                timeBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            } else {
                freqBtn.alpha = 1f; freqBtn.setBackgroundColor(0x00000000)
                freqBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                freqBtn.strokeWidth = (1 * density).toInt()
                freqBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                timeBtn.alpha = 1f; timeBtn.setBackgroundColor(0xFF555555.toInt())
                timeBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                timeBtn.strokeWidth = (2 * density).toInt()
                timeBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            }
        }
        updateGraphToggleStyle(true) // freq active by default
        freqBtn.setOnClickListener {
            if (isGrTraceMode) {
                isGrTraceMode = false
                graphView.visibility = android.view.View.VISIBLE
                grTraceView.visibility = android.view.View.GONE
                updateGraphToggleStyle(true)
            }
        }
        timeBtn.setOnClickListener {
            if (!isGrTraceMode) {
                isGrTraceMode = true
                graphView.visibility = android.view.View.GONE
                grTraceView.visibility = android.view.View.VISIBLE
                updateGraphToggleStyle(false)
                // Set crossovers + thresholds so grid elements render even without spectrum
                grTraceView.crossoverFreqs = crossoverFreqs.copyOf()
                grTraceView.numBands = bandCount
                grTraceView.selectedBand = selectedBand
                for (i in 0 until bandCount) {
                    grTraceView.setThreshold(i, bands[i].threshold)
                }
                grTraceView.invalidate()
                startGrTraceUpdates()
            }
        }
    }

    // Feed GR values to the trace view from MbcGainComputer
    private var grTraceRunnable: Runnable? = null
    private val grTraceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startGrTraceUpdates() {
        grTraceRunnable?.let { grTraceHandler.removeCallbacks(it) }

        val traceComputer = com.bearinmind.equalizer314.audio.MbcGainComputer(bandCount)

        val runnable = object : Runnable {
            override fun run() {
                if (!isGrTraceMode) return

                val renderer = visualizerHelper.renderer
                val specLinear = renderer.getSmoothedLinear()
                // Use AudioPlaybackCallback-driven flag for silence detection
                val isSilent = !visualizerHelper.isMusicPlaying

                grTraceView.selectedBand = selectedBand

                if (!isSilent && specLinear != null && specLinear.isNotEmpty()) {
                    // Convert linear to dB
                    val specDb = FloatArray(specLinear.size) { i ->
                        if (specLinear[i] > 1e-10f) 20f * kotlin.math.log10(specLinear[i])
                        else -96f
                    }

                    // Compute overall input level (RMS of all bins)
                    var sumPower = 0.0
                    var count = 0
                    for (i in 1 until specDb.size) {
                        sumPower += Math.pow(10.0, specDb[i].toDouble() / 10.0)
                        count++
                    }
                    val overallLevel = if (count > 0 && sumPower > 0)
                        (10.0 * Math.log10(sumPower / count)).toFloat() else -96f

                    // Build band settings
                    val settings = Array(bandCount) { i ->
                        val b = bands[i]
                        com.bearinmind.equalizer314.audio.MbcGainComputer.BandSettings(
                            preGain = b.preGain, postGain = b.postGain,
                            threshold = b.threshold, ratio = b.ratio, kneeWidth = b.kneeWidth,
                            noiseGateThreshold = b.noiseGateThreshold, expanderRatio = b.expanderRatio,
                            attackMs = b.attack, releaseMs = b.release,
                            lowCutoff = if (i == 0) 20f else crossoverFreqs[i - 1],
                            highCutoff = if (i >= crossoverFreqs.size) 20000f else crossoverFreqs[i]
                        )
                    }

                    grTraceView.spectrumDb = specDb
                    grTraceView.spectrumBinWidth = renderer.getBinWidthHz()
                    grTraceView.crossoverFreqs = crossoverFreqs.copyOf()
                    grTraceView.selectedBand = selectedBand

                    // Compute per-band RMS levels from the NORMALIZED spectrum (for display)
                    val bandLevelsNormalized = FloatArray(bandCount) { b ->
                        val lowFreq = if (b == 0) 20f else crossoverFreqs[b - 1]
                        val highFreq = if (b >= crossoverFreqs.size) 20000f else crossoverFreqs[b]
                        val binW = renderer.getBinWidthHz()
                        val lowBin = (lowFreq / binW).toInt().coerceIn(1, specDb.size - 1)
                        val highBin = (highFreq / binW).toInt().coerceIn(lowBin, specDb.size - 1)
                        var sumPow = 0.0; var cnt = 0
                        for (k in lowBin..highBin) {
                            sumPow += Math.pow(10.0, specDb[k].toDouble() / 10.0); cnt++
                        }
                        if (cnt > 0 && sumPow > 0) (10.0 * Math.log10(sumPow / cnt)).toFloat().coerceAtLeast(-80f) else -80f
                    }

                    // Calibrate to absolute dBFS for the compressor math
                    // This makes the GR computation match the threshold values
                    val calibrationOffset = visualizerHelper.normToAbsoluteOffset
                    val calibratedSpecDb = FloatArray(specDb.size) { specDb[it] + calibrationOffset }

                    // Recompute GR using CALIBRATED (absolute dBFS) spectrum
                    traceComputer.computeAllBandGains(calibratedSpecDb, 48000, 4096, settings)
                    val grValues = FloatArray(bandCount) { traceComputer.getSmoothedCompressorGR(it) }
                    val gateGrValues = FloatArray(bandCount) { traceComputer.getSmoothedExpanderGR(it) }

                    // Display uses CALIBRATED levels + pre-gain (shows what the compressor sees)
                    val bandLevelsAbsolute = FloatArray(bandCount) {
                        (bandLevelsNormalized[it] + calibrationOffset + bands[it].preGain).coerceIn(-80f, 20f)
                    }

                    grTraceView.pushFrame(grValues, bandLevelsAbsolute, gateGrValues)
                } else {
                    // Silence — GR at 0 (no compression), levels at -80 (bottom)
                    grTraceView.pushFrame(FloatArray(bandCount) { 0f }, FloatArray(bandCount) { -80f })
                }

                grTraceView.invalidate()
                grTraceHandler.postDelayed(this, 33)
            }
        }
        grTraceRunnable = runnable
        grTraceHandler.post(runnable)
    }

    private fun startVisualizer() {
        val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.mbcVisualizerToggle)
        val mbcResetBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.mbcResetButton)
        val density = resources.displayMetrics.density

        // Position buttons in top-right corner of graph (same as main EQ)
        val gapPx = (2 * density).toInt()
        val vPadPx = 80
        graphView.post {
            val viewWidth = graphView.width
            val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
            val btnTop = gapPx
            val btnHeight = vPadPx - 2 * gapPx

            // Spectrum button: between 10kHz line and right edge
            val specLeft = gridLine10k + gapPx
            val specRight = viewWidth - gapPx
            val specWidth = specRight - specLeft
            val lp = vizToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = specWidth
            lp.height = btnHeight
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.leftMargin = specLeft
            lp.topMargin = btnTop
            lp.rightMargin = 0
            vizToggle.layoutParams = lp
            vizToggle.minimumWidth = 0
            vizToggle.minimumHeight = 0
            vizToggle.setPadding(0, 0, 0, 0)

            // Reset button: same size, to left of spectrum
            val resetLp = mbcResetBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            resetLp.width = specWidth
            resetLp.height = btnHeight
            resetLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            resetLp.leftMargin = (specLeft - gapPx - specWidth).coerceAtLeast(gapPx)
            resetLp.topMargin = btnTop
            mbcResetBtn.layoutParams = resetLp
            mbcResetBtn.minimumWidth = 0; mbcResetBtn.minimumHeight = 0
            mbcResetBtn.setPadding(0, 0, 0, 0)

            graphView.invalidate()
        }

        fun updateVizStyle(active: Boolean) {
            if (active) {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(0xFF555555.toInt())
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                vizToggle.strokeWidth = (2 * density).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(0x00000000)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                vizToggle.strokeWidth = (1 * density).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }

        // Reset button: reset all MBC bands to defaults
        mbcResetBtn.setOnClickListener {
            val dialogView = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            }
            val titleTv = android.widget.TextView(this).apply {
                text = "Reset"
                setTextColor(0xFFE2E2E2.toInt())
                textSize = 20f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            val messageTv = android.widget.TextView(this).apply {
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
            dialogView.addView(titleTv)
            dialogView.addView(messageTv)
            dialogView.addView(divider)
            dialogView.addView(btnRow)

            val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
                .setView(dialogView)
                .create()
            cancelBtn.setOnClickListener { dialog.dismiss() }
            resetDialogBtn.setOnClickListener {
                for (i in 0 until bandCount) {
                    bands[i].threshold = 0f
                    bands[i].ratio = 2f
                    bands[i].kneeWidth = 8f
                    bands[i].attack = 1f
                    bands[i].release = 100f
                    bands[i].noiseGateThreshold = -60f
                    bands[i].expanderRatio = 1f
                    bands[i].preGain = 0f
                    bands[i].postGain = 0f
                    saveBand(i)
                }
                graphView.mbcBandGains?.let { for (i in it.indices) it[i] = 0f }
                graphView.invalidate()
                loadBandToUI()
                pushMbcToService()
                android.widget.Toast.makeText(this, "MBC reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.show()
        }

        vizToggle.setOnClickListener {
            if (visualizerHelper.isRunning) {
                visualizerHelper.stop()
                graphView.spectrumRenderer = null
                graphView.invalidate()
                updateVizStyle(false)
                eqPrefs.saveSpectrumEnabled(false)
            } else {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                    return@setOnClickListener
                }
                applySpectrumSettings()
                visualizerHelper.start(graphView)
                graphView.spectrumRenderer = visualizerHelper.renderer
                updateVizStyle(true)
                eqPrefs.saveSpectrumEnabled(true)
            }
        }

        // Restore spectrum state from preferences
        if (eqPrefs.getSpectrumEnabled() &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            applySpectrumSettings()
            visualizerHelper.start(graphView)
            graphView.spectrumRenderer = visualizerHelper.renderer
            updateVizStyle(true)
        } else {
            updateVizStyle(false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(graphView)
            graphView.spectrumRenderer = visualizerHelper.renderer
            val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.mbcVisualizerToggle)
            val d = resources.displayMetrics.density
            vizToggle.alpha = 1.0f
            vizToggle.setBackgroundColor(0xFF555555.toInt())
            vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            vizToggle.strokeWidth = (2 * d).toInt()
            vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
        }
    }

    private fun initViews() {
        // Back button removed — navigable via bottom nav
        // findViewById<android.widget.ImageButton>(R.id.mbcBackButton).setOnClickListener { finish(); overridePendingTransition(0, 0) }

        // masterSwitch must be initialized before bottom nav references it
        masterSwitch = findViewById(R.id.mbcMasterSwitch)

        // Bottom nav — shared helper handles icons, status, and navigation
        com.bearinmind.equalizer314.ui.BottomNavHelper.setup(this, com.bearinmind.equalizer314.ui.NavScreen.MBC, eqPrefs)
        val mbcNavIcon = findViewById<android.widget.ImageButton>(R.id.navMbcButton)
        mbcNavIconRef = mbcNavIcon
        // Power FAB toggles DynamicsProcessing on/off
        val powerFab = findViewById<android.widget.ImageButton>(R.id.powerFab)
        powerFab.setOnClickListener {
            val svc = eqService ?: return@setOnClickListener
            if (svc.dynamicsManager.isActive) {
                svc.dynamicsManager.stop()
            } else {
                // Promote the service to a started foreground service so it
                // survives activity unbind/rebind across navigation. Without
                // this the service is bind-only and gets destroyed when
                // MbcActivity exits, tearing DP down with it.
                com.bearinmind.equalizer314.audio.EqService.start(this)
                val tempEq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
                eqPrefs.restoreState(tempEq)
                svc.dynamicsManager.mbcEnabled = eqPrefs.getMbcEnabled()
                svc.dynamicsManager.mbcBandCount = bandCount
                svc.dynamicsManager.start(tempEq)
                pushMbcToService()
            }
            val on = svc.dynamicsManager.isActive
            com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerState(this, eqPrefs, on)
            android.widget.Toast.makeText(this, if (on) "DynamicsProcessing Start" else "DynamicsProcessing Stop", android.widget.Toast.LENGTH_SHORT).show()
        }

        // Graph with MBC band visualization
        graphView = findViewById(R.id.mbcGraphView)
        graphView.showSaturationCurve = false
        graphView.showBandPoints = false
        val eq = ParametricEqualizer()
        eqPrefs.restoreState(eq)
        graphView.setParametricEqualizer(eq)
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
        // RANGE FEATURE COMMENTED OUT — range UI hidden
        // rangeSlider = findViewById(R.id.mbcRangeSlider)
        // rangeText = findViewById(R.id.mbcRangeText)
        // rangeHint = findViewById(R.id.mbcRangeHint)
        // rangeHintCard = findViewById(R.id.mbcRangeHintCard)
        // // Draw triangle pointer for the hint bubble
        // val triangleView = findViewById<android.widget.ImageView>(R.id.mbcRangeTrianglePointer)
        // val triSize = (8 * resources.displayMetrics.density).toInt()
        // val triWidth = (16 * resources.displayMetrics.density).toInt()
        // val triBitmap = android.graphics.Bitmap.createBitmap(triWidth, triSize, android.graphics.Bitmap.Config.ARGB_8888)
        // val triCanvas = android.graphics.Canvas(triBitmap)
        // val triPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        //     color = 0xFF333333.toInt()
        //     style = android.graphics.Paint.Style.FILL
        // }
        // val triPath = android.graphics.Path().apply {
        //     moveTo(triWidth / 2f, 0f)
        //     lineTo(0f, triSize.toFloat())
        //     lineTo(triWidth.toFloat(), triSize.toFloat())
        //     close()
        // }
        // triCanvas.drawPath(triPath, triPaint)
        // triangleView.setImageBitmap(triBitmap)

        // RANGE FEATURE COMMENTED OUT — range info button and triangle positioning
        // val rangeInfoBtn = findViewById<android.view.View>(R.id.mbcRangeInfoButton)
        // rangeInfoBtn.setOnClickListener {
        //     rangeHintCard.visibility = if (rangeHintCard.visibility == android.view.View.VISIBLE)
        //         android.view.View.GONE else android.view.View.VISIBLE
        // }
        // // Measure actual text width of "Range (dB)" and center triangle under it
        // rangeInfoBtn.post {
        //     val paint = android.graphics.Paint().apply {
        //         textSize = 12f * resources.displayMetrics.scaledDensity // LabelMedium ~12sp
        //     }
        //     val textWidth = paint.measureText("Range (dB)")
        //     val paddingStart = 4 * resources.displayMetrics.density // 4dp paddingStart on the TextView
        //     val textCenter = paddingStart + textWidth / 2f
        //     val triHalfWidth = 8 * resources.displayMetrics.density // half of 16dp triangle
        //     val margin = (textCenter - triHalfWidth).toInt().coerceAtLeast(0)
        //     (triangleView.layoutParams as android.widget.LinearLayout.LayoutParams).leftMargin = margin
        //     triangleView.requestLayout()
        // }
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
        for (i in 0 until bandCount) {
            bands.add(MbcBandData(
                enabled = eqPrefs.getMbcBandEnabled(i),
                cutoff = eqPrefs.getMbcBandCutoff(i, DEFAULT_CUTOFFS.getOrElse(i) { 10000f }),
                attack = eqPrefs.getMbcBandAttack(i),
                release = eqPrefs.getMbcBandRelease(i),
                ratio = eqPrefs.getMbcBandRatio(i),
                threshold = eqPrefs.getMbcBandThreshold(i),
                kneeWidth = eqPrefs.getMbcBandKnee(i),
                noiseGateThreshold = eqPrefs.getMbcBandNoiseGate(i),
                expanderRatio = eqPrefs.getMbcBandExpander(i),
                preGain = eqPrefs.getMbcBandPreGain(i),
                postGain = eqPrefs.getMbcBandPostGain(i),
                range = eqPrefs.getMbcBandRange(i, DEFAULT_RANGES.getOrElse(i) { -6f })
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
        graphView.mbcBandColors = IntArray(bandCount) { mbcBandColors[it] ?: 0 }
        graphView.mbcSelectedBand = selectedBand

        // Initialize all MBC params for spectrum overlay
        syncMbcParamsToGraph()
        // RANGE FEATURE COMMENTED OUT — range data not sent to graph
        // graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }

        graphView.onMbcBandSelected = { bandIndex ->
            selectBand(bandIndex)
        }

        graphView.onMbcCrossoverChanged = { index, freq ->
            crossoverFreqs[index] = freq
            eqPrefs.saveMbcCrossover(index, freq)
            bands[index].cutoff = freq
            saveBand(index)
            // Also update the next band's cutoff reference if it exists
            if (index + 1 < bands.size) {
                bands[index + 1].cutoff = freq
                saveBand(index + 1)
            }
            // Sync cutoff slider/text if the selected band is affected.
            // The slider is in 0..1000 log-space units (see freqToSlider),
            // NOT raw Hz — assigning b.cutoff (which is Hz) directly
            // crashes Material Slider's validateValues at the next draw.
            if (index == selectedBand || index + 1 == selectedBand) {
                val b = bands[selectedBand]
                cutoffSlider.value = freqToSlider(b.cutoff.coerceIn(20f, 20000f))
                cutoffText.setText(b.cutoff.toInt().toString())
            }
        }

        graphView.onMbcBandGainChanged = { bandIndex, gain ->
            val snapped = Math.round(gain * 10f) / 10f
            bands[bandIndex].preGain = snapped
            saveBand(bandIndex)
            // Sync preGain slider if this is the currently selected band
            if (bandIndex == selectedBand) {
                isUpdating = true
                preGainSlider.value = snapped.coerceIn(-30f, 30f)
                preGainText.setText(String.format("%.1f", snapped))
                isUpdating = false
            }
        }

        graphView.onMbcBandGainReset = { bandIndex ->
            bands[bandIndex].preGain = 0f
            saveBand(bandIndex)
            if (bandIndex == selectedBand) {
                isUpdating = true
                preGainSlider.value = 0f
                preGainText.setText("0.0")
                isUpdating = false
            }
        }

        // RANGE FEATURE COMMENTED OUT — range graph callback
        // graphView.onMbcBandRangeChanged = { bandIndex, range ->
        //     val snapped = Math.round(range * 10f) / 10f
        //     bands[bandIndex].range = snapped
        //     saveBand(bandIndex)
        //     if (bandIndex == selectedBand) {
        //         isUpdating = true
        //         rangeSlider.value = snapped.coerceIn(-12f, 0f)
        //         rangeText.setText(String.format("%.1f", snapped))
        //         isUpdating = false
        //     }
        // }
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
        // Only show (+) when bands have been removed below default count
        if (bandCount < DEFAULT_BAND_COUNT) {
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
        // Cutoff reads from crossoverFreqs, log-mapped slider
        val cutoffVal = if (selectedBand < crossoverFreqs.size) crossoverFreqs[selectedBand] else b.cutoff
        cutoffSlider.valueFrom = 0f
        cutoffSlider.valueTo = 1000f
        cutoffSlider.value = freqToSlider(cutoffVal)
        cutoffText.setText(cutoffVal.toInt().toString())
        attackSlider.value = b.attack.coerceIn(0.01f, 500f)
        attackText.setText(String.format("%.2f", b.attack))
        releaseSlider.value = b.release.coerceIn(1f, 5000f)
        releaseText.setText(String.format("%.0f", b.release))
        ratioSlider.value = ratioToSlider(b.ratio)
        ratioText.setText(String.format("%.2f", b.ratio))
        thresholdSlider.value = b.threshold.coerceIn(-60f, 0f)
        thresholdText.setText(String.format("%.1f", b.threshold))
        // RANGE FEATURE COMMENTED OUT — range slider/text in loadBandToUI
        // rangeSlider.value = b.range.coerceIn(-12f, 0f)
        // rangeText.setText(String.format("%.1f", b.range))
        kneeSlider.value = b.kneeWidth.coerceIn(0.01f, 24f)
        kneeText.setText(String.format("%.2f", b.kneeWidth))
        noiseGateSlider.value = b.noiseGateThreshold.coerceIn(-90f, 0f)
        noiseGateText.setText(String.format("%.0f", b.noiseGateThreshold))
        expanderSlider.value = ratioToSlider(b.expanderRatio)
        expanderText.setText(String.format("%.2f", b.expanderRatio))
        preGainSlider.value = b.preGain.coerceIn(-30f, 30f)
        preGainText.setText(String.format("%.1f", b.preGain))
        postGainSlider.value = b.postGain.coerceIn(-30f, 30f)
        postGainText.setText(String.format("%.1f", b.postGain))
        // Sync compressor curve
        compressorCurve.selectedBand = selectedBand
        compressorCurve.threshold = b.threshold
        compressorCurve.ratio = b.ratio
        compressorCurve.kneeWidth = b.kneeWidth
        compressorCurve.gateThreshold = b.noiseGateThreshold  // for dulled dot reference
        gateCurve.selectedBand = selectedBand
        gateCurve.gateThreshold = b.noiseGateThreshold
        gateCurve.expanderRatio = b.expanderRatio
        gateCurve.compressorThreshold = b.threshold  // for dulled dot reference
        // Force redraw after all properties set (software layer may cache stale frame)
        compressorCurve.post { compressorCurve.invalidate() }
        gateCurve.post { gateCurve.invalidate() }
        attackReleaseView.attackMs = b.attack
        attackReleaseView.releaseMs = b.release
        // RANGE FEATURE COMMENTED OUT — updateRangeHint() call in loadBandToUI
        // updateRangeHint()
        isUpdating = false
    }

    private fun setupListeners() {
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            eqPrefs.saveMbcEnabled(checked)
            pushMbcToService()
            com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
        }

        bandSwitch.setOnCheckedChangeListener { _, checked ->
            if (isUpdating) return@setOnCheckedChangeListener
            bands[selectedBand].enabled = checked
            saveBand(selectedBand)
        }

        // Cutoff slider: logarithmic (0-1000 → 20-20000 Hz), clamped between adjacent crossovers
        cutoffSlider.valueFrom = 0f
        cutoffSlider.valueTo = 1000f
        cutoffSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdating) return@addOnChangeListener
            var freq = sliderToFreq(value)
            // Clamp between adjacent crossovers
            val minFreq = if (selectedBand > 0 && selectedBand - 1 < crossoverFreqs.size)
                crossoverFreqs[selectedBand - 1] + 1f else 20f
            val maxFreq = if (selectedBand + 1 < crossoverFreqs.size)
                crossoverFreqs[selectedBand + 1] - 1f else 20000f
            freq = freq.coerceIn(minFreq, maxFreq)
            if (freqToSlider(freq) != value) {
                isUpdating = true
                cutoffSlider.value = freqToSlider(freq)
                isUpdating = false
            }
            cutoffText.setText(freq.toInt().toString())
            bands[selectedBand].cutoff = freq
            if (selectedBand < crossoverFreqs.size) {
                crossoverFreqs[selectedBand] = freq
                eqPrefs.saveMbcCrossover(selectedBand, freq)
                graphView.mbcCrossovers = crossoverFreqs.copyOf()
                graphView.invalidate()
            }
            saveBand(selectedBand)
        }
        cutoffText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val minFreq = if (selectedBand > 0 && selectedBand - 1 < crossoverFreqs.size)
                    crossoverFreqs[selectedBand - 1] + 1f else 20f
                val maxFreq = if (selectedBand + 1 < crossoverFreqs.size)
                    crossoverFreqs[selectedBand + 1] - 1f else 20000f
                val v = cutoffText.text.toString().toFloatOrNull()?.coerceIn(minFreq, maxFreq) ?: minFreq
                cutoffText.setText(v.toInt().toString())
                cutoffSlider.value = freqToSlider(v)
                bands[selectedBand].cutoff = v
                if (selectedBand < crossoverFreqs.size) {
                    crossoverFreqs[selectedBand] = v
                    eqPrefs.saveMbcCrossover(selectedBand, v)
                    graphView.mbcCrossovers = crossoverFreqs.copyOf()
                    graphView.invalidate()
                }
                saveBand(selectedBand)
                cutoffText.clearFocus()
            }
            true
        }
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
            // RANGE FEATURE COMMENTED OUT
            // updateRangeHint()
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
            gateCurve.compressorThreshold = it  // sync dulled dot
            grTraceView.setThreshold(selectedBand, it)
            // RANGE FEATURE COMMENTED OUT
            // updateRangeHint()
        }
        // RANGE FEATURE COMMENTED OUT — range slider setup
        // setupSlider(rangeSlider, rangeText, -12f, 0f, "%.1f") {
        //     bands[selectedBand].range = it
        //     graphView.mbcBandRanges?.let { ranges ->
        //         ranges[selectedBand] = it
        //         graphView.invalidate()
        //     }
        //     updateRangeHint()
        // }
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
            compressorCurve.gateThreshold = it  // sync dulled dot
        }
        // Expander ratio slider: same exponential mapping as compressor ratio
        expanderSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdating) return@addOnChangeListener
            val ratio = sliderToRatio(value)
            expanderText.setText(String.format("%.2f", ratio))
            bands[selectedBand].expanderRatio = ratio
            gateCurve.expanderRatio = ratio
            saveBand(selectedBand)
        }
        expanderText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val v = expanderText.text.toString().toFloatOrNull()?.coerceIn(1f, 50f) ?: 1f
                expanderText.setText(String.format("%.2f", v))
                expanderSlider.value = ratioToSlider(v)
                bands[selectedBand].expanderRatio = v
                gateCurve.expanderRatio = v
                saveBand(selectedBand)
                expanderText.clearFocus()
            }
            true
        }
        setupSlider(preGainSlider, preGainText, -30f, 30f, "%.1f") {
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
            gateCurve.compressorThreshold = value  // sync dulled dot
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

        compressorCurve.onKneeChanged = { value ->
            bands[selectedBand].kneeWidth = value
            saveBand(selectedBand)
            isUpdating = true
            kneeSlider.value = value.coerceIn(0.01f, 24f)
            kneeText.setText(String.format("%.2f", value))
            isUpdating = false
        }

        // Gate callbacks — sync sliders when dragging on the gate graph
        gateCurve.onGateThresholdChanged = { value ->
            bands[selectedBand].noiseGateThreshold = value
            saveBand(selectedBand)
            isUpdating = true
            noiseGateSlider.value = value.coerceIn(-90f, 0f)
            noiseGateText.setText(String.format("%.0f", value))
            compressorCurve.gateThreshold = value  // sync dulled dot
            isUpdating = false
        }
        gateCurve.onExpanderRatioChanged = { value ->
            bands[selectedBand].expanderRatio = value
            saveBand(selectedBand)
            isUpdating = true
            expanderSlider.value = ratioToSlider(value)
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

        // Double-tap slider thumbs to reset to defaults
        addDoubleTapReset(thresholdSlider) {
            bands[selectedBand].threshold = 0f; saveBand(selectedBand)
            thresholdSlider.value = 0f; thresholdText.setText("0.0")
            compressorCurve.threshold = 0f; gateCurve.compressorThreshold = 0f
        }
        addDoubleTapReset(ratioSlider) {
            bands[selectedBand].ratio = 2f; saveBand(selectedBand)
            ratioSlider.value = ratioToSlider(2f); ratioText.setText("2.00")
            compressorCurve.ratio = 2f
        }
        addDoubleTapReset(kneeSlider) {
            bands[selectedBand].kneeWidth = 8f; saveBand(selectedBand)
            kneeSlider.value = 8f; kneeText.setText("8.00")
            compressorCurve.kneeWidth = 8f
        }
        addDoubleTapReset(attackSlider) {
            bands[selectedBand].attack = 1f; saveBand(selectedBand)
            attackSlider.value = 1f; attackText.setText("1.00")
            attackReleaseView.attackMs = 1f
        }
        addDoubleTapReset(releaseSlider) {
            bands[selectedBand].release = 100f; saveBand(selectedBand)
            releaseSlider.value = 100f; releaseText.setText("100")
            attackReleaseView.releaseMs = 100f
        }
        addDoubleTapReset(noiseGateSlider) {
            bands[selectedBand].noiseGateThreshold = -60f; saveBand(selectedBand)
            noiseGateSlider.value = -60f; noiseGateText.setText("-60")
            gateCurve.gateThreshold = -60f; compressorCurve.gateThreshold = -60f
        }
        addDoubleTapReset(expanderSlider) {
            bands[selectedBand].expanderRatio = 1f; saveBand(selectedBand)
            expanderSlider.value = ratioToSlider(1f); expanderText.setText("1.00")
            gateCurve.expanderRatio = 1f
        }
        addDoubleTapReset(preGainSlider) {
            bands[selectedBand].preGain = 0f; saveBand(selectedBand)
            preGainSlider.value = 0f; preGainText.setText("0.0")
            graphView.mbcBandGains?.let { it[selectedBand] = 0f; graphView.invalidate() }
        }
        // RANGE FEATURE COMMENTED OUT — range double-tap reset
        // addDoubleTapReset(rangeSlider) {
        //     val defRange = DEFAULT_RANGES.getOrElse(selectedBand) { -6f }
        //     bands[selectedBand].range = defRange; saveBand(selectedBand)
        //     rangeSlider.value = defRange; rangeText.setText(String.format("%.1f", defRange))
        //     graphView.mbcBandRanges?.let { it[selectedBand] = defRange; graphView.invalidate() }
        // }
        addDoubleTapReset(postGainSlider) {
            bands[selectedBand].postGain = 0f; saveBand(selectedBand)
            postGainSlider.value = 0f; postGainText.setText("0.0")
        }
        addDoubleTapReset(cutoffSlider) {
            val defCutoff = DEFAULT_CUTOFFS.getOrElse(selectedBand) { 1000f }
            bands[selectedBand].cutoff = defCutoff; saveBand(selectedBand)
            cutoffSlider.value = freqToSlider(defCutoff); cutoffText.setText(defCutoff.toInt().toString())
            if (selectedBand < crossoverFreqs.size) {
                crossoverFreqs[selectedBand] = defCutoff
                eqPrefs.saveMbcCrossover(selectedBand, defCutoff)
                graphView.mbcCrossovers = crossoverFreqs.copyOf()
                graphView.invalidate()
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addDoubleTapReset(slider: Slider, onReset: () -> Unit) {
        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { _, event ->
            if (consumeUntilUp) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    consumeUntilUp = false
                }
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    isUpdating = true
                    onReset()
                    isUpdating = false
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            false
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

    // RANGE FEATURE COMMENTED OUT — entire updateRangeHint() function
    // private fun updateRangeHint() {
    //     val b = bands[selectedBand]
    //     val range = b.range
    //     val threshold = b.threshold
    //     if (range >= 0f || threshold >= 0f) {
    //         rangeHint.text = "Range (visual only)\nShows estimated gain reduction on graph"
    //         return
    //     }
    //     val absRange = -range
    //     val absThresh = -threshold
    //     val ratio = b.ratio
    //     val actualRange = if (ratio > 1f) -(absThresh * (1f - 1f / ratio)) else 0f
    //
    //     val sb = StringBuilder("Range (visual only)\n")
    //     sb.append("Current: ${String.format("%.1f", threshold)} dB thresh | ${String.format("%.2f", ratio)}:1 ratio → ${String.format("%.1f", actualRange)} dB reduction\n")
    //     sb.append("\nTo achieve ${String.format("%.1f", range)} dB range:\n")
    //
    //     // Option 1: keep current threshold, adjust ratio
    //     if (absRange < absThresh) {
    //         val neededRatio = absThresh / (absThresh - absRange)
    //         sb.append("• Keep thresh ${String.format("%.1f", threshold)} dB → set ratio to ${String.format("%.2f", neededRatio)}:1\n")
    //     } else {
    //         sb.append("• Keep thresh ${String.format("%.1f", threshold)} dB → need ∞:1 (limiter)\n")
    //     }
    //
    //     // Option 2: keep current ratio, adjust threshold
    //     if (ratio > 1f) {
    //         val neededThresh = -(absRange / (1f - 1f / ratio))
    //         if (neededThresh in -60f..0f) {
    //             sb.append("• Keep ratio ${String.format("%.2f", ratio)}:1 → set thresh to ${String.format("%.1f", neededThresh)} dB")
    //         }
    //     }
    //
    //     rangeHint.text = sb.toString().trimEnd()
    // }

    /** Push MBC band params to the renderer's MbcGainComputer for spectrum overlay */
    private fun syncMbcParamsToGraph() {
        graphView.mbcBandGains = FloatArray(bandCount) { bands[it].preGain }

        // Push full band settings to the renderer's MbcGainComputer
        val renderer = graphView.spectrumRenderer
        if (renderer != null) {
            if (renderer.mbcGainComputer == null || renderer.mbcGainComputer!!.let { false }) {
                renderer.mbcGainComputer = com.bearinmind.equalizer314.audio.MbcGainComputer(bandCount)
            }
            renderer.mbcBandSettings = Array(bandCount) { i ->
                val b = bands[i]
                com.bearinmind.equalizer314.audio.MbcGainComputer.BandSettings(
                    preGain = b.preGain,
                    postGain = b.postGain,
                    threshold = b.threshold,
                    ratio = b.ratio,
                    kneeWidth = b.kneeWidth,
                    noiseGateThreshold = b.noiseGateThreshold,
                    expanderRatio = b.expanderRatio,
                    attackMs = b.attack,
                    releaseMs = b.release,
                    lowCutoff = if (i == 0) 20f else crossoverFreqs[i - 1],
                    highCutoff = if (i >= crossoverFreqs.size) 20000f else crossoverFreqs[i]
                )
            }
        }
        graphView.invalidate()
    }

    /** Push current MBC band settings to DynamicsProcessing via the service */
    private fun pushMbcToService() {
        val service = eqService ?: run {
            android.util.Log.w("MbcActivity", "pushMbcToService: eqService is NULL — service not bound yet")
            return
        }
        val dm = service.dynamicsManager
        val isEnabled = masterSwitch.isChecked
        android.util.Log.d("MbcActivity", "pushMbcToService: isEnabled=$isEnabled, dm.isActive=${dm.isActive}, dm.mbcEnabled=${dm.mbcEnabled}")

        // If MBC enable state or band count changed, need to recreate DP — but only if DP is already running
        if (!dm.isActive) return  // Don't start DP from MBC settings — only power button should start it
        if (dm.mbcEnabled != isEnabled || (isEnabled && dm.mbcBandCount != bandCount)) {
            dm.mbcEnabled = isEnabled
            dm.mbcBandCount = bandCount
            val tempEq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
            val eqState = com.bearinmind.equalizer314.state.EqPreferencesManager(this)
            eqState.restoreState(tempEq)
            dm.start(tempEq)
            android.util.Log.d("MbcActivity", "Recreated DynamicsProcessing with MBC enabled=$isEnabled, bandCount=$bandCount")
        }

        if (!isEnabled) return

        val mbcBands = bands.map { b ->
            com.bearinmind.equalizer314.audio.DynamicsProcessingManager.MbcBandParams(
                enabled = b.enabled,
                attackMs = b.attack,
                releaseMs = b.release,
                ratio = b.ratio,
                thresholdDb = b.threshold,
                kneeDb = b.kneeWidth,
                noiseGateDb = b.noiseGateThreshold,
                expanderRatio = b.expanderRatio,
                preGainDb = b.preGain,
                postGainDb = b.postGain
            )
        }
        service.updateMbc(mbcBands, crossoverFreqs)
    }

    private fun saveBand(index: Int) {
        val b = bands[index]
        eqPrefs.saveMbcBand(index, b.enabled, b.cutoff, b.attack, b.release, b.ratio,
            b.threshold, b.kneeWidth, b.noiseGateThreshold, b.expanderRatio, b.preGain, b.postGain, b.range)
        syncMbcParamsToGraph()
        pushMbcToService()
    }

    override fun onResume() {
        super.onResume()
        // Sync nav bar from saved state
        com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, eqPrefs.getPowerState())
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateHighlight(this, com.bearinmind.equalizer314.ui.NavScreen.MBC)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
        // Apply spectrum settings (PPO smoothing, color, release) — may have
        // changed in SpectrumControlActivity while we were paused.
        applySpectrumSettings()
        // Restart visualizer if it was enabled (may have been stopped in onPause)
        if (eqPrefs.getSpectrumEnabled() && !visualizerHelper.isRunning &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(graphView)
            graphView.spectrumRenderer = visualizerHelper.renderer
        }
    }

    private fun applySpectrumSettings() {
        val ppoValues = intArrayOf(1, 2, 3, 6, 12, 24, 48, 96)
        val renderer = visualizerHelper.renderer
        if (eqPrefs.getPpoEnabled()) {
            renderer.ppoSmoothing = ppoValues[eqPrefs.getPpoIndex().coerceIn(0, 7)]
        } else {
            renderer.ppoSmoothing = 0
        }
        renderer.setSpectrumColor(eqPrefs.getSpectrumColor())
        renderer.releaseAlpha = eqPrefs.getSpectrumRelease()
    }

    override fun onPause() {
        super.onPause()
        // Release visualizer so other activities can use session 0
        visualizerHelper.stop()
        graphView.spectrumRenderer = null
        eqPrefs.saveMbcEnabled(masterSwitch.isChecked)
        for (i in bands.indices) saveBand(i)
    }

    override fun onDestroy() {
        grTraceRunnable?.let { grTraceHandler.removeCallbacks(it) }
        grTraceView.release()
        visualizerHelper.stop()
        graphView.spectrumRenderer = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun addBand() {
        if (bandCount >= DEFAULT_BAND_COUNT) return

        val oldBandCount = bandCount
        bandCount++
        eqPrefs.saveMbcBandCount(bandCount)

        // Add new band with defaults
        bands.add(MbcBandData(cutoff = DEFAULT_CUTOFFS.getOrElse(bandCount - 1) { 10000f }))

        // Recompute crossovers BEFORE saveBand. saveBand → syncMbcParamsToGraph
        // iterates over the new bands.size and reads crossoverFreqs[i] /
        // [i-1]; if crossovers are still at the old size, the loop walks
        // past the end and crashes with ArrayIndexOutOfBoundsException.
        val defaults = DEFAULT_CROSSOVERS_BY_COUNT[bandCount] ?: logSpacedCrossovers(bandCount)
        crossoverFreqs = FloatArray(bandCount - 1) { i ->
            if (i < oldBandCount - 1) crossoverFreqs.getOrElse(i) { defaults[i] }
            else defaults.getOrElse(i) { 1000f }
        }
        for (i in crossoverFreqs.indices) eqPrefs.saveMbcCrossover(i, crossoverFreqs[i])

        saveBand(bandCount - 1)

        // Update graph
        graphView.mbcCrossovers = crossoverFreqs
        graphView.mbcBandGains = FloatArray(bandCount) { bands[it].preGain }
        // RANGE FEATURE COMMENTED OUT
        // graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }
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
        val atMax = bandCount >= DEFAULT_BAND_COUNT
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
        val wasAtMax = bandCount == DEFAULT_BAND_COUNT
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
        // RANGE FEATURE COMMENTED OUT
        // graphView.mbcBandRanges = FloatArray(bandCount) { bands[it].range }
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
                val colorArray = IntArray(bandCount) { mbcBandColors[it] ?: 0 }
                graphView.mbcBandColors = colorArray
                grTraceView.customBandColors = colorArray
                graphView.invalidate()
                grTraceView.invalidate()
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
    // Log-frequency mapping for cutoff slider (slider 0-1000 → freq 20-20000 Hz)
    private fun sliderToFreq(sliderValue: Float): Float {
        val norm = (sliderValue / 1000f).coerceIn(0f, 1f)
        val logMin = kotlin.math.log10(20f)
        val logMax = kotlin.math.log10(20000f)
        return Math.pow(10.0, (logMin + norm * (logMax - logMin)).toDouble()).toFloat()
    }

    private fun freqToSlider(freq: Float): Float {
        val logMin = kotlin.math.log10(20f)
        val logMax = kotlin.math.log10(20000f)
        val norm = (kotlin.math.log10(freq.coerceIn(20f, 20000f)) - logMin) / (logMax - logMin)
        return (norm * 1000f).coerceIn(0f, 1000f)
    }

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
