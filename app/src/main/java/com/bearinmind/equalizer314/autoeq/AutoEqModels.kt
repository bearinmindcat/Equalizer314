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
 * - [filters] — flat list of every filter in source order; always populated,
 *   authoritative for single-channel (no `Channel:` directive) files.
 * - [leftFilters] / [rightFilters] — per-channel EXCLUSIVE buckets for
 *   `Channel: L`/`R` directives.
 * - [sharedFilters] — filters scoped to both channels (`Channel: L R` /
 *   `Channel: All` / before any directive) in a per-channel file; maps to the
 *   app's shared "Both" CSE layer.
 * - [perChannel] — true iff any `Channel: L`/`R` line appeared. When false,
 *   [filters] == [leftFilters] == [rightFilters]; callers can ignore the split.
 */
data class AutoEqProfile(
    val preampDb: Float,
    val filters: List<AutoEqFilter>,
    val leftFilters: List<AutoEqFilter> = filters,
    val rightFilters: List<AutoEqFilter> = filters,
    val perChannel: Boolean = false,
    val sharedFilters: List<AutoEqFilter> = emptyList(),
    /** Preamp lines scoped to a single channel; null = use [preampDb]. */
    val preampLeftDb: Float? = null,
    val preampRightDb: Float? = null,
)
