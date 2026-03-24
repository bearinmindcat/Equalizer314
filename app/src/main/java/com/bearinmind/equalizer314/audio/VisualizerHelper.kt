package com.bearinmind.equalizer314.audio

import android.media.audiofx.Visualizer
import android.util.Log
import com.bearinmind.equalizer314.ui.EqGraphView

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
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS

                // Silence detection state
                var silentFrames = 0

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 64 || v == null) return

                        // Use 16-bit peak/RMS measurement for reliable silence detection
                        // This is unaffected by SCALING_MODE_NORMALIZED
                        val measurement = Visualizer.MeasurementPeakRms()
                        v.getMeasurementPeakRms(measurement)
                        val isSilent = measurement.mPeak < -6000 // -60 dBFS

                        if (isSilent) {
                            silentFrames++
                            // Feed zeros into the smoother so the EMA naturally decays
                            renderer.feedSilence()
                            // Fade opacity: ramp down over ~12 frames (~0.6s at 20Hz)
                            renderer.fadeOut(0.08f)
                        } else {
                            silentFrames = 0
                            renderer.updateWaveformData(waveform)
                        }
                        graphView.postInvalidate()
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) { }
                },
                    Visualizer.getMaxCaptureRate(),
                    true, false
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
