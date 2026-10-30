package com.bearinmind.equalizer314

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.audio.SpectrumAnalyzerRenderer
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.ui.EqGraphView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SpectrumControlActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private var visualizer: android.media.audiofx.Visualizer? = null
    private var renderer: SpectrumAnalyzerRenderer? = null
    private var graphView: EqGraphView? = null
    private var isMusicPlaying = true
    private var audioManager: android.media.AudioManager? = null
    private var playbackCallback: android.media.AudioManager.AudioPlaybackCallback? = null

    private val fftSizes = com.bearinmind.equalizer314.audio.LongWindowAnalyzer.SIZES
    private val fftLabels = fftSizes.map { "$it" }.toTypedArray()
    private val ppoValues = intArrayOf(1, 2, 3, 6, 12, 24, 48, 96)
    private val ppoLabels = arrayOf("1/1", "1/2", "1/3", "1/6", "1/12", "1/24", "1/48", "1/96")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spectrum_control)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.spectrumBackButton).setOnClickListener { finish() }

        // Spectrum preview using EqGraphView
        renderer = SpectrumAnalyzerRenderer()
        graphView = findViewById(R.id.spectrumGraphView)
        graphView?.spectrumRenderer = renderer
        graphView?.showBandPoints = false

        // Load the parametric EQ so the graph initializes (draws grid lines, Hz/dB
        // labels, background, etc.). Without this the graph shows "Parametric EQ not
        // initialized" because bandPoints is empty and onDraw returns early.
        val eq = ParametricEqualizer()
        eqPrefs.restoreState(eq)
        graphView?.setParametricEqualizer(eq)

        // Hide the EQ curves — we only want the grid/background on this screen.
        // showEqCurve = false hides the solid grey EQ frequency response line.
        // showSaturationCurve = false hides the dashed orange tanh saturation line.
        // To bring them back, set either/both to true.
        graphView?.showEqCurve = false
        graphView?.showSaturationCurve = false

        applyCurrentSettings()

        // Spectrum toggle — programmatically sized/positioned to match the main EQ
        // screen's visualizerToggle button (sits between the 10kHz grid line and the
        // right edge of the graph).
        val specToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.spectrumToggle)
        val density = resources.displayMetrics.density
        val gapPx = (2 * density).toInt()
        graphView?.post {
            val viewWidth = graphView?.width ?: return@post
            val vPadPx = 80
            val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
            val btnTop = gapPx
            val btnBottom = vPadPx - gapPx
            val btnHeight = btnBottom - btnTop
            val specWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)

            val specLeft = gridLine10k + gapPx
            val specLp = specToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            specLp.width = specWidth
            specLp.height = btnHeight
            specLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            specLp.leftMargin = specLeft
            specLp.topMargin = btnTop
            specToggle.layoutParams = specLp
            specToggle.minimumWidth = 0; specToggle.minimumHeight = 0
            specToggle.setPadding(0, 0, 0, 0)
        }
        fun updateSpecToggleStyle(active: Boolean) {
            if (active) {
                specToggle.setBackgroundColor(0xFF555555.toInt())
                specToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                specToggle.strokeWidth = (2 * density).toInt()
                specToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                specToggle.setBackgroundColor(0x00000000)
                specToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                specToggle.strokeWidth = (1 * density).toInt()
                specToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }

        var spectrumOn = eqPrefs.getSpectrumEnabled()
        updateSpecToggleStyle(spectrumOn)

        if (spectrumOn && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSpectrum()
        } else if (spectrumOn) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
        }

        specToggle.setOnClickListener {
            spectrumOn = !spectrumOn
            eqPrefs.saveSpectrumEnabled(spectrumOn)
            updateSpecToggleStyle(spectrumOn)
            if (spectrumOn) {
                graphView?.spectrumRenderer = renderer
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    startSpectrum()
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                }
            } else {
                stopSpectrum()
                graphView?.spectrumRenderer = null
                graphView?.invalidate()
            }
        }

        // setupFftSize() — long accumulated analysis windows (real resolution)
        setupFftSize()
        setupGhostDeltaToggles()
        setupPpoSmoothing()
        // setupRelease() — commented out, needs smoother implementation
        setupColor()
    }

    private val fftTickLabelViews = mutableListOf<TextView>()

    private fun setupFftSize() {
        val summarySwitch = findViewById<MaterialSwitch>(R.id.fftSizeSwitch)
        val summarySlider = findViewById<Slider>(R.id.fftSizeSlider)
        val summaryRow = findViewById<android.view.View>(R.id.fftSizeRow)
        val tickRow = findViewById<android.widget.FrameLayout>(R.id.fftSizeTickLabels)

        fftTickLabelViews.clear()
        for (label in fftLabels) {
            val tv = TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF666666.toInt())
            }
            fftTickLabelViews.add(tv)
            tickRow.addView(tv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        summarySlider.post {
            val trackLeft = summarySlider.trackSidePadding.toFloat()
            val trackWidth = (summarySlider.width - 2 * summarySlider.trackSidePadding).toFloat()
            for ((i, tv) in fftTickLabelViews.withIndex()) {
                val cx = trackLeft + trackWidth * i / (fftTickLabelViews.size - 1)
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
                tv.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
                val half = tv.measuredWidth / 2f
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                tv.gravity = android.view.Gravity.CENTER
                (tv.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    width = tv.measuredWidth
                    leftMargin = (cx - half).toInt().coerceAtLeast(0)
                }
                tv.requestLayout()
            }
            updateFftTickHighlight(eqPrefs.getFftSizeIndex().coerceIn(0, fftSizes.size - 1))
        }

        val enabled = eqPrefs.getFftSizeEnabled()
        val idx = eqPrefs.getFftSizeIndex().coerceIn(0, fftSizes.size - 1)
        summarySwitch.isChecked = enabled
        summarySlider.value = idx.toFloat()
        summaryRow.alpha = if (enabled) 1f else 0.4f
        summarySlider.isEnabled = enabled
        updateFftTickHighlight(idx)
        applyFftSize(enabled, idx)

        summarySwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveFftSizeEnabled(isChecked)
            summaryRow.alpha = if (isChecked) 1f else 0.4f
            summarySlider.isEnabled = isChecked
            applyFftSize(isChecked, summarySlider.value.toInt().coerceIn(0, fftSizes.size - 1))
        }

        summarySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val newIdx = value.toInt().coerceIn(0, fftSizes.size - 1)
            updateFftTickHighlight(newIdx)
            eqPrefs.saveFftSizeIndex(newIdx)
            applyFftSize(eqPrefs.getFftSizeEnabled(), newIdx)
        }
    }

    private fun updateFftTickHighlight(selectedIdx: Int) {
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
        for (i in fftTickLabelViews.indices) {
            if (i == selectedIdx) {
                fftTickLabelViews[i].setTextColor(primaryColor)
                fftTickLabelViews[i].setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                fftTickLabelViews[i].setTextColor(0xFF666666.toInt())
                fftTickLabelViews[i].setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /** Live preview of the size on this activity's renderer; MainActivity reapplies from prefs on resume. */
    private fun applyFftSize(enabled: Boolean, idx: Int) {
        val r = renderer ?: return
        if (enabled) r.setFftSize(fftSizes[idx.coerceIn(0, fftSizes.size - 1)])
        else r.clearManualFftSize()
    }

    private fun setupGhostDeltaToggles() {
        val ghostSwitch = findViewById<MaterialSwitch>(R.id.ghostSwitch)
        val deltaSwitch = findViewById<MaterialSwitch>(R.id.deltaSwitch)
        val ghostEnabled = eqPrefs.getTargetGhostEnabled()
        val deltaEnabled = eqPrefs.getTargetDeltaEnabled()
        ghostSwitch.isChecked = ghostEnabled
        deltaSwitch.isChecked = deltaEnabled
        renderer?.showTargetGhost = ghostEnabled
        renderer?.showTargetDelta = deltaEnabled
        loadPreviewTargetCurve()
        ghostSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveTargetGhostEnabled(isChecked)
            renderer?.showTargetGhost = isChecked
            graphView?.invalidate()
        }
        deltaSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveTargetDeltaEnabled(isChecked)
            renderer?.showTargetDelta = isChecked
            graphView?.invalidate()
        }
    }

    private fun loadPreviewTargetCurve() {
        val name = eqPrefs.getSelectedTarget() ?: return
        Thread {
            val fr = try {
                if (name == "__custom__") {
                    eqPrefs.getSelectedTargetName()?.let { customName ->
                        eqPrefs.getImportedTargetText(customName)?.let {
                            com.bearinmind.equalizer314.autoeq.FreqResponseParser.parse(it)
                        }
                    }
                } else {
                    assets.open("targets/$name.csv").bufferedReader().use { reader ->
                        com.bearinmind.equalizer314.autoeq.FreqResponseParser.parse(reader.readText())
                    }
                }
            } catch (_: Exception) { null }
            if (fr != null) runOnUiThread {
                renderer?.targetCurve = fr
                renderer?.targetLabel = eqPrefs.getSelectedTargetName() ?: name
                graphView?.invalidate()
            }
        }.start()
    }

    private val tickLabelViews = mutableListOf<TextView>()
    private fun setupPpoSmoothing() {
        val ppoSwitch = findViewById<MaterialSwitch>(R.id.ppoSwitch)
        val ppoSlider = findViewById<Slider>(R.id.ppoSlider)
        val ppoRow = findViewById<android.view.View>(R.id.ppoRow)
        val tickLabelsRow = findViewById<android.widget.FrameLayout>(R.id.ppoTickLabels)

        // Slider is a stock stepped Material slider now (built-in tick marks
        // + round thumb, same as the DP Latency Window slider). The old
        // custom cross-shaped tick overlay was removed with app:tickVisible.

        // Build tick label views, each absolutely CENTERED under its tick.
        // Weighted-row tricks anchor the edge labels' edges (not centers) on
        // the edge ticks — visibly off for "1/1" and "1/96". Instead, once
        // the slider knows its geometry, place every label so its center
        // sits exactly at trackLeft + i/(N−1) · trackWidth.
        tickLabelViews.clear()
        for (label in ppoLabels) {
            val tv = TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF666666.toInt())
            }
            tickLabelViews.add(tv)
            tickLabelsRow.addView(tv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        ppoSlider.post {
            val trackLeft = ppoSlider.trackSidePadding.toFloat()
            val trackWidth = (ppoSlider.width - 2 * ppoSlider.trackSidePadding).toFloat()
            for ((i, tv) in tickLabelViews.withIndex()) {
                val cx = trackLeft + trackWidth * i / (tickLabelViews.size - 1)
                // Measure BOLD so the highlight toggle doesn't shift centers.
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
                tv.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
                val half = tv.measuredWidth / 2f
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                tv.gravity = android.view.Gravity.CENTER
                (tv.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    width = tv.measuredWidth
                    leftMargin = (cx - half).toInt().coerceAtLeast(0)
                }
                tv.requestLayout()
            }
            updateTickHighlight(ppoSlider.value.toInt().coerceIn(0, 7))
        }

        val ppoEnabled = eqPrefs.getPpoEnabled()
        val ppoIdx = eqPrefs.getPpoIndex().coerceIn(0, 7)
        ppoSwitch.isChecked = ppoEnabled
        ppoSlider.value = ppoIdx.toFloat()
        ppoRow.alpha = if (ppoEnabled) 1f else 0.4f
        ppoSlider.isEnabled = ppoEnabled
        updateTickHighlight(ppoIdx)

        ppoSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.savePpoEnabled(isChecked)
            ppoRow.alpha = if (isChecked) 1f else 0.4f
            ppoSlider.isEnabled = isChecked
            applyCurrentSettings()
        }

        // Stepped slider snaps natively — save + apply per step, like the
        // DP Latency Window slider.
        ppoSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val idx = value.toInt().coerceIn(0, 7)
            updateTickHighlight(idx)
            eqPrefs.savePpoIndex(idx)
            applyCurrentSettings()
        }
    }

    private fun updateTickHighlight(selectedIdx: Int) {
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
        for (i in tickLabelViews.indices) {
            if (i == selectedIdx) {
                tickLabelViews[i].setTextColor(primaryColor)
                tickLabelViews[i].setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tickLabelViews[i].setTextColor(0xFF666666.toInt())
                tickLabelViews[i].setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        // Tick lines stay same color — no highlight change
    }

    // setupRelease() — commented out, needs smoother implementation
    // private fun setupRelease() { ... }

    private fun setupColor() {
        val swatchRow = findViewById<android.widget.LinearLayout>(R.id.colorSwatchRow)
        val density = resources.displayMetrics.density
        val colors = com.bearinmind.equalizer314.ui.TableEqController.BAND_COLORS
        val savedColor = eqPrefs.getSpectrumColor()
        val size = (22 * density).toInt()

        for ((color, _) in colors) {
            val isDefault = color == 0xFF333333.toInt()
            val wrapper = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val displayColor = if (isDefault) 0xFFB4B4B4.toInt() else color
            val swatch: android.view.View = if (isDefault) {
                android.widget.TextView(this).apply {
                    text = "\u2014"
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF333333.toInt())
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                android.view.View(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(size, size).apply {
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
                eqPrefs.saveSpectrumColor(displayColor)
                renderer?.setSpectrumColor(displayColor)
                graphView?.invalidate()
                updateColorSelection(swatchRow, displayColor)
            }
            wrapper.addView(swatch)
            swatchRow.addView(wrapper)
        }
        updateColorSelection(swatchRow, savedColor)
        renderer?.setSpectrumColor(savedColor)
    }

    private fun updateColorSelection(swatchRow: android.widget.LinearLayout, selectedColor: Int) {
        val density = resources.displayMetrics.density
        val colors = com.bearinmind.equalizer314.ui.TableEqController.BAND_COLORS
        for (i in 0 until swatchRow.childCount) {
            val wrapper = swatchRow.getChildAt(i) as? android.widget.FrameLayout ?: continue
            val swatch = wrapper.getChildAt(0) ?: continue
            val bg = swatch.background as? android.graphics.drawable.GradientDrawable ?: continue
            val swatchColor = colors[i].first
            val isDefault = swatchColor == 0xFF333333.toInt()
            val displayColor = if (isDefault) 0xFFB4B4B4.toInt() else swatchColor
            val isSelected = displayColor == selectedColor
            if (isSelected) {
                bg.setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            } else {
                bg.setStroke((1 * density).toInt(), 0xFF666666.toInt())
            }
        }
    }

    private fun applyCurrentSettings() {
        val r = renderer ?: return
        if (eqPrefs.getPpoEnabled()) {
            r.ppoSmoothing = ppoValues[eqPrefs.getPpoIndex().coerceIn(0, 7)]
        } else {
            r.ppoSmoothing = 0
        }
    }

    private fun startSpectrum() {
        audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        isMusicPlaying = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            playbackCallback = object : android.media.AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    val wasPlaying = isMusicPlaying
                    isMusicPlaying = configs != null && configs.isNotEmpty()
                    if (!wasPlaying && isMusicPlaying) {
                        renderer?.resetOpacity()
                    }
                }
            }
            audioManager?.registerAudioPlaybackCallback(playbackCallback!!, null)
        }
        try {
            visualizer = android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 32) return
                        if (isMusicPlaying) {
                            // Visualizer reports the capture rate in MILLIHERTZ (48 kHz → 48_000_000)
                            renderer?.updateWaveformData(waveform, samplingRate / 1000)
                        } else {
                            renderer?.feedSilence()
                            renderer?.fadeOut(0.04f)
                        }
                        graphView?.postInvalidate()
                    }
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
        } catch (e: Exception) {
            android.util.Log.e("SpectrumControl", "Visualizer init failed", e)
        }
    }

    private fun stopSpectrum() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        if (playbackCallback != null) {
            audioManager?.unregisterAudioPlaybackCallback(playbackCallback!!)
            playbackCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSpectrum()
        }
    }

    override fun onResume() {
        super.onResume()
        if (eqPrefs.getSpectrumEnabled() && visualizer == null &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSpectrum()
        }
    }

    override fun onPause() {
        super.onPause()
        stopSpectrum()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpectrum()
        renderer?.release()
        renderer?.shutdownWorker()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

}
