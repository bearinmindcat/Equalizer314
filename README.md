<h1><img width="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Equalizer314" align="absmiddle"> Equalizer314</h1>

A system-wide parametric equalizer for Android, built on the platform's `DynamicsProcessing` API. No root, no audio capture, no media-projection prompts — just biquad math fed straight into the OS audio pipeline.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Latest release](https://img.shields.io/github/v/release/bearinmindcat/Equalizer314)](https://github.com/bearinmindcat/Equalizer314/releases/latest)
[![Min API](https://img.shields.io/badge/min%20SDK-24-brightgreen.svg)](https://developer.android.com/about/versions/nougat)
[![Audio API](https://img.shields.io/badge/audio-DynamicsProcessing%20%28API%2028%2B%29-orange)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)

<a href="https://github.com/bearinmindcat/Equalizer314/releases/latest"><img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="70"></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/{%22id%22:%22com.bearinmind.equalizer314%22,%22url%22:%22https://github.com/bearinmindcat/Equalizer314%22,%22author%22:%22bearinmindcat%22,%22name%22:%22Equalizer314%22}"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/b1c8ac6f2ab08497189721a788a5763e28ff64cd/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="70"></a>

---

## Screenshots

> _Add screenshots here. Suggested: parametric graph, Simple EQ, MBC, Limiter, Spectrum Control, Settings._

| Parametric | Simple EQ | MBC |
|:--:|:--:|:--:|
| ![Parametric](docs/screenshots/parametric.png) | ![Simple EQ](docs/screenshots/simple.png) | ![MBC](docs/screenshots/mbc.png) |

| Limiter | Spectrum | Presets |
|:--:|:--:|:--:|
| ![Limiter](docs/screenshots/limiter.png) | ![Spectrum](docs/screenshots/spectrum.png) | ![Presets](docs/screenshots/presets.png) |

---

## What it is

Equalizer314 is a 100% local, no-ads, no-trackers Android app that shapes the global audio output of the device. It does this by computing parametric biquad filter responses on the CPU, sampling that composite curve at N log-spaced frequencies, and pushing those `(cutoffFrequency, gain)` pairs to `DynamicsProcessing` on the Pre-EQ stage of audio session 0.

The result is a system-wide EQ that affects every app on the device (with the limitations described in [Known Issues](#known-issues)) without needing root, accessibility, or audio-capture permissions.

## Architecture

```
┌────────────────────────────┐   ┌────────────────────────────┐   ┌────────────────────────────┐
│  ParametricEqualizer       │   │  ParametricToDpConverter   │   │  DynamicsProcessing        │
│  (1–16 BiquadFilter bands) │ → │  (samples response at N    │ → │  (Pre-EQ stage on          │
│  RBJ + Vicanek formulas    │   │   log-spaced frequencies)  │   │   session 0, system-wide)  │
└────────────────────────────┘   └────────────────────────────┘   └────────────────────────────┘
```

- **DSP** lives in `app/src/main/java/com/bearinmind/equalizer314/dsp/` — `BiquadFilter`, `ParametricEqualizer`, `ParametricToDpConverter`, `FFT`, `SpectrumAnalyzer`.
- **Audio pipeline** lives in `app/src/main/java/com/bearinmind/equalizer314/audio/` — `DynamicsProcessingManager`, `EqService` (foreground service that owns the `DynamicsProcessing` instance).
- **State** lives in `app/src/main/java/com/bearinmind/equalizer314/state/` — `EqStateManager`, `EqPreferencesManager`.
- **UI** lives in `app/src/main/java/com/bearinmind/equalizer314/ui/` — custom `EqGraphView`, `SimpleEqBarsView`, controllers per UI mode.

## Features

### Equalizer
- **4 UI modes** — Parametric (drag points on a graph), Graphic (vertical sliders), Table (numeric input), Simple (10-band fixed graphic EQ).
- **Up to 16 parametric bands**, individually toggleable.
- **5 filter types** — Bell, Low Shelf, High Shelf, Low Pass, High Pass.
- **Range** — 20 Hz to 20 kHz, ±20 dB, Q from 0.1 to 10.
- **DSP accuracy** — RBJ Audio EQ Cookbook biquads with optional Vicanek impulse-invariance for Bell filters.
- **Live frequency-response curve** with optional translucent fill, optional saturation overlay, and configurable DP band count overlay (32–128).

### Presets
- **Built-in** — Flat, Bass Boost, Treble Boost, Vocal Enhance.
- **Custom presets** — save/load arbitrary band configurations with mini-graph thumbnails.
- **APO export** — write any preset as Equalizer APO `.txt` (PK / LSC / HSC filter syntax) to share or use on desktop.
- **APO import** — pull EAPO-format `.txt` files into the app as named presets.
- **Per-mode presets** — Simple EQ keeps its own preset library separate from the parametric/graphic/table presets.

### AutoEQ
- **Target-curve fitter** — load a measurement (`.txt`/`.csv`) and a target curve, run a grid-search optimizer, and get back an EQ profile sized to a configurable band count (5–20).
- **APO export** of the generated profile.

### Multiband Compression
- **1–6 bands** with draggable crossovers.
- Per-band threshold, ratio, knee, attack, release, pre-gain, post-gain, noise gate, expander, range.
- Live per-band gain-reduction trace + halo animations.
- Soft-knee transfer function (Giannoulis et al., JAES 2012).

### Limiter
- Threshold, ratio, attack, release, post-gain.
- Real-time waveform meter (input vs. output) and a transfer-function ceiling graph.
- On by default to prevent clipping from large EQ gains.

### Spectrum Analyzer
- Real-time 4096-pt Hann-windowed FFT.
- Log-frequency mapping, configurable PPO (points-per-octave) smoothing.
- Asymmetric ballistics — fast attack, tunable release.
- Color, FPS, and smoothing all user-configurable.

### Other
- **Material 3** dark theme.
- **Foreground service** keeps EQ alive across app switches with a notification + "Turn Off" action.
- **System-wide** — affects all apps that route audio through the standard Android pipeline.
- **No analytics, no network calls, no telemetry.**

## Requirements

- **Android 7.0 (API 24)** minimum.
- **Android 9.0 (API 28)** required for the EQ engine itself — `DynamicsProcessing` was added in Pie.
- A device whose audio HAL exposes `DynamicsProcessing` on session 0 (most modern Android phones do; some manufacturers strip it).

## Installation

- **GitHub Releases** — grab the latest signed APK from the [releases page](https://github.com/bearinmindcat/Equalizer314/releases/latest).
- **Obtainium** — use the badge above to add the repo for automatic updates.
- **F-Droid** — _coming soon._
- **Google Play** — _coming soon._

## Building from source

```bash
git clone https://github.com/bearinmindcat/Equalizer314.git
cd Equalizer314
./gradlew assembleDebug
```

Or, for a signed release build (you'll need your own keystore):

```bash
./gradlew assembleRelease bundleRelease \
  -PRELEASE_STORE_FILE=path/to/your.jks \
  -PRELEASE_STORE_PASSWORD=*** \
  -PRELEASE_KEY_ALIAS=*** \
  -PRELEASE_KEY_PASSWORD=***
```

Open in Android Studio: `File > Open` → select project root.

- **Min SDK** 24, **Target/Compile SDK** 35
- **Kotlin** 1.9.20, **AGP** 8.2.0

## Tech stack

- **Kotlin** + Android Views (no Compose).
- **Android `DynamicsProcessing`** API (`android.media.audiofx.DynamicsProcessing`) for Pre-EQ on session 0.
- **AndroidX Media3** (1.2.0) — present in dependencies for future audio routing features; current EQ path does not use ExoPlayer.
- **Material Components for Android** 1.11.0.
- **Custom DSP** — biquad filter implementations and FFT are first-party (no JNI / no native libs).

## Usage notes

- **Power button** is the explicit gate for the EQ — it must be on for any audio shaping to happen. The state is intentionally reset to off on every fresh app launch.
- **Pre-amp** sits before the EQ stage; bring it down if heavy positive boosts cause clipping.
- **Simple EQ mode** is a stripped-down 10-band graphic EQ (31 Hz – 16 kHz, fixed Q ≈ 1.41) for users who don't want the full parametric workflow. Toggling it on/off preserves your advanced EQ — they're stored separately.
- **Experimental settings** (Gain Compensation, DP Band Count) are visible but disabled in the current release. They'll be re-enabled once the gain-compensation behavior is more fully tested.

## Known Issues

### Conflicts with other audio effect apps

Equalizer314 uses Android's DynamicsProcessing API on audio session 0 for system-wide audio processing. Only one app can control session 0 at a time. If another equalizer or audio effect app is installed (such as Precise Volume, Wavelet, Sound Assistant, or any other EQ app), they will fight over session 0 and cause audio glitches or the EQ to turn off unexpectedly.

**If you experience the EQ turning off on its own, uninstall or disable other audio effect apps and reboot your device.**

Equalizer314 includes an auto-reclaim feature that will attempt to reclaim session 0 if another app takes it over, but this cannot fully prevent brief audio dropouts when two apps are competing.

This is a limitation of the Android audio framework, not specific to Equalizer314. Other apps with the same limitation:

- **Wavelet** — uses DynamicsProcessing on session 0. Their FAQ states: *"Wavelet will often not function as expected if other equalizer/hearing aid applications are installed. Freezing or uninstalling the offending application + rebooting your device will resolve issues caused by this."*
- **RootlessJamesDSP** — uses internal audio capture instead of DynamicsProcessing, but still conflicts. Their README states: *"Cannot coexist with (some) other audio effect apps (e.g., Wavelet and other apps that make use of the DynamicsProcessing Android API)."* Because RootlessJamesDSP captures audio after DynamicsProcessing has already modified it, then outputs processed audio back through session 0, the result is double processing.
- **Poweramp EQ** — uses DynamicsProcessing on session 0 for system-wide mode.
- **Precise Volume** — uses DynamicsProcessing on session 0 for volume processing. Recreates its instance on every volume change, which disconnects other apps.

### Apps that bypass the system audio pipeline

Apps using AAudio / OpenSL ES low-latency paths, MMAP / offload modes, or telephony audio will not be affected by Equalizer314 (or by any session-0 EQ). This includes most low-latency music games and the in-call audio path.

## Contributing

Issues and PRs welcome. Some ground rules:

- File a bug report with: device model, Android version, the EQ mode you're in, and (if relevant) a list of other audio apps installed.
- Feature requests are fine — open an issue first so we can talk through fit before you write the code.
- For PRs, keep the diff focused on one concern; don't bundle unrelated cleanup with feature work.

## Acknowledgments

- **Robert Bristow-Johnson** for the [Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) — the foundation of the biquad math.
- **Martin Vicanek** for [Matched Second Order Digital Filters](https://www.vicanek.de/articles/BiquadFits.pdf) — used for the impulse-invariant Bell filter option.
- **Jaakko Pasanen** and the [AutoEq](https://github.com/jaakkopasanen/AutoEq) project for prior art on target-curve fitting.
- **Equalizer APO** for the de-facto file format used for desktop preset interchange.

## License

Equalizer314 is released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

You are free to use, modify, and redistribute this software under the terms of the GPL v3. If you distribute a modified version, you must release the source under the same license.
