package com.bearinmind.equalizer314

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

class ChannelSideEqActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private var eqService: EqService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            eqService = (binder as EqService.EqBinder).service
            // Make sure the DSP picks up any changes made while the service
            // was not yet bound (e.g. quick slider drags during onCreate).
            pushChannelToDsp()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
        }
    }

    /** Copy the currently-stored channel prefs into the running DynamicsProcessing
     *  instance and re-apply the pre-EQ bands so the new per-channel offsets
     *  take effect. Safe to call when EQ is off — just becomes a no-op. */
    private fun pushChannelToDsp() {
        val dm = eqService?.dynamicsManager ?: return
        if (!dm.isActive) return
        dm.channelBalancePercent = eqPrefs.getChannelBalancePercent()
        dm.leftChannelGainDb = eqPrefs.getLeftChannelGainDb()
        dm.rightChannelGainDb = eqPrefs.getRightChannelGainDb()
        dm.updateChannelSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_side_eq)

        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.channelSideEqBackButton)
            .setOnClickListener { finish() }

        setupBalance()
        setupChannelPreamp()
        setupPerChannelEqToggle()

        // Bind to EqService so we can push channel changes into the live
        // DynamicsProcessing instance as the user interacts.
        bindService(android.content.Intent(this, EqService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setupChannelPreamp() {
        wireChannelGainRow(
            sliderId = R.id.leftGainSlider,
            inputId = R.id.leftGainInput,
            initial = eqPrefs.getLeftChannelGainDb(),
            save = { eqPrefs.saveLeftChannelGainDb(it) },
        )
        wireChannelGainRow(
            sliderId = R.id.rightGainSlider,
            inputId = R.id.rightGainInput,
            initial = eqPrefs.getRightChannelGainDb(),
            save = { eqPrefs.saveRightChannelGainDb(it) },
        )
    }

    /**
     * Bind a slider + text input pair so they represent the same dB value.
     * Slider drags update the input; pressing Done on the input updates the
     * slider. Double-tapping the slider resets to 0 dB. All changes are
     * forwarded to [save] so the preference is persisted.
     */
    private fun wireChannelGainRow(
        sliderId: Int,
        inputId: Int,
        initial: Float,
        save: (Float) -> Unit,
    ) {
        val slider = findViewById<Slider>(sliderId)
        val input = findViewById<TextInputEditText>(inputId)

        fun format(db: Float) = String.format("%.1f", db)

        var suppress = false
        val start = initial.coerceIn(-12f, 12f)
        suppress = true
        slider.value = start
        input.setText(format(start))
        suppress = false

        slider.addOnChangeListener { _, value, _ ->
            if (suppress) return@addOnChangeListener
            val rounded = Math.round(value * 10f) / 10f
            save(rounded)
            suppress = true
            if (input.text.toString() != format(rounded)) input.setText(format(rounded))
            suppress = false
            pushChannelToDsp()
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val parsed = input.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
                val rounded = Math.round(parsed * 10f) / 10f
                suppress = true
                slider.value = rounded
                input.setText(format(rounded))
                suppress = false
                save(rounded)
                pushChannelToDsp()
                true
            } else false
        }

        var justDoubleTapped = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                suppress = true
                slider.value = 0f
                input.setText(format(0f))
                suppress = false
                save(0f)
                pushChannelToDsp()
                justDoubleTapped = true
                return true
            }
        })
        slider.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (justDoubleTapped) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    justDoubleTapped = false
                }
                true  // swallow so Slider doesn't commit the tap position
            } else false
        }
    }

    private fun setupBalance() {
        val slider = findViewById<Slider>(R.id.balanceSlider)
        val leftPctText = findViewById<TextView>(R.id.leftPctText)
        val rightPctText = findViewById<TextView>(R.id.rightPctText)
        val follower = findViewById<TextView>(R.id.balancePctFollower)

        fun refreshChannelPcts(balancePct: Int) {
            // Both channels start at 50% when centered. Panning shifts one
            // channel up and the other down by half the slider value.
            // Full left (-100) → L 100%, R 0%. Full right (+100) → L 0%, R 100%.
            val leftPct = (50 - balancePct / 2).coerceIn(0, 100)
            val rightPct = (50 + balancePct / 2).coerceIn(0, 100)
            leftPctText.text = "$leftPct%"
            rightPctText.text = "$rightPct%"
        }

        fun updateFollower(balancePct: Int) {
            // Signed percent: negative = left, positive = right, zero = centered.
            follower.text = when {
                balancePct > 0 -> "+${balancePct}%"
                balancePct < 0 -> "${balancePct}%"
                else -> "0%"
            }
            val sliderWidth = slider.width
            if (sliderWidth <= 0) return
            val pad = slider.trackSidePadding
            val trackW = (sliderWidth - 2 * pad).coerceAtLeast(0)
            val fraction = (balancePct + 100) / 200f  // -100..100 → 0..1
            val thumbX = pad + fraction * trackW
            follower.translationX = thumbX - follower.width / 2f
        }

        val initial = eqPrefs.getChannelBalancePercent().coerceIn(-100, 100)
        slider.value = initial.toFloat()
        refreshChannelPcts(initial)

        slider.addOnChangeListener { _, value, _ ->
            val pct = value.toInt().coerceIn(-100, 100)
            eqPrefs.saveChannelBalancePercent(pct)
            refreshChannelPcts(pct)
            updateFollower(pct)
            pushChannelToDsp()
        }

        // Initial position — defer until the slider has been laid out so we
        // know its width and the follower has its measured width.
        slider.post {
            follower.post { updateFollower(eqPrefs.getChannelBalancePercent()) }
        }
        // Reposition on layout changes (e.g. rotation / width changes).
        slider.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            follower.post { updateFollower(slider.value.toInt()) }
        }

        // Double-tap anywhere on the slider (thumb or track) to reset to 0%.
        // We track whether a double-tap just fired, and if so we SWALLOW the
        // terminating ACTION_UP/CANCEL so the Slider's own tap-to-set code
        // doesn't overwrite our 0 back to the finger's x-position.
        var justDoubleTapped = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                slider.value = 0f
                eqPrefs.saveChannelBalancePercent(0)
                refreshChannelPcts(0)
                updateFollower(0)
                pushChannelToDsp()
                justDoubleTapped = true
                return true
            }
        })
        slider.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (justDoubleTapped) {
                when (event.action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        justDoubleTapped = false
                        return@setOnTouchListener true  // swallow the tap so Slider doesn't re-commit
                    }
                }
                true  // any other event in the same gesture — swallow too
            } else {
                false  // let the slider process drags normally
            }
        }
    }

    /** Per-channel EQ mode toggle. Stored only for now — the actual L/R
     *  EQ editor UI is a future feature. Reuses the existing
     *  channelSideEqEnabled pref so nothing changes at the storage layer. */
    private fun setupPerChannelEqToggle() {
        val switchView = findViewById<MaterialSwitch>(R.id.perChannelEqSwitch)
        switchView.isChecked = eqPrefs.getChannelSideEqEnabled()
        switchView.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveChannelSideEqEnabled(isChecked)
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
