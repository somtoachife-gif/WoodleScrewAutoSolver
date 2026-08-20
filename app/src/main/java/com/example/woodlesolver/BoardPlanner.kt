package com.example.woodlesolver

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** V7 full-board planner with uncertainty + piece/layer awareness. */
object BoardPlanner {
    data class Result(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val visibleMatches: Int,
        val searchScore: Float,
        val runnerUpScore: Float,
        val confidence: Float,
        val margin: Float,
        val depth: Int,
        val pieceId: Int,
        val pieceScrewCount: Int,
        val releasePotential: Float,
        val revealPotential: Float
    ) {
        val safeToTap: Boolean
            get() = confidence >= 0.64f && margin >= 12f && searchScore >= 100f
    }

    private data class Candidate(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val colorCost: Float,
        val localDensity: Int,
        val edgeFreedom: Float,
        val structure: PieceAnalyzer.ScrewStructure
    )

    private data class Scored(val candidate: Candidate, val score: Float)

    fun plan(d: PuzzleDetector.Detection, structure: PieceAnalyzer.Analysis): Result? {
        if (d.state != PuzzleDetector.ScreenState.PUZZLE) return null
        if (d.targets.isEmpty() || d.screws.isEmpty()) return null

        val candidates = buildCandidates(d, structure)
        if (candidates.isEmpty()) return null

        val counts = IntArray(d.targets.size)
        for (c in candidates) counts[c.targetIndex]++

        // V7 keeps the 3-ply beam search, but now each branch knows whether the
        // move is likely to free a whole piece or reveal hidden board beneath it.
        val beam = candidates.sortedByDescending { immediateScore(it, counts) }.take(14)
        val scored = mutableListOf<Scored>()

        for (first in beam) {
            val remaining1 = candidates.filter { it.screw !== first.screw }
            var futureBest = 0f

            val secondBeam = remaining1.sortedByDescending { immediateScore(it, counts) }.take(9)
            for (second in secondBeam) {
                val remaining2 = remaining1.filter { it.screw !== second.screw }
                var thirdBest = 0f
                for (third in remaining2.sortedByDescending { immediateScore(it, counts) }.take(11)) {
                    val s3 = immediateScore(third, counts) + sequenceScore(first, second, third)
                    if (s3 > thirdBest) thirdBest = s3
                }
                val s2 = immediateScore(second, counts) + thirdBest * .40f
                if (s2 > futureBest) futureBest = s2
            }

            scored += Scored(first, immediateScore(first, counts) + futureBest * .57f)
        }

        val ranked = scored.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.score ?: (best.score - 40f)
        val margin = (best.score - second).coerceAtLeast(0f)

        val visualConfidence = (best.candidate.screw.score / 1.35f).coerceIn(0f, 1f)
        val colorConfidence = (1f - best.candidate.colorCost / 58f).coerceIn(0f, 1f)
        val optionConfidence = (margin / 28f).coerceIn(0f, 1f)
        val structureConfidence = (
            best.candidate.structure.exposure*.35f +
            best.candidate.structure.releasePotential*.35f +
            best.candidate.structure.revealPotential*.30f
        ).coerceIn(0f,1f)
        val setConfidence = when {
            counts[best.candidate.targetIndex] >= 3 -> 1f
            counts[best.candidate.targetIndex] == 2 -> .72f
            else -> .42f
        }
        val confidence = (
            visualConfidence*.26f +
            colorConfidence*.23f +
            optionConfidence*.19f +
            setConfidence*.12f +
            structureConfidence*.20f
        ).coerceIn(0f,1f)

        val st = best.candidate.structure
        return Result(
            screw = best.candidate.screw,
            targetIndex = best.candidate.targetIndex,
            visibleMatches = counts[best.candidate.targetIndex],
            searchScore = best.score,
            runnerUpScore = second,
            confidence = confidence,
            margin = margin,
            depth = 3,
            pieceId = st.pieceId,
            pieceScrewCount = st.pieceScrewCount,
            releasePotential = st.releasePotential,
            revealPotential = st.revealPotential
        )
    }

    private fun buildCandidates(
        d: PuzzleDetector.Detection,
        analysis: PieceAnalyzer.Analysis
    ): List<Candidate> {
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
            val freedom = (1f-edge).coerceIn(0f,1f)
            val st = analysis.byScrew[s] ?: PieceAnalyzer.ScrewStructure(
                pieceId=-1,
                pieceScrewCount=3,
                exposure=.5f,
                overlapRisk=.5f,
                releasePotential=.45f,
                revealPotential=.45f
            )
            out += Candidate(s,bestTarget,bestCost,density,freedom,st)
        }
        return out
    }

    private fun immediateScore(c: Candidate, counts: IntArray): Float {
        val count = counts.getOrElse(c.targetIndex){0}
        val setScore = when {
            count >= 3 -> 110f
            count == 2 -> 52f
            else -> 5f
        }

        val structural =
            c.structure.releasePotential*42f +
            c.structure.revealPotential*48f +
            c.structure.exposure*18f -
            c.structure.overlapRisk*7f

        // Extra reward for a piece that is down to one visible screw; these moves
        // are most likely to remove/drop an entire piece and uncover more board.
        val freePieceBonus = when(c.structure.pieceScrewCount) {
            1 -> 36f
            2 -> 18f
            else -> 0f
        }

        return setScore + c.screw.score*70f + (60f-c.colorCost) +
            c.localDensity*4.0f + c.edgeFreedom*7f + structural + freePieceBonus
    }

    private fun sequenceScore(a:Candidate,b:Candidate,c:Candidate):Float {
        var score=0f
        if(a.targetIndex==b.targetIndex && b.targetIndex==c.targetIndex) score+=95f
        else if(a.targetIndex==b.targetIndex || b.targetIndex==c.targetIndex) score+=25f

        // Prefer sequences that finish/open a structural piece rather than tapping
        // three unrelated screws that only happen to share a color.
        if(a.structure.pieceId==b.structure.pieceId) score+=18f
        if(b.structure.pieceId==c.structure.pieceId) score+=14f
        score += (a.structure.revealPotential+b.structure.revealPotential+c.structure.revealPotential)*12f
        score += (a.structure.releasePotential+b.structure.releasePotential+c.structure.releasePotential)*10f

        score -= (a.colorCost+b.colorCost+c.colorCost)*.16f
        score += (a.localDensity+b.localDensity+c.localDensity)*1.4f
        return score
    }

    private fun colorDistance(a:PuzzleDetector.Hsv,b:PuzzleDetector.Hsv):Float {
        val satMin=min(a.s,b.s)
        val hueWeight=if(satMin<.18f).10f else 1f
        return hueDiff(a.h,b.h)*hueWeight + abs(a.s-b.s)*52f + abs(a.v-b.v)*14f
    }

    private fun hueDiff(a:Float,b:Float):Float {
        val d=abs(a-b)%360f
        return min(d,360f-d)
    }
}
