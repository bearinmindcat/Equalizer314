package com.bearinmind.equalizer314.autoeq

import kotlin.math.*

/**
 * Computes parametric EQ filters to match a measurement to a target curve.
 *
 * Ported from Squiglink's equalizer.js algorithm:
 * - 2-stage candidate search (sub-7kHz first, then treble)
 * - Grid-search optimization with 3 delta iterations, bidirectional
 * - Biquad coefficient magnitude response calculation
 * - Filter merging and pruning
 *
 * Reference: https://github.com/squiglink/lab/blob/main/equalizer.js
 */
object EqFitter {

    private const val SAMPLE_RATE = 48000
    private const val TREBLE_START = 7000f
    private val AUTO_EQ_RANGE = floatArrayOf(20f, 15000f)
    private val Q_RANGE = floatArrayOf(0.5f, 2.0f)
    private val GAIN_RANGE = floatArrayOf(-12f, 12f)

    // [maxDF, maxDQ, maxDG, stepDF, stepDQ, stepDG] — reduced from Squiglink for mobile perf
    private val OPTIMIZE_DELTAS = arrayOf(
        floatArrayOf(5f, 5f, 8f, 5f, 0.1f, 0.5f),
        floatArrayOf(5f, 5f, 8f, 1f, 0.1f, 0.2f)
    )

    data class Filter(val type: String, val freq: Float, val q: Float, val gain: Float)

    fun computeCorrection(
        measurement: FreqResponse,
        target: FreqResponse,
        numBands: Int = 10
    ): AutoEqProfile {
        // Build common frequency grid (~1/24 octave for mobile performance)
        val step = 1.029  // ~1/24 octave (~240 points from 20-20kHz)
        val gridSize = ceil(ln(20000.0 / 20.0) / ln(step)).toInt()
        val grid = FloatArray(gridSize) { i -> (20.0 * step.pow(i)).toFloat() }

        // Resample both curves onto the grid
        val measLevels = FreqResponseParser.interpolateAt(measurement, grid)
        val targetLevels = FreqResponseParser.interpolateAt(target, grid)

        // Build FR and target as [freq, dB] arrays
        val fr = Array(gridSize) { floatArrayOf(grid[it], measLevels[it]) }
        val frTarget = Array(gridSize) { floatArrayOf(grid[it], targetLevels[it]) }

        // Normalize: align measurement to target at 1kHz region
        val norm1k = grid.indices.filter { grid[it] in 800f..1200f }
        if (norm1k.isNotEmpty()) {
            val measMean = norm1k.map { measLevels[it] }.average().toFloat()
            val targetMean = norm1k.map { targetLevels[it] }.average().toFloat()
            val offset = targetMean - measMean
            for (i in fr.indices) fr[i][1] += offset
        }

        // Run AutoEQ algorithm
        val filters = autoeq(fr, frTarget, numBands)

        // Convert to AutoEqProfile
        val autoEqFilters = filters.map { f ->
            AutoEqFilter(f.type, f.freq, f.gain, f.q)
        }

        // Compute preamp
        val preamp = calcPreamp(fr, frTarget, filters)

        return AutoEqProfile(preamp, autoEqFilters)
    }

    // ---- Biquad coefficient calculations (cookbook formulas) ----

    private fun peakingCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val f = (freq / SAMPLE_RATE).coerceIn(1e-6f, 1f)
        val qc = q.coerceIn(1e-4f, 1000f)
        val gc = gain.coerceIn(-40f, 40f)
        val w0 = 2.0 * PI * f
        val sinW = sin(w0)
        val cosW = cos(w0)
        val a = 10.0.pow(gc / 40.0)
        val alpha = sinW / (2.0 * qc)
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        return floatArrayOf(1f, (a1 / a0).toFloat(), (a2 / a0).toFloat(), (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat())
    }

