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
        setupTvMode()
        setupVirtualSurround()
        setupBandSolo()
        setupGraphZoom()
        setupSpectrumTracker()

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

    private fun setupBandSolo() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expBandSoloSwitch)
        switch?.isChecked = eqPrefs.getBandSoloEnabled()
        switch?.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveBandSoloEnabled(isChecked)
        }
    }

    private fun setupGraphZoom() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expGraphZoomSwitch)
        switch?.isChecked = eqPrefs.getGraphZoomEnabled()
        switch?.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveGraphZoomEnabled(isChecked)
        }
    }

    private fun setupSpectrumTracker() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expTrackerSwitch)
        val highFftSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expTrackerHighFftSwitch)
        val highFftRow = findViewById<android.view.View>(R.id.expTrackerHighFftRow)
        val highFftDivider = findViewById<android.view.View>(R.id.expTrackerHighFftDivider)

        val trackerOn = eqPrefs.getTrackerEnabled()
        switch?.isChecked = trackerOn
        highFftSwitch?.isChecked = eqPrefs.getTrackerHighFftEnabled()
        fun updateHighFftState(enabled: Boolean) {
            val alpha = if (enabled) 1f else 0.4f
            highFftRow?.alpha = alpha
            highFftSwitch?.isEnabled = enabled
            highFftDivider?.alpha = if (enabled) 0.3f else 0.1f
        }
        updateHighFftState(trackerOn)

        switch?.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveTrackerEnabled(isChecked)
            updateHighFftState(isChecked)
        }
        highFftSwitch?.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveTrackerHighFftEnabled(isChecked)
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
            tvPickerPlaceholder?.text = getString(R.string.no_devices_found)
        }
    }

    // "Searching" → "Searching." → ".." → "..." while the scan runs.
    private var tvSearchDots = 0
    private val tvSearchAnim: Runnable = object : Runnable {
        override fun run() {
            val ph = tvPickerPlaceholder ?: return
            ph.text = getString(R.string.searching) + ".".repeat(tvSearchDots)
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
        root.addView(styledDialogTitle(getString(R.string.remote_eq_mode)))
        root.addView(android.widget.TextView(this).apply {
            text = pin
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 36f
            letterSpacing = 0.25f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        })
        root.addView(android.widget.TextView(this).apply {
            text = getString(R.string.enter_pin_on_remote_device)
            setTextColor(0xFF888888.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        })
        root.addView(styledDialogDivider())
        val cancelBtn = styledDialogButton(getString(R.string.cancel), isCancel = true).apply {
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
        root.addView(styledDialogTitle(getString(R.string.devices_on_network)))

        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val placeholder = android.widget.TextView(this).apply {
            text = getString(R.string.searching)
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
        val cancelBtn = styledDialogButton(getString(R.string.cancel), isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val refreshBtn = styledDialogButton(getString(R.string.refresh), isCancel = false).apply {
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
            placeholder.text = getString(R.string.searching)
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
            hint = getString(R.string.pin_shown_on_device)
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
        val cancelBtn = styledDialogButton(getString(R.string.cancel), isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val pairBtn = styledDialogButton(getString(R.string.pair), isCancel = false).apply {
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

    // ---- Virtual surround (issue #128): an HRIR over captured app audio (VirtualSurroundService) ----

    private var vsSyncing = false

    private val vsImport = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importHrir(uri) }

    private val vsConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        ) {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, com.bearinmind.equalizer314.audio.VirtualSurroundService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.VirtualSurroundService.ACTION_START)
                    .putExtra(com.bearinmind.equalizer314.audio.VirtualSurroundService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(com.bearinmind.equalizer314.audio.VirtualSurroundService.EXTRA_RESULT_DATA, data)
            )
        }
        syncVirtualSurround()
    }

    private val vsRecordPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestVsConsent() else {
            vsToast(getString(R.string.vs_needs_record_permission))
            syncVirtualSurround()
        }
    }

    private val vsStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) = syncVirtualSurround()
    }

    private fun setupVirtualSurround() {
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expVsSwitch)
            .setOnCheckedChangeListener { _, isChecked ->
                if (vsSyncing) return@setOnCheckedChangeListener
                if (isChecked) startVirtualSurround()
                else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    com.bearinmind.equalizer314.audio.VirtualSurroundService.stop(this)
                }
            }
        findViewById<android.view.View>(R.id.expVsHrirButton).setOnClickListener { showHrirPicker() }
        syncVirtualSurround()
    }

    private fun vsToast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    /** Checks first, then the record permission, then Android's "start recording?" consent; the service starts on consent. */
    private fun startVirtualSurround() {
        val problem = when {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> R.string.vs_needs_android_10
            !com.bearinmind.equalizer314.audio.HrirConvolver.available -> R.string.vs_engine_unavailable
            !com.bearinmind.equalizer314.state.HrirLibrary.exists(this, eqPrefs.getVirtualSurroundHrir()) -> R.string.vs_choose_hrir_first
            // Session mode keeps per-app DPs on the very sessions the capture mutes.
            eqPrefs.getAudioRoutingMode() == 1 -> R.string.vs_needs_system_wide
            else -> null
        }
        if (problem != null) {
            vsToast(getString(problem))
            syncVirtualSurround()
            if (problem == R.string.vs_choose_hrir_first) showHrirPicker()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            vsRecordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            requestVsConsent()
        }
    }

    private fun requestVsConsent() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        // Android 14+: preselect the whole device; a single-app share would capture nothing else.
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            vsConsent.launch(intent)
        } catch (_: Exception) {
            syncVirtualSurround()
        }
    }

    private fun syncVirtualSurround() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expVsSwitch) ?: return
        val status = findViewById<android.widget.TextView>(R.id.expVsStatus)
        val name = eqPrefs.getVirtualSurroundHrir()
            ?.takeIf { com.bearinmind.equalizer314.state.HrirLibrary.exists(this, it) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.expVsHrirButton).text =
            name ?: getString(R.string.vs_choose_hrir)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            switch.isEnabled = false
            status.text = getString(R.string.vs_needs_android_10)
            return
        }
        val svc = com.bearinmind.equalizer314.audio.VirtualSurroundService
        vsSyncing = true
        switch.isChecked = svc.isRunning
        vsSyncing = false
        status.text = when (svc.status) {
            com.bearinmind.equalizer314.audio.VirtualSurroundService.Status.STARTING -> getString(R.string.vs_status_starting)
            com.bearinmind.equalizer314.audio.VirtualSurroundService.Status.RUNNING ->
                resources.getQuantityString(R.plurals.vs_status_running, svc.mutedApps, svc.mutedApps)
            com.bearinmind.equalizer314.audio.VirtualSurroundService.Status.FAILED -> getString(
                R.string.vs_status_stopped,
                getString(
                    when (svc.lastError) {
                        com.bearinmind.equalizer314.audio.VirtualSurroundService.ERROR_HRIR -> R.string.vs_error_hrir
                        com.bearinmind.equalizer314.audio.VirtualSurroundService.ERROR_REVOKED -> R.string.vs_error_revoked
                        else -> R.string.vs_error_capture
                    }
                )
            )
            // Waiting for an app: the card's note already says which apps work.
            com.bearinmind.equalizer314.audio.VirtualSurroundService.Status.OFF,
            com.bearinmind.equalizer314.audio.VirtualSurroundService.Status.NO_SESSIONS -> name?.let { eqPrefs.getHrirInfo(it) } ?: ""
        }
    }

    /** File list: tap = use it (a running engine swaps live), ✕ = delete, plus Import. */
    private fun showHrirPicker() {
        if (isFinishing) return
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle(getString(R.string.vs_hrir_files)))
        var dialog: android.app.AlertDialog? = null

        val names = com.bearinmind.equalizer314.state.HrirLibrary.names(this)
        val current = eqPrefs.getVirtualSurroundHrir()
        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for (name in names) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                // Baseline alignment pushed the two-line file button down, clipping its bottom outline.
                isBaselineAligned = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            val info = eqPrefs.getHrirInfo(name)
            val selected = name == current
            val label = android.text.SpannableStringBuilder(name)
            if (info != null) {
                val start = label.length
                label.append("\n").append(info)
                label.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, label.length, 0)
                label.setSpan(android.text.style.ForegroundColorSpan(0xFF888888.toInt()), start, label.length, 0)
            }
            row.addView(styledDialogButton("", isCancel = false).apply {
                text = label
                setAllCaps(false)
                // Selected file gets the accent outline, as in the theme and language pickers.
                if (selected) {
                    setTextColor(0xFFFFFFFF.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary))
                    strokeWidth = (2 * density).toInt()
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    eqPrefs.saveVirtualSurroundHrir(name)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        com.bearinmind.equalizer314.audio.VirtualSurroundService.reload(this@ExperimentalActivity)
                    }
                    dialog?.dismiss()
                    syncVirtualSurround()
                }
            })
            row.addView(styledDialogButton("✕", isCancel = true).apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(0, paddingTop, 0, paddingBottom)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (48 * density).toInt(), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener {
                    dialog?.dismiss()
                    confirmDeleteHrir(name)
                }
            })
            list.addView(row)
        }
        // Long lists scroll; the buttons below stay on screen.
        root.addView(android.widget.ScrollView(this).apply {
            addView(list)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                if (names.size > 5) (5 * 64 * density).toInt() else android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        root.addView(styledDialogDivider())

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = styledDialogButton(getString(R.string.cancel), isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val importBtn = styledDialogButton(getString(R.string.vs_import_file), isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(importBtn)
        root.addView(btnRow)

        val dlg = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        dialog = dlg
        cancelBtn.setOnClickListener { dlg.dismiss() }
        importBtn.setOnClickListener {
            dlg.dismiss()
            // .irs files carry no audio MIME type, so offer everything; the loader validates.
            try { vsImport.launch(arrayOf("*/*")) } catch (_: Exception) {}
        }
        dlg.show()
    }

    private fun confirmDeleteHrir(name: String) {
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle(getString(R.string.delete_quoted_confirm, name)))
        root.addView(styledDialogDivider())
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val cancelBtn = styledDialogButton(getString(R.string.cancel), isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val deleteBtn = styledDialogButton(getString(R.string.delete), isCancel = true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt()
            }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(deleteBtn)
        root.addView(btnRow)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        deleteBtn.setOnClickListener {
            com.bearinmind.equalizer314.state.HrirLibrary.delete(this, name)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                com.bearinmind.equalizer314.audio.VirtualSurroundService.reload(this)
            }
            dialog.dismiss()
            syncVirtualSurround()
        }
        dialog.show()
    }

    /** Copy + validate off the main thread; a good file becomes the chosen one. */
    private fun importHrir(uri: android.net.Uri) {
        Thread {
            val result = runCatching { com.bearinmind.equalizer314.state.HrirLibrary.import(this, uri) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { name ->
                    eqPrefs.saveVirtualSurroundHrir(name)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        com.bearinmind.equalizer314.audio.VirtualSurroundService.reload(this)
                    }
                    vsToast(getString(R.string.imported_quoted, name))
                    syncVirtualSurround()
                }.onFailure { e ->
                    vsToast(
                        if (e is com.bearinmind.equalizer314.dsp.HrirLoader.UnsupportedHrirException)
                            getString(R.string.vs_unsupported_file, e.message)
                        else getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
                    )
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this, vsStateReceiver,
            android.content.IntentFilter(com.bearinmind.equalizer314.audio.VirtualSurroundService.ACTION_STATE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        syncVirtualSurround()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(vsStateReceiver) } catch (_: Exception) {}
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
