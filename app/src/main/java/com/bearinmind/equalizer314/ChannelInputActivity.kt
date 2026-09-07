package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.audio.SessionEffectManager
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.PresetDropdownAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/** "Channel Input" pipeline card detail: per-app EQ presets, applied by SessionEffectManager when an app opens an audio-effect session. */
class ChannelInputActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var appsList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var appsAdapter: AppsAdapter

    // "Now playing" panel — always visible; broadcast-attached and NLS-detected sessions.
    private lateinit var currentSessionSection: LinearLayout
    private lateinit var currentSessionList: RecyclerView
    private lateinit var currentSessionEmpty: TextView
    private lateinit var sessionsAdapter: ActiveSessionsAdapter

    // Collapsible "Apps" section (like AudioOutput's Devices): clickable header, animated open/close, expanded state in local prefs.
    private lateinit var appsHeader: LinearLayout
    private lateinit var appsBody: LinearLayout
    private lateinit var appsChevron: TextView
    private var appsExpanded = true

    // "Session detection" card: switch mirrors system Notification access, taps go to system Settings (the only place it can be flipped).
    private lateinit var enableDetectionCard: MaterialCardView
    private lateinit var enableDetectionTitle: TextView
    private lateinit var enableDetectionBody: TextView
    private lateinit var enableDetectionSwitch: MaterialSwitch

    // "Skip system sounds" toggle — gates the global-DP bypass for notification/ringtone/alarm/call streams; default on, applied live.
    private lateinit var bypassSystemSoundsCard: MaterialCardView
    private lateinit var bypassSystemSoundsSwitch: MaterialSwitch

    // Bound EqService for the live session set; null when not running (the empty card is then correct).
    private var eqService: EqService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            eqService = (binder as? EqService.EqBinder)?.service
            refreshCurrentSessions()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            refreshCurrentSessions()
        }
    }

    private val sessionsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCurrentSessions()
        }
    }

    // Cycling-dots animation for the "Loading apps…" placeholder — 400ms tick: "." → ". ." → ". . ." → loop
    private val loadingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadingFrame = 0
    private val loadingRunnable = object : Runnable {
        override fun run() {
            val dots = when (loadingFrame % 4) {
                0 -> ""
                1 -> " ."
                2 -> " . ."
                else -> " . . ."
            }
            emptyState.text = "Loading apps$dots"
            loadingFrame++
            loadingHandler.postDelayed(this, 400)
        }
    }

    private fun startLoadingAnimation() {
        loadingFrame = 0
        emptyState.visibility = View.VISIBLE
        loadingHandler.removeCallbacks(loadingRunnable)
        loadingHandler.post(loadingRunnable)
    }

    private fun stopLoadingAnimation() {
        loadingHandler.removeCallbacks(loadingRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_input)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.channelInputBackButton).setOnClickListener { finish() }
        appsList = findViewById(R.id.appsList)
        emptyState = findViewById(R.id.appsEmptyState)

        appsAdapter = AppsAdapter()
        appsList.layoutManager = LinearLayoutManager(this)
        appsList.adapter = appsAdapter
        appsList.isNestedScrollingEnabled = false

        currentSessionSection = findViewById(R.id.currentSessionSection)
        currentSessionList = findViewById(R.id.currentSessionList)
        currentSessionEmpty = findViewById(R.id.currentSessionEmpty)
        sessionsAdapter = ActiveSessionsAdapter()
        currentSessionList.layoutManager = LinearLayoutManager(this)
        currentSessionList.adapter = sessionsAdapter
        currentSessionList.isNestedScrollingEnabled = false

        // Collapsible Apps section — header click animates the body; expanded state persists per-screen.
        appsHeader = findViewById(R.id.appsHeader)
        appsBody = findViewById(R.id.appsBody)
        appsChevron = findViewById(R.id.appsChevron)
        appsExpanded = getPreferences(MODE_PRIVATE).getBoolean(PREF_APPS_EXPANDED, true)
        applyAppsExpanded(animate = false)
        appsHeader.setOnClickListener {
            appsExpanded = !appsExpanded
            getPreferences(MODE_PRIVATE)
                .edit().putBoolean(PREF_APPS_EXPANDED, appsExpanded).apply()
            applyAppsExpanded(animate = true)
        }

        enableDetectionCard = findViewById(R.id.enableDetectionCard)
        enableDetectionTitle = findViewById(R.id.enableDetectionTitle)
        enableDetectionBody = findViewById(R.id.enableDetectionBody)
        enableDetectionSwitch = findViewById(R.id.enableDetectionSwitch)
        // Click (not checked-change) listener: snap the switch back to the real NLS state, then open system Settings; onResume re-syncs.
        enableDetectionSwitch.setOnClickListener {
            enableDetectionSwitch.isChecked = isNotificationListenerGranted()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not open Notification access settings", Toast.LENGTH_SHORT).show()
            }
        }
        // Tapping the card body (outside the switch) also goes to Settings — the whole card is the affordance
        enableDetectionCard.setOnClickListener { enableDetectionSwitch.performClick() }

        // "Skip system sounds" — default on so the pre-EQ + limiter don't distort short notification streams; ACTION_APPLY_BYPASS_PREF applies it now.
        bypassSystemSoundsCard = findViewById(R.id.bypassSystemSoundsCard)
        bypassSystemSoundsSwitch = findViewById(R.id.bypassSystemSoundsSwitch)
        bypassSystemSoundsSwitch.isChecked = eqPrefs.getBypassSystemSounds()
        val toggleBypass = {
            val next = !eqPrefs.getBypassSystemSounds()
            eqPrefs.setBypassSystemSounds(next)
            bypassSystemSoundsSwitch.isChecked = next
            val intent = Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_APPLY_BYPASS_PREF)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Throwable) {
                // Service may not be running — pref is saved and picked up next EQ start
            }
        }
        bypassSystemSoundsSwitch.setOnClickListener { toggleBypass() }
        bypassSystemSoundsCard.setOnClickListener { toggleBypass() }

        setupRoutingModeChips()
        setupAppsFilterChips()
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SessionEffectManager.ACTION_SESSIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionsChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionsChangedReceiver, filter)
        }
        // Bind (do not start) — if the service isn't running, the panel just shows its empty state
        bindService(
            Intent(this, EqService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        refreshDetectionCtaVisibility()
        refreshCurrentSessions()
    }

    override fun onResume() {
        super.onResume()
        // The listener may have been toggled in Settings while backgrounded — re-check on every return
        refreshDetectionCtaVisibility()
    }

    /** Expand/collapse the Apps section: height animation over [EXPAND_DURATION_MS] (FastOutSlowIn), chevron rotating in lockstep. */
    private fun applyAppsExpanded(animate: Boolean) {
        val targetRotation = if (appsExpanded) 90f else 0f
        if (!animate) {
            appsBody.visibility = if (appsExpanded) View.VISIBLE else View.GONE
            if (appsExpanded) restoreWrapContentHeight(appsBody)
            appsChevron.rotation = targetRotation
            return
        }
        animateCollapse(appsBody, appsExpanded)
        appsChevron.animate()
            .rotation(targetRotation)
            .setDuration(EXPAND_DURATION_MS)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    /** Animate [body]'s height between 0 and its natural height; after expanding it returns to WRAP_CONTENT. */
    private fun animateCollapse(body: View, expand: Boolean) {
        val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
        if (expand) {
            body.visibility = View.VISIBLE
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (body.parent as View).width, View.MeasureSpec.EXACTLY,
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            body.measure(widthSpec, heightSpec)
            val target = body.measuredHeight
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    body.layoutParams.height = it.animatedValue as Int
                    body.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        restoreWrapContentHeight(body)
                    }
                })
                start()
            }
        } else {
            val start = body.height
            android.animation.ValueAnimator.ofInt(start, 0).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    body.layoutParams.height = it.animatedValue as Int
                    body.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        body.visibility = View.GONE
                        restoreWrapContentHeight(body)
                    }
                })
                start()
            }
        }
    }

    private fun restoreWrapContentHeight(v: View) {
        v.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        v.requestLayout()
    }

    private fun isNotificationListenerGranted(): Boolean =
        NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

    /** Card stays visible in both states; only the switch reflects system Notification access. */
    private fun refreshDetectionCtaVisibility() {
        val granted = isNotificationListenerGranted()
        enableDetectionCard.visibility = View.VISIBLE
        enableDetectionSwitch.isChecked = granted
        // Mechanism (audioserver dump, or MediaSessionManager when the OEM denies it) stays out of the copy.
        enableDetectionBody.text = if (granted)
            "Detects which apps are playing audio so app presets can follow them"
        else
            "Needs Notification access. Tap to open the Android setting"
    }

    override fun onStop() {
        try { unregisterReceiver(sessionsChangedReceiver) } catch (_: Throwable) {}
        try { unbindService(serviceConnection) } catch (_: Throwable) {}
        eqService = null
        super.onStop()
    }

    private fun refreshCurrentSessions() {
        val sessions = eqService?.sessionEffects?.getActiveSessions().orEmpty()
        if (sessions.isEmpty()) {
            currentSessionList.visibility = View.GONE
            currentSessionEmpty.visibility = View.VISIBLE
            sessionsAdapter.setItems(emptyList())
        } else {
            currentSessionEmpty.visibility = View.GONE
            currentSessionList.visibility = View.VISIBLE
            // Resolve icon + label on the fly — usually 1-2 active sessions, negligible cost, no stale cache
            val pm = packageManager
            // Coalesce by package (gapless players open two sessions): BROADCAST beats DETECTED, real id beats synthetic, isPlaying = any.
            val rows = sessions
                .groupBy { it.packageName }
                .map { (pkg, group) ->
                    val rep = group.sortedWith(
                        compareByDescending<SessionEffectManager.ActiveSession> {
                            it.source == SessionEffectManager.AttachSource.BROADCAST
                        }.thenByDescending { it.sessionId > 0 }
                    ).first()
                    val (icon, label) = runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationIcon(info) to pm.getApplicationLabel(info).toString()
                    }.getOrElse { null to pkg }
                    SessionRow(
                        sessionId = rep.sessionId,
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        presetName = rep.presetName,
                        source = rep.source,
                        isPlaying = group.any { it.isPlaying },
                    )
                }
                .sortedBy { it.label.lowercase() }
            sessionsAdapter.setItems(rows)
        }
    }

    private fun setupRoutingModeChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.routingModeChips)
        val global = findViewById<Chip>(R.id.routingModeGlobal)
        val perApp = findViewById<Chip>(R.id.routingModePerApp)
        // 0 = System-wide, 1 = Session-based; legacy "2" (Both) reads as System-wide.
        val mode = eqPrefs.getAudioRoutingMode()
        when (mode) {
            1 -> perApp.isChecked = true
            else -> global.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newMode = if (id == R.id.routingModePerApp) 1 else 0
            eqPrefs.saveAudioRoutingMode(newMode)
            // Apply the new mode — selecting Session-based stops the global DP so bound apps aren't double-EQed
            val serviceIntent = android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_APPLY_ROUTING_MODE)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Throwable) { /* service may already be torn down */ }
        }
    }

    private fun setupAppsFilterChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.appsFilterChips)
        val filtered = findViewById<Chip>(R.id.appsFilterFiltered)
        val showAll = findViewById<Chip>(R.id.appsFilterShowAll)
        when (eqPrefs.getAppListFilterMode()) {
            1 -> showAll.isChecked = true
            else -> filtered.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newMode = if (id == R.id.appsFilterShowAll) 1 else 0
            if (newMode == eqPrefs.getAppListFilterMode()) return@setOnCheckedStateChangeListener
            eqPrefs.saveAppListFilterMode(newMode)
            loadApps()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun loadApps() {
        // Label/icon lookups are slow IPC on big app lists — enumerate on IO, hand results to the adapter on Main.
        startLoadingAnimation()
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager

                // FILTERED (default): media-button receiver, MediaBrowserService, audio/* handler, seen or bound apps. SHOW_ALL: every installed app.
                val showAll = eqPrefs.getAppListFilterMode() == 1
                val mediaCandidates: Set<String>? = if (showAll) null else {
                    val mediaButtonApps = pm.queryBroadcastReceivers(
                        Intent(Intent.ACTION_MEDIA_BUTTON), 0,
                    ).map { it.activityInfo.packageName }.toHashSet()

                    val mediaBrowserApps = pm.queryIntentServices(
                        Intent("android.media.browse.MediaBrowserService"), 0,
                    ).map { it.serviceInfo.packageName }.toHashSet()

                    val audioMimeApps = pm.queryIntentActivities(
                        Intent(Intent.ACTION_VIEW).apply { type = "audio/*" }, 0,
                    ).map { it.activityInfo.packageName }.toHashSet()

                    val seen = eqPrefs.getAllSeenApps().toHashSet()
                    val bindings = eqPrefs.getAllAppBindings().associateBy { it.packageName }

                    mediaButtonApps + mediaBrowserApps + audioMimeApps + seen +
                        bindings.keys  // always show bound apps even if filters drop them
                }

                // Show All: only launchable packages (drops providers/daemons); bound packages stay so stale bindings remain removable.
                val launchablePackages: Set<String>? = if (showAll) {
                    val launchable = pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
                    ).map { it.activityInfo.packageName }.toHashSet()
                    launchable + eqPrefs.getAllAppBindings().map { it.packageName }
                } else null

                pm.getInstalledApplications(0)
                    .filter { mediaCandidates == null || it.packageName in mediaCandidates }
                    .filter { launchablePackages == null || it.packageName in launchablePackages }
                    .filter { it.packageName != packageName }
                    .map { info ->
                        AppRow(
                            packageName = info.packageName,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        )
                    }
                    // Pure alphabetical — the Now playing panel already surfaces what's active.
                    .sortedBy { it.label.lowercase() }
            }

            stopLoadingAnimation()
            if (rows.isEmpty()) {
                emptyState.text = "No apps detected yet."
                emptyState.visibility = View.VISIBLE
                appsAdapter.setItems(emptyList())
            } else {
                emptyState.visibility = View.GONE
                appsAdapter.setItems(rows)
            }
        }
    }

    override fun onDestroy() {
        stopLoadingAnimation()
        super.onDestroy()
    }

    private data class AppRow(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private data class SessionRow(
        val sessionId: Int,
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val presetName: String?,
        val source: SessionEffectManager.AttachSource,
        val isPlaying: Boolean,
    )

    private inner class ActiveSessionsAdapter : RecyclerView.Adapter<ActiveSessionsAdapter.VH>() {
        private val items = mutableListOf<SessionRow>()

        fun setItems(newItems: List<SessionRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.sessionRowIcon)
            val name: TextView = view.findViewById(R.id.sessionRowName)
            val meta: TextView = view.findViewById(R.id.sessionRowMeta)
            val pulse: ImageView = view.findViewById(R.id.sessionRowPulse)
            val presetLayout: TextInputLayout = view.findViewById(R.id.sessionRowPresetLayout)
            val dropdown: MaterialAutoCompleteTextView = view.findViewById(R.id.sessionRowPresetDropdown)
        }

        // Own anti-reopen timestamp (same pattern as AppsAdapter); the two lists never share a row
        private var lastDismissAt = 0L

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_session_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.icon.setImageDrawable(r.icon)
            holder.name.text = r.label
            // Meta is just the package; source/session id are implementation detail.
            holder.meta.text = r.packageName

            // Speaker pulse: animated green while outputting, static dim cone when silent; tint set on the ImageView so frames stay consistent.
            val pulse = holder.pulse.drawable as? android.graphics.drawable.AnimationDrawable
            if (pulse != null) {
                val tintColor = androidx.core.content.ContextCompat.getColor(
                    this@ChannelInputActivity,
                    if (r.isPlaying) R.color.pulse_active_green
                    else R.color.pulse_inactive_red,
                )
                holder.pulse.imageTintList =
                    android.content.res.ColorStateList.valueOf(tintColor)
                if (r.isPlaying) {
                    if (!pulse.isRunning) pulse.start()
                } else {
                    pulse.stop()
                    pulse.selectDrawable(0)
                }
            }

            // Same dropdown behavior as the Apps list — a pick binds this package immediately
            bindPresetDropdown(
                presetLayout = holder.presetLayout,
                dropdown = holder.dropdown,
                packageName = r.packageName,
                appLabel = r.label,
                getLastDismiss = { lastDismissAt },
                setLastDismiss = { lastDismissAt = it },
            )
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {

        private val items = mutableListOf<AppRow>()
        private var lastDismissAt = 0L  // shared anti-reopen timestamp

        fun setItems(newItems: List<AppRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appRowIcon)
            val name: TextView = view.findViewById(R.id.appRowName)
            val pkg: TextView = view.findViewById(R.id.appRowPackage)
            val presetLayout: TextInputLayout = view.findViewById(R.id.appRowPresetLayout)
            val dropdown: MaterialAutoCompleteTextView = view.findViewById(R.id.appRowPresetDropdown)
            val card: MaterialCardView = view as MaterialCardView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.icon.setImageDrawable(row.icon)
            holder.name.text = row.label
            holder.pkg.text = row.packageName
            bindPresetDropdown(
                presetLayout = holder.presetLayout,
                dropdown = holder.dropdown,
                packageName = row.packageName,
                appLabel = row.label,
                getLastDismiss = { lastDismissAt },
                setLastDismiss = { lastDismissAt = it },
            )
        }
    }

    /** Wire a row's preset-binding dropdown (both adapters): "(none)" unbinds, "Disable EQ" binds the sentinel, a missing-preset row is a no-op. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPresetDropdown(
        presetLayout: TextInputLayout,
        dropdown: MaterialAutoCompleteTextView,
        packageName: String,
        appLabel: String,
        getLastDismiss: () -> Long,
        setLastDismiss: (Long) -> Unit,
    ) {
        val knownNames = listCustomPresetNames()
        val binding = eqPrefs.getAppBinding(packageName)
        val isDisable = binding?.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED
        val currentSelection = when {
            binding == null -> "(none)"
            isDisable -> EqPreferencesManager.PRESET_DISABLED_LABEL
            else -> binding.presetName
        }
        val missing = binding != null && !isDisable && binding.presetName !in knownNames
        val entries = buildPresetEntries(if (missing) binding!!.presetName else null)

        dropdown.setText(
            if (missing) "${binding!!.presetName} (missing)" else currentSelection,
            false,
        )
        dropdown.setAdapter(PresetDropdownAdapter(this, entries))

        dropdown.setOnDismissListener { setLastDismiss(System.currentTimeMillis()) }
        presetLayout.setOnClickListener {
            if (System.currentTimeMillis() - getLastDismiss() < 300) {
                setLastDismiss(0L)
                return@setOnClickListener
            }
            if (dropdown.isPopupShowing) dropdown.dismissDropDown() else dropdown.showDropDown()
        }
        applyBoxOutlineRipple(presetLayout, dropdown)

        dropdown.setOnItemClickListener { _, _, pos, _ ->
            val pick = entries[pos].displayName
            when {
                pick == "(none)" -> {
                    eqPrefs.removeAppBinding(packageName)
                    notifyAppBindingChanged(packageName)
                    Toast.makeText(this, "Unbound $appLabel", Toast.LENGTH_SHORT).show()
                }
                pick == EqPreferencesManager.PRESET_DISABLED_LABEL -> {
                    eqPrefs.saveAppBinding(EqPreferencesManager.AppBinding(packageName, EqPreferencesManager.DEVICE_PRESET_DISABLED))
                    notifyAppBindingChanged(packageName)
                    Toast.makeText(this, "EQ disabled for $appLabel", Toast.LENGTH_SHORT).show()
                }
                pick.endsWith(" (missing)") -> { /* dangling */ }
                else -> {
                    eqPrefs.saveAppBinding(EqPreferencesManager.AppBinding(packageName, pick))
                    notifyAppBindingChanged(packageName)
                    Toast.makeText(this, "Bound \"$pick\" to $appLabel", Toast.LENGTH_SHORT).show()
                }
            }
            dropdown.clearFocus()
        }
    }

    /** Rebuild any per-session DP for [audioAppPackage] so a binding edit hits live audio (mirrors AudioOutputActivity.notifyBindingChanged). */
    private fun notifyAppBindingChanged(audioAppPackage: String) {
        sendBroadcast(
            Intent(com.bearinmind.equalizer314.audio.EqService.ACTION_REAPPLY_APP_BINDING)
                .setPackage(this.packageName)
                .putExtra(
                    com.bearinmind.equalizer314.audio.EqService.EXTRA_APP_PACKAGE,
                    audioAppPackage,
                )
        )
    }

    // ---- Helpers (mirrored from AudioOutputActivity) -------------------

    private fun listCustomPresetNames(): List<String> {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        return prefs.all
            .filter { (k, v) -> k.startsWith("preset_") && v is String }
            .keys
            .map { it.removePrefix("preset_") }
            .sorted()
    }

    private fun loadPresetJson(name: String): JSONObject? {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        val str = runCatching { prefs.getString("preset_$name", null) }
            .getOrNull() ?: return null
        return runCatching { JSONObject(str) }.getOrNull()
    }

    private fun buildPresetEntries(missingPresetName: String?): List<PresetDropdownAdapter.Entry> {
        val out = mutableListOf<PresetDropdownAdapter.Entry>()
        out.add(PresetDropdownAdapter.Entry("(none)", null))
        // "Disable EQ" = no per-app DP while this app plays, mirroring the device dropdown.
        out.add(PresetDropdownAdapter.Entry(EqPreferencesManager.PRESET_DISABLED_LABEL, null, isDisable = true))
        for (name in listCustomPresetNames()) {
            out.add(PresetDropdownAdapter.Entry(name, loadPresetJson(name)))
        }
        if (missingPresetName != null) {
            out.add(PresetDropdownAdapter.Entry("$missingPresetName (missing)", null))
        }
        return out
    }

    /** Runtime-sized ripple foreground on the TextInputLayout so it stays inside the outline (same as AudioOutputActivity). */
    private fun applyBoxOutlineRipple(layout: TextInputLayout, dropdown: android.view.View) {
        layout.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (dropdown.width <= 0 || dropdown.height <= 0 || layout.width <= 0) return true
                layout.viewTreeObserver.removeOnPreDrawListener(this)

                val rect = android.graphics.Rect(0, 0, dropdown.width, dropdown.height)
                layout.offsetDescendantRectToMyCoords(dropdown, rect)
                val cornerRadius = layout.boxCornerRadiusTopStart
                val highlightColor = MaterialColors.getColor(
                    layout,
                    com.google.android.material.R.attr.colorControlHighlight,
                )
                val mask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(android.graphics.Color.WHITE)
                }
                val ripple = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(highlightColor),
                    null,
                    mask,
                )
                layout.foreground = android.graphics.drawable.InsetDrawable(
                    ripple,
                    rect.left,
                    rect.top,
                    (layout.width - rect.right).coerceAtLeast(0),
                    (layout.height - rect.bottom).coerceAtLeast(0),
                )
                return true
            }
        })
    }

    companion object {
        private const val PREF_APPS_EXPANDED = "appsExpanded"
        /** Apps section open/close duration (Material "Emphasized" ≈500 ms). */
        private const val EXPAND_DURATION_MS = 500L
    }
}
