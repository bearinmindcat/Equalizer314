package com.bearinmind.equalizer314.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
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

    // Playback state detection (no permissions needed, API 26+)
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    @Volatile
    private var isMusicPlaying = true  // assume playing until told otherwise
    private var graphViewRef: EqGraphView? = null

    fun start(graphView: EqGraphView) {
        stop()
        graphViewRef = graphView

        // Register AudioPlaybackCallback to detect play/pause (works on speaker AND Bluetooth)
        val context = graphView.context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isMusicPlaying = audioManager?.isMusicActive() ?: true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    val wasPlaying = isMusicPlaying
                    // isMusicActive() is the simplest reliable check
                    isMusicPlaying = audioManager?.isMusicActive() ?: false
                    // If just stopped playing, start fading
                    if (wasPlaying && !isMusicPlaying) {
                        Log.d(TAG, "Playback stopped — fading spectrum")
                    }
                    // If just started playing, ensure opacity is restored
                    if (!wasPlaying && isMusicPlaying) {
                        renderer.resetOpacity()
                        Log.d(TAG, "Playback started — showing spectrum")
                    }
                }
            }
            audioManager?.registerAudioPlaybackCallback(playbackCallback!!, null)
        }

        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 64) return

                        if (isMusicPlaying) {
                            // Audio is playing — feed real data
                            renderer.updateWaveformData(waveform)
                        } else {
                            // Audio is paused/stopped — decay and fade
                            renderer.feedSilence()
                            renderer.fadeOut(0.04f)  // slower fade (~25 frames / ~1.2s)
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
        // Unregister playback callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        }
        playbackCallback = null
        audioManager = null
        graphViewRef = null

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
