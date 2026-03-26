# Android Audio Processing Approaches

Reference document for implementing audio DSP on Android — comparing DynamicsProcessing, REMOTE_SUBMIX, and other approaches. Written for future use in a mobile DAW app.

---

## 1. DynamicsProcessing API (Android 9+)

**How it works:** System-level audio effect that attaches to an audio session. Processes audio in the HAL (hardware abstraction layer) pipeline. No audio capture needed.

**Components:**
- Pre-EQ: Multi-band gain stage (FFT-based, up to 128 bands on Samsung)
- MBC: Multiband compressor with Linkwitz-Riley crossovers, per-band threshold/ratio/attack/release/knee/gate
- Post-EQ: Same as Pre-EQ but after compression
- Limiter: Global clipping protection

**Strengths:**
- Zero latency — processes inline in the audio pipeline
- No permissions beyond MODIFY_AUDIO_SETTINGS
- System-wide via session ID 0
- Built into Android — no native code or JNI needed
- MBC has real crossover filters (Linkwitz-Riley) with proper frequency blocking

**Limitations:**
- Pre/Post-EQ is a gain stage, not a true filter — applies gain values at fixed FFT bins
- LPF/HPF rolloff limited by band resolution (~24 dB max with 128 bands for a single biquad)
- 128 band limit on Samsung devices (AOSP has no limit, device-specific)
- Session ID 0 (global) is deprecated — may break on future Android versions
- Can only have one DynamicsProcessing per session — conflicts with other EQ apps
- Priority parameter determines which app wins when competing for the same session
- Channel count must be set to 2 for stereo (easy to miss — causes mono-only processing)

**Best for:** System-wide EQ, multiband compression, limiting. Good enough for bell/shelf curves. Not ideal for steep pass filters.

**Key code pattern:**
```kotlin
val config = DynamicsProcessing.Config.Builder(
    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
    2,          // channel count (MUST be 2 for stereo!)
    true,       // pre-EQ enabled
    128,        // pre-EQ band count
    true,       // MBC enabled
    3,          // MBC band count
    false,      // post-EQ disabled
    0,
    true        // limiter enabled
).build()

val dp = DynamicsProcessing(Int.MAX_VALUE, 0, config) // priority, session
dp.enabled = true

// Apply EQ bands to BOTH channels
for (i in 0 until bandCount) {
    val band = DynamicsProcessing.EqBand(true, cutoffFreq[i], gainDb[i])
    dp.setPreEqBandByChannelIndex(0, i, band) // left
    dp.setPreEqBandByChannelIndex(1, i, band) // right
}
```

---

## 2. REMOTE_SUBMIX Capture (Android 10+, Rootless on 13+)

**How it works:** Creates a virtual audio device, routes system audio through it, captures via AudioRecord, processes in software, plays back via AudioTrack. This is how RootlessJamesDSP works.

**Signal flow:**
```
System Audio → REMOTE_SUBMIX → AudioRecord → Your DSP Code → AudioTrack → Output
```

