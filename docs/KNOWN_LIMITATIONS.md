# Known Limitations

## Spectrum doesn't fade on pause when using phone speaker

**Issue:** When playing audio through the phone's built-in speaker and pausing, the spectrum analyzer stays visible instead of fading out.

**Cause:** The Android Visualizer API on session 0 captures the DAC output, which remains active on the phone speaker even when audio is paused. The signal level stays above the silence detection threshold (-60 dBFS) because the speaker output path keeps a baseline signal.

**Works correctly on:** Bluetooth, wired headphones, USB audio — any output where the signal truly drops to silence when paused.

**This is an Android hardware/API limitation, not a bug.** The Visualizer API does not distinguish between "audio playing" and "speaker DAC idle noise." Multiple threshold values were tested (-35, -40, -60 dBFS) but lower thresholds cause the spectrum to barely appear during Bluetooth playback. The -60 dBFS threshold is the best balance.

**Workaround:** Toggle the spectrum visualizer off and on using the spectrum button on the graph.
