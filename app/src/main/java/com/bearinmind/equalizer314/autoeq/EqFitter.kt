package com.bearinmind.equalizer314.autoeq

import kotlin.math.*

/**
 * Computes parametric EQ filters to match a measurement to a target curve.
 *
 * Based on AutoEQ (jaakkopasanen/AutoEq) algorithm:
 * - Peak detection by width × height (largest deviation region)
 * - High shelf filter for treble correction
 * - Above 10kHz: both target and FR collapsed to mean (energy matching)
 * - Sharpness penalty prevents unrealistically steep filters
 * - Greedy initialization with residual subtraction
 * - Grid-search optimization (mobile-friendly alternative to SLSQP)
 *
 * Reference: https://github.com/jaakkopasanen/AutoEq
 */
object EqFitter {

    private const val SAMPLE_RATE = 48000
    private const val LOW_SHELF_FC_MIN = 40f
    private const val LOW_SHELF_FC_MAX = 400f
    private const val HIGH_SHELF_FC_MIN = 2000f
    private const val HIGH_SHELF_FC_MAX = 12000f
    private val Q_RANGE = floatArrayOf(0.5f, 10.0f)
    private val GAIN_RANGE = floatArrayOf(-12f, 12f)
    private val FREQ_RANGE = floatArrayOf(20f, 20000f)

    data class Filter(val type: String, val freq: Float, val q: Float, val gain: Float)

    fun computeCorrection(
        measurement: FreqResponse,
        target: FreqResponse,
        numBands: Int = 10
    ): AutoEqProfile {
        // Build common frequency grid (~1/24 octave)
        val step = 1.029
        val gridSize = ceil(ln(20000.0 / 20.0) / ln(step)).toInt()
        val grid = FloatArray(gridSize) { i -> (20.0 * step.pow(i)).toFloat() }

        val measLevels = FreqResponseParser.interpolateAt(measurement, grid)
        val targetLevels = FreqResponseParser.interpolateAt(target, grid)

        val fr = Array(gridSize) { floatArrayOf(grid[it], measLevels[it]) }
        val frTarget = Array(gridSize) { floatArrayOf(grid[it], targetLevels[it]) }

        // Normalize at 1kHz region
        val norm1k = grid.indices.filter { grid[it] in 800f..1200f }
        if (norm1k.isNotEmpty()) {
            val offset = norm1k.map { targetLevels[it] }.average().toFloat() -
                         norm1k.map { measLevels[it] }.average().toFloat()
            for (i in fr.indices) fr[i][1] += offset
        }

        val filters = autoeq(fr, frTarget, numBands)
        val autoEqFilters = filters.map { AutoEqFilter(it.type, it.freq, it.gain, it.q) }
        val preamp = calcPreamp(fr, filters)
        return AutoEqProfile(preamp, autoEqFilters)
    }

    // ---- Biquad coefficients (RBJ cookbook) ----

