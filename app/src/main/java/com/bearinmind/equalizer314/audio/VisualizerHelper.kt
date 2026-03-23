package com.bearinmind.equalizer314.audio

import android.media.audiofx.Visualizer
import android.util.Log
import com.bearinmind.equalizer314.ui.EqGraphView

/**
 * Thin wrapper around Android's Visualizer API.
 *
 * KEY CHANGE: Captures WAVEFORM data instead of FFT data.
 *
 * Why: Visualizer.getFft() returns a pre-cooked 8-bit FFT with:
 *   - No windowing (causes spectral leakage = spiky look)
 *   - No control over normalization (absolute dB values are meaningless)
 *   - Interleaved byte format that's easy to parse wrong
 *
 * Visualizer.getWaveForm() returns raw 8-bit PCM that we feed into
 * our own pipeline: Hann window → zero-pad 1024→4096 → float FFT → dBFS.
 * Same 8-bit source, dramatically better display quality.
 */
class VisualizerHelper {

    companion object {
        private const val TAG = "VisualizerHelper"
    }

    private var visualizer: Visualizer? = null
    val renderer = SpectrumAnalyzerRenderer()
    var isRunning = false
        private set

    fun start(graphView: EqGraphView) {
        stop()

        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // max (typically 1024)

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 64) return
                        // Feed raw waveform into our own FFT pipeline
                        renderer.updateWaveformData(waveform)
                        graphView.postInvalidate()
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        // No longer used — we do our own FFT from waveform data
                    }
                },
                    Visualizer.getMaxCaptureRate(),
                    true,   // waveform = true  ← THE KEY CHANGE
                    false   // fft = false (we don't need it anymore)
                )

                enabled = true
            }
            isRunning = true
            Log.d(TAG, "Visualizer started (waveform mode), capture size: ${visualizer?.captureSize}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Visualizer", e)
            visualizer = null
            isRunning = false
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Visualizer", e)
        }
        visualizer = null
        isRunning = false
        renderer.release()
    }
}
