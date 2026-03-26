# Compressor Display Terminology — Industry Reference

## The Two Main Visual Elements

### Top Trace: "Gain Reduction" (GR)
Shows how much the compressor is reducing gain. 0 dB at top = no compression. Dips downward when compression engages. The deeper the dip, the more gain reduction.

### Bottom Trace: "Input Level" / "Input Waveform"
Shows the raw input audio level (amplitude envelope). Grows upward from the bottom. Loud audio = tall fill, quiet = flat.

---

## Terminology By Manufacturer

### FabFilter Pro-C 2/3
- **GR element**: "gain reduction" — displayed as "a red line" in the "level display"
- **Input element**: "input" — "The input is shown in dark grey"
- **Output element**: "light grey with a light stroke"
- **Overall display**: "Level Display" — "visualizes the input and output level, together with the applied gain change"
- **Meters**: "level meters" showing "input, gain reduction and output levels"
- Uses "gain change" and "gain reduction" interchangeably ("gain change" is broader since it covers expansion too)

### Ableton Live 12 Compressor
- **GR element**: "Gain Reduction meter" — "The orange Gain Reduction meter shows how much the gain is being reduced at any given moment." Abbreviated "GR" in UI
- **Input element**: "the level of the input signal" — "The Activity view shows the level of the input signal in light gray"
- **Overall display**: "Activity View"

### Ableton Glue Compressor (SSL-modeled)
- **GR element**: "gain reduction" — "The Glue Compressor's needle display shows the amount of gain reduction in dB"

### iZotope Neutron 4
- **GR element**: "Gain Reduction Trace" — "Draws a line that represents the gain reduction applied to the selected band over time"
- **Input element**: "Input waveform" — "the dark grey waveform displayed behind the output (wet) signal waveform"
- **Output element**: Part of "Scrolling waveform" — "displays the amplitude of the input (uncompressed) and output (compressed) signals over time"
- **Overall display**: "Scrolling Waveform"

### Waves (C1, C6, Renaissance Compressor)
- **GR element**: "Gain Reduction meter" or "Gain Change meter" — "The Gain Change meter shows the instantaneous gain reduction or increase"
- **Input element**: "input meter" / "input level"
- **C6 unique term**: "Dynamic Line" for real-time dynamics visualization
- C1 shows "gain reduction in dB (in red below 0 dB) or gain increase in dB (in yellow above 0 dB)"

### SSL (Solid State Logic) — Hardware and Plugins
- **GR element**: "Gain Reduction meter" — "VU-style Gain Reduction meter displays the amount of gain reduction occurring in the compressor"
- **Input element**: SSL hardware generally does NOT display an input level waveform in the compressor section. GR meter is the primary/only visual feedback.

### Universal Audio (UAD) — 1176 and LA-2A
- **GR element**: "GR" (Gain Reduction) on meter switch — "The VU meter displays the amount of gain reduction (GR) or output level"
- **Input element**: Single VU meter switchable between GR and output level. No simultaneous display.
- 1176 meter positions: "GR", "+4", "+8" (output levels)
- LA-2A: "GR" and output level positions

### Logic Pro Compressor
- **GR element**: "Gain Reduction meter" / "Gain Reduction graph" — "The Gain Reduction meter shows the amount of compression in real time"
- **Input element**: "Input Gain meter" — "displays the real-time level at the compressor input"
- Also has "Input Peak indicator"

### Pro Tools / Avid
- **GR element**: "gain reduction meter" — shown as "orange gain-reduction meters"
- **Input element**: Pro Compressor shows a transfer curve (input vs gain reduction) rather than a real-time input waveform

### dbx 160 (Hardware)
- **GR element**: "GAIN CHANGE" — notably different from most others. Meter switch positions: INPUT, OUTPUT, GAIN CHANGE
- **Input element**: "INPUT" level
- Single switchable meter

### UREI 1176 (Original Hardware)
- **GR element**: "GR" (Gain Reduction) on meter switch
- Single VU meter switchable between GR and output

### Teletronix LA-2A (Original Hardware)
- **GR element**: "GR" (Gain Reduction) on meter switch
- Single VU meter switchable between GR and output

---

## Consensus Summary

### GR Element (top trace):
**Universal term: "Gain Reduction" (GR)**
- Every manufacturer uses this term
- Variations: "Gain Change" (dbx, FabFilter — broader term covering expansion)
- iZotope's "Gain Reduction Trace" specifically names the scrolling visualization

