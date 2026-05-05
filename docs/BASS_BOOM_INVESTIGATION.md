# Bass-Boom Investigation — open issue

> **Status: open**, partial fix committed.
> Last touched: 2026-05-05.

## Symptom

When loading APO presets (Wavelet / Poweramp / EasyEffects), audio
plays with an audible **bass boom / compression-like artefact** on
bass-heavy content, and slightly on peak filters at any frequency.
The same APO preset plays cleanly through:

- **Poweramp Eq** (Android, closed-source player)
- **EasyEffects** (Linux PC, LADSPA/LV2 plugin host)

The preset itself isn't the problem — both reference apps render it
clean. Something in this app's audio chain is producing the artefact.

## What's been ruled out (with evidence)

### 1. `tanh` saturation — confirmed dead in audio path

Every `tanh` call site, traced:

| File:line | Where | Reaches audio? |
|---|---|---|
| `dsp/ParametricEqualizer.kt:123–124` | inside `process(buffer)` — `tanh` per L/R sample | **No.** `process()` is never called from anywhere. Verified: `grep -rn "\.process\b\|fun process" app/src/main/java/com/bearinmind/equalizer314/dsp/` returns only the definition. Audio path goes through Android's native `DynamicsProcessing`, which never executes Kotlin code on samples. |
| `dsp/ParametricEqualizer.kt:161–162` | inside `getFrequencyResponseWithSaturation(freq)` | **No.** Only caller is `ui/EqGraphView.kt:729`, which renders the dashed orange curve on the EQ visualisation. UI only. |

The audio path:
```
ParametricEqualizer state
  → ParametricToDpConverter.convert(eq)
  → eq.getFrequencyResponse(centers[i])    ← does NOT call tanh
  → DynamicsProcessingManager.applyParametricResponse
  → dp.setPreEqBandByChannelIndex(channel, i, EqBand(cutoff_Hz, gain_dB))
  → [Android DP native engine processes audio]
```

`tanh` does not appear anywhere in that chain. Removing the dead
`process()` method would not change a single sample of output.

### 2. Sample rate (44100 vs 48000) — fixed, was not the cause

`BiquadFilter` and `ParametricEqualizer` were hardcoded to
`sampleRate = 44100`, but Android's `DynamicsProcessing` runs at
the device's actual output rate (48000 Hz on this device, verified
via `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`).

Magnitude of the error this introduced (bilinear-transform
pre-warp scales as `(f/Fs)²` for `f << Nyquist`):

| Frequency | Approx. error in reported dB |
|---|---|
| 50 Hz | < 0.05 dB |
| 100 Hz | < 0.10 dB |
| 1 kHz | ~0.4 dB |
| 5 kHz | ~1 dB |
| 15 kHz | ~3 dB |

Sub-1 dB error in the bass region. Below the ~1 dB human
discriminable threshold. Cannot produce audible "boom".

