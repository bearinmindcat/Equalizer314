package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager

class ExperimentalActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_experimental)

        val root = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupDpBandCount()
        setupMaxEqBands()
        setupNotifInfo()
        setupFrameSlider()
        setupInterleave()
        setupCompatMode()
        setupMbcVolComp()
        setupTvMode()

        // Hide the legacy "Experimental DP Engine" switch row — the
        // experimental path is now the only path. Keeping the view
        // hidden (rather than removing the layout XML) preserves the
        // surrounding card structure for the remaining rows.
        findViewById<android.view.View>(R.id.expDpModeSwitch)
            ?.let { switch ->
                (switch.parent as? android.view.View)?.visibility = android.view.View.GONE
            }
    }

    // Gain Compensation (auto-gain) graduated out of Experimental — it now
    // lives as a real, enabled "Auto-Gain" card on the main settings screen
    // (on by default; see EqPreferencesManager.getAutoGainEnabled).
    // DP Band Count is now read-only: the converter always uses the full
    // Wavelet band table (ParametricToDpConverter.numBands), so we just show
    // that number rather than a slider that didn't actually change anything.
    private fun setupDpBandCount() {
        // Reflect what the NEXT DP start will use: 32 in Compat Mode, else 128.
        findViewById<android.widget.TextView>(R.id.expDpBandCountValue).text =
            if (eqPrefs.getDpCompatMode())
                com.bearinmind.equalizer314.audio.DynamicsProcessingManager.COMPAT_BAND_COUNT.toString()
            else "128"
    }

    // Experimental "Add more EQ bands" toggle (issue #31). On → cap 64,
    // off → default 16. Updates the live cap so it takes effect on the next
    // band add / EQ-screen interaction.
    private fun setupMaxEqBands() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expMaxBandsSwitch)
        switch.isChecked = eqPrefs.getMaxEqBands() > 16
        switch.setOnCheckedChangeListener { _, isChecked ->
            val cap = if (isChecked) EqStateManager.ABSOLUTE_MAX_BANDS else 16
            eqPrefs.saveMaxEqBands(cap)
            EqStateManager.MAX_BANDS = cap
        }
    }

    // Issue #26: DP FFT window ("EQ precision"). One pref (dpFrameMs),
    // one 4-stop slider over the engine's useful power-of-two block sizes
    // (2048/4096/8192/16384 samples). Longer windows render the bass EQ
    // more faithfully (finer FFT bins) at the cost of audio delay --
    // REW-measured: a 12 Hz-wide bass filter is erased at 43 ms, renders
    // within 0.2 dB at 341 ms. Takes effect on the next EQ power cycle
    // (the window is baked in at DynamicsProcessing creation).
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
        // Recycle the live DP only when the finger lifts — one rebuild per
        // adjustment, not one per rung dragged across.
        slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                requestDpRecycle()
            }
        })
        syncFrameUi()
    }

    /** Ask EqService to rebuild the live DP so creation-time settings
     *  (interleave, DP Latency Window) apply immediately. No-op when the
     *  EQ is off. The service toasts + broadcasts so MainActivity's power
     *  FAB echoes the off→on cycle. */
    private fun requestDpRecycle() {
        try {
            startService(
                android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_RECYCLE_DP)
            )
        } catch (_: Exception) {}
    }

    // Pre+Post EQ interleave (issue #26 follow-up): second staircase on the
    // DP's Post-EQ stage, cutoffs offset half a stair — 256 effective stairs.
    // Baked in at DP creation, so flipping the switch asks EqService to
    // recycle the live DP — no manual power cycle needed.
    private fun setupInterleave() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expInterleaveSwitch)
        switch.isChecked = eqPrefs.getDpInterleave()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveDpInterleave(isChecked)
            com.bearinmind.equalizer314.audio.DynamicsProcessingManager.interleaveEnabled = isChecked
            requestDpRecycle()
        }
    }

    // Compatibility Mode: cap DP at 32 bands for band-limited HALs (Pixel /
    // some Samsung). Auto-on for Google/Pixel by default. Baked at DP
    // creation → recycle the live DP on change.
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

    // Issue #58: hide the foreground-service notification while the EQ is off.

    // ---- TV Mode (issues #35 / #55) -------------------------------------
    // Off / TV (server: this device is remotely controlled) / Remote
    // (client: this device's UI drives a TV found via LAN discovery).

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
        // Re-entering the screen in Remote mode without a live link: rescan
        // right away so the device picker pops on its own.
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

    /** Entering TV or Remote mode powers the DP off — the user turns it
     *  back on deliberately, so both ends start from a known-off state and
     *  the first power-on propagates cleanly over the link. */
    private fun stopDpForModeChange() {
        try {
            startService(
                android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_STOP)
            )
        } catch (_: Exception) {}
    }

    // House dialog style (matches MainActivity's save-preset dialog):
    // custom vertical layout, 20sp title, rounded outlined widgets, thin
    // divider, side-by-side outlined buttons with the red Cancel.
    // Notification info picker (issue #65): choose which lines the notification shows.
    private fun setupNotifInfo() {
        findViewById<android.view.View>(R.id.expNotifInfoCard).setOnClickListener { showNotifInfoDialog() }
    }

    private fun showNotifInfoDialog() {
        if (isFinishing) return
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle("Notification Settings"))

        // Live mock of the notification, updates as the toggles flip.
        val previewTitle = android.widget.TextView(this).apply {
            setTextColor(0xFFE2E2E2.toInt()); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val previewBody = android.widget.TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 12f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2A2A2A.toInt()); cornerRadius = 12 * density
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
            addView(previewTitle); addView(previewBody)
        }.also { (it.layoutParams as android.widget.LinearLayout.LayoutParams).bottomMargin = (6 * density).toInt() })

        fun refreshPreview() {
            val on = eqPrefs.getPowerState()
            previewTitle.text = if (on) "Equalizer314: Online" else "Equalizer314: Offline"
            val am = getSystemService(android.media.AudioManager::class.java)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val volPct = if (max > 0) am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max else 0
            val name = eqPrefs.getPresetName()
            val isRealPreset = name.isNotBlank() &&
                getSharedPreferences("custom_presets", MODE_PRIVATE).contains("preset_$name")
            val device = com.bearinmind.equalizer314.audio.EqService.staticLastDeviceLabel ?: "—"
            val lines = mutableListOf<String>()
            for (key in eqPrefs.getNotifLineOrder()) when (key) {
                "volume" -> if (eqPrefs.getNotifShowVolume()) lines.add("Volume: $volPct%")
                "mode" -> if (eqPrefs.getNotifShowMode()) lines.add(
                    "Mode: " + if (eqPrefs.getAudioRoutingMode() == 1) "Session" else "System")
                "preset" -> if (eqPrefs.getNotifShowPreset()) lines.add("Preset: ${if (isRealPreset) name else "none"}")
                "device" -> if (eqPrefs.getNotifShowDevice()) lines.add("Device: $device")
            }
            previewBody.text = lines.joinToString("\n")
            previewBody.visibility = if (lines.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        refreshPreview()

        fun notifyServiceRefresh() {
            sendBroadcast(android.content.Intent(
                com.bearinmind.equalizer314.audio.EqService.ACTION_NOTIFICATION_REFRESH
            ).setPackage(packageName))
        }

        // Drag-reorderable toggle rows (same handle-drag pattern as the pipeline screen).
        data class NotifRow(val key: String, val label: String, val get: () -> Boolean, val set: (Boolean) -> Unit)
        val allRows = mapOf(
            "volume" to NotifRow("volume", "Volume", { eqPrefs.getNotifShowVolume() }, { eqPrefs.saveNotifShowVolume(it) }),
            "mode" to NotifRow("mode", "Mode", { eqPrefs.getNotifShowMode() }, { eqPrefs.saveNotifShowMode(it) }),
            "preset" to NotifRow("preset", "Preset", { eqPrefs.getNotifShowPreset() }, { eqPrefs.saveNotifShowPreset(it) }),
            "device" to NotifRow("device", "Device", { eqPrefs.getNotifShowDevice() }, { eqPrefs.saveNotifShowDevice(it) }),
        )
        val ordered = eqPrefs.getNotifLineOrder().mapNotNull { allRows[it] }.toMutableList()

        class LineVH(
            row: android.view.View,
            val handle: android.widget.ImageView,
            val label: android.widget.TextView,
            val switch: com.google.android.material.materialswitch.MaterialSwitch,
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

        lateinit var touchHelper: androidx.recyclerview.widget.ItemTouchHelper
        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<LineVH>() {
            override fun getItemCount() = ordered.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LineVH {
                val row = android.widget.LinearLayout(this@ExperimentalActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT)
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                }
                val rippleAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleAttr, true)
                val handle = android.widget.ImageView(this@ExperimentalActivity).apply {
                    setImageResource(R.drawable.ic_menu_handle)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (32 * density).toInt(), (32 * density).toInt()
                    ).apply { marginEnd = (8 * density).toInt() }
                    setBackgroundResource(rippleAttr.resourceId)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    val pad = (6 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    contentDescription = "Drag handle"
                }
                val label = android.widget.TextView(this@ExperimentalActivity).apply {
                    setTextColor(0xFFDDDDDD.toInt())
                    textSize = 15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val switch = com.google.android.material.materialswitch.MaterialSwitch(this@ExperimentalActivity)
                row.addView(handle); row.addView(label); row.addView(switch)
                return LineVH(row, handle, label, switch)
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(h: LineVH, position: Int) {
                val r = ordered[position]
                h.label.text = r.label
                h.switch.setOnCheckedChangeListener(null)
                h.switch.isChecked = r.get()
                h.switch.setOnCheckedChangeListener { _, checked ->
                    r.set(checked)
                    refreshPreview()
                    notifyServiceRefresh()
                }
                h.handle.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                    false
                }
            }
        }
        touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                override fun isLongPressDragEnabled() = false
                override fun isItemViewSwipeEnabled() = false
                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    to: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                ): Boolean {
                    val a = from.bindingAdapterPosition
                    val b = to.bindingAdapterPosition
                    if (a == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        b == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
                    val moved = ordered.removeAt(a)
                    ordered.add(b, moved)
                    adapter.notifyItemMoved(a, b)
                    return true
                }
                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}
                override fun clearView(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                ) {
                    super.clearView(rv, vh)
                    eqPrefs.saveNotifLineOrder(ordered.map { it.key })
                    refreshPreview()
                    notifyServiceRefresh()
                }
            })
        val rowList = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@ExperimentalActivity)
            this.adapter = adapter
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }
        touchHelper.attachToRecyclerView(rowList)
        root.addView(rowList, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (6 * density).toInt() })

        root.addView(styledDialogDivider())

        val hideRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }
        hideRow.addView(android.widget.TextView(this).apply {
            text = "Hide notification"
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 15f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        hideRow.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = eqPrefs.getHideNotificationWhenOff()
            setOnCheckedChangeListener { _, checked ->
                eqPrefs.setHideNotificationWhenOff(checked)
                try {
                    startService(
                        android.content.Intent(this@ExperimentalActivity, com.bearinmind.equalizer314.audio.EqService::class.java)
                            .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_REFRESH_NOTIFICATION)
                    )
                } catch (_: Exception) {}
            }
        })
        root.addView(hideRow)

        root.addView(styledDialogDivider())
        val closeBtn = styledDialogButton("Close", isCancel = false).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(closeBtn)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

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

    /** Server-mode PIN popup — the pairing code, house dialog style.
     *  Auto-dismisses when a remote connects; Cancel backs out of TV mode. */
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
            // Cancel = back out of TV mode entirely (the group listener
            // handles the teardown).
            dialog.dismiss()
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.expTvModeGroup)
                .check(R.id.expTvModeOff)
        }
        tvPinDialog = dialog
        dialog.show()
    }

    /** Device picker — opens immediately when Remote is selected (even with
     *  nothing found yet) and fills in rows live as devices appear. */
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
        // Chromecast-style pair-once: a stored token from an earlier pairing
        // skips the PIN; first contact with a device still requires it.
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
        // Leaving the screen with TV Mode armed but NOTHING connected =
        // accidental — reset to Off so no orphaned server/scan lingers.
        // A live connection (either role) is deliberate and survives.
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
