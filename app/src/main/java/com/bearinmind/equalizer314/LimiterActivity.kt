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
import com.bearinmind.equalizer314.ui.AttackReleaseView
import com.bearinmind.equalizer314.ui.CompressorCurveView
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
    private lateinit var limiterCurve: CompressorCurveView
    private lateinit var attackReleaseView: AttackReleaseView

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
            pushToService()
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
        startMetering()

        val intent = android.content.Intent(this, EqService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun initViews() {
        masterSwitch = findViewById(R.id.limiterMasterSwitch)
        findViewById<android.widget.ImageButton>(R.id.limiterBackButton).setOnClickListener { finish() }

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

        // Waveform / level meter
        waveformView = findViewById(R.id.limiterWaveform)
        waveformView.ceilingDb = eqPrefs.getLimiterThreshold()

        // Ceiling + GR view
        ceilingView = findViewById(R.id.limiterCeilingView)

        // Transfer function curve
        limiterCurve = findViewById(R.id.limiterCurve)

        // Attack/Release envelope
        attackReleaseView = findViewById(R.id.limiterAttackReleaseView)
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

        // Transfer curve
        limiterCurve.threshold = threshold
        limiterCurve.ratio = ratio
        limiterCurve.kneeWidth = 0f  // limiters typically have no knee (hard knee)

        // Attack/Release view
        attackReleaseView.attackMs = eqPrefs.getLimiterAttack()
        attackReleaseView.releaseMs = eqPrefs.getLimiterRelease()

        // Ceiling view
        ceilingView.ceilingDb = threshold
    }

    private fun setupListeners() {
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            eqPrefs.saveLimiterEnabled(checked)
            pushToService()
        }

        setupSlider(thresholdSlider, thresholdText, "%.1f") {
            eqPrefs.saveLimiterThreshold(it)
            limiterCurve.threshold = it
            ceilingView.ceilingDb = it
            waveformView.ceilingDb =it
            pushToService()
        }
        setupSlider(ratioSlider, ratioText, "%.1f") {
            eqPrefs.saveLimiterRatio(it)
            limiterCurve.ratio = it
            pushToService()
        }
        setupSlider(attackSlider, attackText, "%.2f") {
            eqPrefs.saveLimiterAttack(it)
            attackReleaseView.attackMs = it
            pushToService()
        }
        setupSlider(releaseSlider, releaseText, "%.0f") {
            eqPrefs.saveLimiterRelease(it)
            attackReleaseView.releaseMs = it
            pushToService()
        }
        setupSlider(postGainSlider, postGainText, "%.1f") {
            eqPrefs.saveLimiterPostGain(it); pushToService()
        }

        // Transfer curve callbacks
        limiterCurve.onThresholdChanged = { value ->
            eqPrefs.saveLimiterThreshold(value)
            isUpdating = true
            thresholdSlider.value = value.coerceIn(-30f, 0f)
            thresholdText.setText(String.format("%.1f", value))
            ceilingView.ceilingDb = value
            waveformView.ceilingDb =value
            isUpdating = false
            pushToService()
        }
        limiterCurve.onRatioChanged = { value ->
            eqPrefs.saveLimiterRatio(value)
            isUpdating = true
            ratioSlider.value = value.coerceIn(1f, 50f)
            ratioText.setText(String.format("%.1f", value))
            isUpdating = false
            pushToService()
        }

        // Ceiling view drag callback
        ceilingView.onCeilingChanged = { value ->
            eqPrefs.saveLimiterThreshold(value)
            isUpdating = true
            thresholdSlider.value = value.coerceIn(-30f, 0f)
            thresholdText.setText(String.format("%.1f", value))
            limiterCurve.threshold = value
            waveformView.ceilingDb =value
            isUpdating = false
            pushToService()
        }

        // Attack/Release callbacks
        attackReleaseView.onAttackChanged = { value ->
            eqPrefs.saveLimiterAttack(value)
            isUpdating = true
            attackSlider.value = value.coerceIn(0.01f, 100f)
            attackText.setText(String.format("%.2f", value))
            isUpdating = false
            pushToService()
        }
        attackReleaseView.onReleaseChanged = { value ->
            eqPrefs.saveLimiterRelease(value)
            isUpdating = true
            releaseSlider.value = value.coerceIn(1f, 500f)
            releaseText.setText(String.format("%.0f", value))
            isUpdating = false
            pushToService()
        }
    }

    private var visualizer: android.media.audiofx.Visualizer? = null
    @Volatile private var latestPeakDb = -96f
    @Volatile private var latestGrDb = 0f

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

        // Visualizer with waveform capture — 32 sub-blocks per callback = 640 peaks/sec
        // View handles staging queue + peak tracking + rendering internally
        latestPeakDb = -96f
        latestGrDb = 0f

        try {
            visualizer = android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                // AS_PLAYED = bytes represent actual amplitude, not auto-scaled
                scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_AS_PLAYED
                measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS

                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 32) return
                        if (isMusicPlaying) {
                            // Push 32 sub-block peaks into the view's staging queue
                            // With AS_PLAYED, these are REAL levels — no calibration needed
                            waveformView.pushWaveformData(waveform)

                            // Compute overall peak for the ceiling meter from the same real data
                            var maxSample = 0f
                            for (b in waveform) {
                                val sample = kotlin.math.abs(((b.toInt() and 0xFF) - 128) / 128f)
                                if (sample > maxSample) maxSample = sample
                            }
                            val peakDb = if (maxSample > 0.0001f)
                                (20f * kotlin.math.log10(maxSample)).coerceIn(-96f, 10f) else -96f
                            val threshold = eqPrefs.getLimiterThreshold()
                            latestPeakDb = peakDb
                            latestGrDb = if (peakDb > threshold) -(peakDb - threshold) else 0f
                        }
                    }
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)

                enabled = true
            }
        } catch (_: Exception) {}

        // 33ms timer — just updates the ceiling/GR meter (waveform has its own drain timer)
        meterRunnable?.let { meterHandler.removeCallbacks(it) }
        wasMusicPlaying = true
        val runnable = object : Runnable {
            override fun run() {
                // Edge detect: flush waveform on pause transition
                if (!isMusicPlaying && wasMusicPlaying) {
                    waveformView.flushToSilence()
                }
                wasMusicPlaying = isMusicPlaying

                if (!isMusicPlaying) {
                    ceilingView.inputDb = -24f
                    ceilingView.grDb = 0f
                } else {
                    ceilingView.inputDb = latestPeakDb
                    ceilingView.grDb = latestGrDb
                }
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
        svc.dynamicsManager.limiterEnabled = eqPrefs.getLimiterEnabled()
        svc.dynamicsManager.limiterAttackMs = eqPrefs.getLimiterAttack()
        svc.dynamicsManager.limiterReleaseMs = eqPrefs.getLimiterRelease()
        svc.dynamicsManager.limiterRatio = eqPrefs.getLimiterRatio()
        svc.dynamicsManager.limiterThresholdDb = eqPrefs.getLimiterThreshold()
        svc.dynamicsManager.limiterPostGainDb = eqPrefs.getLimiterPostGain()
        svc.dynamicsManager.updateLimiter()
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
