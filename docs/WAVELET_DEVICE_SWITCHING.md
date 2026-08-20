# Wavelet device-profile switching — decompile findings & how Equalizer314 maps to it

Reference notes from a jadx decompile of `com.pittvandewitt.wavelet`, gathered while fixing
device profile switching (issue #62 thread: switching only swapped the EQ, not MBC/limiter,
and detection guessed instead of using the real routed device). Goal: mirror Wavelet, since
it's the closest to seamless per-device switching.

## Where Equalizer314 stands after the 2026-08 fixes

Done:
- **Full-chain presets** (`state/PresetChainIo.kt`): preset JSON now carries optional `mbc`
  and `limiter` blocks; applied on preset load, device auto-switch, and TV Mode sync.
  Wavelet stores its entire chain (34 keys) per device — this was the missing piece.
- **Real routed-device signal** (`EqService.systemSoundCallback` → `AudioRoutingMonitor.reportRoutedDevice`):
  on API 33+ we read `AudioPlaybackConfiguration.getAudioDeviceInfo()` and it overrides the
  BT>USB>wired>speaker priority guess. Wavelet never uses this API — our signal is more direct.
- Our device keys (`DeviceIdentity`: BT MAC / wired / USB) are more stable than Wavelet's
  (sanitized display names — break on rename or system-language change). Keep ours.

Done (2026-08-20): **Output-switcher moves** — `EqService.startRouteSelectionWatcher()`
registers `MediaRouter2` RouteCallback + ControllerCallback (API 30+, `FEATURE_LIVE_AUDIO`);
on a selected-route change it re-reads the routed device from live playback configs
(immediately + a 350 ms settle re-poll) and feeds `reportRoutedDevice`.

Optional grafts (small, not urgent):
- Label sanitization: reject `Build.MODEL`, `"boot_headset"`, `"h2w"` as wired product names
  in `DeviceIdentity.labelOf` (some HALs report those for wired headsets).
- `getStreamVolumeDb` HAL quirk: some HALs return linear gain, not dB (detect: index 0 → 0.0
  and max index → 1.0; convert via `20*log10`). Affects MBC Volume Tether's attenuation math.

