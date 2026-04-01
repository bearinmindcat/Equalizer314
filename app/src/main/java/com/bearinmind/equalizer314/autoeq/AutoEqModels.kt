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

data class AutoEqProfile(
    val preampDb: Float,
    val filters: List<AutoEqFilter>
)
