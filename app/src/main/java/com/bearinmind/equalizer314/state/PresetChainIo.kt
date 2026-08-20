package com.bearinmind.equalizer314.state

import android.content.Context
import android.content.Intent
import com.bearinmind.equalizer314.audio.DynamicsProcessingManager
import com.bearinmind.equalizer314.audio.EqService
import org.json.JSONArray
import org.json.JSONObject

/** Optional `mbc`/`limiter` preset blocks — written on save, applied on load/auto-switch/TV sync. */
object PresetChainIo {

    /** Append the current MBC + limiter settings to a preset JSON. */
    fun appendChain(json: JSONObject, p: EqPreferencesManager) {
        val bandCount = p.getMbcBandCount()
        json.put("mbc", JSONObject().apply {
            put("enabled", p.getMbcEnabled())
            put("bandCount", bandCount)
            put("bands", JSONArray().apply {
                for (i in 0 until bandCount) {
                    put(JSONObject().apply {
                        put("enabled", p.getMbcBandEnabled(i))
                        put("cutoff", p.getMbcBandCutoff(i, 1000f).toDouble())
                        put("attack", p.getMbcBandAttack(i).toDouble())
                        put("release", p.getMbcBandRelease(i).toDouble())
                        put("ratio", p.getMbcBandRatio(i).toDouble())
                        put("threshold", p.getMbcBandThreshold(i).toDouble())
                        put("knee", p.getMbcBandKnee(i).toDouble())
                        put("noiseGate", p.getMbcBandNoiseGate(i).toDouble())
                        put("expander", p.getMbcBandExpander(i).toDouble())
                        put("preGain", p.getMbcBandPreGain(i).toDouble())
                        put("postGain", p.getMbcBandPostGain(i).toDouble())
                        put("range", p.getMbcBandRange(i).toDouble())
                    })
                }
            })
            put("crossovers", JSONArray().apply {
                for (i in 0 until bandCount - 1) put(p.getMbcCrossover(i, 1000f).toDouble())
            })
        })
        json.put("limiter", JSONObject().apply {
            put("enabled", p.getLimiterEnabled())
            put("attack", p.getLimiterAttack().toDouble())
            put("release", p.getLimiterRelease().toDouble())
            put("ratio", p.getLimiterRatio().toDouble())
            put("threshold", p.getLimiterThreshold().toDouble())
            put("postGain", p.getLimiterPostGain().toDouble())
        })
    }

    /** Persist + apply a preset's chain blocks; recycles DP on MBC structure change. Returns true if present. */
    fun applyChain(
        context: Context,
        preset: JSONObject,
        p: EqPreferencesManager,
        dm: DynamicsProcessingManager?,
    ): Boolean {
        val mbc = preset.optJSONObject("mbc")
        val limiter = preset.optJSONObject("limiter")
        if (mbc == null && limiter == null) return false

        if (limiter != null) {
            p.saveLimiterEnabled(limiter.optBoolean("enabled", p.getLimiterEnabled()))
            p.saveLimiterAttack(limiter.optDouble("attack", p.getLimiterAttack().toDouble()).toFloat())
            p.saveLimiterRelease(limiter.optDouble("release", p.getLimiterRelease().toDouble()).toFloat())
            p.saveLimiterRatio(limiter.optDouble("ratio", p.getLimiterRatio().toDouble()).toFloat())
            p.saveLimiterThreshold(limiter.optDouble("threshold", p.getLimiterThreshold().toDouble()).toFloat())
            p.saveLimiterPostGain(limiter.optDouble("postGain", p.getLimiterPostGain().toDouble()).toFloat())
        }

        if (mbc != null) {
            val newEnabled = mbc.optBoolean("enabled", p.getMbcEnabled())
            val newCount = mbc.optInt("bandCount", p.getMbcBandCount()).coerceIn(1, 8)
            p.saveMbcEnabled(newEnabled)
            p.saveMbcBandCount(newCount)
            val bands = mbc.optJSONArray("bands")
            if (bands != null) {
                for (i in 0 until minOf(bands.length(), newCount)) {
                    val b = bands.optJSONObject(i) ?: continue
                    p.saveMbcBand(
                        i,
                        b.optBoolean("enabled", true),
                        b.optDouble("cutoff", 1000.0).toFloat(),
                        b.optDouble("attack", 1.0).toFloat(),
                        b.optDouble("release", 100.0).toFloat(),
                        b.optDouble("ratio", 2.0).toFloat(),
                        b.optDouble("threshold", 0.0).toFloat(),
                        b.optDouble("knee", 8.0).toFloat(),
                        b.optDouble("noiseGate", -60.0).toFloat(),
                        b.optDouble("expander", 1.0).toFloat(),
                        b.optDouble("preGain", 0.0).toFloat(),
                        b.optDouble("postGain", 0.0).toFloat(),
                        b.optDouble("range", -12.0).toFloat(),
                    )
                }
            }
            val crossovers = mbc.optJSONArray("crossovers")
            if (crossovers != null) {
                for (i in 0 until crossovers.length()) {
                    p.saveMbcCrossover(i, crossovers.optDouble(i, 1000.0).toFloat())
                }
            }
        }

        // EqService owns the live apply: it syncs limiter + MBC fields from prefs
        // and recycles the DP itself when the MBC structure changed (issue #78).
        try {
            context.startService(
                Intent(context, EqService::class.java).setAction(EqService.ACTION_REAPPLY_MBC)
            )
        } catch (_: Exception) {}
        return true
    }
}