Do NOT copy: name-based device keys, legacy `MediaRouter` framework-string matching,
`media.audio_policy` dump parsing (session-based apps only; we're global session 0).

---

# Full decompile report

How `com.pittvandewitt.wavelet` detects the active output device, identifies it, debounces
route changes, stores per-device settings, and reacts mid-playback. Sourced from the jadx
tree at `equalizer md and apk/wavelet-jadx/sources/` plus a `--show-bad-code` pass
(`wavelet-badcode/`) for three methods jadx bailed on.

Class/method names are given as obfuscated-name → resolved-role. Field names in `RouteState`
and `RoutingBundle` are recovered from their intact `toString()` bodies, so those are original
names, not guesses.

## Headline answer

**Wavelet does *not* use `AudioDeviceCallback`, and does *not* register any Bluetooth
listener** — no `BluetoothA2dp`, no `BluetoothProfile.ServiceListener`, no
`ACTION_ACL_CONNECTED` receiver.

Its detection is a two-part split:

| Question | Wavelet's source |
|---|---|
| *"Did something change?"* (trigger) | `AudioManager.AudioPlaybackCallback` + `MediaSessionManager.OnActiveSessionsChangedListener` |
| *"Where is audio going?"* (answer) | `MediaRouter2` selected route (API ≥ 34) / legacy `MediaRouter.getSelectedRoute()` (API < 34) |

Wavelet's `AudioPlaybackCallback` reads **only** `getAudioAttributes().getUsage()` — it never
calls `AudioPlaybackConfiguration.getAudioDeviceInfo()`. The whole effect chain is stored per
device, 34 keys' worth, in a separate DataStore file per device.

## 1. Device detection

### 1a. The trigger — `AudioPlaybackCallback` (`b6/i.java`)

```java
public final void onPlaybackConfigChanged(java.util.List list) {
    if (list == null) list = emptyList();
    if (list.isEmpty()) return;
    for (AudioPlaybackConfiguration c : list) {
        if (c.getAudioAttributes().getUsage() == 1) {   // USAGE_MEDIA
            this.f1249a.f2182g.q(100L);                 // tryEmit(100L)
            return;
        }
    }
}
```

- Filters to `USAGE_MEDIA` — ignores notifications, ringtones, assistant.
- Emits the literal **`100L`** into a `MutableSharedFlow`; the number IS the debounce delay
  (delay-as-payload, see §3).
- Purely a "recheck now" nudge.

### 1b. The trigger — active media sessions (`SessionListenerService`)

Extends `NotificationListenerService` (permission gate for
`MediaSessionManager.getActiveSessions()`), implements `OnActiveSessionsChangedListener`.
Session changes emit **`0L`** — zero delay vs 100 ms for playback-config churn.

Registration (`b6/f.java`) unregisters first (idempotent) and primes initial state
synchronously with `getActiveSessions()` so there's no cold-start gap.

### 1c. The answer — route flows (`n5/d.java`)

Mode 0 (modern):

```java
MediaRouter2 mr2 = MediaRouter2.getInstance(context);
mr2.registerRouteCallback(mainExecutor, routeCallback,
    new RouteDiscoveryPreference.Builder(
        List.of("android.media.route.feature.LIVE_AUDIO"), true).build());
mr2.registerControllerCallback(mainExecutor, controllerCallback);
```

Two things worth stealing:
1. Discovery filtered to `FEATURE_LIVE_AUDIO` — doesn't wake for cast/remote routes.
2. **`ControllerCallback`, not just `RouteCallback`** — RouteCallback fires when routes
   appear/disappear; ControllerCallback fires when the *selected* route changes. That's what
   catches "user moved audio to speaker via the output switcher while BT stays connected."

Mode 1 is Android Auto detection via the `androidx.car.app` CarConnection content provider +
`CAR_CONNECTION_UPDATED` broadcast — the only broadcast receiver Wavelet registers.

### 1d. Composition (`d6/g.java` constructor)

API gate is **34**:

| Android | Route source |
|---|---|
| API ≥ 34 | `MediaRouter2` + RouteCallback + ControllerCallback |
| API < 34 | legacy `android.media.MediaRouter`, `getSelectedRoute(ROUTE_TYPE_LIVE_AUDIO)` |
| `FEATURE_AUTOMOTIVE` | CarConnection branch replaced by a constant source |

## 2. Device identity

### 2a. The model — `RouteState`

```kotlin
RouteState(
    isDeviceSpeaker: Boolean = true,
    isUsb: Boolean = false,
    isBluetooth: Boolean = false,
    currentAttenuation: Float = 0.0f,
    name: String                         // ← the storage key
)
```

`equals`/`hashCode` include all five fields — a volume change makes a `!=` RouteState, which
re-triggers the pipeline (deliberate: attenuation feeds equal-loudness).

### 2b. Device key = display name, not BT MAC

No `BluetoothDevice.getAddress()` anywhere. Consequences: identical-model headphones share a
profile; renaming a BT device orphans its profile; the speaker key is a **localized** string,
so changing system language orphans it. (This is why Equalizer314 keeps MAC-based keys.)

### 2c. Name resolution + sanitization — `n5/h.b()` (reconstructed)

```kotlin
// currentAttenuation: getStreamVolumeDb with HAL-quirk handling
atten = when {
    c(dev, 0) == 0.0f && dbAtMax == 1.0f -> 20f * log10(dbAtCur)  // HAL reports linear gain
    dbAtMax < -1.0f -> (maxVol - curVol) * (c(dev, 1) / maxVol)   // real dB → interpolate
    else -> dbAtCur
}

// name priority: route-supplied BT name
//   → AudioDeviceInfo.productName (reject Build.MODEL, "boot_headset", "h2w")
//   → localized per-type fallback (speaker/headset/headphone/BT/USB/hearing aid)

// sanitize (name becomes a FILENAME):
name = name.replace("/", "%2F")
    .replace(" L ", " ").replace(" R ", " ")
    .removeSuffix(" L").removeSuffix(" R")   // TWS earbud halves share one profile
```

Hard-won details: the `Build.MODEL`/`"boot_headset"`/`"h2w"` rejection list (some HALs return
those as wired product names), and the L/R stripping (TWS buds expose per-bud names; without
stripping, switching primary bud switches EQ).

### 2d. Resolvers

Modern (`q5/c.java`): pure `AudioDeviceInfo.getType()` checks — BT `{8, 26}`, speaker `{2}`,
USB `{11, 12, 22}`.

Legacy (`p5/a.java`): compares route names against **framework-internal string resources
looked up by name** (`Resources.getSystem().getIdentifier("default_audio_route_name", ...)`),
then maps category → candidate types → first connected match from
`getDevices(GET_DEVICES_OUTPUTS)`, with fallback chain: match → hearing aid (23) → speaker (2)
→ null. Fragile; do not port.

## 3. Switch sequencing / anti-flapping

Three pieces:

1. `MutableSharedFlow(replay = 1, extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` —
   emitters pick their own latency: session change `0L`, playback-config `100L`.
2. Consumed with **`collectLatest`**.
3. The collector's first statement is `delay(delayMs)`.

Result: debounce-by-cancellation. A burst during BT handover collapses to one execution
100 ms after the last event. No handlers, no timestamps, no flap counter.

```kotlin
routeTrigger.collectLatest { delayMs ->
    delay(delayMs)
    applyRoutingChange()
}
routeTrigger.tryEmit(0L)     // urgent
routeTrigger.tryEmit(100L)   // let it settle
```

Caveat: continuous events faster than the delay starve the work; fine because route churn is
bursty. (Equalizer314's 400 ms handler debounce is equivalent in effect.)

## 4. Per-device storage

Two-tier:
- **Global** DataStore (7 keys): `wavelet_enable`, `purchased`, `recents`, `timestamp`,
  `legacy_mode`, `buffer_size`, `aidl_mode`.
- **Per device**: a separate Preferences DataStore per device, named after the sanitized
  device name — `filesDir/datastore/<device name>.preferences_pb` — cached in a HashMap,
  with SharedPreferences migration attached.

Per-device keys (34 — the entire chain): auto-EQ (enable/device/strength — the chosen AutoEq
headphone profile is itself per-device), graphic EQ (3), personal EQ (2), bass boost (2),
bass tuner (4), channel balance (3), equal loudness (4), input gain (3), limiter
(enable/threshold/ratio/attack/release/postGain), virtualizer (2), preset reverb (2).

Application is reactive, not imperative: all 34 setting flows derive from a
`StateFlow<RoutingBundle>` (route + its DataStore + prefs snapshot); route change swaps the
bundle and everything downstream re-emits.

## 5. Mid-playback switches

Same pipeline, no special-casing. Works because:
- **`ControllerCallback`** catches a selected-route change on an unchanged route set — the
  exact output-switcher-to-speaker case.
- The engine receives **complete desired state** on every change (all sessions that should
  have effects), not deltas — naturally idempotent, spurious triggers are free.

Session discovery (not relevant to session-0 apps): reflects
`ServiceManager.getService("media.audio_policy")`, calls `IBinder.dump()`, regex-parses
`"Session\sID:\s(\d+);?\sUID:?\s(\d+)"`, maps UID→package via PackageManager, filters out
`com.android.server.telecom`. This is why Wavelet needs notification-listener access and why
it's fragile across OEM ROMs.

## 6. Graft ranking (from the report)

1. Delay-as-payload + `collectLatest` debounce (elegant; ours is equivalent, skip).
2. Name sanitization rules (`Build.MODEL`/`boot_headset`/`h2w`, TWS L/R merge).
3. A selected-route/controller-change trigger, not just device add/remove — **test the
   system output switcher explicitly**; add `MediaRouter2.ControllerCallback` if we miss it.
4. Send complete desired state to the engine, not deltas.
5. Per-device full-chain storage keyed by stable identity — done (PresetChainIo + MAC keys).

## 7. Wavelet file index

| Role | File |
|---|---|
| AudioPlaybackCallback (trigger, 100 ms) | `b6/i.java` |
| Callback registration + initial prime | `b6/f.java` |
| Session listener + audio-policy reflection | `session/SessionListenerService.java` |
| Debounce collector (`collectLatest`) | `b6/o.java` (case 0) |
| Debounce handler (`delay` + work) | `b6/n.java` → `o()` (`--show-bad-code`) |
| Route flows (MediaRouter2 / CarConnection) | `n5/d.java` (modes 0 / 1) |
| Route-source composition + API-34 gate | `d6/g.java` (constructor) |
| Abstract resolver + RouteState builder | `n5/h.java` → `b()` (`--show-bad-code`) |
| Modern resolver (MediaRouter2) | `q5/c.java` |
| Legacy resolver (MediaRouter) | `p5/a.java` |
| Device-category enum | `n5/g.java` |
| Device identity model | `audiorouting/RouteState.java` |
| Runtime tuple RoutingBundle | `d6/b.java` |
| Per-device store creation + cache | `d6/e.java` |
| DataStore factory + 34-key catalog | `d6/a.java` |
| Global key catalog (7 keys) | `d6/w.java` |
| Settings object (34 derived flows) | `d6/l1.java` |
| Singleton factory + default RouteState | `a5/f.java` → `h(Context)` |
| DataStore filename resolver | `a1/u.java:92` |
