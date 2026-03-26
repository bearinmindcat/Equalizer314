# Range Feature - Commented Out

## What Was Commented Out

All range-related UI, drawing, touch handling, and hint/popup code has been commented out
across three files. The `range` field in `MbcBandData` and all `EqPreferencesManager` save/load
methods remain active so range values are still persisted for when the feature is re-enabled.

## What the Range Feature Did

The range parameter was a **visual-only** display showing the maximum gain reduction cap for
each MBC (Multiband Compressor) band. It appeared as:

- A **slider** in the band parameters card (Range dB, from -12 to 0)
- A **dashed curve** on the EQ graph below the postGain curve, showing where gain reduction
  would bottom out
- **Colored fill** between the postGain curve and range curve per band
- An **upward-pointing triangle** (range handle) on the graph that could be dragged vertically
- A **hint popup** with a triangle pointer explaining the range concept and showing computed
  threshold/ratio suggestions to achieve the desired range

## Why It Was Removed

Android's `DynamicsProcessing` API has **no native range parameter**. The API only exposes:
threshold, ratio, knee width, attack, release, pre-gain, post-gain, and noise gate per band.

Unlike hardware/plugin compressors (e.g., Fabfilter Pro-C, SSL G-Series) that have a dedicated
range knob limiting how much gain reduction can occur, DynamicsProcessing provides no way to
cap the reduction depth.

### Workarounds That Were Explored

1. **Ratio computation** - Computing a ratio that achieves approximately the desired range at
   a given threshold. The hint popup showed this calculation:
   `needed_ratio = |threshold| / (|threshold| - |range|)`. This works at one specific input
   level but doesn't truly cap reduction at all levels like a real range parameter would.

2. **Visual-only display** - Showing the range curve on the graph as a reference for what
   gain reduction the current threshold+ratio settings produce, without actually enforcing it.
   The hint popup computed `actual_range = -|threshold| * (1 - 1/ratio)` and displayed
   suggestions for achieving the desired range by adjusting threshold or ratio.

3. **Post-gain compensation** - Using post-gain to offset excessive reduction. This shifts
   the entire signal, not just the compressed portion, so it doesn't truly implement range.

None of these workarounds provide true range behavior (a hard floor on gain reduction), so the
feature was shelved to reduce UI complexity and avoid misleading users.

## Files and Sections Affected

### MbcActivity.kt
(`app/src/main/java/com/bearinmind/equalizer314/MbcActivity.kt`)

| Section | Approximate Lines | Description |
|---------|------------------|-------------|
| Variable declarations | ~71-75 | `rangeSlider`, `rangeText`, `rangeHint`, `rangeHintCard` |
| `initViews()` - findViewById calls | ~161-164 | Range view lookups |
| `initViews()` - Triangle drawing | ~166-183 | Triangle pointer bitmap for hint bubble |
| `initViews()` - Range info button | ~184-201 | Click handler and triangle positioning |
| `setupMbcGraph()` - mbcBandRanges | ~256 | `graphView.mbcBandRanges` assignment |
| `setupMbcGraph()` - onMbcBandRangeChanged | ~293-303 | Callback for graph range drag |
| `loadBandToUI()` - range values | ~403-404 | `rangeSlider.value` and `rangeText` |
| `loadBandToUI()` - updateRangeHint | ~427 | `updateRangeHint()` call |
| `setupListeners()` - ratio listener | ~468 | `updateRangeHint()` call in ratio callback |
| `setupListeners()` - threshold listener | ~486 | `updateRangeHint()` call in threshold callback |
| `setupListeners()` - range slider | ~488-495 | Entire `setupSlider(rangeSlider, ...)` block |
| `setupListeners()` - double-tap reset | ~645-650 | `addDoubleTapReset(rangeSlider)` block |
| `updateRangeHint()` | ~714-748 | Entire function (computed suggestions text) |
| `addBand()` | ~784 | `graphView.mbcBandRanges` assignment |
| `performRemoveBand()` | ~972 | `graphView.mbcBandRanges` assignment |

### activity_mbc.xml
(`app/src/main/res/layout/activity_mbc.xml`)

| Section | Approximate Lines | Description |
|---------|------------------|-------------|
| Range slider row | ~279-340 | `mbcRangeInfoButton`, `mbcRangeSlider`, `mbcRangeText` |
| Range hint card | ~342-378 | `mbcRangeHintCard`, `mbcRangeTrianglePointer`, `mbcRangeHint` |

The entire block from "Range (visual only)" through the closing of `mbcRangeHintCard` is
wrapped in `<!-- ... -->` XML comments.

### EqGraphView.kt
(`app/src/main/java/com/bearinmind/equalizer314/ui/EqGraphView.kt`)

| Section | Approximate Lines | Description |
|---------|------------------|-------------|
| `mbcBandRanges` variable | ~57 | Per-band range data array |
| `onMbcBandRangeChanged` callback | ~61 | Range change callback |
| `draggingMbcRange` variable | ~69 | Touch state for range dragging |
| Range paint objects | ~100-116 | `mbcRangeCurvePaint`, `mbcRangeFillPaint`, `mbcRangeDotRingPaint` |
| `drawMbcBands()` - range curve | ~873-941 | Dashed range curve, fill between curves, per-band colored fills |
| `drawMbcBands()` - range triangle | ~1015-1052 | Upward triangle handle, halo, color ring |
| `drawMbcBands()` - isDraggingRange | ~965 | Local variable for range drag state |
| `handleMbcTouch()` - ACTION_DOWN | ~1205, 1211, 1228-1234, 1241 | Range hit detection and drag start |
| `handleMbcTouch()` - ACTION_MOVE | ~1283-1292 | Range drag movement |
| `handleMbcTouch()` - ACTION_UP | ~1309, 1312 | Range drag end, halo animation |

## What Was NOT Commented Out (Still Active)

- `MbcBandData.range` field - still part of the data class
- `EqPreferencesManager.saveMbcBand()` / `getMbcBandRange()` - still saves/loads range values
- `DEFAULT_RANGES` companion object array - still available for defaults
- `range` loading in `loadState()` - still reads from preferences

## How to Re-Enable

1. Uncomment all sections marked with `// RANGE FEATURE COMMENTED OUT` in the Kotlin files
2. Uncomment the `<!-- RANGE FEATURE COMMENTED OUT -->` XML block in `activity_mbc.xml`
3. Rebuild the project

All the code is preserved in place - just remove the comment markers to restore full
functionality. Search for "RANGE FEATURE COMMENTED OUT" across the project to find every
affected section.
