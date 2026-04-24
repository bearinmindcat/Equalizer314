package com.bearinmind.equalizer314.autoeq

data class AutoEqEntry(
    val name: String,
    val source: String,
    val type: String,
    val rig: String,
    val path: String
)

data class AutoEqFilter(
    val filterType: String,
    val frequency: Float,
    val gain: Float,
    val q: Float
)

/**
 * Parsed representation of an APO config file.
 *
 * - [filters] — flat list of every filter in the file, in source order.
 *   Always populated. For single-channel (no `Channel:` directive) files
 *   this is the authoritative list.
 * - [leftFilters] / [rightFilters] — per-channel buckets when the file
 *   contains `Channel: L` / `Channel: R` directives. Filters scoped to both
 *   (`Channel: L R` or filters before any directive) appear in BOTH lists.
 * - [perChannel] — true iff any `Channel: L` or `Channel: R` line appeared
 *   in the file. When false, [filters] == [leftFilters] == [rightFilters]
 *   (all identical); callers can ignore the split.
 */
data class AutoEqProfile(
    val preampDb: Float,
    val filters: List<AutoEqFilter>,
    val leftFilters: List<AutoEqFilter> = filters,
    val rightFilters: List<AutoEqFilter> = filters,
    val perChannel: Boolean = false,
)
