package com.bearinmind.equalizer314

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.ReverbVisualizerView
import com.google.android.material.slider.Slider

/**
 * Slider-driven editor for the parameters of `android.media.audiofx.EnvironmentalReverb`.
 * Stores values into [EqPreferencesManager] only — the live `AudioEffect`
 * attach/detach to session 0 happens in EqService once we wire up the
 * pipeline. The on/off toggle for this effect lives on the pipeline
 * screen's per-card power button, so this screen is just the parameter
 * editor.
 */
class EnvironmentalReverbActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var visualizer: ReverbVisualizerView
    private var isUpdating = false

    // Slider/text refs cached so the visualizer's drag handles can push
    // matching slider positions and text values back through the UI.
    private lateinit var decayTimeSlider: Slider
    private lateinit var decayTimeText: EditText
    private lateinit var decayHfSlider: Slider
    private lateinit var decayHfText: EditText
    private lateinit var reverbLevelSlider: Slider
    private lateinit var reverbLevelText: EditText
    private lateinit var roomSlider: Slider
    private lateinit var roomText: EditText
    private lateinit var reflectDelaySlider: Slider
    private lateinit var reflectDelayText: EditText
    private lateinit var reflectLevelSlider: Slider
    private lateinit var reflectLevelText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_environmental_reverb)
        eqPrefs = EqPreferencesManager(this)
        visualizer = findViewById(R.id.reverbVisualizer)

        findViewById<ImageButton>(R.id.reverbBackButton).setOnClickListener { finish() }

        // Seed visualizer with persisted values so it renders correctly on
        // first frame (before any slider has fired onChange).
        visualizer.decayTimeMs = eqPrefs.getReverbDecayTimeMs()
        visualizer.decayHfRatio = eqPrefs.getReverbDecayHfRatio()
        visualizer.reverbLevelDb = eqPrefs.getReverbReverbLevelDb()
        visualizer.roomLevelDb = eqPrefs.getReverbRoomLevelDb()
        visualizer.reflectionsDelayMs = eqPrefs.getReverbReflectionsDelayMs()
        visualizer.reflectionsLevelDb = eqPrefs.getReverbReflectionsLevelDb()
        visualizer.diffusionPct = eqPrefs.getReverbDiffusionPct()
        visualizer.densityPct = eqPrefs.getReverbDensityPct()

        decayTimeSlider = findViewById(R.id.reverbDecayTimeSlider)
        decayTimeText = findViewById(R.id.reverbDecayTimeText)
        decayHfSlider = findViewById(R.id.reverbDecayHfSlider)
        decayHfText = findViewById(R.id.reverbDecayHfText)
        reverbLevelSlider = findViewById(R.id.reverbLevelSlider)
        reverbLevelText = findViewById(R.id.reverbLevelText)
        roomSlider = findViewById(R.id.reverbRoomSlider)
        roomText = findViewById(R.id.reverbRoomText)
        reflectDelaySlider = findViewById(R.id.reverbReflectDelaySlider)
        reflectDelayText = findViewById(R.id.reverbReflectDelayText)
        reflectLevelSlider = findViewById(R.id.reverbReflectLevelSlider)
        reflectLevelText = findViewById(R.id.reverbReflectLevelText)

        wire(
            decayTimeSlider, decayTimeText,
            "%.0f", eqPrefs.getReverbDecayTimeMs(), eqPrefs::saveReverbDecayTimeMs
        ) { visualizer.decayTimeMs = it }
        wire(
            decayHfSlider, decayHfText,
            "%.2f", eqPrefs.getReverbDecayHfRatio(), eqPrefs::saveReverbDecayHfRatio
        ) { visualizer.decayHfRatio = it }
        wire(
            reverbLevelSlider, reverbLevelText,
            "%.0f", eqPrefs.getReverbReverbLevelDb(), eqPrefs::saveReverbReverbLevelDb
        ) { visualizer.reverbLevelDb = it }
        wire(
            roomSlider, roomText,
            "%.0f", eqPrefs.getReverbRoomLevelDb(), eqPrefs::saveReverbRoomLevelDb
        ) { visualizer.roomLevelDb = it }
        wire(
            reflectDelaySlider, reflectDelayText,
            "%.0f", eqPrefs.getReverbReflectionsDelayMs(), eqPrefs::saveReverbReflectionsDelayMs
        ) { visualizer.reflectionsDelayMs = it }
        wire(
            reflectLevelSlider, reflectLevelText,
            "%.0f", eqPrefs.getReverbReflectionsLevelDb(), eqPrefs::saveReverbReflectionsLevelDb
        ) { visualizer.reflectionsLevelDb = it }
        wire(
            findViewById(R.id.reverbDiffusionSlider), findViewById(R.id.reverbDiffusionText),
            "%.0f", eqPrefs.getReverbDiffusionPct(), eqPrefs::saveReverbDiffusionPct
        ) { visualizer.diffusionPct = it }
        wire(
            findViewById(R.id.reverbDensitySlider), findViewById(R.id.reverbDensityText),
            "%.0f", eqPrefs.getReverbDensityPct(), eqPrefs::saveReverbDensityPct
        ) { visualizer.densityPct = it }

        // Drag handles on the visualizer route back here so the slider,
        // text input, and persisted value all stay in sync.
        visualizer.onParameterChanged = { param, value -> applyVisualizerChange(param, value) }
    }

    private fun applyVisualizerChange(
        param: ReverbVisualizerView.Param,
        value: Float,
    ) {
        when (param) {
            ReverbVisualizerView.Param.DECAY_TIME -> {
                eqPrefs.saveReverbDecayTimeMs(value)
                pushSlider(decayTimeSlider, decayTimeText, "%.0f", value)
            }
            ReverbVisualizerView.Param.DECAY_HF -> {
                eqPrefs.saveReverbDecayHfRatio(value)
                pushSlider(decayHfSlider, decayHfText, "%.2f", value)
            }
            ReverbVisualizerView.Param.REVERB_LEVEL -> {
                eqPrefs.saveReverbReverbLevelDb(value)
                pushSlider(reverbLevelSlider, reverbLevelText, "%.0f", value)
            }
            ReverbVisualizerView.Param.ROOM_LEVEL -> {
                eqPrefs.saveReverbRoomLevelDb(value)
                pushSlider(roomSlider, roomText, "%.0f", value)
            }
            ReverbVisualizerView.Param.REFLECTIONS_DELAY -> {
                eqPrefs.saveReverbReflectionsDelayMs(value)
                pushSlider(reflectDelaySlider, reflectDelayText, "%.0f", value)
            }
            ReverbVisualizerView.Param.REFLECTIONS_LEVEL -> {
                eqPrefs.saveReverbReflectionsLevelDb(value)
                pushSlider(reflectLevelSlider, reflectLevelText, "%.0f", value)
            }
        }
    }

    private fun pushSlider(slider: Slider, text: EditText, format: String, value: Float) {
        // isUpdating gates the slider's onChangeListener so we don't bounce
        // back into the visualizer from the slider's own callback.
        isUpdating = true
        slider.value = value.coerceIn(slider.valueFrom, slider.valueTo)
        text.setText(String.format(format, value))
        isUpdating = false
    }

    private fun wire(
        slider: Slider,
        text: EditText,
        format: String,
        initial: Float,
        save: (Float) -> Unit,
        onValueChanged: (Float) -> Unit,
    ) {
        isUpdating = true
        val clamped = initial.coerceIn(slider.valueFrom, slider.valueTo)
        slider.value = clamped
        text.setText(String.format(format, clamped))
        isUpdating = false

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdating) {
                text.setText(String.format(format, value))
                save(value)
                onValueChanged(value)
            }
        }
        text.setOnEditorActionListener { _, _, _ ->
            val v = text.text.toString().toFloatOrNull()
                ?.coerceIn(slider.valueFrom, slider.valueTo)
            if (v != null) {
                isUpdating = true
                slider.value = v
                text.setText(String.format(format, v))
                isUpdating = false
                save(v)
                onValueChanged(v)
            }
            true
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