**Fixed in commit `ac56cd0`** ("parametric EQ now uses
deviceSampleRate instead"):
- `dsp/BiquadFilter.kt` default `sampleRate` 44100 → 48000.
- `dsp/ParametricEqualizer.kt` default `sampleRate` 44100 → 48000.
- `state/EqStateManager.kt` queries `PROPERTY_OUTPUT_SAMPLE_RATE`,
  threads it into the three audio-path EQ instances.

After the fix: high-frequency accuracy is correct on this device
(no more 3 dB error at 15 kHz). Bass boom: **still there**, as
predicted.

### 3. Limiter — bypassed when off

User confirmed: boom is present with limiter explicitly disabled.
`Limiter(inUse=false, ...)` flag puts the stage out of the
processing chain. Verified `DynamicsProcessing.Limiter` semantics
in `audio/DynamicsProcessingManager.kt:95–99`.

### 4. MBC (Multi-band Compressor) — bypassed when off

Same story. User confirmed boom remains with MBC disabled.
`mbcEnabled = false` excludes it from the DP config builder
(`audio/DynamicsProcessingManager.kt:82–83`).

### 5. Biquad math — verified correct

RBJ Audio EQ Cookbook formulas in `dsp/BiquadFilter.kt`:

- BELL (peaking): lines 88–102. Verified match RBJ canonical form
  (`b0 = 1 + alpha·A`, `a0 = 1 + alpha/A`, etc.).
- LOW_SHELF / HIGH_SHELF: lines 104–122. Verified `2·sqrt(A)·alpha`
  → `beta·sin(omega)` substitution (where `beta = sqrt(A)/Q`).
- LOW_PASS / HIGH_PASS: lines 124–140. Standard RBJ form.
- 1st-order shelves (LSHELF 6dB / HSHELF 6dB): lines 168–210. Use
  Zölzer DAFX bilinear-transform form with `K = tan(π·Fc/Fs)`.
- All-pass / Notch / Band-pass: lines 214–242. Standard RBJ.

DC and Nyquist gain checked for the +6 dB low-shelf case:
`H(z=1) = +6 dB`, `H(z=-1) = 0 dB`. Math is correct.

## What's still suspect

The remaining suspect is **how the parametric EQ is fed to
Android's `DynamicsProcessing` Pre-EQ**.

This app's pipeline:
```
APO preset → biquad coefficients → sample response at 128 log-
spaced frequencies → feed (cutoff, gain) pairs to DP → DP's
FFT-based per-band engine processes audio
```

Reference apps (Poweramp, EasyEffects):
```
APO preset → biquad coefficients → run biquads directly on every
audio sample in the time domain (no FFT, no per-band conversion)
```

The **bolded** step in our pipeline is the structural difference.
That conversion is lossy in three potential ways:

1. **Cutoff vs centre semantics.**
   `dsp/ParametricToDpConverter.kt:65–68` evaluates the biquad's
   response at the band's *geometric centre* (`centerFrequencies[i]`)
   but `audio/DynamicsProcessingManager.kt:219, 222` passes the band's
   *upper edge* (`cutoffFrequencies[i]`) to DP as the band's frequency.
   If DP interprets the EqBand frequency as the band centre, every
   band's gain effectively shifts ~6 % upward in frequency.
2. **128 log-spaced bands packed below DP's FFT bin width.**
   At ~50 Hz our band spacing is ~6 Hz; DP's FFT bin width with
   `VARIANT_FAVOR_FREQUENCY_RESOLUTION` is roughly 12–24 Hz at
   48 kHz. Multiple of our bands collapse into one bin — DP must
   merge / interpolate across them, which can cause frequency-
   domain smearing that subjectively sounds like compression on bass.
   This is consistent with "boom on bass, slight on peak filters
   elsewhere" (peak filters near sub-bass would suffer the same
   bin-packing).
3. **FFT-based EQ time-domain artefacts.**
   DP uses overlap-add windowed FFT processing. The window length
   is on the order of a typical bass period (10–50 ms). That's a
   known source of intermodulation distortion on long bass cycles.

None of these are publicly documented as Android bugs — they are
hypotheses based on general DSP / FFT-EQ behaviour. Direct evidence
would require reading the AOSP source for `DynamicsProcessing`.

## Next steps to try (cheapest first)

### Step 1 — A/B band count

Reduce default `numBands` from 128 → 64 → 32 and listen.

- File: `app/src/main/java/com/bearinmind/equalizer314/dsp/ParametricToDpConverter.kt`
- Line 17: `var numBands: Int = 128` → try 64, then 32.
- The band-count slider in the EQ UI also lets you A/B without
  rebuilding — `MainActivity.kt:1691, 1702` calls
  `ParametricToDpConverter.setNumBands(count)`.

**If the boom changes with band count → hypothesis #2 confirmed.**
Pick the lowest count that still gives acceptable curve fidelity.
**If the boom doesn't change → move to step 2.**

### Step 2 — Switch DP variant

Change `VARIANT_FAVOR_FREQUENCY_RESOLUTION` →
`VARIANT_FAVOR_TIME_RESOLUTION`.

- File: `app/src/main/java/com/bearinmind/equalizer314/audio/DynamicsProcessingManager.kt`
- Line 78: change the variant constant.

`FAVOR_TIME_RESOLUTION` uses a smaller FFT, so:
- Less time-domain smearing on long bass periods (good for boom).
- Coarser frequency resolution between bands (worse for narrow Q bells).

**If the boom decreases under TIME variant → hypothesis #3
confirmed.** Decide whether the frequency-resolution loss is
acceptable.

### Step 3 — Try `centerFrequencies` instead of `cutoffFrequencies`

Tests whether DP interprets the band frequency as the band centre
(in which case our current code is shifting every band ~6 %).

- File: `app/src/main/java/com/bearinmind/equalizer314/audio/DynamicsProcessingManager.kt`
- Lines 183, 219, 222: replace `cutoffsSnap[i]` /
  `cutoffs[i]` with `centerFrequencies[i]`.

Sweep an EQ tone test through a known-frequency probe — if the
gain peak lands at the *cutoff* freq, the original code was
right; if it lands at the *centre*, we need to swap.

### Step 4 — Read AOSP source for `DynamicsProcessing`

Turns the hypotheses into facts. The canonical paths are:

- `frameworks/av/media/libaudiohal/impl/EffectHalAidl.cpp`
- `frameworks/av/media/libeffects/dynamicsproc/dsp/DPBase.cpp`
- `frameworks/av/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp`

