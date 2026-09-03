package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch

/** Groups UI presentation settings: Spectrum Control, Light Theme (EQ mode toggles land here too). */
class UiEqModesActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ui_eq_modes)
        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.uiEqModesBackButton).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.changeEqModesCard).setOnClickListener { showEqModesDialog() }

        findViewById<android.view.View>(R.id.uiNotifInfoCard).setOnClickListener { showNotifInfoDialog() }

        findViewById<android.view.View>(R.id.spectrumControlCard).setOnClickListener {
            startActivity(android.content.Intent(this, SpectrumControlActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Theme toggle: switch ON = light. setDefaultNightMode recreates all live
        // activities; EqApp re-applies the saved choice on the next cold start.
        val themeSwitch = findViewById<MaterialSwitch>(R.id.themeSwitch)
        themeSwitch.isChecked = eqPrefs.getLightTheme()
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveLightTheme(isChecked)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (isChecked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        }
    }

    // Change EQ Modes popup (issue #77): preview + drag-reorderable mode toggles,
    // same structure as the Notification Settings dialog. MainActivity applies on resume.
    private fun showEqModesDialog() {
        if (isFinishing) return
        val density = resources.displayMetrics.density
        val root = styledDialogRoot()
        root.addView(styledDialogTitle("Change EQ Modes"))

        // Live mock of the main screen's mode tab rows (wraps to a 2nd row past two modes).
        val previewRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
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
            ).apply { bottomMargin = (6 * density).toInt() }
            addView(previewRow)
        })

        val labels = mapOf(
            "parametric" to "Parametric", "graphic" to "Graphic",
            "table" to "Table", "simple" to "Simple")

        fun refreshPreview() {
            previewRow.removeAllViews()
            val keys = eqPrefs.getEqModeOrder().filter { eqPrefs.getEqModeEnabled(it) }
            var currentRow: android.widget.LinearLayout? = null
            keys.forEachIndexed { i, key ->
                if (i % 2 == 0) {
                    currentRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { if (i > 0) topMargin = (4 * density).toInt() }
                    }
                    previewRow.addView(currentRow)
                }
                currentRow?.addView(android.widget.TextView(this).apply {
                    text = labels[key]
                    textSize = 12f
                    setTextColor(0xFFDDDDDD.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x00000000)
                        cornerRadius = 12 * density
                        setStroke((1 * density).toInt(), 0xFF555555.toInt())
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = if (i % 2 == 0) 0 else (4 * density).toInt() }
                })
            }
        }
        refreshPreview()

        data class ModeRow(val key: String, val label: String)
        val ordered = eqPrefs.getEqModeOrder().map { ModeRow(it, labels[it] ?: it) }.toMutableList()

        class ModeVH(
            row: android.view.View,
            val handle: android.widget.ImageView,
            val label: android.widget.TextView,
            val switch: MaterialSwitch,
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

        lateinit var touchHelper: androidx.recyclerview.widget.ItemTouchHelper
        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ModeVH>() {
            override fun getItemCount() = ordered.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ModeVH {
                val row = android.widget.LinearLayout(this@UiEqModesActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT)
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                }
                val rippleAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleAttr, true)
                val handle = android.widget.ImageView(this@UiEqModesActivity).apply {
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
                val label = android.widget.TextView(this@UiEqModesActivity).apply {
                    setTextColor(0xFFDDDDDD.toInt())
                    textSize = 15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val switch = MaterialSwitch(this@UiEqModesActivity)
                row.addView(handle); row.addView(label); row.addView(switch)
                return ModeVH(row, handle, label, switch)
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(h: ModeVH, position: Int) {
                val r = ordered[position]
                h.label.text = r.label
                h.switch.setOnCheckedChangeListener(null)
                h.switch.isChecked = eqPrefs.getEqModeEnabled(r.key)
                h.switch.setOnCheckedChangeListener { btn, checked ->
                    if (!checked && ordered.count { eqPrefs.getEqModeEnabled(it.key) } <= 1) {
                        btn.isChecked = true
                        android.widget.Toast.makeText(
                            this@UiEqModesActivity, "At least one mode must stay on",
                            android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                    eqPrefs.saveEqModeEnabled(r.key, checked)
                    refreshPreview()
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
                    eqPrefs.saveEqModeOrder(ordered.map { it.key })
                    refreshPreview()
                }
            })
        val rowList = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@UiEqModesActivity)
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

    // House dialog style (same as ExperimentalActivity's dialogs).
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
                val row = android.widget.LinearLayout(this@UiEqModesActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT)
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                }
                val rippleAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleAttr, true)
                val handle = android.widget.ImageView(this@UiEqModesActivity).apply {
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
                val label = android.widget.TextView(this@UiEqModesActivity).apply {
                    setTextColor(0xFFDDDDDD.toInt())
                    textSize = 15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val switch = com.google.android.material.materialswitch.MaterialSwitch(this@UiEqModesActivity)
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
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@UiEqModesActivity)
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
                        android.content.Intent(this@UiEqModesActivity, com.bearinmind.equalizer314.audio.EqService::class.java)
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

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
