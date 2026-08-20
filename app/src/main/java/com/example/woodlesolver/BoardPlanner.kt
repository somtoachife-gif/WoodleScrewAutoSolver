package com.example.woodlesolver

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** V6 full-board planner with uncertainty awareness. */
object BoardPlanner {
    data class Result(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val visibleMatches: Int,
        val searchScore: Float,
        val runnerUpScore: Float,
        val confidence: Float,
        val margin: Float,
        val depth: Int
    ) {
        val safeToTap: Boolean
            get() = confidence >= 0.62f && margin >= 13f && searchScore >= 95f
    }

    private data class Candidate(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val colorCost: Float,
        val localDensity: Int,
        val edgeFreedom: Float
    )

    private data class Scored(val candidate: Candidate, val score: Float)

    fun plan(d: PuzzleDetector.Detection): Result? {
        if (d.state != PuzzleDetector.ScreenState.PUZZLE) return null
        if (d.targets.isEmpty() || d.screws.isEmpty()) return null

        val candidates = buildCandidates(d)
        if (candidates.isEmpty()) return null

        val counts = IntArray(d.targets.size)
        for (c in candidates) counts[c.targetIndex]++

        val beam = candidates.sortedByDescending { immediateScore(it, counts) }.take(12)
        val scored = mutableListOf<Scored>()

        for (first in beam) {
            val remaining1 = candidates.filter { it.screw !== first.screw }
            var futureBest = 0f

            val secondBeam = remaining1.sortedByDescending { immediateScore(it, counts) }.take(8)
            for (second in secondBeam) {
                val remaining2 = remaining1.filter { it.screw !== second.screw }
                var thirdBest = 0f
                for (third in remaining2.sortedByDescending { immediateScore(it, counts) }.take(10)) {
                    val s3 = immediateScore(third, counts) + sequenceScore(first, second, third)
                    if (s3 > thirdBest) thirdBest = s3
                }
                val s2 = immediateScore(second, counts) + thirdBest * 0.38f
                if (s2 > futureBest) futureBest = s2
            }

            scored += Scored(first, immediateScore(first, counts) + futureBest * 0.55f)
        }

        val ranked = scored.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.score ?: (best.score - 40f)
        val margin = (best.score - second).coerceAtLeast(0f)

        val visualConfidence = (best.candidate.screw.score / 1.35f).coerceIn(0f, 1f)
        val colorConfidence = (1f - best.candidate.colorCost / 58f).coerceIn(0f, 1f)
        val optionConfidence = (margin / 30f).coerceIn(0f, 1f)
        val setConfidence = when {
            counts[best.candidate.targetIndex] >= 3 -> 1f
            counts[best.candidate.targetIndex] == 2 -> .72f
            else -> .42f
        }
        val confidence = (
            visualConfidence * .34f +
            colorConfidence * .28f +
            optionConfidence * .23f +
            setConfidence * .15f
        ).coerceIn(0f, 1f)

        return Result(
            screw = best.candidate.screw,
            targetIndex = best.candidate.targetIndex,
            visibleMatches = counts[best.candidate.targetIndex],
            searchScore = best.score,
            runnerUpScore = second,
            confidence = confidence,
            margin = margin,
            depth = 3
        )
    }

    private fun buildCandidates(d: PuzzleDetector.Detection): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (s in d.screws) {
            var bestTarget = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (i in d.targets.indices) {
                val cost = colorDistance(s.hsv, d.targets[i])
                val limit = if (d.targets[i].s < .20f) 58f else 49f
                if (cost < bestCost && cost <= limit) {
                    bestCost = cost
                    bestTarget = i
                }
            }
            if (bestTarget < 0) continue

            val density = d.screws.count { other ->
                other !== s && hypot((other.x-s.x).toDouble(), (other.y-s.y).toDouble()) < 150.0
            }
            val edge = min(abs(s.x - 360f), abs(s.y - 720f)) / 720f
            val freedom = (1f - edge).coerceIn(0f, 1f)
            out += Candidate(s, bestTarget, bestCost, density, freedom)
        }
        return out
    }

    private fun immediateScore(c: Candidate, counts: IntArray): Float {
        val count = counts.getOrElse(c.targetIndex) { 0 }
        val setScore = when {
            count >= 3 -> 110f
            count == 2 -> 52f
            else -> 5f
        }
        return setScore + c.screw.score * 70f + (60f-c.colorCost) + c.localDensity*4.5f + c.edgeFreedom*8f
    }

    private fun sequenceScore(a: Candidate, b: Candidate, c: Candidate): Float {
        var score = 0f
        if (a.targetIndex == b.targetIndex && b.targetIndex == c.targetIndex) score += 95f
        else if (a.targetIndex == b.targetIndex || b.targetIndex == c.targetIndex) score += 25f
        score -= (a.colorCost+b.colorCost+c.colorCost)*.16f
        score += (a.localDensity+b.localDensity+c.localDensity)*1.6f
        return score
    }

    private fun colorDistance(a: PuzzleDetector.Hsv, b: PuzzleDetector.Hsv): Float {
        val satMin = min(a.s,b.s)
        val hueWeight = if (satMin < .18f) .10f else 1f
        return hueDiff(a.h,b.h)*hueWeight + abs(a.s-b.s)*52f + abs(a.v-b.v)*14f
    }

    private fun hueDiff(a: Float,b: Float): Float {
        val d = abs(a-b)%360f
        return min(d,360f-d)
    }
}
