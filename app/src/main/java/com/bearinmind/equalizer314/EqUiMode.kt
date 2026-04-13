package com.bearinmind.equalizer314

enum class EqUiMode {
    PARAMETRIC,  // Full XY drag, filter types, Hz/dB/Q
    GRAPHIC,     // Vertical-only drag, bars from 0dB, dB-only controls
    TABLE,       // Non-interactive graph, table-based editing
    SIMPLE       // Fixed 10-band graphic EQ (31Hz–16kHz), no graph card
}
