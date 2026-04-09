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

    private val fftSizes = intArrayOf(1024, 2048, 4096, 8192)
    private val fftLabels = arrayOf("1024", "2048", "4096", "8192")
    private val ppoValues = intArrayOf(1, 2, 3, 6, 12, 24, 48, 96)
    private val ppoLabels = arrayOf("1/1", "1/2", "1/3", "1/6", "1/12", "1/24", "1/48", "1/96")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spectrum_control)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.spectrumBackButton).setOnClickListener { finish() }

        // Spectrum preview using EqGraphView — load current EQ curve
        renderer = SpectrumAnalyzerRenderer()
        graphView = findViewById(R.id.spectrumGraphView)
        graphView?.spectrumRenderer = renderer
        graphView?.showBandPoints = false
        val eq = ParametricEqualizer()
        eqPrefs.restoreState(eq)
        graphView?.setParametricEqualizer(eq)
        applyCurrentSettings()

        // Spectrum toggle
        val specToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.spectrumToggle)
        val density = resources.displayMetrics.density
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

        // setupFftSize() — commented out, zero-padding only
        setupPpoSmoothing()
        // setupRelease() — commented out, needs smoother implementation
        setupColor()
    }

    // setupFftSize() — commented out, zero-padding only

    private val tickLabelViews = mutableListOf<TextView>()
    private val tickDotViews = mutableListOf<android.view.View>()

    private fun setupPpoSmoothing() {
        val ppoSwitch = findViewById<MaterialSwitch>(R.id.ppoSwitch)
        val ppoSlider = findViewById<Slider>(R.id.ppoSlider)
        val ppoRow = findViewById<android.view.View>(R.id.ppoRow)
        val tickLabelsRow = findViewById<android.widget.LinearLayout>(R.id.ppoTickLabels)
        val density = resources.displayMetrics.density

        // Custom tick dots overlay on the slider track
        val tickDotsContainer = findViewById<android.widget.FrameLayout>(R.id.ppoTickDots)

        ppoSlider.post {
            tickDotsContainer.removeAllViews()
            tickDotViews.clear()
            val trackLeft = ppoSlider.trackSidePadding.toFloat()
            val trackWidth = ppoSlider.width - 2 * ppoSlider.trackSidePadding
            val lineWidth = (2 * density).toInt()
            val lineHeight = (12 * density).toInt()
            val tickColor = 0xFF666666.toInt()
            val centerY = ppoSlider.height / 2

            val trackThickness = (4 * density).toInt()
            val armLength = (12 * density).toInt()
            val cornerR = (2 * density)

            for (i in ppoLabels.indices) {
                val fraction = i.toFloat() / (ppoLabels.size - 1)
                val cx = trackLeft + fraction * trackWidth
                // Vertical arm
                val vLine = android.view.View(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(trackThickness, armLength).apply {
                        leftMargin = (cx - trackThickness / 2).toInt()
                        topMargin = centerY - armLength / 2
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(tickColor)
                        cornerRadius = cornerR
                    }
                }
                // Horizontal arm
                val hLine = android.view.View(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(armLength, trackThickness).apply {
                        leftMargin = (cx - armLength / 2).toInt()
                        topMargin = centerY - trackThickness / 2
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(tickColor)
                        cornerRadius = cornerR
                    }
                }
                tickDotViews.add(vLine)
                tickDotsContainer.addView(vLine)
                tickDotsContainer.addView(hLine)
            }
            updateTickHighlight(ppoSlider.value.toInt().coerceIn(0, 7))
        }

        // Build tick label views under each tick mark
        tickLabelViews.clear()
        for (label in ppoLabels) {
            val tv = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFF666666.toInt())
            }
            tickLabelViews.add(tv)
            tickLabelsRow.addView(tv)
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

        // Smooth drag — update highlight live as thumb moves
        ppoSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val nearest = kotlin.math.round(value).toInt().coerceIn(0, 7)
            updateTickHighlight(nearest)
        }

        // Snap to nearest tick on release with spring animation
        ppoSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val snapped = kotlin.math.round(slider.value).toInt().coerceIn(0, 7)
                // Spring animation to snap value
                android.animation.ValueAnimator.ofFloat(slider.value, snapped.toFloat()).apply {
                    duration = 300
                    interpolator = android.view.animation.OvershootInterpolator(1.2f)
                    addUpdateListener { anim ->
                        val v = anim.animatedValue as Float
                        slider.value = v.coerceIn(0f, 7f)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            slider.value = snapped.toFloat()
                            updateTickHighlight(snapped)
                            eqPrefs.savePpoIndex(snapped)
                            applyCurrentSettings()
                        }
                    })
                    start()
                }
            }
        })
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
                            renderer?.updateWaveformData(waveform)
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
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

}
