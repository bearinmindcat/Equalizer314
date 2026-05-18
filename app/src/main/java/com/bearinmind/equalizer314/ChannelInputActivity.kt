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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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

/**
 * Detail screen of the "Channel Input" pipeline card. Lists installed
 * apps so the user can assign saved EQ presets per app. When an app
 * later broadcasts `OPEN_AUDIO_EFFECT_CONTROL_SESSION`,
 * [com.bearinmind.equalizer314.audio.SessionEffectManager] looks up
 * the package's binding and applies the bound preset to its session.
 */
class ChannelInputActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var appsList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var appsAdapter: AppsAdapter

    // "Now playing" panel — always visible. Shows both broadcast-attached
    // and NLS-detected sessions.
    private lateinit var currentSessionSection: LinearLayout
    private lateinit var currentSessionList: RecyclerView
    private lateinit var currentSessionEmpty: TextView
    private lateinit var sessionsAdapter: ActiveSessionsAdapter

    // Persistent "Session detection" toggle card. Always visible — the
    // button label, body, and title change to reflect whether the user
    // currently has Notification access granted. Tapping the button
    // always opens system Settings (the only place Android lets the
    // user flip the listener bind).
    private lateinit var enableDetectionCard: MaterialCardView
    private lateinit var enableDetectionTitle: TextView
    private lateinit var enableDetectionBody: TextView
    private lateinit var enableDetectionButton: MaterialButton

    // Bound EqService so we can read the live set of attached sessions
    // (SessionEffectManager.getActiveSessions). Null when the service
    // isn't running — Session-based mode shows the empty card in that
    // case, which is the right thing because no sessions are attached.
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

    // Cycling-dots animation for the "Loading apps…" placeholder.
    // Tick every 400ms: "Loading apps ." → ". ." → ". . ." → loop.
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

        enableDetectionCard = findViewById(R.id.enableDetectionCard)
        enableDetectionTitle = findViewById(R.id.enableDetectionTitle)
        enableDetectionBody = findViewById(R.id.enableDetectionBody)
        enableDetectionButton = findViewById(R.id.enableDetectionButton)
        enableDetectionButton.setOnClickListener {
            // Both ON and OFF states route to the same system Settings
            // screen — Android doesn't allow third-party apps to flip
            // BIND_NOTIFICATION_LISTENER_SERVICE directly. The user
            // toggles it there; we re-sync the UI on the next onResume.
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not open Notification access settings", Toast.LENGTH_SHORT).show()
            }
        }

        setupRoutingModeChips()
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
        // Bind (do not start) — if the service isn't running, the
        // panel just shows its empty state.
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
        // User may have toggled the listener in Settings while we were
        // in the background — re-check on every return.
        refreshDetectionCtaVisibility()
    }

    /** The card stays visible in both states so the user can turn
     *  detection on AND off. The button label / body copy varies based
     *  on whether [NotificationManagerCompat.getEnabledListenerPackages]
     *  currently includes us. Both states open the system Settings
     *  screen — Android doesn't let third-party apps flip the listener
     *  bind directly. */
    private fun refreshDetectionCtaVisibility() {
        val granted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        enableDetectionCard.visibility = View.VISIBLE
        if (granted) {
            enableDetectionTitle.text = "Session detection is on"
            enableDetectionBody.text = "Apps currently playing audio appear under \"Now playing.\" Tap below to manage Notification access in system Settings."
            enableDetectionButton.text = "Manage in Settings"
        } else {
            enableDetectionTitle.text = "Session detection is off"
            enableDetectionBody.text = "To list YouTube, Netflix, Chrome, games etc., grant Notification access. We never read your notifications — this is the only Android API that exposes active media sessions to apps."
            enableDetectionButton.text = "Enable detection"
        }
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
            // Resolve icon + label on the fly — there will usually be
            // only one or two active sessions at a time, so the cost
            // is negligible and avoids holding a stale cache.
            val pm = packageManager
            val rows = sessions.map { s ->
                val (icon, label) = runCatching {
                    val info = pm.getApplicationInfo(s.packageName, 0)
                    pm.getApplicationIcon(info) to pm.getApplicationLabel(info).toString()
                }.getOrElse { null to s.packageName }
                SessionRow(s.sessionId, s.packageName, label, icon, s.presetName, s.source)
            }.sortedBy { it.label.lowercase() }
            sessionsAdapter.setItems(rows)
        }
    }

    private fun setupRoutingModeChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.routingModeChips)
        val global = findViewById<Chip>(R.id.routingModeGlobal)
        val perApp = findViewById<Chip>(R.id.routingModePerApp)
        // Map: 0 = System-wide, 1 = Session-based.
        // Any legacy "2" (was Both in the previous 3-mode setup) is
        // treated as System-wide on read so existing installs migrate.
        val mode = eqPrefs.getAudioRoutingMode()
        when (mode) {
            1 -> perApp.isChecked = true
            else -> global.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newMode = if (id == R.id.routingModePerApp) 1 else 0
            eqPrefs.saveAudioRoutingMode(newMode)
            // Tell the service to apply the new mode — if Session-
            // based was just selected, the service stops the global
            // DP so we don't end up double-EQing bound apps.
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

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun loadApps() {
        // PackageManager.getApplicationLabel + getApplicationIcon are
        // synchronous IPC calls that on a phone with 100+ apps add up
        // to several hundred milliseconds — enough to noticeably lag
        // the activity opening. Push the enumeration onto IO, then
        // hand the result to the adapter on Main.
        startLoadingAnimation()
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager

                // Filter to apps that would actually benefit from EQ —
                // same heuristics Wavelet / Poweramp EQ use to populate
                // their app pickers. An app is included if it matches
                // ANY of:
                //   (a) declares a MEDIA_BUTTON broadcast receiver
                //       (Spotify, Poweramp, AIMP, AutoEQ tooling, …)
                //   (b) declares a MediaBrowserService (the Android-
                //       Auto-compatible media-app contract)
                //   (c) handles audio/* MIME types (file-manager-style
                //       music players)
                //   (d) we've already seen it broadcast a session
                //       (catch-all for atypical apps — games etc. that
                //       still talk to the audio-effect-control system)
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

                val mediaCandidates =
                    mediaButtonApps + mediaBrowserApps + audioMimeApps + seen +
                        bindings.keys  // always show bound apps even if filters drop them

                pm.getInstalledApplications(0)
                    .filter { it.packageName in mediaCandidates }
                    .filter { it.packageName != packageName }
                    .map { info ->
                        val label = pm.getApplicationLabel(info).toString()
                        val priority = when {
                            bindings.containsKey(info.packageName) -> 0
                            seen.contains(info.packageName) -> 1
                            else -> 2
                        }
                        AppRow(
                            packageName = info.packageName,
                            label = label,
                            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                            priority = priority,
                        )
                    }
                    .sortedWith(compareBy({ it.priority }, { it.label.lowercase() }))
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
        val priority: Int,
    )

    private data class SessionRow(
        val sessionId: Int,
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val presetName: String?,
        val source: SessionEffectManager.AttachSource,
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
            val sourceBadge: ImageView = view.findViewById(R.id.sessionRowSourceBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_session_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.icon.setImageDrawable(r.icon)
            holder.name.text = r.label
            // BROADCAST = the app told us about its session via the
            // OPEN intent → we can attach effects authoritatively.
            // DETECTED = we recovered it from the audio dump → if the
            // dump didn't surface a sessionId (some OEMs lock the dump
            // down), we render the row with the marker -1 set by the
            // public-API fallback path.
            val hasRealSession = r.sessionId > 0
            val sourceTag = when (r.source) {
                SessionEffectManager.AttachSource.BROADCAST -> "Bound"
                SessionEffectManager.AttachSource.DETECTED ->
                    if (!hasRealSession) "Detected (no session)" else "Detected"
            }
            val preset = r.presetName?.let { "preset: $it" } ?: "no preset bound"
            // Real audioserver session IDs are always positive — only
            // surface them when we have one. Synthetic negative IDs
            // (assigned when audioserver's dumpsys is locked down) are
            // implementation detail and stay out of the UI.
            holder.meta.text = if (hasRealSession) {
                "${r.packageName} • session ${r.sessionId} • $sourceTag • $preset"
            } else {
                "${r.packageName} • $sourceTag • $preset"
            }
            holder.sourceBadge.setImageResource(
                when (r.source) {
                    SessionEffectManager.AttachSource.BROADCAST -> R.drawable.ic_volume_filled
                    SessionEffectManager.AttachSource.DETECTED -> R.drawable.ic_volume_outline
                }
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

            val knownNames = listCustomPresetNames()
            val binding = eqPrefs.getAppBinding(row.packageName)
            val currentSelection = binding?.presetName ?: "(none)"
            val missing = binding != null && binding.presetName !in knownNames
            val entries = buildPresetEntries(if (missing) binding!!.presetName else null)

            val dropdown = holder.dropdown
            dropdown.setText(
                if (missing) "${binding!!.presetName} (missing)" else currentSelection,
                false,
            )
            dropdown.setAdapter(PresetDropdownAdapter(this@ChannelInputActivity, entries))

            dropdown.setOnDismissListener {
                lastDismissAt = System.currentTimeMillis()
            }
            holder.presetLayout.setOnClickListener {
                if (System.currentTimeMillis() - lastDismissAt < 300) {
                    lastDismissAt = 0L
                    return@setOnClickListener
                }
                if (dropdown.isPopupShowing) dropdown.dismissDropDown() else dropdown.showDropDown()
            }
            applyBoxOutlineRipple(holder.presetLayout, dropdown)

            dropdown.setOnItemClickListener { _, _, pos, _ ->
                val pick = entries[pos].displayName
                when {
                    pick == "(none)" -> {
                        eqPrefs.removeAppBinding(row.packageName)
                        Toast.makeText(this@ChannelInputActivity, "Unbound ${row.label}", Toast.LENGTH_SHORT).show()
                    }
                    pick.endsWith(" (missing)") -> { /* dangling */ }
                    else -> {
                        eqPrefs.saveAppBinding(EqPreferencesManager.AppBinding(row.packageName, pick))
                        Toast.makeText(this@ChannelInputActivity, "Bound \"$pick\" to ${row.label}", Toast.LENGTH_SHORT).show()
                    }
                }
                dropdown.clearFocus()
            }
        }
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
        for (name in listCustomPresetNames()) {
            out.add(PresetDropdownAdapter.Entry(name, loadPresetJson(name)))
        }
        if (missingPresetName != null) {
            out.add(PresetDropdownAdapter.Entry("$missingPresetName (missing)", null))
        }
        return out
    }

    /** Same pattern as AudioOutputActivity — applies a runtime-sized
     *  ripple foreground to the TextInputLayout so the dropdown box's
     *  ripple stays inside the outline rectangle exactly. */
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
}
