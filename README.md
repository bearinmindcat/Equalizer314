<h1><img width="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Equalizer314" align="absmiddle"> Equalizer314</h1>

<a href="https://github.com/bearinmindcat/Equalizer314/releases/latest"><img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="80"></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/{%22id%22:%22com.bearinmind.equalizer314%22,%22url%22:%22https://github.com/bearinmindcat/Equalizer314%22,%22author%22:%22bearinmindcat%22,%22name%22:%22Equalizer314%22}"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/b1c8ac6f2ab08497189721a788a5763e28ff64cd/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Known Issues

### Conflicts with other audio effect apps

Equalizer314 uses Android's DynamicsProcessing API on audio session 0 for system-wide audio processing. Only one app can control session 0 at a time. If another equalizer or audio effect app is installed (such as Precise Volume, Wavelet, Sound Assistant, or any other EQ app), they will fight over session 0 and cause audio glitches or the EQ to turn off unexpectedly.

**If you experience the EQ turning off on its own, uninstall or disable other audio effect apps and reboot your device.**

Equalizer314 includes an auto-reclaim feature that will attempt to reclaim session 0 if another app takes it over, but this cannot fully prevent brief audio dropouts when two apps are competing.

This is a limitation of the Android audio framework, not specific to Equalizer314. Wavelet, Poweramp EQ, and all other system-wide EQ apps have the same limitation.
