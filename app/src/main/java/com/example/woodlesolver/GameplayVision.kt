package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.*

/**
 * V14: ground-up detector built against the actual Woodle Screw gameplay UI.
 * It does NOT depend on the old PuzzleDetector target sampling or board-state gates.
 *
 * Important observations from real gameplay:
 * - Two colored collector boxes live at fixed normalized positions across the top.
 * - Their OUTER frame color is the requested screw color.
 * - Visible screws are colored circular heads with a darker '+' / cross center.
 * - The green LEVEL button is a large component in the lower-left home-screen area.
 */
object GameplayVision {
    enum class Screen { HOME, PUZZLE, WAIT }
    enum class Color { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, BROWN, GRAY, UNKNOWN }

    data class Hsv(val h:Float, val s:Float, val v:Float)
    data class Screw(val x:Int, val y:Int, val hsv:Hsv, val color:Color, val score:Float)
    data class Tray(
        val index:Int,
        val color:Color,
        val hsv:Hsv,
        val active:Boolean,
        val confidence:Float,
        val filled:Int
    )
    data class Detection(
        val screen:Screen,
        val levelX:Int?=null,
        val levelY:Int?=null,
        val trays:List<Tray> = emptyList(),
        val screws:List<Screw> = emptyList()
    )

    fun analyze(source:Bitmap):Detection {
        val level = detectLevelButton(source)
        if(level != null) return Detection(Screen.HOME, level.first, level.second)

        val trays = detectTrays(source)
        val screws = detectScrews(source)

        // A puzzle is recognized from the real top collector geometry. We do not
        // require screws/targets to both be non-empty before entering PUZZLE state.
        // This prevents the old "WAIT forever" failure mode.
        val puzzle = trays.any { it.confidence >= .42f }
        return Detection(if(puzzle) Screen.PUZZLE else Screen.WAIT, trays=trays, screws=screws)
    }

