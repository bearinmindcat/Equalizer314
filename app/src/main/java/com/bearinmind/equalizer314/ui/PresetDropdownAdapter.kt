package com.bearinmind.equalizer314.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.bearinmind.equalizer314.R
import org.json.JSONObject

/**
 * Adapter used by every preset dropdown on the Audio Output screen.
 * Each row shows the preset name on the left and a small EQ-curve
 * preview ([PresetCurveView]) on the right. Sentinel rows like
 * `"(none)"` and `"<name> (missing)"` render with the empty grid.
 *
 * Extends [ArrayAdapter] so filtering inside
 * `MaterialAutoCompleteTextView` keeps working out of the box.
 */
class PresetDropdownAdapter(
    context: Context,
    private val entries: List<Entry>,
) : ArrayAdapter<String>(
    context,
    R.layout.item_preset_dropdown,
    R.id.presetRowName,
    entries.map { it.displayName },
) {

    /** One row of the dropdown.
     *  @param displayName what shows in the row's name TextView
     *  @param presetJson  full preset JSON used to render the curve
     *                     (includes `channelSideEqEnabled` etc. so CSE
     *                     presets stack L/R); null for `"(none)"` /
     *                     missing-preset rows. */
    data class Entry(val displayName: String, val presetJson: JSONObject?)

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.item_preset_dropdown, parent, false)
        val entry = entries[position]
        row.findViewById<TextView>(R.id.presetRowName).text = entry.displayName
        row.findViewById<PresetCurveView>(R.id.presetRowCurve).setPreset(entry.presetJson)
        return row
    }
}
