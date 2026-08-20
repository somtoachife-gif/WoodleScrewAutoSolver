package com.example.woodlesolver

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * V5 board-search planner.
 *
 * The vision layer first scans the full safe puzzle region and returns every
 * screw-shaped object it can see. This planner then evaluates the entire visible
 * board instead of greedily choosing the strongest single color match.
 *
 * Because Woodle uses animation/physics and can reveal previously hidden screws,
 * V5 intentionally replans from a fresh screenshot after every real move. The
 * search below evaluates several future removals on the currently visible board,
 * then closed-loop vision corrects the model after the tap.
 */
object BoardPlanner {
    data class Result(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val visibleMatches: Int,
        val searchScore: Float,
        val depth: Int
    )

    private data class Candidate(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val colorCost: Float,
        val localDensity: Int,
        val edgeFreedom: Float
    )

    fun plan(d: PuzzleDetector.Detection): Result? {
        if (d.state != PuzzleDetector.ScreenState.PUZZLE) return null
        if (d.targets.isEmpty() || d.screws.isEmpty()) return null

        val candidates = buildCandidates(d)
        if (candidates.isEmpty()) return null

        val counts = IntArray(d.targets.size)
        for (c in candidates) counts[c.targetIndex]++

        // Beam-search across several visible future removals. We cannot perfectly
        // simulate falling wooden pieces from pixels alone, so the score favors
        // moves that complete a set of 3, preserve options, and remove screws from
        // crowded/central areas that are more likely to expose additional board.
        val beam = candidates
            .sortedByDescending { immediateScore(it, counts) }
            .take(12)

        var best: Candidate? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (first in beam) {
            val remaining1 = candidates.filter { it.screw !== first.screw }
            var futureBest = 0f

            val secondBeam = remaining1
                .sortedByDescending { immediateScore(it, counts) }
                .take(8)

            for (second in secondBeam) {
                val remaining2 = remaining1.filter { it.screw !== second.screw }
                var thirdBest = 0f
                for (third in remaining2.take(10)) {
                    val sequenceBonus = sequenceScore(first, second, third)
                    val s3 = immediateScore(third, counts) + sequenceBonus
                    if (s3 > thirdBest) thirdBest = s3
                }

                val s2 = immediateScore(second, counts) + thirdBest * 0.38f
                if (s2 > futureBest) futureBest = s2
            }

            val total = immediateScore(first, counts) + futureBest * 0.55f
            if (total > bestScore) {
                bestScore = total
                best = first
            }
        }

        val chosen = best ?: return null
        return Result(
            screw = chosen.screw,
            targetIndex = chosen.targetIndex,
            visibleMatches = counts[chosen.targetIndex],
            searchScore = bestScore,
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

            // Lower/outer screws are often easier/free; central dense screws can
            // expose more board. Keep both signals instead of hard-coding one rule.
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

        val visual = c.screw.score * 70f
        val color = 60f - c.colorCost
        val exposure = c.localDensity * 4.5f
        val freedom = c.edgeFreedom * 8f
        return setScore + visual + color + exposure + freedom
    }

    private fun sequenceScore(a: Candidate, b: Candidate, c: Candidate): Float {
        var score = 0f
        // Strongly reward planning a full 3-of-a-kind sequence.
        if (a.targetIndex == b.targetIndex && b.targetIndex == c.targetIndex) score += 95f
        else if (a.targetIndex == b.targetIndex || b.targetIndex == c.targetIndex) score += 25f

        // Avoid bouncing among weakly matched colors unless it creates options.
        score -= (a.colorCost + b.colorCost + c.colorCost) * .16f
        score += (a.localDensity + b.localDensity + c.localDensity) * 1.6f
        return score
    }

    private fun colorDistance(a: PuzzleDetector.Hsv, b: PuzzleDetector.Hsv): Float {
        val satMin = min(a.s, b.s)
        val hueWeight = if (satMin < .18f) .10f else 1f
        return hueDiff(a.h, b.h) * hueWeight + abs(a.s-b.s)*52f + abs(a.v-b.v)*14f
    }

    private fun hueDiff(a: Float, b: Float): Float {
        val d = abs(a-b) % 360f
        return min(d, 360f-d)
    }
}
