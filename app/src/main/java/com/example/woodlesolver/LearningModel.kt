package com.example.woodlesolver

import android.content.Context
import kotlin.math.roundToInt

/**
 * V8/V10 lightweight on-device experience model.
 * Learns which structural move types actually cause useful board changes.
 * No network/model download is required; observations are stored in SharedPreferences.
 */
object LearningModel {
    private const val PREFS = "woodle_v10_learning"

    data class Features(
        val pieceScrewCount: Int,
        val releasePotential: Float,
        val revealPotential: Float,
        val overlapRisk: Float,
        val exposure: Float,
        val visibleMatches: Int
    )

    private fun bucket(v: Float): Int = (v.coerceIn(0f, 1f) * 4f).roundToInt().coerceIn(0, 4)

    fun key(f: Features): String = buildString {
        append("p").append(f.pieceScrewCount.coerceIn(1, 5))
        append("_r").append(bucket(f.releasePotential))
        append("_v").append(bucket(f.revealPotential))
        append("_o").append(bucket(f.overlapRisk))
        append("_e").append(bucket(f.exposure))
        append("_m").append(f.visibleMatches.coerceIn(1, 4))
    }

    /** Returns a learned planner bonus roughly in -35..+55. */
    fun bonus(context: Context, f: Features): Float {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(f)
        val n = p.getInt("${k}_n", 0)
        if (n == 0) return 0f
        val mean = p.getFloat("${k}_mean", 0.5f)
        val confidence = (n / 8f).coerceIn(0f, 1f)
        return ((mean - 0.45f) * 100f * confidence).coerceIn(-35f, 55f)
    }

    /** reward: 0 = useless/failed, 1 = very productive structural move. */
    fun observe(context: Context, f: Features, reward: Float) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(f)
        val n = p.getInt("${k}_n", 0)
        val old = p.getFloat("${k}_mean", 0.5f)
        // Capped-count moving average keeps adapting when higher levels behave differently.
        val effectiveN = n.coerceAtMost(24)
        val next = (old * effectiveN + reward.coerceIn(0f, 1f)) / (effectiveN + 1)
        p.edit().putInt("${k}_n", (n + 1).coerceAtMost(10000)).putFloat("${k}_mean", next).apply()
    }
}
