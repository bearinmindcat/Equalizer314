package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.google.android.material.card.MaterialCardView

/**
 * Drag-reorder screen for the audio-effects pipeline: visual order = intended processing order.
 * Each effect is an [EffectId]; the order persists in EqPreferencesManager for the chain executor
 * in EqService to read back. Left drag handle starts a drag; card body opens the effect's detail
 * screen (Environmental Reverb wiring: issue #4).
 */
class AudioEffectsPipelineActivity : AppCompatActivity() {

    enum class EffectId(
        val title: String,
        val description: String,
        val isFixed: Boolean = false,
        /** Supports the right-side on/off toggle. Channel Input / Audio Output are fixed bookends;
         *  DynamicsProcessing is the always-on main chain (controlled by the global Power FAB). */
        val canToggle: Boolean = true,
    ) {
        AUDIO_INPUT(
            "Channel Input",
            "System audio at session 0 — input to the effects chain",
            isFixed = true,
            canToggle = false
        ),
        DYNAMICS_PROCESSING(
            "Dynamics Processing",
            "Main audio processing chain for EQ, MBC & Limiting",
            canToggle = false
        ),
        ENVIRONMENTAL_REVERB(
            "Environmental Reverb",
            ""
        ),
        AUDIO_OUTPUT(
            "Audio Output",
            "Speakers, headphones, or other connected output",
            isFixed = true,
            canToggle = false
        ),
    }

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PipelineAdapter
    private val items = mutableListOf<EffectId>()
    private val enabledMap = HashMap<EffectId, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_effects_pipeline)
        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.audioPipelineBackButton).setOnClickListener { finish() }

        // Info button toggles the "how-to" card (same height animation as Apps/Devices sections).
        // Card sits between top bar and list, hidden by default in XML; icon brightens when visible, dims when hidden.
        val infoCard = findViewById<MaterialCardView>(R.id.audioPipelineInfoCard)
        val infoButton = findViewById<ImageButton>(R.id.audioPipelineInfoButton)
        infoButton.setOnClickListener {
            // Drive animation + tint off the predicted next state: toggleInfoCard changes visibility
            // itself (synchronously on expand, on animation-end on collapse), so reading the live value after the call is racy.
            val willBeVisible = infoCard.visibility != View.VISIBLE
            toggleInfoCard(infoCard)
            updateInfoButtonTint(infoButton, lit = willBeVisible)
        }

        recyclerView = findViewById(R.id.audioPipelineRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        loadOrder()

        // Hydrate enable state once; per-row binds read this map so toggles
        // are immediate and survive rebinds during drag without a pref hit.
        for (id in EffectId.values()) {
            enabledMap[id] = eqPrefs.isAudioEffectEnabled(id.name)
        }

        adapter = PipelineAdapter(
            items,
            isEnabled = { enabledMap[it] ?: false },
            onToggle = { effect ->
                val newState = !(enabledMap[effect] ?: false)
                enabledMap[effect] = newState
                eqPrefs.setAudioEffectEnabled(effect.name, newState)
                if (effect == EffectId.ENVIRONMENTAL_REVERB) {
                    // Let the service attach/release per-session reverbs to match the new toggle state
                    val intent = android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                        .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_APPLY_REVERB)
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } catch (_: Throwable) { /* service may not be running */ }
                }
            },
            onHandleTouch = { vh -> touchHelper.startDrag(vh) },
            onCardClick = { effect -> openDetailScreen(effect) },
            descriptionFor = { effect ->
                when (effect) {
                    EffectId.AUDIO_OUTPUT -> currentAudioOutputDescription() ?: effect.description
                    EffectId.AUDIO_INPUT -> currentChannelInputDescription()
                    else -> effect.description
                }
            },
            priorityFor = { effect -> currentPriorityFor(effect) },
        )
        recyclerView.adapter = adapter
        touchHelper.attachToRecyclerView(recyclerView)
        recyclerView.addItemDecoration(ConnectorDecoration(this))
    }

    private fun loadOrder() {
        items.clear()
        val draggable = mutableListOf<EffectId>()
        val saved = eqPrefs.getAudioEffectsOrder()
        if (saved != null) {
            for (name in saved) {
                val id = runCatching { EffectId.valueOf(name) }.getOrNull() ?: continue
                if (!id.isFixed && id !in draggable) draggable.add(id)
            }
            // Append any draggable IDs not in the saved order yet — keeps
            // older saved orders forward-compatible when new effects ship.
            for (id in EffectId.values()) {
                if (!id.isFixed && id !in draggable) draggable.add(id)
            }
        } else {
            for (id in EffectId.values()) if (!id.isFixed) draggable.add(id)
        }
        // Audio Source is always at the top, Audio Output always at the bottom.
        items.add(EffectId.AUDIO_INPUT)
        items.addAll(draggable)
        items.add(EffectId.AUDIO_OUTPUT)
    }

    private fun persistOrder() {
        // Only persist the draggable middle — fixed input/output bookends
        // are derived at load time so they can never be lost or reordered.
        eqPrefs.saveAudioEffectsOrder(items.filter { !it.isFixed }.map { it.name })
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false  // we drive drag from the handle
        override fun isItemViewSwipeEnabled() = false

        override fun getDragDirs(
            rv: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return 0
            // Audio Source / Audio Output are bookends; they can't be moved.
            return if (items[pos].isFixed) 0 else (ItemTouchHelper.UP or ItemTouchHelper.DOWN)
        }

        override fun onMove(
            rv: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder
        ): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
            // Don't let the user drag a draggable card into either fixed slot.
            if (items[a].isFixed || items[b].isFixed) return false
            val moved = items.removeAt(a)
            items.add(b, moved)
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            // Hold the handle in the pressed state during the drag so the
            // borderless circle ripple stays visible while the row moves.
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG &&
                viewHolder is PipelineAdapter.ViewHolder) {
                viewHolder.handle.isPressed = true
            }
        }

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            // Drag ended — release the press state so the ripple fades out.
            if (viewHolder is PipelineAdapter.ViewHolder) {
                viewHolder.handle.isPressed = false
            }
            persistOrder()
        }
    })

    private fun openDetailScreen(effect: EffectId) {
        when (effect) {
            EffectId.ENVIRONMENTAL_REVERB -> {
                startActivity(android.content.Intent(this, EnvironmentalReverbActivity::class.java))
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            EffectId.AUDIO_OUTPUT -> {
                startActivity(android.content.Intent(this, AudioOutputActivity::class.java))
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            EffectId.AUDIO_INPUT -> {
                startActivity(android.content.Intent(this, ChannelInputActivity::class.java))
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            else -> { /* detail screens for the other effects land later */ }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        // Re-bind the Audio Output + Channel Input cards so descriptions pick up changes made while
        // away (current routed device / current routing mode).
        if (::adapter.isInitialized) {
            val outPos = items.indexOf(EffectId.AUDIO_OUTPUT)
            if (outPos >= 0) adapter.notifyItemChanged(outPos)
            val inPos = items.indexOf(EffectId.AUDIO_INPUT)
            if (inPos >= 0) adapter.notifyItemChanged(inPos)
        }
    }

    /** Info button tint: bright when the card is shown (`colorOnSurface`), dim when hidden
     *  (`colorOnSurfaceVariant` — the app's secondary-glyph color, reads as "off" at a glance). */
    private fun updateInfoButtonTint(button: ImageButton, lit: Boolean) {
        val attr = if (lit)
            com.google.android.material.R.attr.colorOnSurface
        else
            com.google.android.material.R.attr.colorOnSurfaceVariant
        val tint = com.google.android.material.color.MaterialColors.getColor(button, attr)
        button.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    /** Height-based show/hide for the info card — same feel as the Apps/Devices collapsibles:
     *  500 ms FastOutSlowInInterpolator on `layoutParams.height` (deliberate slide, not a pop). */
    private fun toggleInfoCard(card: View) {
        val expand = card.visibility != View.VISIBLE
        val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
        val duration = 500L
        if (expand) {
            card.visibility = View.VISIBLE
            val parent = card.parent as View
            val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.measure(widthSpec, heightSpec)
            val target = card.measuredHeight
            android.animation.ValueAnimator.ofInt(0, target).apply {
                this.duration = duration
                interpolator = interp
                addUpdateListener {
                    card.layoutParams.height = it.animatedValue as Int
                    card.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        card.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        card.requestLayout()
                    }
                })
                start()
            }
        } else {
            val start = card.height
            android.animation.ValueAnimator.ofInt(start, 0).apply {
                this.duration = duration
                interpolator = interp
                addUpdateListener {
                    card.layoutParams.height = it.animatedValue as Int
                    card.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        card.visibility = View.GONE
                        card.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        card.requestLayout()
                    }
                })
                start()
            }
        }
    }

    /** User-facing label for the Channel Input card from the routing-mode pref (0 = System-wide,
     *  1 = Session-based); stays in sync with ChannelInputActivity's chips. */
    private fun currentChannelInputDescription(): String =
        when (eqPrefs.getAudioRoutingMode()) {
            1 -> "Session-based"
            else -> "System-wide"
        }

    /** Which scope drives the audio, for the priority pill. Hierarchy: 1. Session-based routing →
     *  Channel Input (per-app DPs handle audio); 2. System-wide + auto-switch ON → Audio Output
     *  (overwrites the global preset on route changes); 3. System-wide + auto-switch OFF →
     *  Channel Input (global session-0 mix is the only active scope). Null for every other effect → pill hidden. */
    private fun currentPriorityFor(effect: EffectId): CardPriority? {
        val sessionBased = eqPrefs.getAudioRoutingMode() == 1
        val autoSwitch = eqPrefs.getDeviceAutoSwitchEnabled()
        // Audio Output wins only in the narrow System-wide + auto-switch-ON window; every other
        // state hands Priority to Channel Input (global session-0 preset is the audio scope).
        val outputWins = !sessionBased && autoSwitch
        return when (effect) {
            EffectId.AUDIO_INPUT ->
                if (outputWins) CardPriority.NOT_PRIORITY else CardPriority.PRIORITY
            EffectId.AUDIO_OUTPUT ->
                if (outputWins) CardPriority.PRIORITY else CardPriority.NOT_PRIORITY
            else -> null
        }
    }

    /** Currently routed output via the Audio Output screen's priority rules
     *  (`DeviceIdentity.priority`), as "<device label> · <display key>". Null when no tracked
     *  output is connected — caller falls back to the static enum description. */
    private fun currentAudioOutputDescription(): String? {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return null
        var best: android.media.AudioDeviceInfo? = null
        var bestPriority = 0
        for (d in am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink) continue
            com.bearinmind.equalizer314.audio.DeviceIdentity.keyOf(d) ?: continue
            val p = com.bearinmind.equalizer314.audio.DeviceIdentity.priority(d)
            if (p > bestPriority) {
                bestPriority = p
                best = d
            }
        }
        val active = best ?: return null
        val label = com.bearinmind.equalizer314.audio.DeviceIdentity.labelOf(active)
        val key = com.bearinmind.equalizer314.audio.DeviceIdentity.keyOf(active) ?: return label
        val keyDisplay = com.bearinmind.equalizer314.audio.DeviceIdentity.displayKey(key)
        return if (keyDisplay.isNotEmpty()) "$label · $keyDisplay" else label
    }

    // ---- Adapter --------------------------------------------------------

    /** Which card is driving the audio. Only Channel Input and Audio Output participate; every
     *  other effect returns null from [PipelineAdapter.priorityFor] (no indicator). */
    private enum class CardPriority { PRIORITY, NOT_PRIORITY }

    private class PipelineAdapter(
        private val items: List<EffectId>,
        private val isEnabled: (EffectId) -> Boolean,
        private val onToggle: (EffectId) -> Unit,
        private val onHandleTouch: (RecyclerView.ViewHolder) -> Unit,
        private val onCardClick: (EffectId) -> Unit,
        /** Description override — Audio Output shows current device name + connection type instead
         *  of the static enum fallback. Defaults to the enum's `description`. */
        private val descriptionFor: (EffectId) -> String = { it.description },
        /** Priority indicator for the Channel Input / Audio Output cards (null elsewhere = no
         *  indicator); resolved by the activity from routing-mode + auto-switch. */
        private val priorityFor: (EffectId) -> CardPriority? = { null },
    ) : RecyclerView.Adapter<PipelineAdapter.ViewHolder>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density

            val card = MaterialCardView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
                radius = 16 * density
                cardElevation = 0f
                strokeWidth = 0
                val bgAttr = android.util.TypedValue()
                ctx.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorSurfaceContainerHigh, bgAttr, true
                )
                setCardBackgroundColor(bgAttr.data)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (16 * density).toInt(),
                           (16 * density).toInt(), (16 * density).toInt())
            }
            card.addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val rippleAttr = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, rippleAttr, true
            )
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_menu_handle)
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(), (36 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                setBackgroundResource(rippleAttr.resourceId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.drag_handle)
            }
            row.addView(handle)

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val title = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                val tc = android.util.TypedValue()
                ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tc, true)
                setTextColor(tc.data)
            }
            textCol.addView(title)
            val description = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                val tc = android.util.TypedValue()
                ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tc, true)
                setTextColor(tc.data)
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            textCol.addView(description)
            row.addView(textCol)

            // Pill sits OUTSIDE textCol as a row sibling, inheriting the row's CENTER_VERTICAL —
            // centered against the combined title+description height, not pinned to the title line.
            val pill = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                val padH = (12 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(padH, padV, padH, padV)
                gravity = android.view.Gravity.CENTER
                visibility = View.GONE
            }
            row.addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * density).toInt() })

            // Right-side power button — styled like the main Power FAB (12dp rounded square with
            // stroke); background set in paintPower since fill/stroke/icon tint flip with on/off state.
            val power = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_nav_power)
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(), (36 * density).toInt()
                ).apply { marginStart = (8 * density).toInt() }
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pp = (8 * density).toInt()
                setPadding(pp, pp, pp, pp)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.toggle_effect)
            }
            row.addView(power)

            return ViewHolder(card, title, description, handle, power, pill)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val effect = items[position]
            holder.title.text = effect.title
            val desc = descriptionFor(effect)
            holder.description.text = desc
            // Hide the second line entirely when an effect has no
            // description, so the title doesn't sit above an empty
            // grey gap.
            holder.description.visibility =
                if (desc.isEmpty()) View.GONE else View.VISIBLE
            if (effect.isFixed) {
                // Bookend cards (input / output) can't be moved — hide the
                // handle entirely so the row reads as a fixed pipeline node.
                holder.handle.visibility = View.GONE
                holder.handle.setOnTouchListener(null)
            } else {
                holder.handle.visibility = View.VISIBLE
                holder.handle.setOnTouchListener { v, ev ->
                    // Drive the press state ourselves so the ripple stays
                    // pinned at full alpha through ACTION_DOWN → drag-start
                    // → drag-end without flickering when ItemTouchHelper
                    // takes over the gesture.
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            onHandleTouch(holder)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                        }
                    }
                    false
                }
            }

            if (effect.canToggle) {
                holder.power.visibility = View.VISIBLE
                paintPower(holder.power, isEnabled(effect))
                holder.power.setOnClickListener {
                    onToggle(effect)
                    paintPower(holder.power, isEnabled(effect))
                }
            } else {
                holder.power.visibility = View.GONE
                holder.power.setOnClickListener(null)
            }

            // Tapping the card body (anywhere except the handle / power
            // button) opens that effect's detail screen.
            holder.itemView.setOnClickListener { onCardClick(effect) }
            (holder.itemView as? MaterialCardView)?.apply {
                isClickable = true
                isFocusable = true
            }

            paintPriority(holder, priorityFor(effect))
        }

        /** Renders the priority affordance on a card. The card stroke
         *  and left-edge stripe were considered earlier; both got
         *  pulled in favor of just the outline-only pill, which carries
         *  the same signal at a fraction of the visual noise. Cards
         *  that don't participate (every effect except Channel Input /
         *  Audio Output) pass [priority] = null and the pill collapses. */
        private fun paintPriority(holder: ViewHolder, priority: CardPriority?) {
            val ctx = holder.itemView.context
            val density = ctx.resources.displayMetrics.density

            if (priority == null) {
                holder.pill.visibility = View.GONE
                return
            }

            // Red for Not Priority stands out harder than yellow on
            // the dark surface — flagged scopes shout, current scope
            // affirms (green). Reuses the same Material Red 200 the
            // "Now playing" idle-speaker icon uses, so the palette
            // stays consistent.
            val green = androidx.core.content.ContextCompat.getColor(ctx, R.color.pulse_active_green)
            val red = androidx.core.content.ContextCompat.getColor(ctx, R.color.pulse_inactive_red)
            val active = priority == CardPriority.PRIORITY
            val accent = if (active) green else red

            // Outline-only pill, vertically centered against the card
            // row. Transparent fill with a colored stroke and matching
            // text color so the label reads against the card background.
            holder.pill.visibility = View.VISIBLE
            holder.pill.text = if (active) "Priority" else "Not Priority"
            holder.pill.setTextColor(accent)
            holder.pill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(0x00000000)
                setStroke((1.5f * density).toInt(), accent)
            }
        }

        private fun paintPower(view: ImageView, enabled: Boolean) {
            // Same 12dp dark rounded square on both states. When the effect
            // is on, the icon and stroke both light up white; when off,
            // both fall back to the muted gray. Color inversion is reserved
            // for the main DynamicsProcessing FAB on the home screen.
            val density = view.resources.displayMetrics.density
            val on = enabled
            val iconColor = if (on) 0xFFFFFFFF.toInt() else 0xFF555555.toInt()
            val strokeColor = if (on) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFF2A2A2A.toInt())
                setStroke((1 * density).toInt(), strokeColor)
            }
            val mask = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFFFFFFFF.toInt())
            }
            view.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt()), shape, mask
            )
            view.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
        }

        class ViewHolder(
            view: View,
            val title: TextView,
            val description: TextView,
            val handle: ImageView,
            val power: ImageView,
            val pill: TextView,
        ) : RecyclerView.ViewHolder(view)
    }

    /** Draws a flexible wire in the gap between consecutive cards. The
     *  wire bows laterally in proportion to the dragged card's vertical
     *  velocity (with exponential decay) so it lags the card's motion
     *  and springs back when the drag stops — same idea as a real cable
     *  swinging through the air. Anti-aliased cubic Bezier path. */
    private class ConnectorDecoration(context: android.content.Context) :
        RecyclerView.ItemDecoration() {

        private val density = context.resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
            )
            color = tv.data
            strokeWidth = 2 * density
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val path = android.graphics.Path()

        // Per-adapter-position bookkeeping for the spring-physics feel.
        // We track the previous translationY and an exponentially-decayed
        // velocity. When the user drops the card and translationY snaps to
        // 0, the velocity keeps decaying for a handful of frames so the
        // wire whips back into place instead of jumping to straight.
        private val lastTransY = HashMap<Int, Float>()
        private val velocity = HashMap<Int, Float>()

        // Tuning knobs — felt-good defaults from a quick session of swiping.
        private val decay = 0.82f
        private val bowPerVelocityPx: Float by lazy { 0.9f }
        private val maxBowPx: Float by lazy { 56f * density }
        private val velocityWakeThreshold = 0.15f  // px/frame before we sleep

        override fun onDraw(
            c: android.graphics.Canvas,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            var anyMoving = false
            for (i in 0 until parent.childCount - 1) {
                val a = parent.getChildAt(i)
                val b = parent.getChildAt(i + 1)
                val aPos = parent.getChildAdapterPosition(a)
                val bPos = parent.getChildAdapterPosition(b)
                if (aPos == RecyclerView.NO_POSITION || bPos == RecyclerView.NO_POSITION) continue
                if (bPos != aPos + 1) continue

                // Velocity = position delta + decayed prior velocity, so the
                // bend keeps moving for a few frames after the user lets go.
                val aT = a.translationY
                val bT = b.translationY
                val aDelta = aT - (lastTransY[aPos] ?: aT)
                val bDelta = bT - (lastTransY[bPos] ?: bT)
                val aV = aDelta + (velocity[aPos] ?: 0f) * decay
                val bV = bDelta + (velocity[bPos] ?: 0f) * decay
                lastTransY[aPos] = aT
                lastTransY[bPos] = bT
                velocity[aPos] = aV
                velocity[bPos] = bV

                // Average the two endpoints' velocity for the bow direction.
                // Sign flipped so a downward drag bows the wire to the left,
                // matching the natural "trailing cable" intuition.
                val avgV = (aV + bV) * 0.5f
                val bow = (-avgV * bowPerVelocityPx).coerceIn(-maxBowPx, maxBowPx)

                val cx = (a.left + a.right) / 2f
                val startY = a.bottom + a.translationY
                val endY = b.top + b.translationY
                if (endY <= startY) continue

                // Cubic Bezier with lateral-offset control points sitting at
                // 1/3 and 2/3 of the gap. When bow == 0 the curve collapses
                // to a clean straight vertical line.
                path.reset()
                path.moveTo(cx, startY)
                val gap = endY - startY
                path.cubicTo(
                    cx + bow, startY + gap * 0.33f,
                    cx + bow, startY + gap * 0.67f,
                    cx, endY
                )
                c.drawPath(path, paint)

                if (kotlin.math.abs(aV) > velocityWakeThreshold ||
                    kotlin.math.abs(bV) > velocityWakeThreshold) {
                    anyMoving = true
                }
            }
            // Force a redraw next frame while velocity is still non-trivial,
            // otherwise the spring-back wouldn't animate once the dragged
            // card stops translating.
            if (anyMoving) parent.postInvalidateOnAnimation()
        }
    }
}