Look for:
- What does `cutoffFrequency` actually mean? (band centre?
  upper edge? lower edge?)
- What FFT size is used by each variant? At what overlap?
- How are gains interpolated between bands when bands fall
  inside the same FFT bin?

### Step 5 — Try Android's legacy `Equalizer` class

Sanity check: does the SAME APO preset boom when applied via
`android.media.audiofx.Equalizer` instead of `DynamicsProcessing`?

- The legacy class only supports 5–10 fixed-frequency bands, but
  it uses a different (likely IIR cascade) processing approach.
- If `Equalizer` sounds clean → the bug is `DynamicsProcessing`-
  specific.

This requires building a small test or temporary alternate code
path; it's not a one-line change.

### Step 6 — Bypass DP, run biquads in time domain (LAST RESORT)

Match what Poweramp / EasyEffects do: process audio sample-by-
sample through the biquad cascade directly.

- Significant architectural change. Lose Android's automatic
  system-wide audio routing through DP — would need to set up
  an `AudioEffect` subclass or use OpenSL ES / AAudio for
  intercepting the audio stream.
- This is the definitive fix IF the architecture is the problem,
  but only worth attempting after steps 1–5 have eliminated the
  cheaper alternatives.

## Useful background

### Files that matter for this problem

| File | What it does |
|---|---|
| `app/src/main/java/com/bearinmind/equalizer314/dsp/BiquadFilter.kt` | RBJ-cookbook biquad with frequency-response evaluator. Used to compute coefficients and to sample the response curve. **Not used to process audio samples.** |
| `app/src/main/java/com/bearinmind/equalizer314/dsp/ParametricEqualizer.kt` | Holds N `BiquadFilter` instances; sums their log-response. Has a `process(buffer)` method that's never called. |
| `app/src/main/java/com/bearinmind/equalizer314/dsp/ParametricToDpConverter.kt` | Samples the parametric response at log-spaced centres, hands `(cutoff, gain)` pairs to DP. **This is where the conversion happens.** |
| `app/src/main/java/com/bearinmind/equalizer314/audio/DynamicsProcessingManager.kt` | Owns the `DynamicsProcessing` instance. Builds the config, sets per-band EqBand entries, runs the limiter. **The audio path lives here.** |
| `app/src/main/java/com/bearinmind/equalizer314/state/EqStateManager.kt` | Owns the audio-path EQ instances (both / left / right). Now passes the device's actual sample rate. |
| `app/src/main/java/com/bearinmind/equalizer314/ui/EqGraphView.kt` | Renders the EQ curve, including the dashed `tanh` saturation line. Pure UI. |

### How to read the audio chain at a glance

The full path (system-wide audio with EQ enabled):

1. Apps play audio → mixed to session-0 output.
2. Android's audio HAL routes session 0 through any attached
   `AudioEffect` instances.
3. Our `DynamicsProcessing` instance is attached to session 0
   from `audio/EqService.kt` (foreground service).
4. DP runs: optional Pre-EQ → optional MBC → optional
   Post-EQ → optional Limiter.
5. We use Pre-EQ only (with N log-spaced bands), MBC and Post-EQ
   off, Limiter off (per user preference).
6. Output goes to the device's DAC at the HAL's sample rate
   (48 kHz on this device).

Step 4 is where the boom comes from. The Pre-EQ stage receives our
`(cutoff, gain)` pairs and constructs an FFT-based filter mask.

### Reference: how Poweramp & EasyEffects differ

Both apply biquads in the time domain — sample-by-sample IIR
processing. No FFT, no per-band conversion, no interpolation
between bands. The exact mathematical biquad response is what hits
the audio.

This is fundamentally different from Android's `DynamicsProcessing`
API, which abstracts EQ as "a list of (frequency, gain) pairs" and
lets the system synthesize a filter from that. The abstraction is
lossy — and the loss is most audible at low frequencies, where
band spacing falls inside FFT bin boundaries.

## Quick verification commands

```bash
# Check the device's sample rate (should already be in logcat
# from EqStateManager when the app launches):
adb logcat -d -s EqStateManager | tail -5

# Watch the per-band cutoff/gain values in real time as you drag
# the EQ (if you add the diagnostic log from earlier in
# DynamicsProcessingManager.applyParametricResponse):
adb logcat -s DynamicsProcessingMgr

# Build & install:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Out of scope (deferred)

- Removing the dead `process()` method — cosmetic cleanup, defer
  until a clean-up pass.
- Removing the dead `getFrequencyResponseWithSaturation()` — used
  by the visualizer; harmless.
- Any UI changes related to this issue.