### Input Element (bottom trace):
**Common term: "Input Level"**
- Less standardized — many hardware compressors don't show this at all
- iZotope: "Input Waveform" (most specific)
- FabFilter: "Input"
- Ableton: "Input Signal"
- Logic: "Input Gain meter"
- Waves: "Input Level" / "Input Meter"

### Overall Display Names:
- FabFilter: "Level Display"
- Ableton: "Activity View"
- iZotope: "Scrolling Waveform"

---

## Quick Reference Table — All Plugins

| Plugin / Device | Top Trace (GR) | Bottom Trace (Input) | Overall Display |
|----------------|----------------|---------------------|-----------------|
| FabFilter Pro-C 2/3 | "Gain Reduction" (red line) | "Input" (dark grey) | "Level Display" |
| FabFilter Pro-MB | "Overall Dynamic Frequency Response" (yellow curve) | "Pre-processing signal" (grey fill) | "Spectrum Display" |
| Ableton Compressor | "Gain Reduction" / "GR" (orange) | "Input Signal" (light grey) | "Activity View" |
| Ableton Glue Compressor | "Gain Reduction" (needle) | N/A (no input trace) | "Needle Display" |
| iZotope Neutron 4 | "Gain Reduction Trace" (colored line) | "Input Waveform" (dark grey) | "Scrolling Waveform" |
| iZotope Ozone | "Gain Reduction" (colored lines) | "Input Level" | "Dynamics Display" |
| Waves C1 | "Gain Reduction meter" (red) | "Input Meter" | "Metering Display" |
| Waves C6 | "Dynamic Line" (orange) | "Input Level" | "Dynamic Line Display" |
| Waves Renaissance Comp | "Gain Change meter" | "Input Meter" | "Metering Display" |
| SSL Bus Compressor | "Gain Reduction meter" (VU needle) | N/A (no input trace) | "VU Meter" |
| SSL X-Comp | "Gain Reduction" | "Input Level" | "Metering Display" |
| UAD 1176 | "GR" (VU meter position) | N/A (switchable VU) | "VU Meter" |
| UAD LA-2A | "GR" (VU meter position) | N/A (switchable VU) | "VU Meter" |
| Logic Pro Compressor | "Gain Reduction meter/graph" | "Input Gain meter" | "Compressor Display" |
| Pro Tools Pro Compressor | "Gain Reduction meter" (orange) | N/A (transfer curve instead) | "Transfer Curve / Meters" |
| dbx 160 | "GAIN CHANGE" | "INPUT" (switchable) | "Meter Display" |
| UREI 1176 (hardware) | "GR" | N/A (switchable VU) | "VU Meter" |
| Teletronix LA-2A (hardware) | "GR" | N/A (switchable VU) | "VU Meter" |
| TDR Kotelnikov | "Gain Reduction" (yellow fill) | "Input/Output Level" | "Level Display" |
| Tokyo Dawn Nova | "Gain Reduction" (dark yellow area) | "Analyzer" (blue) | "Spectrum + GR Display" |

---

## Recommended Terminology for This App

| Element | Label | Description |
|---------|-------|-------------|
| Top trace | **GR** or **Gain Reduction** | Per-band colored line dipping from 0 dB |
| Bottom trace | **Input Level** | Per-band audio level waveform from bottom |
| Overall view | **Activity View** or **Level Display** | The second graph mode in MBC |
| Display toggle icons | Frequency view / Activity view | The two graph mode buttons |

---

## Sources
- FabFilter Pro-C 3 Help: https://www.fabfilter.com/help/pro-c/using/displays
- Ableton Live 12 Reference: https://www.ableton.com/en/manual/live-audio-effect-reference/
- iZotope Neutron 4 Help: https://s3.amazonaws.com/izotopedownloads/docs/neutron4/en/compressor/index.html
- Waves C1 User Guide: https://assets.wavescdn.com/pdf/plugins/c1-compressor.pdf
- SSL Bus Compressor 2 Guide: https://support.solidstatelogic.com/hc/en-gb/articles/4408670926109
- UA 1176 Manual: https://help.uaudio.com/hc/en-us/articles/4419447352980
- Logic Pro Compressor: https://support.apple.com/guide/logicpro/compressor-main-parameters-lgcead9636ef/mac
- dbx 160 Manual: https://www.technicalaudio.com/pdf/dbx/dbx_160.pdf
