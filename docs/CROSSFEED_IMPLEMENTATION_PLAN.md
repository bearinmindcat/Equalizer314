# Crossfeed Implementation Plan

## What is Crossfeed?
Crossfeed mixes a portion of the left channel into the right ear (and vice versa) with a slight time delay, simulating the natural sound of speakers when listening through headphones. It reduces stereo fatigue caused by the exaggerated channel separation in headphones.

### How It Works Technically
- In natural speaker listening, sound from the left speaker reaches both ears — the right ear receives it ~0.3ms later with some high-frequency rolloff
- Headphones eliminate this natural crossfeed entirely
- A crossfeed processor adds this back: mix a filtered/delayed copy of L into R and vice versa
- The BS2B algorithm (used in camilladsp-crossfeed) is the industry standard implementation

### Reference Implementation
- **Repository**: [Wang-Yue/camilladsp-crossfeed](https://github.com/Wang-Yue/camilladsp-crossfeed)
- **Algorithm**: BS2B (Bauer stereophonic-to-binaural)
- **Presets**: 5 intensity levels from subtle (-13.5 dB @ 650 Hz) to strong (-3 dB @ 700 Hz)

---

## Current App Architecture

### What We Have
- **DynamicsProcessing API** (Android API 28+) for system-wide EQ
- Session 0 (system-wide audio processing)
- `BiquadFilter.kt` — already has `processStereoInPlace()` with independent L/R filter state
- `ParametricEqualizer.kt` — handles interleaved stereo correctly

### Why Crossfeed Can't Use DynamicsProcessing
DynamicsProcessing processes channels **independently** — all parameters are per-channel with zero inter-channel mixing. Crossfeed requires mixing audio between L and R channels, which the API doesn't support.

```
Current pipeline (no mixing possible):
Channel 0 (L) → [Pre-EQ] → [Limiter] → Output L
Channel 1 (R) → [Pre-EQ] → [Limiter] → Output R
              (NO MIXING BETWEEN CHANNELS)
```

---

## Implementation Approach: REMOTE_SUBMIX Capture

This is the same approach used by **RootlessJamesDSP** for system-wide audio processing.

### Pipeline
```
System Audio → AudioPlaybackCapture → AudioRecord
    → Custom DSP (Crossfeed + EQ + Limiter)
    → AudioTrack → Output
```

### Requirements
- **Android 13+** for rootless operation (`AudioPlaybackCaptureConfiguration`)
- **RECORD_AUDIO** permission
- **MediaProjection** permission (user must approve a "Start recording?" dialog once per session)
- **MODIFY_AUDIO_SETTINGS** permission (already have this)

### Pros
- True system-wide crossfeed with BS2B-style delay compensation
- Full DSP control — can implement any effect
- Future-proof for adding more effects (reverb, spatial audio, etc.)
- Existing `BiquadFilter` code is stereo-ready

### Cons
- 10-20ms latency (acceptable for music, not for gaming)
- Requires screen/audio capture permission dialog per session
- 5-10% battery impact from real-time DSP
- Android 13+ only (API 33)
- Significant architectural addition — new audio pipeline alongside existing DynamicsProcessing

---

## Implementation Steps

### Phase 1: Audio Capture Pipeline
1. Create `AudioCaptureService` — foreground service with MediaProjection
2. Set up `AudioRecord` with `AudioPlaybackCaptureConfiguration`
3. Set up `AudioTrack` for output
4. Implement capture → process → playback loop with ring buffer
5. Handle permissions (MediaProjection approval dialog)

### Phase 2: Crossfeed DSP
1. Implement BS2B crossfeed algorithm in Kotlin:
   - Crossfeed filter (lowpass on the cross-mixed signal)
   - Inter-channel delay (~0.3ms at 48kHz = ~14 samples)
   - Mixing matrix with configurable intensity
2. Create `CrossfeedProcessor` class with:
   - `fun process(buffer: FloatArray)` — processes interleaved stereo in-place
   - Intensity presets (5 levels matching camilladsp-crossfeed)
   - Bypass toggle

### Phase 3: Integration
1. Chain: Crossfeed → Parametric EQ → Limiter (all in custom DSP)
2. Option to keep DynamicsProcessing for basic EQ OR switch entirely to custom pipeline
3. Detect headphone vs speaker output — only apply crossfeed on headphones

### Phase 4: UI
1. Crossfeed card in Settings page
2. Crossfeed activity with:
   - On/off toggle
   - Intensity slider or 5 preset buttons
   - Headphone-only auto-enable option
   - Visual showing L→R and R→L mixing amount

---

## Existing Code That Can Be Reused

| File | What to Reuse |
|------|--------------|
| `BiquadFilter.kt` | `processStereoInPlace()` for the crossfeed filter |
| `ParametricEqualizer.kt` | Full EQ chain (already stereo-ready) |
| `EqService.kt` | Foreground service pattern for the capture service |
| `DynamicsProcessingManager.kt` | Limiter logic (can be reimplemented in custom DSP) |
| `EqPreferencesManager.kt` | Settings persistence pattern |
| `MbcActivity.kt` | UI pattern for the crossfeed settings screen |

---

## Effort Estimate
- **Phase 1** (Audio Pipeline): Major — this is the core architectural work
- **Phase 2** (Crossfeed DSP): Medium — BS2B algorithm is well-documented
- **Phase 3** (Integration): Medium — wiring up the pipeline
- **Phase 4** (UI): Small — straightforward settings screen

**Total**: Multi-session effort. The audio capture pipeline is the biggest piece.

---

## References
- [BS2B Library](http://bs2b.sourceforge.net/) — original C implementation
- [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP) — Android app using REMOTE_SUBMIX
- [Wang-Yue/camilladsp-crossfeed](https://github.com/Wang-Yue/camilladsp-crossfeed) — CamillaDSP crossfeed configs
- [Android AudioPlaybackCapture](https://developer.android.com/guide/topics/media/playback-capture)
- [Crossfeed — Wikipedia](https://en.wikipedia.org/wiki/Crossfeed)