    /** Largest bright-green mass in the actual LEVEL-button area. */
    private fun detectLevelButton(b:Bitmap):Pair<Int,Int>? {
        val x1=(b.width*.02f).toInt(); val x2=(b.width*.55f).toInt()
        val y1=(b.height*.68f).toInt(); val y2=(b.height*.87f).toInt()
        var sx=0L; var sy=0L; var n=0
        var minX=x2; var maxX=x1; var minY=y2; var maxY=y1
        val step=max(2,b.width/360)
        for(y in y1 until y2 step step) for(x in x1 until x2 step step){
            val h=rgbToHsv(b.getPixel(x,y))
            if(h.h in 72f..158f && h.s>.38f && h.v>.28f){
                sx+=x;sy+=y;n++;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y)
            }
        }
        if(n < max(120,(b.width*b.height*.0011f).toInt())) return null
        // LEVEL is broad. Small green badges/plus icons fail this width/height test.
        if(maxX-minX < b.width*.20f || maxY-minY < b.height*.045f) return null
        val cx=(sx/n).toInt(); val cy=(sy/n).toInt()
        if(cx > b.width*.48f || cy < b.height*.70f) return null
        return cx to cy
    }

    private fun detectTrays(b:Bitmap):List<Tray> {
        val out=mutableListOf<Tray>()
        // Real gameplay centers are ~14% and ~38% of the screen width.
        val centers=floatArrayOf(.14f,.38f)
        for(i in centers.indices){
            val cx=(b.width*centers[i]).toInt()
            val border = sampleTrayBorder(b,cx)
            val color = canonical(border)
            val borderConf = ((border.s-.15f)/.55f).coerceIn(0f,1f) * .72f + border.v.coerceIn(0f,1f)*.28f

            val slots = traySlotSamples(b,cx)
            val slotColors=slots.map{canonical(it)}
            val filled=slots.count { s ->
                val c=canonical(s)
                c==color && s.s>.28f
            }
            // Completion animation fills the tray with yellow/gold coins. If at
            // least two slot regions are strongly colored but disagree with the
            // outer frame, wait for the next requested color instead of tapping.
            val foreignStrong=slots.count { s -> s.s>.38f && canonical(s)!=color && canonical(s)!=Color.GRAY }
            val coinLike=slotColors.count{it==Color.YELLOW||it==Color.ORANGE}>=2 && color!=Color.YELLOW && color!=Color.ORANGE
            val active = color!=Color.UNKNOWN && borderConf>.36f && foreignStrong<2 && !coinLike
            out += Tray(i,color,border,active,borderConf,filled)
        }
        return out
    }

    private fun sampleTrayBorder(b:Bitmap,cx:Int):Hsv {
        val yTop=(b.height*.108f).toInt(); val yBottom=(b.height*.216f).toInt()
        val half=(b.width*.105f).toInt()
        val left=cx-half; val right=cx+half
        val strip=max(5,(b.width*.013f).toInt())
        val values=mutableListOf<Hsv>()
        fun add(x:Int,y:Int){
            if(x in 0 until b.width && y in 0 until b.height){
                val h=rgbToHsv(b.getPixel(x,y))
                if(h.v>.16f && h.s>.18f) values+=h
            }
        }
        // top/handle and outer side/bottom frame, deliberately avoiding white interior
        for(x in left+strip until right-strip step 4) for(y in yTop until min(yTop+strip*2,b.height) step 3) add(x,y)
        for(y in yTop+strip until yBottom step 4){
            for(x in left until min(left+strip*2,b.width) step 3) add(x,y)
            for(x in max(0,right-strip*2) until min(right,b.width) step 3) add(x,y)
        }
        for(x in left+strip until right-strip step 4) for(y in max(0,yBottom-strip*2) until yBottom step 3) add(x,y)
        if(values.isEmpty()) return Hsv(0f,0f,0f)
        val strongest=values.sortedByDescending{it.s*1.2f+it.v*.15f}.take(max(8,values.size*2/3))
        return Hsv(circularHue(strongest), strongest.map{it.s}.average().toFloat(), strongest.map{it.v}.average().toFloat())
    }

    private fun traySlotSamples(b:Bitmap,cx:Int):List<Hsv> {
        val pts=listOf(
            cx to (b.height*.149f).toInt(),
            (cx-b.width*.044f).toInt() to (b.height*.179f).toInt(),
            (cx+b.width*.044f).toInt() to (b.height*.179f).toInt()
        )
        return pts.map{(x,y)->samplePatch(b,x,y,max(4,(b.width*.011f).toInt()))}
    }

    private fun detectScrews(source:Bitmap):List<Screw> {
        val scale=min(1f,720f/source.width.toFloat())
        val w=max(1,(source.width*scale).roundToInt()); val h=max(1,(source.height*scale).roundToInt())
        val b=if(w==source.width)source else Bitmap.createScaledBitmap(source,w,h,true)
        val found=mutableListOf<Screw>()
        val x1=(w*.015f).toInt(); val x2=(w*.985f).toInt()
        val y1=(h*.275f).toInt(); val y2=(h*.79f).toInt()
        val step=max(4,w/170)
        val radii=intArrayOf(max(13,(w*.020f).toInt()),max(17,(w*.026f).toInt()),max(21,(w*.032f).toInt()),max(25,(w*.038f).toInt()),max(28,(w*.043f).toInt()))

        for(y in y1 until y2 step step) for(x in x1 until x2 step step){
            var best:Screw?=null
            for(r in radii){
                if(x-r<0||x+r>=w||y-r<0||y+r>=h) continue
                val ring=ringHsv(b,x,y,r)
                if(ring.v<.25f || ring.s<.18f) continue
                val center=samplePatch(b,x,y,max(3,r/6))
                val darkness=(ring.v-center.v).coerceAtLeast(0f)
                if(darkness<.075f) continue
                val symmetry=ringSymmetry(b,x,y,r)
                val cross=crossConfidence(b,x,y,r,ring.v)
                val score=darkness*2.2f + ring.s*.48f + symmetry*.40f + cross*.50f
                if(score<.48f) continue
                val color=canonical(ring)
                if(color==Color.UNKNOWN || color==Color.BROWN) continue
                val c=Screw(x,y,ring,color,score)
                if(best==null||c.score>best!!.score) best=c
            }
            val c=best?:continue
            val minSep=w*.050f
            val dup=found.indexOfFirst{(it.x-c.x).toFloat().pow(2)+(it.y-c.y).toFloat().pow(2)<minSep*minSep}
            if(dup<0)found+=c else if(c.score>found[dup].score)found[dup]=c
        }
        val inv=source.width.toFloat()/w
        val mapped=found.sortedByDescending{it.score}.take(80).map{it.copy(x=(it.x*inv).roundToInt(),y=(it.y*inv).roundToInt())}
        if(b!==source)b.recycle()
        return mapped
    }

    private fun ringHsv(b:Bitmap,cx:Int,cy:Int,r:Int):Hsv {
        val vals=mutableListOf<Hsv>()
        for(i in 0 until 20){
            val a=2.0*Math.PI*i/20.0
            val rr=r*.68
            val x=(cx+cos(a)*rr).roundToInt(); val y=(cy+sin(a)*rr).roundToInt()
            if(x in 0 until b.width&&y in 0 until b.height)vals+=rgbToHsv(b.getPixel(x,y))
        }
        if(vals.isEmpty())return Hsv(0f,0f,0f)
        val strong=vals.sortedByDescending{it.s+it.v*.12f}.take(14)
        return Hsv(circularHue(strong),strong.map{it.s}.average().toFloat(),strong.map{it.v}.average().toFloat())
    }

    private fun ringSymmetry(b:Bitmap,cx:Int,cy:Int,r:Int):Float {
        val vals=FloatArray(16)
        for(i in 0 until 16){
            val a=2.0*Math.PI*i/16.0
            val x=(cx+cos(a)*r*.68).roundToInt().coerceIn(0,b.width-1)
            val y=(cy+sin(a)*r*.68).roundToInt().coerceIn(0,b.height-1)
            vals[i]=luma(b.getPixel(x,y))
        }
        val avg=vals.average().toFloat(); val dev=vals.map{abs(it-avg)}.average().toFloat()
        return (1f-dev*3.0f).coerceIn(0f,1f)
    }

    private fun crossConfidence(b:Bitmap,cx:Int,cy:Int,r:Int,ringV:Float):Float {
        val d=max(2,r/5)
        val samples=listOf(cx-d to cy,cx+d to cy,cx to cy-d,cx to cy+d)
        var v=0f;var n=0
        for((x,y) in samples)if(x in 0 until b.width&&y in 0 until b.height){v+=rgbToHsv(b.getPixel(x,y)).v;n++}
        if(n==0)return 0f
        return ((ringV-v/n)*3.2f).coerceIn(0f,1f)
    }

    private fun samplePatch(b:Bitmap,cx:Int,cy:Int,r:Int):Hsv {
        val vals=mutableListOf<Hsv>()
        for(y in cy-r..cy+r step max(1,r/2))for(x in cx-r..cx+r step max(1,r/2))if(x in 0 until b.width&&y in 0 until b.height)vals+=rgbToHsv(b.getPixel(x,y))
        if(vals.isEmpty())return Hsv(0f,0f,0f)
        return Hsv(circularHue(vals),vals.map{it.s}.average().toFloat(),vals.map{it.v}.average().toFloat())
    }

    fun canonical(h:Hsv):Color {
        if(h.v<.12f)return Color.UNKNOWN
        if(h.s<.16f)return Color.GRAY
        return when(val x=h.h){
            in 0f..<12f -> Color.RED
            in 12f..<28f -> if(h.v<.48f)Color.BROWN else Color.ORANGE
            in 28f..<60f -> Color.YELLOW
            in 60f..<155f -> Color.GREEN
            in 155f..<195f -> Color.CYAN
            in 195f..<250f -> Color.BLUE
            in 250f..<292f -> Color.PURPLE
            in 292f..<345f -> Color.PINK
            else -> Color.RED
        }
    }

    private fun circularHue(v:List<Hsv>):Float {
        if(v.isEmpty())return 0f
        var sx=0.0;var sy=0.0
        for(c in v){val a=Math.toRadians(c.h.toDouble());sx+=cos(a);sy+=sin(a)}
        var h=Math.toDegrees(atan2(sy,sx)).toFloat();if(h<0)h+=360f;return h
    }
    private fun luma(c:Int):Float {val r=((c shr 16)and 255)/255f;val g=((c shr 8)and 255)/255f;val b=(c and 255)/255f;return r*.299f+g*.587f+b*.114f}
    private fun rgbToHsv(c:Int):Hsv {
        val r=((c shr 16)and 255)/255f;val g=((c shr 8)and 255)/255f;val bl=(c and 255)/255f
        val mx=max(r,max(g,bl));val mn=min(r,min(g,bl));val d=mx-mn
        var h=when{d==0f->0f;mx==r->60f*(((g-bl)/d)%6f);mx==g->60f*(((bl-r)/d)+2f);else->60f*(((r-g)/d)+4f)}
        if(h<0)h+=360f
        return Hsv(h,if(mx==0f)0f else d/mx,mx)
    }
}