    private fun lowShelfCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val f = (freq / SAMPLE_RATE).coerceIn(1e-6f, 1f)
        val qc = q.coerceIn(1e-4f, 1000f)
        val gc = gain.coerceIn(-40f, 40f)
        val w0 = 2.0 * PI * f
        val sinW = sin(w0)
        val cosW = cos(w0)
        val a = 10.0.pow(gc / 40.0)
        val alpha = sinW / (2.0 * qc)
        val alphamod = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + alphamod
        val a1 = -2.0 * ((a - 1) + (a + 1) * cosW)
        val a2 = (a + 1) + (a - 1) * cosW - alphamod
        val b0 = a * ((a + 1) - (a - 1) * cosW + alphamod)
        val b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW)
        val b2 = a * ((a + 1) - (a - 1) * cosW - alphamod)
        return floatArrayOf(1f, (a1 / a0).toFloat(), (a2 / a0).toFloat(), (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat())
    }

    private fun highShelfCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val f = (freq / SAMPLE_RATE).coerceIn(1e-6f, 1f)
        val qc = q.coerceIn(1e-4f, 1000f)
        val gc = gain.coerceIn(-40f, 40f)
        val w0 = 2.0 * PI * f
        val sinW = sin(w0)
        val cosW = cos(w0)
        val a = 10.0.pow(gc / 40.0)
        val alpha = sinW / (2.0 * qc)
        val alphamod = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cosW + alphamod
        val a1 = 2.0 * ((a - 1) - (a + 1) * cosW)
        val a2 = (a + 1) - (a - 1) * cosW - alphamod
        val b0 = a * ((a + 1) + (a - 1) * cosW + alphamod)
        val b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
        val b2 = a * ((a + 1) + (a - 1) * cosW - alphamod)
        return floatArrayOf(1f, (a1 / a0).toFloat(), (a2 / a0).toFloat(), (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat())
    }

    private fun filterToCoeffs(f: Filter): FloatArray? {
        if (f.freq == 0f || f.gain == 0f || f.q == 0f) return null
        return when (f.type) {
            "LSC" -> lowShelfCoeffs(f.freq, f.q, f.gain)
            "HSC" -> highShelfCoeffs(f.freq, f.q, f.gain)
            "PK" -> peakingCoeffs(f.freq, f.q, f.gain)
            else -> null
        }
    }

    // ---- Magnitude response from biquad coefficients ----

    private fun calcGains(freqs: FloatArray, filters: List<Filter>): FloatArray {
        val gains = FloatArray(freqs.size)
        val coeffsList = filters.mapNotNull { filterToCoeffs(it) }
        for (coeffs in coeffsList) {
            val (a0, a1, a2, b0, b1, b2) = coeffs
            for (j in freqs.indices) {
                val w = 2.0 * PI * freqs[j] / SAMPLE_RATE
                val phi = 4.0 * sin(w / 2.0).pow(2)
                val num = (b0 + b1 + b2).pow(2) + (b0 * b2 * phi - (b1 * (b0 + b2) + 4 * b0 * b2)) * phi
                val den = (a0 + a1 + a2).pow(2) + (a0 * a2 * phi - (a1 * (a0 + a2) + 4 * a0 * a2)) * phi
                if (den > 0 && num > 0) {
                    gains[j] += (10.0 * log10(num / den)).toFloat()
                }
            }
        }
        return gains
    }

    private operator fun FloatArray.component6() = this[5]

    // ---- Apply filters to FR ----

    private fun apply(fr: Array<FloatArray>, filters: List<Filter>): Array<FloatArray> {
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val gains = calcGains(freqs, filters)
        return Array(fr.size) { floatArrayOf(fr[it][0], fr[it][1] + gains[it]) }
    }

    // ---- Distance (mean absolute error, ignoring < 0.1dB) ----

    private fun calcDistance(fr1: Array<FloatArray>, fr2: Array<FloatArray>): Float {
        var distance = 0f
        for (i in fr1.indices) {
            val d = abs(fr1[i][1] - fr2[i][1])
            distance += if (d >= 0.1f) d else 0f
        }
        return distance / fr1.size
    }

    // ---- Preamp ----

    private fun calcPreamp(fr: Array<FloatArray>, frTarget: Array<FloatArray>, filters: List<Filter>): Float {
        val eqFr = apply(fr, filters)
        var maxGain = Float.NEGATIVE_INFINITY
        for (i in eqFr.indices) {
            maxGain = max(maxGain, eqFr[i][1] - fr[i][1])
        }
        return -maxGain
    }

    // ---- Frequency unit for rounding ----

    private fun freqUnit(freq: Float): Float = when {
        freq < 100f -> 1f
        freq < 1000f -> 10f
        freq < 10000f -> 100f
        else -> 1000f
    }

    // ---- Strip: round values for cleaner output ----

    private fun strip(filters: List<Filter>): List<Filter> {
        return filters.map { f ->
            Filter(
                f.type,
                floor(f.freq - f.freq % freqUnit(f.freq)),
                min(max(floor(f.q * 10f) / 10f, Q_RANGE[0]), Q_RANGE[1]),
                min(max(floor(f.gain * 10f) / 10f, GAIN_RANGE[0]), GAIN_RANGE[1])
            )
        }
    }

    // ---- Interpolation helper ----

    private fun interp(fv: FloatArray, fr: Array<FloatArray>): Array<FloatArray> {
        var i = 0
        return Array(fv.size) { idx ->
            val f = fv[idx]
            var result = floatArrayOf(f, fr.last()[1])
            for (j in i until fr.size - 1) {
                val (f0, v0) = fr[j]
                val (f1, v1) = fr[j + 1]
                if (j == 0 && f < f0) {
                    result = floatArrayOf(f, v0)
                    break
                } else if (f >= f0 && f < f1) {
                    val v = v0 + (v1 - v0) * (f - f0) / (f1 - f0)
                    result = floatArrayOf(f, v)
                    i = j
                    break
                }
            }
            result
        }
    }

    // ---- Search for peak/dip candidates ----

    private fun searchCandidates(fr: Array<FloatArray>, frTarget: Array<FloatArray>, threshold: Float): List<Filter> {
        var state = 0  // 1: peak, 0: matched, -1: dip
        var startIndex = -1
        val candidates = mutableListOf<Filter>()
        val (minFreq, maxFreq) = AUTO_EQ_RANGE

        for (i in fr.indices) {
            val f = fr[i][0]
            val v0 = fr[i][1]
            val v1 = frTarget[i][1]
            val delta = v0 - v1
            val deltaAbs = abs(delta)
            val nextState = if (deltaAbs < threshold) 0 else if (delta > 0) 1 else -1

            if (nextState == state) continue

            if (startIndex >= 0) {
                if (state != 0) {
                    val start = fr[startIndex][0]
                    val end = f
                    val center = sqrt(start * end)
                    val centerArr = floatArrayOf(center)
                    val targetSlice = frTarget.sliceArray(startIndex until i)
                    val frSlice = fr.sliceArray(startIndex until i)
                    val gain = interp(centerArr, targetSlice)[0][1] - interp(centerArr, frSlice)[0][1]
                    val q = center / (end - start)
                    if (center in minFreq..maxFreq) {
                        candidates.add(Filter("PK", center, q, gain))
                    }
                }
                startIndex = -1
            } else {
                startIndex = i
            }
            state = nextState
        }
        return candidates
    }

    // ---- Optimize filters ----

    private fun optimize(
        fr: Array<FloatArray>, frTarget: Array<FloatArray>,
        inputFilters: List<Filter>, iteration: Int, dir: Int = 0
    ): List<Filter> {
        val filters = strip(inputFilters).toMutableList()
        val (minFreq, maxFreq) = AUTO_EQ_RANGE
        val (minQ, maxQ) = Q_RANGE
        val (minGain, maxGain) = GAIN_RANGE
        val deltas = OPTIMIZE_DELTAS[iteration]
        val maxDF = deltas[0].toInt()
        val maxDQ = deltas[1].toInt()
        val maxDG = deltas[2].toInt()
        val stepDF = deltas[3]
        val stepDQ = deltas[4]
        val stepDG = deltas[5]

        val indices = if (dir == 1) (filters.size - 1 downTo 0) else (0 until filters.size)

        for (i in indices) {
            val f = filters[i]
            // FR with all filters except this one
            val otherFilters = filters.filterIndexed { idx, _ -> idx != i }
            val fr1 = apply(fr, otherFilters)
            val fr2 = apply(fr1, listOf(f))
            var bestFilter = f
            var bestDistance = calcDistance(fr2, frTarget)

            for (df in -maxDF until maxDF) {
                for (dq in maxDQ - 1 downTo -maxDQ) {
                    // Test positive gains
                    for (dg in 1 until maxDG) {
                        val freq = f.freq + df * freqUnit(f.freq) * stepDF
                        val q = f.q + dq * stepDQ
                        val gain = f.gain + dg * stepDG
                        if (freq < minFreq || freq > maxFreq || q < minQ || q > maxQ || gain < minGain || gain > maxGain) break
                        val newFilter = Filter(f.type, freq, q, gain)
                        val newFR = apply(fr1, listOf(newFilter))
                        val newDistance = calcDistance(newFR, frTarget)
                        if (newDistance < bestDistance) {
                            bestFilter = newFilter
                            bestDistance = newDistance
                        } else break
                    }
                    // Test negative gains
                    for (dg in -1 downTo -maxDG) {
                        val freq = f.freq + df * freqUnit(f.freq) * stepDF
                        val q = f.q + dq * stepDQ
                        val gain = f.gain + dg * stepDG
                        if (freq < minFreq || freq > maxFreq || q < minQ || q > maxQ || gain < minGain || gain > maxGain) break
                        val newFilter = Filter(f.type, freq, q, gain)
                        val newFR = apply(fr1, listOf(newFilter))
                        val newDistance = calcDistance(newFR, frTarget)
                        if (newDistance < bestDistance) {
                            bestFilter = newFilter
                            bestDistance = newDistance
                        } else break
                    }
                }
            }
            filters[i] = bestFilter
        }

        if (dir == 0) {
            return optimize(fr, frTarget, filters, iteration, 1)
        }

        // Sort by frequency
        filters.sortBy { it.freq }

        // Merge adjacent filters with similar freq and Q
        var i = 0
        while (i < filters.size - 1) {
            val f1 = filters[i]
            val f2 = filters[i + 1]
            if (abs(f1.freq - f2.freq) <= freqUnit(f1.freq) && abs(f1.q - f2.q) <= 0.1f) {
                filters[i] = Filter(f1.type, f1.freq, f1.q, f1.gain + f2.gain)
                filters.removeAt(i + 1)
            } else {
                i++
            }
        }

        // Remove unnecessary filters
        var bestDistance = calcDistance(apply(fr, filters), frTarget)
        i = 0
        while (i < filters.size) {
            if (abs(filters[i].gain) <= 0.1f) {
                filters.removeAt(i)
                continue
            }
            val withoutThis = filters.filterIndexed { idx, _ -> idx != i }
            val newDistance = calcDistance(apply(fr, withoutThis), frTarget)
            if (newDistance < bestDistance) {
                filters.removeAt(i)
                bestDistance = newDistance
            } else {
                i++
            }
        }

        return filters
    }

    // ---- Main AutoEQ algorithm (2-stage) ----

    private fun autoeq(fr: Array<FloatArray>, frTarget: Array<FloatArray>, maxFilters: Int): List<Filter> {
        // First batch: sub-7kHz, wider bandwidth first
        val firstBatchSize = max(maxFilters / 2 - 1, 1)
        val firstCandidates = searchCandidates(fr, frTarget, 1f)
        var firstFilters = firstCandidates
            .filter { it.freq <= TREBLE_START }
            .sortedBy { it.q }
            .take(firstBatchSize)
            .sortedBy { it.freq }

        for (i in OPTIMIZE_DELTAS.indices) {
            firstFilters = optimize(fr, frTarget, firstFilters, i)
        }

        // Second batch: remaining filters on the residual
        val secondFR = apply(fr, firstFilters)
        val secondBatchSize = maxFilters - firstFilters.size
        val secondCandidates = searchCandidates(secondFR, frTarget, 0.5f)
        var secondFilters = secondCandidates
            .sortedBy { it.q }
            .take(secondBatchSize)
            .sortedBy { it.freq }

        for (i in OPTIMIZE_DELTAS.indices) {
            secondFilters = optimize(secondFR, frTarget, secondFilters, i)
        }

        // Final: optimize all filters together
        var allFilters = firstFilters + secondFilters
        for (i in OPTIMIZE_DELTAS.indices) {
            allFilters = optimize(fr, frTarget, allFilters, i)
        }

        return strip(allFilters)
    }
}
