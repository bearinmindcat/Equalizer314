# Privacy Policy — Equalizer314

**Effective date:** 2026-04-19
**App:** Equalizer314 (`com.bearinmind.equalizer314`)
**Developer:** bearinmind
**Source code:** https://github.com/bearinmindcat/Equalizer314
**License:** GNU General Public License v3.0

---

## Short version

Equalizer314 **does not collect, store, or transmit any personal data**. It has no internet access, no analytics, no advertising, no trackers, no accounts, and no third-party SDKs. All app settings stay on your device.

If a 30-word version is enough for you, the remainder of this document is the detail.

---

## Detailed version

### 1. What data is collected

**None.** Equalizer314 does not:

- Collect personal information of any kind (name, email, phone number, device identifiers, location, etc.).
- Create, require, or sync to any user account.
- Send data to any server operated by the developer or any third party.
- Track usage, interactions, crashes, install sources, or any analytics event.
- Display or contain advertising of any kind.
- Bundle any third-party SDK that could collect data on its behalf.

### 2. Network access

**The app has no internet access.** The `INTERNET` permission is **not declared** in the app's Android manifest. Android will refuse any network request the app might attempt, because the permission was never requested. You can verify this yourself by examining the installed APK's manifest, or by inspecting [AndroidManifest.xml](https://github.com/bearinmindcat/Equalizer314/blob/main/app/src/main/AndroidManifest.xml) in the open-source repository.

### 3. Permissions used and why

The app declares only the following Android permissions. Each is used exclusively for core audio functionality.

| Permission | Why it's needed |
|---|---|
| `android.permission.MODIFY_AUDIO_SETTINGS` | Required to register the `DynamicsProcessing` audio effect on global audio session 0, which is the mechanism by which the app provides system-wide equalization. |
| `android.permission.RECORD_AUDIO` | Used **only** to feed Android's `Visualizer` API, which drives the on-screen real-time spectrum analyzer and the multiband compressor / limiter gain-reduction visualizations. Audio samples are processed frame-by-frame in memory and discarded. **Samples are never saved to disk, never copied off the device, and never transmitted anywhere.** The app cannot transmit them because it has no `INTERNET` permission. |
| `android.permission.FOREGROUND_SERVICE` | Required to run the equalizer engine as a foreground service so Android does not terminate it while other apps play audio. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ service-type declaration matching the foreground service's audio-processing role. |
| `android.permission.POST_NOTIFICATIONS` | Required on Android 13+ to show the foreground service's ongoing notification (which lets you turn the EQ off from the notification shade). |

The app does **not** request:
- `INTERNET` — so no network calls are possible.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — so no location data is possible.
- `READ_CONTACTS`, `READ_SMS`, `CAMERA`, `READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, or any other sensitive permission beyond those listed above.

### 4. What is stored on your device

Your EQ settings, custom presets, UI preferences, and AutoEQ / Target Curve selections are saved locally using Android's `SharedPreferences` mechanism. These files are private to the app's sandbox, sit under `/data/data/com.bearinmind.equalizer314/`, are not accessible to other apps, and are removed when you uninstall the app.

Imported APO presets and measurement files are stored in the same app-private area. They are never transmitted.

### 5. Audio processing scope

Equalizer314 applies system-wide audio equalization via Android's `DynamicsProcessing` API on session 0. This means the app **observes** and **shapes** the audio mix produced by other apps on the device. It does **not** record that audio to any persistent storage, and it does not send it anywhere.

The one exception where the audio enters Java/Kotlin memory rather than just the native DSP pipeline is the `Visualizer` API feed for the spectrum analyzer and multiband compression / limiter graphs (see the `RECORD_AUDIO` row above). Even there, samples live only long enough to compute a single FFT frame or RMS measurement, then are overwritten by the next frame.

### 6. Children's privacy

The app does not knowingly collect data from anyone, including children. Because it collects nothing at all, it has no data to protect, delete, or respond to requests about.

### 7. Third-party services

The app uses only:
- Android's built-in `AudioEffect` / `DynamicsProcessing` / `Visualizer` APIs (Android OS).
- Open-source libraries listed in the project's `app/build.gradle.kts` (AndroidX, Material Components for Android, Kotlin, kotlinx.coroutines, AndroidX Media3). None of these libraries make network calls in the way Equalizer314 uses them. See the project's [README Acknowledgments](https://github.com/bearinmindcat/Equalizer314#acknowledgments) and [fdroidreproduceablebuilds.md](https://github.com/bearinmindcat/Equalizer314/blob/main/fdroidreproduceablebuilds.md) for full dependency list.

No third-party analytics, crash reporting, advertising, or tracking SDK is integrated.

### 8. Data sharing

Because no data is collected, no data is shared. Neither the developer nor any third party has access to anything the app does on your device.

### 9. Your rights

Because the app collects no personal data, there is nothing to access, delete, export, or port. You can uninstall the app at any time; all saved presets and settings are removed with the app.

If you believe this policy is inaccurate or misses something, please open an issue at https://github.com/bearinmindcat/Equalizer314/issues — the source code is public and auditable.

### 10. Changes to this policy

If the data practices of the app ever change (they are not expected to), this document will be updated and a new effective date set at the top. The full history of changes is visible in the repository's git log for this file.

### 11. Contact

- **Issue tracker:** https://github.com/bearinmindcat/Equalizer314/issues
- **Source code:** https://github.com/bearinmindcat/Equalizer314
- **Author:** bearinmind (GitHub: [@bearinmindcat](https://github.com/bearinmindcat))
