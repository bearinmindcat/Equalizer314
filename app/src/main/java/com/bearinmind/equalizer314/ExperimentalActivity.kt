package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager

class ExperimentalActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_experimental)

        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupDpBandCount()
        setupMaxEqBands()
        setupFrameSlider()
        setupInterleave()
        setupCompatMode()
        setupMbcVolComp()
        setupPresetAutosave()
        setupAmoledTheme()
        setupTvMode()

        // Hide the legacy "Experimental DP Engine" switch row; the view stays so the card structure holds.
        findViewById<android.view.View>(R.id.expDpModeSwitch)
            ?.let { switch ->
                (switch.parent as? android.view.View)?.visibility = android.view.View.GONE
            }
    }

    // Auto-gain moved to the main settings; DP Band Count is read-only (the converter always uses the full table).
    private fun setupDpBandCount() {
        // Reflect what the NEXT DP start will use: 32 in Compat Mode, else 128.
        findViewById<android.widget.TextView>(R.id.expDpBandCountValue).text =
            if (eqPrefs.getDpCompatMode())
                com.bearinmind.equalizer314.audio.DynamicsProcessingManager.COMPAT_BAND_COUNT.toString()
            else "128"
    }

    // "Add more EQ bands" (issue #31): on → cap 64, off → 16; applies on the next band add.
    private fun setupMaxEqBands() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expMaxBandsSwitch)
        switch.isChecked = eqPrefs.getMaxEqBands() > 16
        switch.setOnCheckedChangeListener { _, isChecked ->
            val cap = if (isChecked) EqStateManager.ABSOLUTE_MAX_BANDS else 16
            eqPrefs.saveMaxEqBands(cap)
            EqStateManager.MAX_BANDS = cap
        }
    }

    // Issue #26: DP FFT window — one pref (dpFrameMs), 4 power-of-two stops; longer = finer bass, more delay; baked at DP creation.
    private val frameRungsMs = floatArrayOf(40f, 80f, 160f, 320f)
    private fun currentFrameRung(): Int {
        val ms = eqPrefs.getDpFrameMs()
        var best = 1; var bd = Float.MAX_VALUE
        for (i in frameRungsMs.indices) {
            val d = kotlin.math.abs(frameRungsMs[i] - ms)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun syncFrameUi() {
        val idx = currentFrameRung()
        findViewById<com.google.android.material.slider.Slider>(R.id.expFrameSlider).value = idx.toFloat()
        com.bearinmind.equalizer314.audio.DynamicsProcessingManager.frameDurationMs = frameRungsMs[idx]
    }

    private fun setupFrameSlider() {
        val slider = findViewById<com.google.android.material.slider.Slider>(R.id.expFrameSlider)
        slider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                eqPrefs.saveDpFrameMs(frameRungsMs[v.toInt().coerceIn(0, frameRungsMs.size - 1)])
                syncFrameUi()
            }
        }
        // Recycle the live DP only when the finger lifts — one rebuild per adjustment.
        slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                requestDpRecycle()
            }
        })
        syncFrameUi()
    }

    /** Ask EqService to rebuild the live DP so creation-time settings apply now; no-op when the EQ is off. */
    private fun requestDpRecycle() {
        try {
            startService(
                android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_RECYCLE_DP)
            )
        } catch (_: Exception) {}
    }

    // Pre+Post EQ interleave (issue #26): second staircase on the Post-EQ stage; baked at creation, so recycle on change.
    private fun setupInterleave() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expInterleaveSwitch)
        switch.isChecked = eqPrefs.getDpInterleave()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveDpInterleave(isChecked)
            com.bearinmind.equalizer314.audio.DynamicsProcessingManager.interleaveEnabled = isChecked
            requestDpRecycle()
        }
    }

    // Compat Mode: 32 DP bands for band-limited HALs, auto-on for Pixels; baked at creation → recycle on change.
    private fun setupCompatMode() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expCompatModeSwitch)
        switch.isChecked = eqPrefs.getDpCompatMode()
        syncCompatDependents()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveDpCompatMode(isChecked)
            com.bearinmind.equalizer314.audio.DynamicsProcessingManager.compatMode = isChecked
            requestDpRecycle()
            setupDpBandCount()
            syncCompatDependents()
        }
    }

    // Compat mode overrides the latency window + interleave — grey those cards out while it's on.
    private fun syncCompatDependents() {
        val on = eqPrefs.getDpCompatMode()
        val alpha = if (on) 0.4f else 1f
        findViewById<android.view.View>(R.id.expFrameCard).alpha = alpha
        findViewById<android.view.View>(R.id.expInterleaveCard).alpha = alpha
        findViewById<com.google.android.material.slider.Slider>(R.id.expFrameSlider).isEnabled = !on
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expInterleaveSwitch).isEnabled = !on
    }

    private fun setupPresetAutosave() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expPresetAutosaveSwitch)
        switch.isChecked = eqPrefs.getPresetAutosave()
        switch.setOnCheckedChangeListener { _, isChecked -> eqPrefs.savePresetAutosave(isChecked) }
    }

    // Black (AMOLED) theme: EqApp overlays it at activity creation; bump the stamp so live screens rebuild.
    private fun setupAmoledTheme() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expAmoledThemeSwitch)
        switch.isChecked = eqPrefs.getAmoledTheme()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveAmoledTheme(isChecked)
            EqApp.themeStamp++
            recreate()
        }
    }

    // MBC thresholds follow the media volume (applies live via EqService).
    private fun setupMbcVolComp() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expMbcVolCompSwitch)
        switch.isChecked = eqPrefs.getMbcVolumeCompEnabled()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveMbcVolumeCompEnabled(isChecked)
            try {
                startService(
                    android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                        .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_REAPPLY_MBC)
                )
            } catch (_: Exception) {}
        }
    }

    // ---- TV Mode (issues #35 / #55): Off / TV (server, remotely controlled) / Remote (client driving a TV) ----

    private var tvDiscovery: com.bearinmind.equalizer314.remote.TvRemoteDiscovery? = null
    private val foundTvs = LinkedHashMap<String, Pair<String, Int>>() // name -> (host, port)
    private var tvPickerDialog: android.app.AlertDialog? = null
    private var tvPickerList: android.widget.LinearLayout? = null
    private var tvPickerPlaceholder: android.widget.TextView? = null

    private fun setupTvMode() {
        val hub = com.bearinmind.equalizer314.remote.TvRemoteHub
        val group = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.expTvModeGroup)
        val status = findViewById<android.widget.TextView>(R.id.expTvModeStatus)

        hub.statusListener = { msg -> status.text = msg }
        status.text = hub.lastStatus()
        // Auto-dismiss the PIN popup the moment a remote connects.
        hub.serverClientsListener = { n -> if (n > 0) tvPinDialog?.dismiss() }

        val mode = hub.getMode(this)
        group.check(
            when (mode) {
                com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_SERVER -> R.id.expTvModeServer
                com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_CLIENT -> R.id.expTvModeClient
                else -> R.id.expTvModeOff
            }
        )
        // Re-entering in Remote mode without a live link: rescan right away so the picker pops on its own.
        if (mode == com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_CLIENT &&
            hub.client?.connected != true
        ) {
            showTvPicker()
            startTvDiscovery()
        }

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.expTvModeOff -> {
                    stopTvDiscovery()
                    hub.setMode(this, com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_OFF)
                }
                R.id.expTvModeServer -> {
                    stopTvDiscovery()
                    stopDpForModeChange()
                    hub.setMode(this, com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_SERVER)
                    showTvPinDialog()
                }
                R.id.expTvModeClient -> {
                    stopDpForModeChange()
                    hub.setMode(this, com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_CLIENT)
                    showTvPicker()
                    startTvDiscovery()
                }
            }
        }
    }

    private val tvScanTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tvScanTimeout = Runnable {
        if (foundTvs.isEmpty() && tvDiscovery != null) {
            tvScanTimeoutHandler.removeCallbacks(tvSearchAnim)
            tvPickerPlaceholder?.text = "No devices found"
        }
    }

    // "Searching" → "Searching." → ".." → "..." while the scan runs.
    private var tvSearchDots = 0
    private val tvSearchAnim: Runnable = object : Runnable {
        override fun run() {
            val ph = tvPickerPlaceholder ?: return
            ph.text = "Searching" + ".".repeat(tvSearchDots)
            tvSearchDots = (tvSearchDots + 1) % 4
            tvScanTimeoutHandler.postDelayed(this, 400L)
        }
    }

    private fun startTvSearchAnim() {
        tvSearchDots = 0
        tvScanTimeoutHandler.removeCallbacks(tvSearchAnim)
        tvScanTimeoutHandler.post(tvSearchAnim)
    }

    private fun startTvDiscovery() {
        foundTvs.clear()
        tvDiscovery?.stop()
        findViewById<android.widget.TextView>(R.id.expTvModeStatus)?.text = ""
        tvDiscovery = com.bearinmind.equalizer314.remote.TvRemoteDiscovery(
            this,
            onFound = { name, host, port ->
                tvScanTimeoutHandler.removeCallbacks(tvScanTimeout)
                tvScanTimeoutHandler.removeCallbacks(tvSearchAnim)
                val isNew = foundTvs.put(name, host to port) == null
                if (isNew) addTvRow(name)
            },
            onStatus = { msg ->
                findViewById<android.widget.TextView>(R.id.expTvModeStatus)?.text = msg
            },
        )
        tvDiscovery?.start()
        tvScanTimeoutHandler.removeCallbacks(tvScanTimeout)
        tvScanTimeoutHandler.postDelayed(tvScanTimeout, 8000L)
    }

    private fun stopTvDiscovery() {
        tvScanTimeoutHandler.removeCallbacks(tvScanTimeout)
        tvScanTimeoutHandler.removeCallbacks(tvSearchAnim)
        tvDiscovery?.stop()
        tvDiscovery = null
    }

    /** Entering TV or Remote mode powers the DP off so both ends start from a known-off state. */
    private fun stopDpForModeChange() {
        try {
            startService(
                android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_STOP)
            )
        } catch (_: Exception) {}
    }

    // House dialog style (matches MainActivity's save-preset dialog).
    private fun styledDialogRoot(): android.widget.LinearLayout {
        val density = resources.displayMetrics.density
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
    }

    private fun styledDialogTitle(text: String): android.widget.TextView {
        val density = resources.displayMetrics.density
        return android.widget.TextView(this).apply {
            this.text = text
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
    }

    private fun styledDialogDivider(): android.view.View {
        val density = resources.displayMetrics.density
        return android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(0xFF444444.toInt())
        }
    }

    private fun styledDialogButton(label: String, isCancel: Boolean): com.google.android.material.button.MaterialButton {
        val density = resources.displayMetrics.density
        return com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            cornerRadius = (12 * density).toInt()
            setTextColor(if (isCancel) 0xFFEF9A9A.toInt() else 0xFFDDDDDD.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * density).toInt()
            setBackgroundColor(0x00000000)
            insetTop = 0; insetBottom = 0
        }
    }

    /** Server-mode PIN popup; auto-dismisses when a remote connects, Cancel backs out of TV mode. */
    private var tvPinDialog: android.app.AlertDialog? = null
    private fun showTvPinDialog() {
        val pin = com.bearinmind.equalizer314.remote.TvRemoteHub.server?.pin
        if (pin.isNullOrEmpty() || isFinishing) return
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle("Remote EQ Mode"))
        root.addView(android.widget.TextView(this).apply {
            text = pin
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 36f
            letterSpacing = 0.25f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        })
        root.addView(android.widget.TextView(this).apply {
            text = "Enter this PIN on the remote device"
            setTextColor(0xFF888888.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        })
        root.addView(styledDialogDivider())
        val cancelBtn = styledDialogButton("Cancel", isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(cancelBtn)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        dialog.setOnDismissListener { if (tvPinDialog === dialog) tvPinDialog = null }
        cancelBtn.setOnClickListener {
            // Cancel = back out of TV mode entirely (the group listener handles the teardown).
            dialog.dismiss()
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.expTvModeGroup)
                .check(R.id.expTvModeOff)
        }
        tvPinDialog = dialog
        dialog.show()
    }

    /** Device picker — opens as soon as Remote is selected and fills rows live as devices appear. */
    private fun showTvPicker() {
        if (isFinishing) return
        tvPickerDialog?.dismiss()
        val density = resources.displayMetrics.density

        val root = styledDialogRoot()
        root.addView(styledDialogTitle("Devices on your network"))

        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val placeholder = android.widget.TextView(this).apply {
            text = "Searching"
            setTextColor(0xFF888888.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        }
        list.addView(placeholder)
        root.addView(list)
        root.addView(styledDialogDivider())

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = styledDialogButton("Cancel", isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val refreshBtn = styledDialogButton("Refresh", isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(refreshBtn)
        root.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        dialog.setOnDismissListener {
            if (tvPickerDialog === dialog) {
                tvPickerDialog = null
                tvPickerList = null
                tvPickerPlaceholder = null
            }
        }
        cancelBtn.setOnClickListener {
            stopTvDiscovery()
            dialog.dismiss()
        }
        refreshBtn.setOnClickListener {
            list.removeAllViews()
            placeholder.text = "Searching"
            list.addView(placeholder)
            startTvDiscovery()
            startTvSearchAnim()
        }

        tvPickerDialog = dialog
        tvPickerList = list
        tvPickerPlaceholder = placeholder
        dialog.show()
        if (foundTvs.isEmpty()) startTvSearchAnim()
        for (name in foundTvs.keys) addTvRow(name)
    }

    /** Add a discovered device row to the open picker (no-op if closed). */
    private fun addTvRow(name: String) {
        val list = tvPickerList ?: return
        val density = resources.displayMetrics.density
        tvPickerPlaceholder?.let { if (it.parent === list) list.removeView(it) }
        val row = styledDialogButton(name, isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            setOnClickListener {
                tvPickerDialog?.dismiss()
                onTvPicked(name)
            }
        }
        list.addView(row)
    }

    private fun onTvPicked(name: String) {
        val hub = com.bearinmind.equalizer314.remote.TvRemoteHub
        val (host, port) = foundTvs[name] ?: return
        stopTvDiscovery()
        // Pair-once: a stored token from an earlier pairing skips the PIN; first contact still requires it.
        if (!hub.needsPairing(this, name)) {
            hub.connectClient(this, name, host, port, null)
            return
        }
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle("Pair with $name"))

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
        val input = android.widget.EditText(this).apply {
            hint = "PIN shown on the device"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = null
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isSingleLine = true
        }
        inputBox.addView(input)
        root.addView(inputBox)
        root.addView(styledDialogDivider())

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = styledDialogButton("Cancel", isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val pairBtn = styledDialogButton("Pair", isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(pairBtn)
        root.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        pairBtn.setOnClickListener {
            dialog.dismiss()
            hub.connectClient(this, name, host, port, input.text.toString().trim())
        }
        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        // Leaving with TV Mode armed but nothing connected is accidental — reset to Off; a live link survives.
        val hub = com.bearinmind.equalizer314.remote.TvRemoteHub
        val connected = (hub.server?.connectedRemotes() ?: 0) > 0 ||
            hub.client?.connected == true
        if (hub.getMode(this) != com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_OFF && !connected) {
            hub.setMode(this, com.bearinmind.equalizer314.remote.TvRemoteHub.MODE_OFF)
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.expTvModeGroup)
                ?.check(R.id.expTvModeOff)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.bearinmind.equalizer314.remote.TvRemoteHub.statusListener = null
        com.bearinmind.equalizer314.remote.TvRemoteHub.serverClientsListener = null
        stopTvDiscovery()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
