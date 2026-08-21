package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot

/** V12 board graph + collector/deadlock model. */
object BoardGraphAI {
    data class PieceNode(val id:Int,val screws:List<PuzzleDetector.Screw>,val centerX:Float,val centerY:Float)
    data class Edge(val above:Int,val below:Int,val confidence:Float)
    data class Graph(val pieces:List<PieceNode>,val edges:List<Edge>,val byScrew:Map<PuzzleDetector.Screw,Int>)
    data class Risk(val deadlock:Float,val collectorPenalty:Float,val graphBonus:Float,val reason:String)

    fun build(frame:Bitmap,d:PuzzleDetector.Detection,analysis:PieceAnalyzer.Analysis):Graph {
        val groups=d.screws.groupBy { analysis.byScrew[it]?.pieceId ?: -1 }
            .filterKeys { it>=0 }
        val pieces=groups.map { (id,screws)-> PieceNode(id,screws,screws.map{it.x}.average().toFloat(),screws.map{it.y}.average().toFloat()) }
        val by=mutableMapOf<PuzzleDetector.Screw,Int>(); pieces.forEach{p->p.screws.forEach{by[it]=p.id}}
        val edges=mutableListOf<Edge>()
        for(i in pieces.indices) for(j in i+1 until pieces.size){
            val a=pieces[i];val b=pieces[j]
            val dist=hypot((a.centerX-b.centerX).toDouble(),(a.centerY-b.centerY).toDouble()).toFloat()
            if(dist>frame.width*.42f) continue
            val overlap=overlapEvidence(frame,a,b)
            if(overlap>.38f){
                val above=if(a.centerY<=b.centerY)a.id else b.id
                val below=if(above==a.id)b.id else a.id
                edges+=Edge(above,below,overlap)
            }
        }
        return Graph(pieces,edges,by)
    }

    fun riskFor(s:PuzzleDetector.Screw,targetIndex:Int,d:PuzzleDetector.Detection,graph:Graph):Risk {
        val color=VisionReliability.canonical(s.hsv)
        val partial=CollectorTracker.count(color)
        val matching=d.screws.count { VisionReliability.canonical(it.hsv)==color }
        val wouldComplete=partial>=2
        val isolated=(partial==0 && matching<3)
        val collectorPenalty=when { wouldComplete -> -30f; partial==1 -> -8f; isolated -> 48f; else -> 5f }
        val deadlock=when { isolated -> .88f; partial==1&&matching<2 -> .72f; else -> .18f }
        val pieceId=graph.byScrew[s]
        val supports=graph.edges.count{it.above==pieceId}
        val blockedBy=graph.edges.count{it.below==pieceId}
        val graphBonus=supports*18f + blockedBy*7f
        val reason=when { isolated -> "isolated color"; wouldComplete -> "completes triple"; supports>0 -> "frees layered piece"; else -> "normal" }
        return Risk(deadlock,collectorPenalty,graphBonus,reason)
    }

    private fun overlapEvidence(frame:Bitmap,a:PieceNode,b:PieceNode):Float {
        val dx=abs(a.centerX-b.centerX)/frame.width
        val dy=abs(a.centerY-b.centerY)/frame.height
        return (1f-(dx*1.4f+dy*2.1f)).coerceIn(0f,1f)
    }
}
