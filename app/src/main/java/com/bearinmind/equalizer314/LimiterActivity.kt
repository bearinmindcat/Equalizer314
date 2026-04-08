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
            // Don't pushToService on screen entry — causes audio dropout from DP recreation
            // Settings are already applied from when DP was started
            com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this@LimiterActivity, wasActive)
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
        if (eqPrefs.getSpectrumEnabled()) startMetering()

        val intent = android.content.Intent(this, EqService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun initViews() {
        masterSwitch = findViewById(R.id.limiterMasterSwitch)
        // Back button removed — navigable via bottom nav
        // findViewById<android.widget.ImageButton>(R.id.limiterBackButton).setOnClickListener { finish(); overridePendingTransition(0, 0) }

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

        // Bottom nav — shared helper handles icons, status, and navigation
        com.bearinmind.equalizer314.ui.BottomNavHelper.setup(this, com.bearinmind.equalizer314.ui.NavScreen.LIMITER, eqPrefs)
        // Power FAB toggles DynamicsProcessing on/off
        val powerFab = findViewById<android.widget.ImageButton>(R.id.powerFab)
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
            val on = svc.dynamicsManager.isActive
            com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerState(this, eqPrefs, on)
            android.widget.Toast.makeText(this, if (on) "DynamicsProcessing Start" else "DynamicsProcessing Stop", android.widget.Toast.LENGTH_SHORT).show()
        }

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

        // Spectrum toggle + Reset button — use full-screen-width calculation for sizing
        val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.limiterVisualizerToggle)
        val limResetBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.limiterResetButton)
        val density = resources.displayMetrics.density
        val gapPx = (2 * density).toInt()
        val vPadPx = 80
        val fullGraphWidth = resources.displayMetrics.widthPixels - (32 * density).toInt()
        val gridLine10k = (fullGraphWidth * 3.0 / 3.301).toInt()
        val btnWidth = (fullGraphWidth - gapPx) - (gridLine10k + gapPx)
        val btnHeight = vPadPx - 2 * gapPx
        waveformView.post {
            // Spectrum button
            val lp = vizToggle.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = btnWidth
            lp.height = btnHeight
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.topMargin = gapPx
            lp.rightMargin = gapPx
            vizToggle.layoutParams = lp
            vizToggle.minimumWidth = 0; vizToggle.minimumHeight = 0
            vizToggle.setPadding(0, 0, 0, 0)

            // Reset button: same size, to left of spectrum
            val resetLp = limResetBtn.layoutParams as android.widget.FrameLayout.LayoutParams
            resetLp.width = btnWidth
            resetLp.height = btnHeight
            resetLp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            resetLp.topMargin = gapPx
            resetLp.rightMargin = gapPx + btnWidth + gapPx
            limResetBtn.layoutParams = resetLp
            limResetBtn.minimumWidth = 0; limResetBtn.minimumHeight = 0
            limResetBtn.setPadding(0, 0, 0, 0)
        }
        // Reset button: reset limiter to defaults
        limResetBtn.setOnClickListener {
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
                isUpdating = true
                eqPrefs.saveLimiterThreshold(0f)
                eqPrefs.saveLimiterRatio(2f)
                eqPrefs.saveLimiterAttack(0.01f)
                eqPrefs.saveLimiterRelease(1f)
                eqPrefs.saveLimiterPostGain(0f)
                thresholdSlider.value = 0f; thresholdText.setText("0.0")
                ratioSlider.value = 2f; ratioText.setText("2.0")
                attackSlider.value = 0.01f; attackText.setText("0.01")
                releaseSlider.value = 1f; releaseText.setText("1")
                postGainSlider.value = 0f; postGainText.setText("0.0")
                waveformView.ceilingDb = 0f
                ceilingView.ceilingDb = 0f
                isUpdating = false
                pushToService()
                android.widget.Toast.makeText(this, "Limiter reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.show()
        }
        fun updateVizStyle(active: Boolean) {
            if (active) {
                vizToggle.setBackgroundColor(0xFF555555.toInt())
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                vizToggle.strokeWidth = (2 * density).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                vizToggle.setBackgroundColor(0x00000000)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                vizToggle.strokeWidth = (1 * density).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        vizToggle.setOnClickListener {
            if (visualizer != null) {
                stopMetering()
                eqPrefs.saveSpectrumEnabled(false)
                updateVizStyle(false)
            } else {
                startMetering()
                eqPrefs.saveSpectrumEnabled(true)
                updateVizStyle(true)
            }
        }
        // Respect global spectrum preference
        if (eqPrefs.getSpectrumEnabled()) {
            updateVizStyle(true)
        } else {
            stopMetering()
            updateVizStyle(false)
        }
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
            com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
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
        // Sync nav bar from saved state
        com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, eqPrefs.getPowerState())
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateHighlight(this, com.bearinmind.equalizer314.ui.NavScreen.LIMITER)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqPrefs)
        if (visualizer == null && eqPrefs.getSpectrumEnabled()) startMetering()
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
