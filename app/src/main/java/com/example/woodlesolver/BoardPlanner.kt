package com.example.woodlesolver

import android.content.Context
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** V12 planner: learning + structure + board graph + collector/deadlock risk + adaptive depth. */
object BoardPlanner {
    data class Result(
        val screw:PuzzleDetector.Screw,val targetIndex:Int,val visibleMatches:Int,
        val searchScore:Float,val runnerUpScore:Float,val confidence:Float,val margin:Float,val depth:Int,
        val pieceId:Int,val pieceScrewCount:Int,val releasePotential:Float,val revealPotential:Float,
        val overlapRisk:Float,val exposure:Float,val learningBonus:Float,
        val deadlockRisk:Float,val riskReason:String
    ) {
        val safeToTap:Boolean get()=confidence>=.64f&&margin>=9f&&searchScore>=100f&&deadlockRisk<.78f
        fun learningFeatures()=LearningModel.Features(pieceScrewCount,releasePotential,revealPotential,overlapRisk,exposure,visibleMatches)
    }
    private data class Candidate(
        val screw:PuzzleDetector.Screw,val targetIndex:Int,val colorCost:Float,val localDensity:Int,val edgeFreedom:Float,
        val structure:PieceAnalyzer.ScrewStructure,val learningBonus:Float,val risk:BoardGraphAI.Risk
    )
    private data class Scored(val c:Candidate,val score:Float)

    fun plan(context:Context,d:PuzzleDetector.Detection,structure:PieceAnalyzer.Analysis,graph:BoardGraphAI.Graph):Result?{
        if(d.state!=PuzzleDetector.ScreenState.PUZZLE||d.targets.isEmpty()||d.screws.isEmpty())return null
        val candidates=buildCandidates(context,d,structure,graph).filter{it.risk.deadlock<.93f}
        if(candidates.isEmpty())return null
        val counts=IntArray(d.targets.size);candidates.forEach{counts[it.targetIndex]++}
        val risky=candidates.count{it.risk.deadlock>.55f}
        val depth=when{candidates.size<=8->7;risky>=candidates.size/2->6;else->5}
        val firstBeam=candidates.sortedByDescending{immediateScore(it,counts)}.take(if(depth>=6)12 else 14)
        val scored=mutableListOf<Scored>()
        for(first in firstBeam){
            val rem=candidates.filter{it.screw!==first.screw}
            val future=bestFuture(rem,counts,depth-1,if(depth>=6)6 else 7,listOf(first))
            scored+=Scored(first,immediateScore(first,counts)+future*.55f)
        }
        val ranked=scored.sortedByDescending{it.score};val best=ranked.firstOrNull()?:return null
        val second=ranked.getOrNull(1)?.score?:best.score-35f;val margin=(best.score-second).coerceAtLeast(0f)
        val st=best.c.structure
        val visual=(best.c.screw.score/1.35f).coerceIn(0f,1f)
        val color=(1f-best.c.colorCost/58f).coerceIn(0f,1f)
        val option=(margin/25f).coerceIn(0f,1f)
        val struct=(st.exposure*.28f+st.releasePotential*.34f+st.revealPotential*.28f+(1f-st.overlapRisk)*.10f).coerceIn(0f,1f)
        val set=when{counts[best.c.targetIndex]>=3->1f;counts[best.c.targetIndex]==2->.72f;else->.42f}
        val learned=((best.c.learningBonus+35f)/90f).coerceIn(0f,1f)
        val riskConfidence=(1f-best.c.risk.deadlock).coerceIn(0f,1f)
        val confidence=(visual*.20f+color*.18f+option*.16f+set*.12f+struct*.18f+learned*.07f+riskConfidence*.09f).coerceIn(0f,1f)
        return Result(best.c.screw,best.c.targetIndex,counts[best.c.targetIndex],best.score,second,confidence,margin,depth,
            st.pieceId,st.pieceScrewCount,st.releasePotential,st.revealPotential,st.overlapRisk,st.exposure,best.c.learningBonus,
            best.c.risk.deadlock,best.c.risk.reason)
    }

