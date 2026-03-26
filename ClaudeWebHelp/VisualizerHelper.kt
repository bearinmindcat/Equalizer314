package com.bearinmind.equalizer314.audio

import android.media.audiofx.Visualizer
import android.util.Log
import com.bearinmind.equalizer314.ui.EqGraphView

/**
 * Thin wrapper around Android's Visualizer API.
 * Captures FFT data and feeds it to SpectrumAnalyzerRenderer.
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
                captureSize = Visualizer.getCaptureSizeRange()[1]

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 4) return
                        renderer.updateFftData(fft)
                        graphView.postInvalidate()
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)

                enabled = true
            }
            isRunning = true
            Log.d(TAG, "Visualizer started, capture size: ${visualizer?.captureSize}")
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