    private fun peakingCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val sinW = sin(w0); val cosW = cos(w0)
        val a = 10.0.pow(gain / 40.0)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return floatArrayOf(1f, (-2.0 * cosW / a0).toFloat(), ((1.0 - alpha / a) / a0).toFloat(),
            ((1.0 + alpha * a) / a0).toFloat(), (-2.0 * cosW / a0).toFloat(), ((1.0 - alpha * a) / a0).toFloat())
    }

    private fun highShelfCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val sinW = sin(w0); val cosW = cos(w0)
        val a = 10.0.pow(gain / 40.0)
        val alpha = sinW / (2.0 * q)
        val am = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cosW + am
        return floatArrayOf(1f,
            (2.0 * ((a - 1) - (a + 1) * cosW) / a0).toFloat(),
            (((a + 1) - (a - 1) * cosW - am) / a0).toFloat(),
            (a * ((a + 1) + (a - 1) * cosW + am) / a0).toFloat(),
            (-2.0 * a * ((a - 1) + (a + 1) * cosW) / a0).toFloat(),
            (a * ((a + 1) + (a - 1) * cosW - am) / a0).toFloat())
    }

    private fun lowShelfCoeffs(freq: Float, q: Float, gain: Float): FloatArray {
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val sinW = sin(w0); val cosW = cos(w0)
        val a = 10.0.pow(gain / 40.0)
        val alpha = sinW / (2.0 * q)
        val am = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + am
        return floatArrayOf(1f,
            (-2.0 * ((a - 1) + (a + 1) * cosW) / a0).toFloat(),
            (((a + 1) + (a - 1) * cosW - am) / a0).toFloat(),
            (a * ((a + 1) - (a - 1) * cosW + am) / a0).toFloat(),
            (2.0 * a * ((a - 1) - (a + 1) * cosW) / a0).toFloat(),
            (a * ((a + 1) - (a - 1) * cosW - am) / a0).toFloat())
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

    // ---- Magnitude response ----

    private fun calcGains(freqs: FloatArray, filters: List<Filter>): FloatArray {
        val gains = FloatArray(freqs.size)
        for (filt in filters) {
            val coeffs = filterToCoeffs(filt) ?: continue
            val (a0, a1, a2, b0, b1, b2) = coeffs
            for (j in freqs.indices) {
                val w = 2.0 * PI * freqs[j] / SAMPLE_RATE
                val phi = 4.0 * sin(w / 2.0).pow(2)
                val num = (b0 + b1 + b2).pow(2) + (b0 * b2 * phi - (b1 * (b0 + b2) + 4 * b0 * b2)) * phi
                val den = (a0 + a1 + a2).pow(2) + (a0 * a2 * phi - (a1 * (a0 + a2) + 4 * a0 * a2)) * phi
                if (den > 0 && num > 0) gains[j] += (10.0 * log10(num / den)).toFloat()
            }
        }
        return gains
    }

    private operator fun FloatArray.component6() = this[5]

    private fun apply(fr: Array<FloatArray>, filters: List<Filter>): Array<FloatArray> {
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val gains = calcGains(freqs, filters)
        return Array(fr.size) { floatArrayOf(fr[it][0], fr[it][1] + gains[it]) }
    }

    // ---- Loss function (AutoEQ-style: MSE with 10kHz+ energy matching + sharpness penalty) ----

    private fun calcLoss(fr: Array<FloatArray>, frTarget: Array<FloatArray>, filters: List<Filter>): Float {
        val eqFr = apply(fr, filters)
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val ix10k = freqs.indexOfFirst { it >= 10000f }.let { if (it < 0) freqs.size else it }

        // Collapse above 10kHz to mean (AutoEQ: only total energy matters there)
        val targetAbove10kMean = if (ix10k < frTarget.size) (ix10k until frTarget.size).map { frTarget[it][1] }.average().toFloat() else 0f
        val frAbove10kMean = if (ix10k < eqFr.size) (ix10k until eqFr.size).map { eqFr[it][1] }.average().toFloat() else 0f

        var mse = 0f
        var count = 0
        for (i in fr.indices) {
            val t = if (i >= ix10k) targetAbove10kMean else frTarget[i][1]
            val v = if (i >= ix10k) frAbove10kMean else eqFr[i][1]
            mse += (t - v).pow(2)
            count++
        }
        mse /= count

        // Sharpness penalty (AutoEQ: prevents > ~18 dB/octave slopes)
        for (filt in filters) {
            if (filt.type == "PK") {
                val gainLimit = -0.095f + 20.575f * (1f / filt.q)
                if (gainLimit > 0f) {
                    val x = abs(filt.gain) / gainLimit - 1f
                    val sigmoid = 1f / (1f + exp(-x * 100f))
                    mse += filt.gain.pow(2) * sigmoid * 0.01f
                }
            }
        }

        return sqrt(mse)
    }

    // ---- Preamp ----

    private fun calcPreamp(fr: Array<FloatArray>, filters: List<Filter>): Float {
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val gains = calcGains(freqs, filters)
        return -(gains.maxOrNull() ?: 0f)
    }

    // ---- Peak detection (AutoEQ-style: find peaks/dips by width × height) ----

    private data class Peak(val index: Int, val width: Float, val height: Float, val isPositive: Boolean)

    private fun findPeaks(error: FloatArray, freqs: FloatArray): List<Peak> {
        val peaks = mutableListOf<Peak>()
        // Find local maxima and minima
        for (i in 1 until error.size - 1) {
            val isMax = error[i] > error[i - 1] && error[i] > error[i + 1] && error[i] > 0.5f
            val isMin = error[i] < error[i - 1] && error[i] < error[i + 1] && error[i] < -0.5f
            if (!isMax && !isMin) continue

            // Measure width at half height
            val halfH = abs(error[i]) / 2f
            var left = i; var right = i
            while (left > 0 && abs(error[left]) > halfH) left--
            while (right < error.size - 1 && abs(error[right]) > halfH) right++

            val widthOctaves = if (right > left) ln(freqs[right] / freqs[left]) / ln(2f) else 0.1f
            peaks.add(Peak(i, widthOctaves, abs(error[i]), isMax))
        }
        return peaks
    }

    // ---- Initialize high shelf (AutoEQ-style: dot product projection) ----

    private fun initHighShelf(error: FloatArray, freqs: FloatArray, fr: Array<FloatArray>): Filter {
        val minIdx = freqs.indexOfFirst { it >= HIGH_SHELF_FC_MIN }.coerceAtLeast(0)
        val maxIdx = freqs.indexOfLast { it <= HIGH_SHELF_FC_MAX }.let { if (it < 0) freqs.size - 1 else it }

        // Find fc where |mean(error[fc:])| is greatest
        var bestIdx = minIdx
        var bestAbsAvg = 0f
        for (i in minIdx..maxIdx) {
            val avg = (i until error.size).map { error[it] }.average().toFloat()
            if (abs(avg) > bestAbsAvg) {
                bestAbsAvg = abs(avg)
                bestIdx = i
            }
        }

        val fc = freqs[bestIdx]
        // Compute gain via dot product projection (AutoEQ style)
        val shelfFilter = Filter("HSC", fc, 0.7f, 1f)
        val shelfFR = calcGains(freqs, listOf(shelfFilter))
        val dot = (error.indices).sumOf { (error[it] * shelfFR[it]).toDouble() }.toFloat()
        val norm = shelfFR.sumOf { (it * it).toDouble() }.toFloat()
        val gain = if (norm > 0.001f) (dot / norm).coerceIn(GAIN_RANGE[0], GAIN_RANGE[1]) else 0f

        return Filter("HSC", fc, 0.7f, gain)
    }

    // ---- Initialize low shelf for bass (AutoEQ-style: dot product projection) ----

    private fun initLowShelf(error: FloatArray, freqs: FloatArray): Filter {
        val minIdx = freqs.indexOfFirst { it >= LOW_SHELF_FC_MIN }.coerceAtLeast(0)
        val maxIdx = freqs.indexOfLast { it <= LOW_SHELF_FC_MAX }.let { if (it < 0) freqs.size - 1 else it }

        // Find fc where |mean(error[:fc])| is greatest (AutoEQ: average before the point)
        var bestIdx = minIdx
        var bestAbsAvg = 0f
        for (i in minIdx..maxIdx) {
            val avg = (0..i).map { error[it] }.average().toFloat()
            if (abs(avg) > bestAbsAvg) {
                bestAbsAvg = abs(avg)
                bestIdx = i
            }
        }

        val fc = freqs[bestIdx]
        val shelfFilter = Filter("LSC", fc, 0.7f, 1f)
        val shelfFR = calcGains(freqs, listOf(shelfFilter))
        val dot = (error.indices).sumOf { (error[it] * shelfFR[it]).toDouble() }.toFloat()
        val norm = shelfFR.sumOf { (it * it).toDouble() }.toFloat()
        val gain = if (norm > 0.001f) (dot / norm).coerceIn(GAIN_RANGE[0], GAIN_RANGE[1]) else 0f

        return Filter("LSC", fc, 0.7f, gain)
    }

    // ---- Fast error calculation against pre-computed residual ----

    private fun calcResidualError(residual: Array<FloatArray>, frTarget: Array<FloatArray>,
                                   candidateGains: FloatArray, ix10k: Int): Float {
        var mse = 0f
        // Below 10kHz: per-point MSE
        for (i in 0 until ix10k) {
            val v = residual[i][1] + candidateGains[i]
            val d = frTarget[i][1] - v
            mse += d * d
        }
        // Above 10kHz: energy matching (collapse to mean)
        if (ix10k < residual.size) {
            var tSum = 0f; var vSum = 0f; var cnt = 0
            for (i in ix10k until residual.size) {
                tSum += frTarget[i][1]
                vSum += residual[i][1] + candidateGains[i]
                cnt++
            }
            if (cnt > 0) {
                val d = tSum / cnt - vSum / cnt
                mse += d * d * cnt
            }
        }
        return mse / residual.size
    }

    // ---- Optimize a single filter via grid search (pre-computed residual for speed) ----

    private fun optimizeFilter(
        fr: Array<FloatArray>, frTarget: Array<FloatArray>,
        filter: Filter, otherFilters: List<Filter>
    ): Filter {
        // Pre-compute residual (FR with all other filters applied) — only done once
        val residual = apply(fr, otherFilters)
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val ix10k = freqs.indexOfFirst { it >= 10000f }.let { if (it < 0) freqs.size else it }

        var best = filter
        val bestGains = calcGains(freqs, listOf(best))
        var bestError = calcResidualError(residual, frTarget, bestGains, ix10k)

        // Coarse search
        val fcRange = if (filter.type == "HSC") {
            generateSequence(HIGH_SHELF_FC_MIN.toDouble()) { it * 1.15 }
                .takeWhile { it <= HIGH_SHELF_FC_MAX }.map { it.toFloat() }.toList()
        } else if (filter.type == "LSC") {
            generateSequence(LOW_SHELF_FC_MIN.toDouble()) { it * 1.15 }
                .takeWhile { it <= LOW_SHELF_FC_MAX }.map { it.toFloat() }.toList()
        } else {
            val minF = (filter.freq / 2f).coerceAtLeast(FREQ_RANGE[0]).toDouble()
            val maxF = (filter.freq * 2f).coerceAtMost(FREQ_RANGE[1]).toDouble()
            generateSequence(minF) { it * 1.08 }.takeWhile { it <= maxF }.map { it.toFloat() }.toList()
        }

        val qRange = if (filter.type == "HSC" || filter.type == "LSC") {
            listOf(0.5f, 0.7f, 1.0f)
        } else {
            listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f)
        }

        for (fc in fcRange) {
            for (q in qRange) {
                for (gStep in -12..12) {
                    val g = gStep * 1.0f
                    if (g == 0f) continue
                    val candidate = Filter(filter.type, fc, q, g)
                    val gains = calcGains(freqs, listOf(candidate))
                    val error = calcResidualError(residual, frTarget, gains, ix10k)
                    if (error < bestError) { best = candidate; bestError = error }
                }
            }
        }

        // Fine search around best
        val fineFcRange = generateSequence((best.freq * 0.94f).toDouble()) { it * 1.02 }
            .takeWhile { it <= best.freq * 1.06f }.map { it.toFloat() }.toList()
        val fineQRange = listOf(best.q * 0.8f, best.q * 0.9f, best.q, best.q * 1.1f, best.q * 1.2f)
            .map { it.coerceIn(Q_RANGE[0], Q_RANGE[1]) }

        for (fc in fineFcRange) {
            for (q in fineQRange) {
                for (gStep in -5..5) {
                    val g = (best.gain + gStep * 0.2f).coerceIn(GAIN_RANGE[0], GAIN_RANGE[1])
                    if (g == 0f) continue
                    val candidate = Filter(best.type, fc, q, g)
                    val gains = calcGains(freqs, listOf(candidate))
                    val error = calcResidualError(residual, frTarget, gains, ix10k)
                    if (error < bestError) { best = candidate; bestError = error }
                }
            }
        }

        return best
    }

    // ---- Main AutoEQ-style algorithm ----

    private fun autoeq(fr: Array<FloatArray>, frTarget: Array<FloatArray>, maxFilters: Int): List<Filter> {
        val freqs = FloatArray(fr.size) { fr[it][0] }
        val filters = mutableListOf<Filter>()
        val peakingSlots = maxFilters - 2  // Reserve 2 for low shelf + high shelf

        // Step 1: Greedy peaking filter initialization (AutoEQ: biggest peak by width × height)
        var currentFR = fr.map { it.clone() }.toTypedArray()
        for (n in 0 until peakingSlots) {
            val error = FloatArray(freqs.size) { frTarget[it][1] - currentFR[it][1] }
            val peaks = findPeaks(error, freqs)
            if (peaks.isEmpty()) break

            // Select peak with largest width × height (AutoEQ approach)
            val bestPeak = peaks.maxByOrNull { it.width * it.height } ?: break
            if (bestPeak.height < 0.5f) break

            val fc = freqs[bestPeak.index]
            val gain = if (bestPeak.isPositive) bestPeak.height else -bestPeak.height
            // Q from bandwidth
            val bw = bestPeak.width.coerceAtLeast(0.1f)
            val q = (sqrt(2f.pow(bw)) / (2f.pow(bw) - 1f)).coerceIn(Q_RANGE[0], Q_RANGE[1])

            val initial = Filter("PK", fc, q, gain.coerceIn(GAIN_RANGE[0], GAIN_RANGE[1]))
            filters.add(initial)
            currentFR = apply(currentFR, listOf(initial))
        }

        // Step 2: Low shelf for bass + High shelf for treble (AutoEQ style)
        val errorAfterPeaking = FloatArray(freqs.size) { frTarget[it][1] - currentFR[it][1] }
        val lowShelf = initLowShelf(errorAfterPeaking, freqs)
        if (abs(lowShelf.gain) > 0.3f) {
            filters.add(lowShelf)
            currentFR = apply(currentFR, listOf(lowShelf))
        }
        val errorAfterLowShelf = FloatArray(freqs.size) { frTarget[it][1] - currentFR[it][1] }
        val highShelf = initHighShelf(errorAfterLowShelf, freqs, fr)
        if (abs(highShelf.gain) > 0.3f) {
            filters.add(highShelf)
        }

        // Step 3: Optimize each filter (2 passes, AutoEQ uses SLSQP — we use grid search)
        for (pass in 0..1) {
            for (i in filters.indices) {
                val others = filters.filterIndexed { idx, _ -> idx != i }
                filters[i] = optimizeFilter(fr, frTarget, filters[i], others)
            }
        }

        // Step 4: Prune negligible filters
        val pruned = filters.filter { abs(it.gain) > 0.3f }.toMutableList()

        // Step 5: Remove any filter that doesn't improve the loss
        var bestLoss = calcLoss(fr, frTarget, pruned)
        var i = 0
        while (i < pruned.size) {
            val without = pruned.filterIndexed { idx, _ -> idx != i }
            val newLoss = calcLoss(fr, frTarget, without)
            if (newLoss <= bestLoss) {
                pruned.removeAt(i)
                bestLoss = newLoss
            } else i++
        }

        return pruned.map { f ->
            Filter(f.type, roundFreq(f.freq),
                (round(f.q * 10f) / 10f).coerceIn(Q_RANGE[0], Q_RANGE[1]),
                (round(f.gain * 10f) / 10f).coerceIn(GAIN_RANGE[0], GAIN_RANGE[1]))
        }.sortedBy { it.freq }
    }

    private fun roundFreq(freq: Float): Float {
        val unit = when {
            freq < 100f -> 1f
            freq < 1000f -> 10f
            freq < 10000f -> 100f
            else -> 1000f
        }
        return round(freq / unit) * unit
    }
}
