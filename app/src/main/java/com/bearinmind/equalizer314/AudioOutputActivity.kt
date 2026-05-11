package com.bearinmind.equalizer314

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.bearinmind.equalizer314.audio.AudioRoutingMonitor
import com.bearinmind.equalizer314.audio.DeviceIdentity
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.PresetDropdownAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Detail screen of the "Audio Output" pipeline card. Single self-
 * contained surface for the per-device EQ binding feature: lists the
 * currently routed output, lists every device the app has seen, and
 * lets the user assign saved presets to each.
 */
class AudioOutputActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var devicesList: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var activeDeviceLabel: TextView
    private lateinit var currentlyRoutedCard: MaterialCardView
    private lateinit var devicesHeader: LinearLayout
    private lateinit var devicesBody: LinearLayout
    private lateinit var devicesChevron: TextView
    private lateinit var currentDeviceDropdown: MaterialAutoCompleteTextView
    private lateinit var currentDeviceDropdownLayout: TextInputLayout
    private var devicesExpanded = true

    private var eqService: EqService? = null
    private var serviceBound = false
    private var activeKey: String? = null
    private var activeLabel: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? EqService.EqBinder ?: return
            eqService = binder.service
            serviceBound = true
            refreshActiveDevice()
            // Subscribe to live route changes so the screen updates as
            // the user plugs / unplugs devices while it's open.
            binder.service.routingMonitor?.let { monitor ->
                val previous = monitor.onRouteChange
                monitor.onRouteChange = { change ->
                    previous?.invoke(change)
                    runOnUiThread {
                        activeKey = change.key
                        activeLabel = change.label
                        refreshActiveDevice()
                        refreshDevices()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_output)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.audioOutputBackButton).setOnClickListener { finish() }
        currentlyRoutedCard = findViewById(R.id.currentlyRoutedCard)
        activeDeviceLabel = findViewById(R.id.activeDeviceLabel)
        devicesList = findViewById(R.id.devicesList)
        emptyState = findViewById(R.id.devicesEmptyState)
        devicesHeader = findViewById(R.id.devicesHeader)
        devicesBody = findViewById(R.id.devicesBody)
        devicesChevron = findViewById(R.id.devicesChevron)
        currentDeviceDropdownLayout = findViewById(R.id.currentDevicePresetLayout)
        currentDeviceDropdown = findViewById(R.id.currentDevicePresetDropdown)

        // Restore the last expand/collapse choice; default expanded.
        devicesExpanded = getPreferences(MODE_PRIVATE).getBoolean(PREF_DEVICES_EXPANDED, true)
        applyDevicesExpanded(animate = false)
        devicesHeader.setOnClickListener {
            devicesExpanded = !devicesExpanded
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_DEVICES_EXPANDED, devicesExpanded).apply()
            applyDevicesExpanded(animate = true)
        }

        maybeRequestBluetoothPermission()
    }

    override fun onStart() {
        super.onStart()
        // Bind to EqService — the same pattern MainActivity uses — so we
        // can read the live routing monitor and react to changes.
        bindService(Intent(this, EqService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        // Standalone scan: when the EQ service isn't running (user
        // hasn't pressed power yet), the routing monitor isn't alive
        // either, so nothing has been remembered. Enumerate output
        // sinks ourselves so the list still populates with whatever's
        // currently connected.
        scanCurrentlyConnectedOutputs()
        refreshDevices()
    }

    private fun scanCurrentlyConnectedOutputs() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        for (d in am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink) continue
            val key = DeviceIdentity.keyOf(d) ?: continue
            eqPrefs.rememberSeenDevice(key, DeviceIdentity.labelOf(d))
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            eqService = null
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ---- Active device card --------------------------------------------

    private fun refreshActiveDevice() {
        val monitor = eqService?.routingMonitor
        val active = monitor?.pickActiveOutput()
        if (active == null) {
            activeKey = null
            activeLabel = null
            activeDeviceLabel.text = "No current device"
            currentDeviceDropdownLayout.visibility = View.GONE
            return
        }
        activeKey = DeviceIdentity.keyOf(active)
        activeLabel = DeviceIdentity.labelOf(active)
        // Same "<label> · <key>" format as the device rows so the
        // Current Device card reads identically to its row counterpart.
        val keyDisplay = activeKey?.trimEnd(':').orEmpty()
        activeDeviceLabel.text = if (keyDisplay.isNotEmpty() && activeLabel != null) {
            "$activeLabel · $keyDisplay"
        } else {
            activeLabel ?: "Current output"
        }
        val binding = activeKey?.let { eqPrefs.getDeviceBinding(it) }

        // Mirror of the per-row dropdown in the Devices list, but bound
        // to the active device. Picking here writes the same binding
        // entry the row dropdown would; both are kept in sync via
        // refreshActiveDevice + refreshDevices.
        val key = activeKey
        currentDeviceDropdownLayout.visibility = View.VISIBLE
        if (key == null) {
            currentDeviceDropdown.setOnItemClickListener(null)
            return
        }
        val knownNames = listCustomPresetNames()
        val currentSelection = binding?.presetName ?: "(none)"
        val missing = binding != null && binding.presetName !in knownNames
        val entries = buildPresetEntries(if (missing) binding!!.presetName else null)
        currentDeviceDropdown.setText(
            if (missing) "${binding!!.presetName} (missing)" else currentSelection,
            false,
        )
        currentDeviceDropdown.setAdapter(PresetDropdownAdapter(this, entries))
        currentDeviceDropdown.setOnItemClickListener { _, _, position, _ ->
            val pick = entries[position].displayName
            val label = activeLabel ?: ""
            when {
                pick == "(none)" -> {
                    eqPrefs.removeDeviceBinding(key)
                    Toast.makeText(this, "Unbound $label", Toast.LENGTH_SHORT).show()
                }
                pick.endsWith(" (missing)") -> {
                    // dangling — keep as-is
                }
                else -> {
                    eqPrefs.saveDeviceBinding(EqPreferencesManager.Binding(key, label, pick))
                    Toast.makeText(this, "Bound \"$pick\" to $label", Toast.LENGTH_SHORT).show()
                }
            }
            // Keep both views in sync — the active device also shows up
            // as a row in the Devices list.
            refreshActiveDevice()
            refreshDevices()
        }
    }

    // ---- Devices list --------------------------------------------------

    private fun refreshDevices() {
        devicesList.removeAllViews()
        val seen = eqPrefs.getAllSeenDevices().toMutableList()
        // Make sure the currently active device is in the list even if
        // it was never explicitly remembered (e.g. first launch).
        val activeKey = this.activeKey
        val activeLabel = this.activeLabel
        if (activeKey != null && activeLabel != null && seen.none { it.first == activeKey }) {
            seen.add(0, activeKey to activeLabel)
        }
        // Pin the active device to the top of the list.
        seen.sortByDescending { it.first == activeKey }

        if (seen.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            return
        }
        emptyState.visibility = View.GONE
        for ((key, label) in seen) {
            devicesList.addView(buildDeviceRow(key, label, isActive = key == activeKey))
        }
    }

    private fun buildDeviceRow(key: String, label: String, isActive: Boolean): View {
        // Inflate from XML so the dropdown picks up the same
        // `Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu`
        // style the Current Device card uses — guaranteeing pixel-identical
        // boxes between the two.
        val card = layoutInflater.inflate(R.layout.item_device_row, devicesList, false) as MaterialCardView

        // Combined "<label> · <key>" line, mirroring the Target-status
        // pattern in MainActivity.updateTargetStatus(). Trailing colons
        // on keys with no value (e.g. "SPEAKER:", "WIRED:") are stripped
        // so the result reads "Phone speaker · SPEAKER" not "...SPEAKER:".
        val keyDisplay = key.trimEnd(':')
        card.findViewById<TextView>(R.id.deviceRowName).text = "$label · $keyDisplay"
        card.findViewById<TextView>(R.id.deviceRowActiveBadge).apply {
            visibility = if (isActive) View.VISIBLE else View.GONE
            if (isActive) {
                val density = resources.displayMetrics.density
                val tc = android.util.TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tc, true)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setStroke((1 * density).toInt(), tc.data)
                }
            }
        }

        val knownNames = listCustomPresetNames()
        val binding = eqPrefs.getDeviceBinding(key)
        val currentSelection = binding?.presetName ?: "(none)"
        val missing = binding != null && binding.presetName !in knownNames
        val entries = buildPresetEntries(if (missing) binding!!.presetName else null)

        val dropdown = card.findViewById<MaterialAutoCompleteTextView>(R.id.deviceRowPresetDropdown)
        dropdown.setText(
            if (missing) "${binding!!.presetName} (missing)" else currentSelection,
            false,
        )
        dropdown.setAdapter(PresetDropdownAdapter(this, entries))

        dropdown.setOnItemClickListener { _, _, position, _ ->
            val pick = entries[position].displayName
            when {
                pick == "(none)" -> {
                    eqPrefs.removeDeviceBinding(key)
                    Toast.makeText(this, "Unbound $label", Toast.LENGTH_SHORT).show()
                }
                pick.endsWith(" (missing)") -> {
                    // Picked the dangling entry — keep the binding as-is, no change.
                }
                else -> {
                    eqPrefs.saveDeviceBinding(EqPreferencesManager.Binding(key, label, pick))
                    Toast.makeText(this, "Bound \"$pick\" to $label", Toast.LENGTH_SHORT).show()
                }
            }
            refreshActiveDevice()
        }

        // Long-press the row → "Forget device" option
        card.setOnLongClickListener {
            PopupMenu(this, card).apply {
                menu.add("Forget device")
                setOnMenuItemClickListener {
                    eqPrefs.forgetSeenDevice(key)
                    eqPrefs.removeDeviceBinding(key)
                    refreshDevices()
                    true
                }
                show()
            }
            true
        }

        return card
    }

    // ---- Helpers -------------------------------------------------------

    private fun listCustomPresetNames(): List<String> {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        // Only keys whose value is a String are real presets — MainActivity
        // also stores a `preset_names` StringSet bookkeeping key in this
        // same prefs file, which would otherwise be parsed as a preset
        // called "names" and crash with ClassCastException on getString().
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

    /** Builds the entries used by every preset dropdown on this screen.
     *  Order: `"(none)"`, every custom preset name (with full preset
     *  JSON so the curve preview can detect CSE), then optionally a
     *  sentinel for a dangling/missing binding. */
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

    private fun maybeRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) return
        // Fire-and-forget, like the notification permission flow. We
        // don't gate the UI on this — without it, BT identity falls back
        // to product name only.
        requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_CONNECT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            if (grantResults.isNotEmpty()
                && grantResults[0] != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Bluetooth identification will use device name only — two of the same model collide.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            refreshDevices()
        }
    }

    /** Mirror of the AppDrawer launcher's collapsible-section feel:
     *  250 ms expand, 200 ms collapse, chevron rotates 0 → 90° via
     *  property animator. The View-system equivalent of Compose's
     *  AnimatedVisibility is `TransitionManager.beginDelayedTransition`
     *  with `AutoTransition` (which combines fade + bounds). */
    private fun applyDevicesExpanded(animate: Boolean) {
        if (animate) {
            val parent = devicesBody.parent as ViewGroup
            TransitionManager.beginDelayedTransition(
                parent,
                AutoTransition().apply {
                    duration = if (devicesExpanded) 250L else 200L
                },
            )
        }
        devicesBody.visibility = if (devicesExpanded) View.VISIBLE else View.GONE
        val targetRotation = if (devicesExpanded) 90f else 0f
        if (animate) {
            devicesChevron.animate().rotation(targetRotation).setDuration(200).start()
        } else {
            devicesChevron.rotation = targetRotation
        }
    }

    companion object {
        private const val REQ_BT_CONNECT = 300
        private const val PREF_DEVICES_EXPANDED = "devicesExpanded"
    }
}
