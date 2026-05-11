package com.bearinmind.equalizer314.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * `MaterialAutoCompleteTextView` variant that doesn't show the text
 * insertion handle on tap.
 *
 * The default subclass extends `EditText`, so on `ACTION_DOWN` it runs
 * the text-selection / cursor-placement logic which briefly fades in
 * the insertion-handle teardrop near the touched character. Setting
 * `cursorVisible="false"`, `textIsSelectable="false"`, etc. suppresses
 * this on stock Android, but Samsung One UI's customised `Editor` still
 * draws the handle for a frame or two before checking the flag.
 *
 * We bypass `EditText.onTouchEvent` entirely. `ACTION_UP` just calls
 * `performClick()` — which is what the `ExposedDropdownMenu` style
 * listens for internally to open / dismiss the popup — so the dropdown
 * still toggles, the popup-item click handler set by the activity
 * still fires, but the handle animation never starts.
 */
class DropdownAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    // Don't call super (which runs EditText's insertion-handle code) AND
    // don't consume the touch — return false so the gesture bubbles up
    // to the parent TextInputLayout. The parent owns the ripple
    // foreground + the showDropDown() click handler; it animates ripple
    // and opens the popup using standard View touch processing, so we
    // never have to manage pressed-state by hand.
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