**Strengths:**
- Full control over audio processing — real IIR biquad filters, convolution, any DSP algorithm
- True precision filtering — -80 dB, -120 dB, whatever you compute
- No band count limits — process every sample individually
- Can implement any filter: parametric EQ, graphic EQ, crossfeed, reverb, convolution
- Works alongside other audio effects (doesn't conflict like DynamicsProcessing)

**Limitations:**
- 10-20ms latency (capture → process → playback buffer)
- Requires RECORD_AUDIO permission
- Android 13+ for rootless operation via MediaProjection
- User must grant screen/audio capture permission (notification shown)
- Battery impact — continuous audio processing in your app's process
- Can interfere with other apps doing the same thing
- Audio routing complexity — need to handle headphones, Bluetooth, speaker switches

**Best for:** DAW apps, precision EQ, convolution reverb, any DSP that needs sample-level accuracy.

**Key code pattern:**
```kotlin
// Request media projection
val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

// Configure audio capture
val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingUsage(AudioAttributes.USAGE_GAME)
    .build()

val audioRecord = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
    .setAudioFormat(AudioFormat.Builder()
        .setSampleRate(48000)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .build())
    .build()

val audioTrack = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build())
    .setAudioFormat(AudioFormat.Builder()
        .setSampleRate(48000)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .build())
    .setBufferSizeInBytes(bufferSize)
    .build()

// Processing loop
audioRecord.startRecording()
audioTrack.play()
while (running) {
    val read = audioRecord.read(buffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
    // Apply your DSP here — real biquad filters, convolution, etc.
    applyBiquadFilter(buffer, read)
    audioTrack.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
}
```

---

## 3. Viper4Android (Root Required)

**How it works:** Installs a custom audio effect library (.so) into the system's audio effects directory. Replaces or augments the audio HAL's processing pipeline.

**Strengths:**
- Zero latency — runs in the kernel audio pipeline
- True IIR filters with full precision
- Most powerful option — convolution, crossfeed, analog modeling
- No audio capture/routing needed

**Limitations:**
- Requires root access (Magisk module)
- Not available on most modern locked-bootloader devices
- Can break with Android updates
- Security risk — modifying system libraries
- Not distributable on Play Store

**Best for:** Power users with rooted devices who want maximum audio quality.

---

## 4. Android Equalizer API (Legacy)

**How it works:** Built-in Android AudioEffect with 5-10 fixed bands. Very limited.

**Strengths:**
- Simple API, works on all Android versions
- Zero latency

**Limitations:**
- Fixed number of bands (usually 5)
- Fixed frequencies — can't choose where bands are
- Very limited gain range
- No compression, limiting, or pass filters

**Best for:** Simple tone controls. Not suitable for serious EQ work.

---

## 5. Oboe/AAudio + Custom DSP (For DAW Apps)

**How it works:** Low-latency audio I/O library from Google. You provide a callback that processes audio samples. Combined with your own DSP code for real-time processing.

**Strengths:**
- Lowest possible latency (< 10ms on supported devices)
- Full control over DSP — implement anything
- Professional-grade audio quality
- Google-supported, actively maintained
- Works with MIDI for DAW applications

**Limitations:**
- Only processes audio from your app (not system-wide)
- Must implement all DSP from scratch (or use a library)
- C++ required for the audio callback (JNI bridge for Kotlin)
- More complex architecture

**Best for:** DAW apps, synthesizers, audio recording, real-time effects on app-generated audio.

**Key code pattern (C++):**
```cpp
class MyCallback : public oboe::AudioStreamCallback {
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *stream,
        void *audioData,
        int32_t numFrames) override {

        float *buffer = static_cast<float*>(audioData);
        // Apply your DSP here
        for (int i = 0; i < numFrames * 2; i++) {
            buffer[i] = processWithBiquad(buffer[i]);
        }
        return oboe::DataCallbackResult::Continue;
    }
};
```

---

## Comparison Matrix

| Feature | DynamicsProcessing | REMOTE_SUBMIX | Viper4Android | Oboe/AAudio |
|---|---|---|---|---|
| System-wide | ✅ | ✅ | ✅ | ❌ (app only) |
| Latency | Zero | 10-20ms | Zero | < 10ms |
| Filter precision | Limited | Unlimited | Unlimited | Unlimited |
| Root required | No | No | Yes | No |
| Min Android | 9 | 13 (rootless) | Any (rooted) | 8 |
| MBC/Limiter | Built-in | DIY | Built-in | DIY |
| Battery impact | Minimal | Moderate | Minimal | Low |
| Play Store OK | ✅ | ✅ | ❌ | ✅ |
| Conflicts with others | Yes (same session) | Possible | No | No |

---

## Recommendations for a Mobile DAW App

1. **Use Oboe/AAudio** for the core audio engine — lowest latency, full DSP control
2. **Implement biquad filters in C++** for the EQ — cascadable for steep slopes
3. **Use DynamicsProcessing** only if you need system-wide processing as an optional feature
4. **Consider REMOTE_SUBMIX** for a "system EQ" feature within the DAW — lets users apply DAW effects to any audio source
5. **Implement your own MBC** using cascaded biquad crossover filters + per-band compressor math (Giannoulis soft-knee) — gives you full control over band count, crossover order, and compression behavior without DynamicsProcessing's limitations

---

## References

- [Android DynamicsProcessing API](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
- [AOSP DynamicsProcessing source](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/)
- [Google Research — DynamicsProcessing paper](https://research.google/pubs/pub47502/)
- [RootlessJamesDSP source](https://github.com/timschneeberger/RootlessJamesDSP)
- [Oboe audio library](https://github.com/google/oboe)
- [Giannoulis/Massberg/Reiss 2012 — Dynamic Range Compression](https://www.eecs.qmul.ac.uk/~josh/documents/2012/GiannoulisMassbergReiss-dynamicrangecompression-JAES2012.pdf)
- [RBJ Audio EQ Cookbook](https://www.w3.org/2011/audio/audio-eq-cookbook.html)
- [FabFilter Pro-MB Manual](https://www.fabfilter.com/help/pro-mb/)
