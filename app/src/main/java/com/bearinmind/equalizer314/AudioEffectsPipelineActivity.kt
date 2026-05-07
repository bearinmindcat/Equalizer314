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
 * Lets the user drag-reorder the audio-effects pipeline. The visual order
 * represents the intended processing order in the chain. Each effect is
 * one entry in [EffectId]; the order persists in EqPreferencesManager so
 * the (eventual) chain executor in EqService can read it back.
 *
 * For now the rows are display-only — tapping the body is a no-op. The
 * drag handle on the left starts a drag on touch. Issue #4 / EnvironmentalReverb
 * wiring lands in a follow-up commit.
 */
class AudioEffectsPipelineActivity : AppCompatActivity() {

    enum class EffectId(
        val title: String,
        val description: String,
        val isFixed: Boolean = false,
        /** Whether this effect supports the right-side on/off toggle.
         *  Channel Input / Audio Output are fixed bookends with nothing to
         *  toggle, and DynamicsProcessing is the main always-on chain
         *  (controlled by the global Power FAB on the main screen). */
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
            },
            onHandleTouch = { vh -> touchHelper.startDrag(vh) },
            onCardClick = { effect -> openDetailScreen(effect) }
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
            else -> { /* detail screens for the other effects land later */ }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ---- Adapter --------------------------------------------------------

    private class PipelineAdapter(
        private val items: List<EffectId>,
        private val isEnabled: (EffectId) -> Boolean,
        private val onToggle: (EffectId) -> Unit,
        private val onHandleTouch: (RecyclerView.ViewHolder) -> Unit,
        private val onCardClick: (EffectId) -> Unit,
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
                contentDescription = "Drag handle"
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
            val description = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                val tc = android.util.TypedValue()
                ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tc, true)
                setTextColor(tc.data)
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            textCol.addView(title)
            textCol.addView(description)
            row.addView(textCol)

            // Right-side power button — styled like the main Power FAB
            // (12dp rounded square with stroke). Background drawable is set
            // in onBindViewHolder/paintPower since fill, stroke, and icon
            // tint all flip between on/off states.
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
                contentDescription = "Toggle effect"
            }
            row.addView(power)

            return ViewHolder(card, title, description, handle, power)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val effect = items[position]
            holder.title.text = effect.title
            holder.description.text = effect.description
            // Hide the second line entirely when an effect has no
            // description, so the title doesn't sit above an empty
            // grey gap.
            holder.description.visibility =
                if (effect.description.isEmpty()) View.GONE else View.VISIBLE
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
