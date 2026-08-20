package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** V13 perception pass: canonical colors + stricter local screw-shape validation. */
object VisionReliability {
    enum class ColorClass { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, BROWN, GRAY, UNKNOWN }

    data class Result(
        val detection: PuzzleDetector.Detection,
        val rejected: Int,
        val rejectedScrews: List<PuzzleDetector.Screw>,
        val avgConfidence: Float,
        val targetClasses: List<ColorClass>
    )

    fun refine(frame: Bitmap, raw: PuzzleDetector.Detection, careful: Boolean): Result {
        if (raw.state != PuzzleDetector.ScreenState.PUZZLE) return Result(raw,0,emptyList(),1f,emptyList())
        val minScore = if (careful) .48f else .38f
        val kept = mutableListOf<PuzzleDetector.Screw>()
        val rejected = mutableListOf<PuzzleDetector.Screw>()

        for (s in raw.screws) {
            val shape = screwShapeConfidence(frame,s.x,s.y)
            val cc = canonical(s.hsv)
            if (shape < (if(careful).60f else .48f) || s.score < minScore || cc == ColorClass.UNKNOWN) {
                rejected += s
            } else {
                kept += s.copy(score = (s.score*.62f + shape*.62f).coerceAtMost(1.6f))
            }
        }

        val targets = raw.targets.map { canonical(it) }
        val active = targets.filter { it != ColorClass.UNKNOWN }.toSet()
        val matched = mutableListOf<PuzzleDetector.Screw>()
        for (s in kept) {
            val cc = canonical(s.hsv)
            if(active.isEmpty() || cc in active || cc == ColorClass.GRAY) matched += s else rejected += s
        }

        val avg = if(matched.isEmpty())0f else matched.map{(it.score/1.6f).coerceIn(0f,1f)}.average().toFloat()
        val d = raw.copy(screws = matched)
        return Result(d, rejected.size, rejected, avg, targets)
    }

    fun canonical(h: PuzzleDetector.Hsv): ColorClass {
        if (h.v < .12f) return ColorClass.UNKNOWN
        if (h.s < .16f) return ColorClass.GRAY
        val x = h.h
        return when {
            x < 12f || x >= 345f -> ColorClass.RED
            x < 28f -> if(h.v < .50f) ColorClass.BROWN else ColorClass.ORANGE
            x < 55f -> ColorClass.YELLOW
            x < 155f -> ColorClass.GREEN
            x < 195f -> ColorClass.CYAN
            x < 250f -> ColorClass.BLUE
            x < 292f -> ColorClass.PURPLE
            x < 345f -> ColorClass.PINK
            else -> ColorClass.UNKNOWN
        }
    }

    private fun screwShapeConfidence(frame: Bitmap, cx:Int, cy:Int):Float {
        val r=max(10,(frame.width*.024f).toInt())
        if(cx-r<0||cy-r<0||cx+r>=frame.width||cy+r>=frame.height)return 0f
        val ringVals=FloatArray(16)
        for(i in 0 until 16){
            val a=Math.PI*2*i/16.0
            val x=(cx+kotlin.math.cos(a)*r*.72).toInt().coerceIn(0,frame.width-1)
            val y=(cy+kotlin.math.sin(a)*r*.72).toInt().coerceIn(0,frame.height-1)
            ringVals[i]=luma(frame.getPixel(x,y))
        }
        val ringAvg=ringVals.average().toFloat()
        val ringDev=ringVals.map{abs(it-ringAvg)}.average().toFloat()
        val center=luma(frame.getPixel(cx,cy))
        val dx=max(2,r/5)
        val horiz=(luma(frame.getPixel((cx-dx).coerceAtLeast(0),cy))+luma(frame.getPixel((cx+dx).coerceAtMost(frame.width-1),cy)))/2f
        val vert=(luma(frame.getPixel(cx,(cy-dx).coerceAtLeast(0)))+luma(frame.getPixel(cx,(cy+dx).coerceAtMost(frame.height-1))))/2f
        val darkCenter=(ringAvg-center).coerceAtLeast(0f)
        val cross=(ringAvg-min(horiz,vert)).coerceAtLeast(0f)
        val symmetry=(1f-ringDev*3.2f).coerceIn(0f,1f)
        return (darkCenter*1.8f + cross*1.2f + symmetry*.42f).coerceIn(0f,1f)
    }

    private fun luma(c:Int):Float {
        val r=((c shr 16) and 255)/255f; val g=((c shr 8) and 255)/255f; val b=(c and 255)/255f
        return r*.299f+g*.587f+b*.114f
    }
}

object CollectorTracker {
    private val partial = mutableMapOf<VisionReliability.ColorClass,Int>()
    fun reset(){ partial.clear() }
    fun observeSuccessful(color:VisionReliability.ColorClass){
        if(color==VisionReliability.ColorClass.UNKNOWN)return
        val n=(partial[color]?:0)+1
        partial[color]=if(n>=3)0 else n
    }
    fun count(color:VisionReliability.ColorClass)=partial[color]?:0
    fun summary():String = partial.filterValues{it>0}.entries.joinToString(" "){"${it.key.name.lowercase()}:${it.value}"}.ifBlank{"empty"}
}
