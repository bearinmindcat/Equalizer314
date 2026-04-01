package com.bearinmind.equalizer314

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.LimiterCeilingView
import com.bearinmind.equalizer314.ui.LimiterWaveformView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.log10
import kotlin.math.pow

class LimiterActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var masterSwitch: MaterialSwitch

    private lateinit var thresholdSlider: Slider
    private lateinit var thresholdText: EditText
    private lateinit var ratioSlider: Slider
    private lateinit var ratioText: EditText
    private lateinit var attackSlider: Slider
    private lateinit var attackText: EditText
    private lateinit var releaseSlider: Slider
    private lateinit var releaseText: EditText
    private lateinit var postGainSlider: Slider
    private lateinit var postGainText: EditText

    // Visualizations
    private lateinit var waveformView: LimiterWaveformView
    private lateinit var ceilingView: LimiterCeilingView

    // Metering — same pattern as MBC: Visualizer + AudioPlaybackCallback + 33ms timer
    private val meterHandler = Handler(Looper.getMainLooper())
    private var meterRunnable: Runnable? = null
    // AudioPlaybackCallback for play/pause detection
    private var audioManager: android.media.AudioManager? = null
    private var playbackCallback: android.media.AudioManager.AudioPlaybackCallback? = null
    @Volatile private var isMusicPlaying = true
    private var wasMusicPlaying = true

    private var isUpdating = false
    private var eqService: EqService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EqService.EqBinder
            eqService = binder.service
            serviceBound = true
            // Check DP state BEFORE pushToService (which only updates if DP is already running)
            val wasActive = eqService?.dynamicsManager?.isActive == true
            if (wasActive) pushToService()
            val fab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.powerFab)
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(if (wasActive) 0xFFFFFFFF.toInt() else 0xFF2A2A2A.toInt())
            fab.imageTintList = android.content.res.ColorStateList.valueOf(if (wasActive) 0xFF000000.toInt() else 0xFF555555.toInt())
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_limiter)

        eqPrefs = EqPreferencesManager(this)
        initViews()
        loadState()
        setupListeners()
        // Refresh nav icon after loadState sets the switch
        val limIcon = findViewById<android.widget.ImageButton>(R.id.navLimiterButton)
        limIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (masterSwitch.isChecked) com.google.android.material.color.MaterialColors.getColor(limIcon, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
        )
        startMetering()

        val intent = android.content.Intent(this, EqService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun initViews() {
        masterSwitch = findViewById(R.id.limiterMasterSwitch)
        findViewById<android.widget.ImageButton>(R.id.limiterBackButton).setOnClickListener { finish(); overridePendingTransition(0, 0) }

        thresholdSlider = findViewById(R.id.limiterThresholdSlider)
        thresholdText = findViewById(R.id.limiterThresholdText)
        ratioSlider = findViewById(R.id.limiterRatioSlider)
        ratioText = findViewById(R.id.limiterRatioText)
        attackSlider = findViewById(R.id.limiterAttackSlider)
        attackText = findViewById(R.id.limiterAttackText)
        releaseSlider = findViewById(R.id.limiterReleaseSlider)
        releaseText = findViewById(R.id.limiterReleaseText)
        postGainSlider = findViewById(R.id.limiterPostGainSlider)
        postGainText = findViewById(R.id.limiterPostGainText)

        // Bottom nav — icons reflect saved preference state for each module
        val limiterNavIcon = findViewById<android.widget.ImageButton>(R.id.navLimiterButton)
        val mbcNavIcon = findViewById<android.widget.ImageButton>(R.id.navMbcButton)
        fun updateIconTint(icon: android.widget.ImageButton, enabled: Boolean) {
            val tint = if (enabled)
                com.google.android.material.color.MaterialColors.getColor(icon, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else
                0xFF555555.toInt()
            icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        }
        updateIconTint(limiterNavIcon, eqPrefs.getLimiterEnabled())
        updateIconTint(mbcNavIcon, eqPrefs.getMbcEnabled())

        findViewById<android.widget.ImageButton>(R.id.navPresetsButton).setOnClickListener {
            finish(); overridePendingTransition(0, 0)
        }
        mbcNavIcon.setOnClickListener {
            finish()
            startActivity(android.content.Intent(this, MbcActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<android.widget.ImageButton>(R.id.navSettingsButton).setOnClickListener {
            val intent = android.content.Intent(this, MainActivity::class.java)
            intent.putExtra("showSettings", true)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
        // Power FAB toggles DynamicsProcessing on/off (same as main EQ)
        val powerFab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.powerFab)
        fun updateFabStyle() {
            val svc = eqService
            val isOn = svc != null && svc.dynamicsManager.isActive
            if (isOn) {
                powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
            } else {
                powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
                powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
            }
        }
        powerFab.setOnClickListener {
            val svc = eqService ?: return@setOnClickListener
            if (svc.dynamicsManager.isActive) {
                svc.dynamicsManager.stop()
            } else {
                val tempEq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
                eqPrefs.restoreState(tempEq)
                svc.dynamicsManager.start(tempEq)
                pushToService()
            }
            updateFabStyle()
        }
        // Default to "off" style — onServiceConnected will update if DP is active
        powerFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
        powerFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())

        // Waveform / level meter
        waveformView = findViewById(R.id.limiterWaveform)
        waveformView.ceilingDb = eqPrefs.getLimiterThreshold()
        waveformView.attackMs = eqPrefs.getLimiterAttack()
        waveformView.releaseMs = eqPrefs.getLimiterRelease()
        waveformView.onCeilingChanged = { value ->
            eqPrefs.saveLimiterThreshold(value)
            isUpdating = true
            thresholdSlider.value = value.coerceIn(-30f, 0f)
            thresholdText.setText(String.format("%.1f", value))
            ceilingView.ceilingDb = value
            isUpdating = false
            pushToService()
        }

        // Ceiling + GR view
        ceilingView = findViewById(R.id.limiterCeilingView)

    }

    private fun loadState() {
        isUpdating = true
        masterSwitch.isChecked = eqPrefs.getLimiterEnabled()

        val threshold = eqPrefs.getLimiterThreshold()
        thresholdSlider.value = threshold.coerceIn(-30f, 0f)
        thresholdText.setText(String.format("%.1f", threshold))

        val ratio = eqPrefs.getLimiterRatio()
        ratioSlider.value = ratio.coerceIn(1f, 50f)
        ratioText.setText(String.format("%.1f", ratio))

        val attack = eqPrefs.getLimiterAttack()
        val snappedAttack = (Math.round(attack * 100f) / 100f).coerceIn(0.01f, 100f)
        attackSlider.value = snappedAttack
        attackText.setText(String.format("%.2f", attack))

        val release = eqPrefs.getLimiterRelease()
        releaseSlider.value = release.coerceIn(1f, 500f)
        releaseText.setText(String.format("%.0f", release))

        val postGain = eqPrefs.getLimiterPostGain()
        postGainSlider.value = postGain.coerceIn(-12f, 12f)
        postGainText.setText(String.format("%.1f", postGain))

        // Sync visualizations
        syncVisualizations()

        isUpdating = false
    }

    private fun syncVisualizations() {
        val threshold = eqPrefs.getLimiterThreshold()
        val ratio = eqPrefs.getLimiterRatio()


        // Ceiling view
        ceilingView.ceilingDb = threshold
    }

    private fun setupListeners() {
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            eqPrefs.saveLimiterEnabled(checked)
            pushToService()
            val icon = findViewById<android.widget.ImageButton>(R.id.navLimiterButton)
            val tint = if (checked)
                com.google.android.material.color.MaterialColors.getColor(icon, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt())
            else 0xFF555555.toInt()
            icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        }

        setupSlider(thresholdSlider, thresholdText, "%.1f") {
            eqPrefs.saveLimiterThreshold(it)
            ceilingView.ceilingDb = it
            waveformView.ceilingDb =it
            pushToService()
        }
        setupSlider(ratioSlider, ratioText, "%.1f") {
            eqPrefs.saveLimiterRatio(it)
            pushToService()
        }
        setupSlider(attackSlider, attackText, "%.2f") {
            eqPrefs.saveLimiterAttack(it)
            waveformView.attackMs = it
            pushToService()
        }
        setupSlider(releaseSlider, releaseText, "%.0f") {
            eqPrefs.saveLimiterRelease(it)
            waveformView.releaseMs = it
            pushToService()
        }
        setupSlider(postGainSlider, postGainText, "%.1f") {
            eqPrefs.saveLimiterPostGain(it); pushToService()
        }

        // Ceiling view drag callback
        ceilingView.onCeilingChanged = { value ->
            eqPrefs.saveLimiterThreshold(value)
            isUpdating = true
            thresholdSlider.value = value.coerceIn(-30f, 0f)
            thresholdText.setText(String.format("%.1f", value))
            waveformView.ceilingDb = value
            isUpdating = false
            pushToService()
        }

        // Double-tap slider thumbs to reset to defaults
        addDoubleTapReset(thresholdSlider) {
            eqPrefs.saveLimiterThreshold(0f)
            thresholdSlider.value = 0f; thresholdText.setText("0.0")
            ceilingView.ceilingDb = 0f; waveformView.ceilingDb = 0f
            pushToService()
        }
        addDoubleTapReset(ratioSlider) {
            eqPrefs.saveLimiterRatio(2f)
            ratioSlider.value = 2f; ratioText.setText("2.0")
            pushToService()
        }
        addDoubleTapReset(attackSlider) {
            eqPrefs.saveLimiterAttack(0.01f)
            attackSlider.value = 0.01f; attackText.setText("0.01")
            pushToService()
        }
        addDoubleTapReset(releaseSlider) {
            eqPrefs.saveLimiterRelease(1f)
            releaseSlider.value = 1f; releaseText.setText("1")
            pushToService()
        }
        addDoubleTapReset(postGainSlider) {
            eqPrefs.saveLimiterPostGain(0f)
            postGainSlider.value = 0f; postGainText.setText("0.0")
            pushToService()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addDoubleTapReset(slider: com.google.android.material.slider.Slider, onReset: () -> Unit) {
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

    private var visualizer: android.media.audiofx.Visualizer? = null
    @Volatile private var latestPeakDb = -96f
    @Volatile private var latestGrDb = 0f
    @Volatile private var latestLufsDb = -80f
    private val lufsProcessor = com.bearinmind.equalizer314.audio.LufsProcessor()

    private fun startMetering() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        // AudioPlaybackCallback for play/pause detection (same as VisualizerHelper)
        audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        isMusicPlaying = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            playbackCallback = object : android.media.AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    isMusicPlaying = configs != null && configs.isNotEmpty()
                }
            }
            audioManager?.registerAudioPlaybackCallback(playbackCallback!!, null)
        }

        // Visualizer — waveform callback stores latest peak
        latestPeakDb = -80f
        latestGrDb = 0f

        try {
            visualizer = android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_AS_PLAYED
                measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS

                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 32) return
                        if (isMusicPlaying) {
                            var maxSample = 0f
                            for (b in waveform) {
                                val sample = kotlin.math.abs(((b.toInt() and 0xFF) - 128) / 128f)
                                if (sample > maxSample) maxSample = sample
                            }
                            val peakDb = if (maxSample > 0.0001f)
                                (20f * kotlin.math.log10(maxSample)).coerceIn(-80f, 10f) else -80f
                            val threshold = eqPrefs.getLimiterThreshold()
                            latestPeakDb = peakDb
                            latestGrDb = if (peakDb > threshold) -(peakDb - threshold) else 0f
                            // K-weighted LUFS from the same waveform
                            latestLufsDb = lufsProcessor.processWaveform(waveform)
                        }
                    }
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)

                enabled = true
            }
        } catch (_: Exception) {}

        // Single 33ms timer — pushes same data to BOTH graph AND ceiling (like MBC)
        meterRunnable?.let { meterHandler.removeCallbacks(it) }
        wasMusicPlaying = true
        val runnable = object : Runnable {
            override fun run() {
                if (!isMusicPlaying && wasMusicPlaying) {
                    for (i in 0 until 5) waveformView.pushFrame(-80f, 0f)
                }
                wasMusicPlaying = isMusicPlaying

                if (!isMusicPlaying) {
                    waveformView.pushFrame(-80f, 0f)
                    ceilingView.inputDb = -40f
                    ceilingView.grDb = 0f
                } else {
                    val peakDb = latestPeakDb
                    val gr = latestGrDb
                    val lufs = latestLufsDb
                    waveformView.pushFrame(peakDb, gr, lufs)
                    ceilingView.inputDb = peakDb
                    ceilingView.grDb = gr
                }
                waveformView.invalidate()
                ceilingView.invalidate()
                meterHandler.postDelayed(this, 33)
            }
        }
        meterRunnable = runnable
        meterHandler.post(runnable)
    }

    private fun stopMetering() {
        meterRunnable?.let { meterHandler.removeCallbacks(it) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            playbackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        }
        playbackCallback = null
        audioManager = null
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMetering()
        }
    }

    private fun setupSlider(slider: Slider, text: EditText, format: String, onChanged: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdating) {
                text.setText(String.format(format, value))
                onChanged(value)
            }
        }
        text.setOnEditorActionListener { _, _, _ ->
            val v = text.text.toString().toFloatOrNull()?.coerceIn(slider.valueFrom, slider.valueTo)
            if (v != null) {
                isUpdating = true
                slider.value = v
                text.setText(String.format(format, v))
                isUpdating = false
                onChanged(v)
            }
            true
        }
    }

    private fun pushToService() {
        val svc = eqService ?: return
        if (!svc.dynamicsManager.isActive) return
        // Update limiter fields then recreate DP to apply them
        // (updateLimiter fails with "invalid parameter operation" on Samsung —
        //  limiter params can only be set during DP construction)
        svc.dynamicsManager.limiterEnabled = eqPrefs.getLimiterEnabled()
        svc.dynamicsManager.limiterAttackMs = eqPrefs.getLimiterAttack()
        svc.dynamicsManager.limiterReleaseMs = eqPrefs.getLimiterRelease()
        svc.dynamicsManager.limiterRatio = eqPrefs.getLimiterRatio()
        svc.dynamicsManager.limiterThresholdDb = eqPrefs.getLimiterThreshold()
        svc.dynamicsManager.limiterPostGainDb = eqPrefs.getLimiterPostGain()
        // Recreate DP with updated limiter config
        val tempEq = com.bearinmind.equalizer314.dsp.ParametricEqualizer()
        eqPrefs.restoreState(tempEq)
        svc.dynamicsManager.start(tempEq)
    }

    override fun onPause() {
        super.onPause()
        stopMetering()
    }

    override fun onResume() {
        super.onResume()
        if (visualizer == null) startMetering()
    }

    override fun onDestroy() {
        stopMetering()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
