package com.example.woodlesolver

import android.content.Context
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** V10 combined planner: V7 structure + V8 learning + V9 deeper search. */
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
        val revealPotential: Float,
        val overlapRisk: Float,
        val exposure: Float,
        val learningBonus: Float
    ) {
        val safeToTap: Boolean
            get() = confidence >= 0.64f && margin >= 10f && searchScore >= 105f

        fun learningFeatures(): LearningModel.Features = LearningModel.Features(
            pieceScrewCount, releasePotential, revealPotential, overlapRisk, exposure, visibleMatches
        )
    }

    private data class Candidate(
        val screw: PuzzleDetector.Screw,
        val targetIndex: Int,
        val colorCost: Float,
        val localDensity: Int,
        val edgeFreedom: Float,
        val structure: PieceAnalyzer.ScrewStructure,
        val learningBonus: Float
    )

    private data class Scored(val candidate: Candidate, val score: Float)

    fun plan(context: Context, d: PuzzleDetector.Detection, structure: PieceAnalyzer.Analysis): Result? {
        if (d.state != PuzzleDetector.ScreenState.PUZZLE || d.targets.isEmpty() || d.screws.isEmpty()) return null

        val candidates = buildCandidates(context, d, structure)
        if (candidates.isEmpty()) return null

        val counts = IntArray(d.targets.size)
        for (c in candidates) counts[c.targetIndex]++

        // V9: deeper 5-ply beam search. The beam stays deliberately narrow so it remains fast on-phone.
        val rankedFirst = candidates.sortedByDescending { immediateScore(it, counts) }.take(14)
        val scored = mutableListOf<Scored>()
        for (first in rankedFirst) {
            val remaining = candidates.filter { it.screw !== first.screw }
            val future = bestFuture(remaining, counts, depth = 4, beamWidth = 7, previous = listOf(first))
            scored += Scored(first, immediateScore(first, counts) + future * .56f)
        }

        val ranked = scored.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.score ?: best.score - 35f
        val margin = (best.score - second).coerceAtLeast(0f)
        val st = best.candidate.structure

        val visualConfidence = (best.candidate.screw.score / 1.35f).coerceIn(0f, 1f)
        val colorConfidence = (1f - best.candidate.colorCost / 58f).coerceIn(0f, 1f)
        val optionConfidence = (margin / 26f).coerceIn(0f, 1f)
        val structureConfidence = (st.exposure*.30f + st.releasePotential*.34f + st.revealPotential*.28f + (1f-st.overlapRisk)*.08f).coerceIn(0f,1f)
        val setConfidence = when {
            counts[best.candidate.targetIndex] >= 3 -> 1f
            counts[best.candidate.targetIndex] == 2 -> .72f
            else -> .42f
        }
        val learnedConfidence = ((best.candidate.learningBonus + 35f) / 90f).coerceIn(0f,1f)
        val confidence = (visualConfidence*.23f + colorConfidence*.20f + optionConfidence*.17f + setConfidence*.12f + structureConfidence*.20f + learnedConfidence*.08f).coerceIn(0f,1f)

        return Result(
            screw = best.candidate.screw,
            targetIndex = best.candidate.targetIndex,
            visibleMatches = counts[best.candidate.targetIndex],
            searchScore = best.score,
            runnerUpScore = second,
            confidence = confidence,
            margin = margin,
            depth = 5,
            pieceId = st.pieceId,
            pieceScrewCount = st.pieceScrewCount,
            releasePotential = st.releasePotential,
            revealPotential = st.revealPotential,
            overlapRisk = st.overlapRisk,
            exposure = st.exposure,
            learningBonus = best.candidate.learningBonus
        )
    }

    private fun bestFuture(
        remaining: List<Candidate>, counts: IntArray, depth: Int, beamWidth: Int, previous: List<Candidate>
    ): Float {
        if (depth <= 0 || remaining.isEmpty()) return 0f
        var best = 0f
        val beam = remaining.sortedByDescending { immediateScore(it, counts) }.take(beamWidth)
        for (c in beam) {
            var s = immediateScore(c, counts)
            if (previous.isNotEmpty()) s += pairSequenceScore(previous.last(), c)
            if (previous.size >= 2) s += tripleSequenceScore(previous[previous.size-2], previous.last(), c)
            val next = remaining.filter { it.screw !== c.screw }
            val future = bestFuture(next, counts, depth-1, (beamWidth-1).coerceAtLeast(3), (previous + c).takeLast(3))
            val total = s + future * .43f
            if (total > best) best = total
        }
        return best
    }

    private fun buildCandidates(context: Context, d: PuzzleDetector.Detection, analysis: PieceAnalyzer.Analysis): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (s in d.screws) {
            var bestTarget = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (i in d.targets.indices) {
                val cost = colorDistance(s.hsv, d.targets[i])
                val limit = if (d.targets[i].s < .20f) 58f else 49f
                if (cost < bestCost && cost <= limit) { bestCost = cost; bestTarget = i }
            }
            if (bestTarget < 0) continue

            val density = d.screws.count { other -> other !== s && hypot((other.x-s.x).toDouble(), (other.y-s.y).toDouble()) < 150.0 }
            val edge = min(abs(s.x-360f), abs(s.y-720f))/720f
            val freedom = (1f-edge).coerceIn(0f,1f)
            val st = analysis.byScrew[s] ?: PieceAnalyzer.ScrewStructure(-1,3,.5f,.5f,.45f,.45f)
            val matches = d.screws.count { other ->
                var cost = Float.POSITIVE_INFINITY
                for (i in d.targets.indices) if (i == bestTarget) cost = min(cost, colorDistance(other.hsv,d.targets[i]))
                cost <= if (d.targets[bestTarget].s < .20f) 58f else 49f
            }
            val features = LearningModel.Features(st.pieceScrewCount,st.releasePotential,st.revealPotential,st.overlapRisk,st.exposure,matches)
            out += Candidate(s,bestTarget,bestCost,density,freedom,st,LearningModel.bonus(context,features))
        }
        return out
    }

    private fun immediateScore(c: Candidate, counts: IntArray): Float {
        val count = counts.getOrElse(c.targetIndex){0}
        val setScore = when { count >= 3 -> 110f; count == 2 -> 52f; else -> 5f }
        val st = c.structure
        val structural = st.releasePotential*42f + st.revealPotential*48f + st.exposure*18f - st.overlapRisk*7f
        val freePieceBonus = when(st.pieceScrewCount){1->36f;2->18f;else->0f}
        return setScore + c.screw.score*70f + (60f-c.colorCost) + c.localDensity*4f + c.edgeFreedom*7f + structural + freePieceBonus + c.learningBonus
    }

    private fun pairSequenceScore(a: Candidate, b: Candidate): Float {
        var score = 0f
        if (a.targetIndex == b.targetIndex) score += 20f
        if (a.structure.pieceId >= 0 && a.structure.pieceId == b.structure.pieceId) score += 16f
        score += b.structure.revealPotential*7f + b.structure.releasePotential*6f
        return score
    }

    private fun tripleSequenceScore(a: Candidate,b: Candidate,c: Candidate): Float {
        var score=0f
        if(a.targetIndex==b.targetIndex && b.targetIndex==c.targetIndex) score+=92f
        else if(a.targetIndex==b.targetIndex || b.targetIndex==c.targetIndex) score+=24f
        if(a.structure.pieceId==b.structure.pieceId) score+=13f
        if(b.structure.pieceId==c.structure.pieceId) score+=12f
        score += (a.structure.revealPotential+b.structure.revealPotential+c.structure.revealPotential)*9f
        score -= (a.colorCost+b.colorCost+c.colorCost)*.12f
        return score
    }

    private fun colorDistance(a:PuzzleDetector.Hsv,b:PuzzleDetector.Hsv):Float {
        val satMin=min(a.s,b.s); val hueWeight=if(satMin<.18f).10f else 1f
        return hueDiff(a.h,b.h)*hueWeight + abs(a.s-b.s)*52f + abs(a.v-b.v)*14f
    }
    private fun hueDiff(a:Float,b:Float):Float { val d=abs(a-b)%360f; return min(d,360f-d) }
}
