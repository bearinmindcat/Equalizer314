package com.bearinmind.equalizer314.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * A [ScrollView] that shows exactly ONE content row and scrolls the rest, so it
 * occupies the same footprint as a single band-toggle row. Used for the
 * experimental "Add more EQ bands" extra rows (issue #31): bands 9+ live here
 * and scroll within this one-row-tall strip instead of pushing the page.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Measure normally first so the content (and its rows) get sized.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Then clamp our visible height to exactly the first row's height — no
        // dp guessing, no post-layout timing races; the extra rows scroll.
        val content = getChildAt(0) as? ViewGroup ?: return
        val firstRow = content.getChildAt(0) ?: return
        val oneRow = firstRow.measuredHeight
        if (oneRow in 1 until measuredHeight) {
            setMeasuredDimension(measuredWidth, oneRow)
        }
    }

    // This view lives inside the page's outer ScrollView, and its rows are full
    // of clickable band buttons. A drag that STARTS on a button means our
    // onTouchEvent never sees the DOWN, so claim the gesture here — this runs
    // before children get the touch. On DOWN, if we have something to scroll,
    // tell ancestors not to intercept so only these rows scroll.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            (canScrollVertically(-1) || canScrollVertically(1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                if (canScrollVertically(-1) || canScrollVertically(1)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }
}