    private fun bestFuture(rem:List<Candidate>,counts:IntArray,depth:Int,beamWidth:Int,prev:List<Candidate>):Float{
        if(depth<=0||rem.isEmpty())return 0f
        var best=0f
        val beam=rem.sortedByDescending{immediateScore(it,counts)}.take(beamWidth)
        for(c in beam){
            var s=immediateScore(c,counts)
            if(prev.isNotEmpty())s+=pairScore(prev.last(),c)
            if(prev.size>=2)s+=tripleScore(prev[prev.size-2],prev.last(),c)
            val future=bestFuture(rem.filter{it.screw!==c.screw},counts,depth-1,(beamWidth-1).coerceAtLeast(3),(prev+c).takeLast(3))
            best=maxOf(best,s+future*.41f)
        }
        return best
    }

    private fun buildCandidates(context:Context,d:PuzzleDetector.Detection,a:PieceAnalyzer.Analysis,g:BoardGraphAI.Graph):List<Candidate>{
        val out=mutableListOf<Candidate>()
        for(s in d.screws){
            var ti=-1;var cost=Float.POSITIVE_INFINITY
            for(i in d.targets.indices){val c=colorDistance(s.hsv,d.targets[i]);val lim=if(d.targets[i].s<.20f)58f else 49f;if(c<cost&&c<=lim){cost=c;ti=i}}
            if(ti<0)continue
            val density=d.screws.count{o->o!==s&&hypot((o.x-s.x).toDouble(),(o.y-s.y).toDouble())<150.0}
            val freedom=(1f-min(abs(s.x-360f),abs(s.y-720f))/720f).coerceIn(0f,1f)
            val st=a.byScrew[s]?:PieceAnalyzer.ScrewStructure(-1,3,.5f,.5f,.45f,.45f)
            val matches=d.screws.count{VisionReliability.canonical(it.hsv)==VisionReliability.canonical(s.hsv)}
            val feat=LearningModel.Features(st.pieceScrewCount,st.releasePotential,st.revealPotential,st.overlapRisk,st.exposure,matches)
            out+=Candidate(s,ti,cost,density,freedom,st,LearningModel.bonus(context,feat),BoardGraphAI.riskFor(s,ti,d,g))
        }
        return out
    }

    private fun immediateScore(c:Candidate,counts:IntArray):Float{
        val count=counts.getOrElse(c.targetIndex){0};val set=when{count>=3->110f;count==2->52f;else->5f};val st=c.structure
        val structural=st.releasePotential*42f+st.revealPotential*48f+st.exposure*18f-st.overlapRisk*7f
        val free=when(st.pieceScrewCount){1->36f;2->18f;else->0f}
        return set+c.screw.score*70f+(60f-c.colorCost)+c.localDensity*4f+c.edgeFreedom*7f+structural+free+c.learningBonus+c.risk.graphBonus-c.risk.collectorPenalty-c.risk.deadlock*72f
    }
    private fun pairScore(a:Candidate,b:Candidate):Float{var s=0f;if(a.targetIndex==b.targetIndex)s+=20f;if(a.structure.pieceId>=0&&a.structure.pieceId==b.structure.pieceId)s+=16f;s+=b.structure.revealPotential*7f+b.structure.releasePotential*6f;s-=b.risk.deadlock*18f;return s}
    private fun tripleScore(a:Candidate,b:Candidate,c:Candidate):Float{var s=0f;if(a.targetIndex==b.targetIndex&&b.targetIndex==c.targetIndex)s+=92f else if(a.targetIndex==b.targetIndex||b.targetIndex==c.targetIndex)s+=24f;if(a.structure.pieceId==b.structure.pieceId)s+=13f;if(b.structure.pieceId==c.structure.pieceId)s+=12f;s+=(a.structure.revealPotential+b.structure.revealPotential+c.structure.revealPotential)*9f;s-=(a.colorCost+b.colorCost+c.colorCost)*.12f;s-=(a.risk.deadlock+b.risk.deadlock+c.risk.deadlock)*9f;return s}
    private fun colorDistance(a:PuzzleDetector.Hsv,b:PuzzleDetector.Hsv):Float{val satMin=min(a.s,b.s);val hw=if(satMin<.18f).10f else 1f;return hueDiff(a.h,b.h)*hw+abs(a.s-b.s)*52f+abs(a.v-b.v)*14f}
    private fun hueDiff(a:Float,b:Float):Float{val d=abs(a-b)%360f;return min(d,360f-d)}
}
