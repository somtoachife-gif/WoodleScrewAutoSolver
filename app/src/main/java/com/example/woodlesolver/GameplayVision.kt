package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.*

/** V15: fast active-color-first Woodle detector. */
object GameplayVision {
    enum class Screen { HOME, PUZZLE, WAIT }
    enum class Color { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, BROWN, GRAY, UNKNOWN }
    data class Hsv(val h:Float,val s:Float,val v:Float)
    data class Screw(val x:Int,val y:Int,val hsv:Hsv,val color:Color,val score:Float)
    data class Tray(val index:Int,val color:Color,val hsv:Hsv,val active:Boolean,val confidence:Float,val filled:Int)
    data class Detection(val screen:Screen,val levelX:Int?=null,val levelY:Int?=null,val trays:List<Tray> = emptyList(),val screws:List<Screw> = emptyList())

    fun analyze(source:Bitmap):Detection {
        val level=detectLevelButton(source)
        if(level!=null)return Detection(Screen.HOME,level.first,level.second)
        val trays=detectTrays(source)
        val activeColors=trays.filter{it.active}.map{it.color}.filter{it!=Color.UNKNOWN}.toSet()
        // V15 speed change: only search for screw colors that are actually requested.
        val screws=if(activeColors.isEmpty()) emptyList() else detectScrewsFast(source,activeColors)
        val puzzle=trays.any{it.confidence>=.42f}
        return Detection(if(puzzle)Screen.PUZZLE else Screen.WAIT,trays=trays,screws=screws)
    }

    private fun detectLevelButton(b:Bitmap):Pair<Int,Int>?{
        val x1=(b.width*.02f).toInt();val x2=(b.width*.55f).toInt();val y1=(b.height*.68f).toInt();val y2=(b.height*.87f).toInt()
        var sx=0L;var sy=0L;var n=0;var minX=x2;var maxX=x1;var minY=y2;var maxY=y1
        val step=max(3,b.width/300)
        for(y in y1 until y2 step step)for(x in x1 until x2 step step){val h=rgbToHsv(b.getPixel(x,y));if(h.h in 72f..158f&&h.s>.38f&&h.v>.28f){sx+=x;sy+=y;n++;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y)}}
        if(n<max(90,(b.width*b.height*.0008f).toInt()))return null
        if(maxX-minX<b.width*.20f||maxY-minY<b.height*.045f)return null
        val cx=(sx/n).toInt();val cy=(sy/n).toInt();if(cx>b.width*.48f||cy<b.height*.70f)return null
        return cx to cy
    }

    private fun detectTrays(b:Bitmap):List<Tray>{
        val out=mutableListOf<Tray>();val centers=floatArrayOf(.14f,.38f)
        for(i in centers.indices){
            val cx=(b.width*centers[i]).toInt();val border=sampleTrayBorder(b,cx);val color=canonical(border)
            val conf=((border.s-.15f)/.55f).coerceIn(0f,1f)*.72f+border.v.coerceIn(0f,1f)*.28f
            val slots=traySlotSamples(b,cx);val filled=slots.count{canonical(it)==color&&it.s>.28f}
            val foreign=slots.count{it.s>.38f&&canonical(it)!=color&&canonical(it)!=Color.GRAY}
            val coinLike=slots.count{canonical(it)==Color.YELLOW||canonical(it)==Color.ORANGE}>=2&&color!=Color.YELLOW&&color!=Color.ORANGE
            out+=Tray(i,color,border,color!=Color.UNKNOWN&&conf>.36f&&foreign<2&&!coinLike,conf,filled)
        }
        return out
    }

    /**
     * Two-stage scanner:
     * 1) shrink to <=420 px wide and cheaply test only center pixels whose color matches an active tray;
     * 2) run the expensive ring/cross test only near those color hits, using just 2 likely radii.
     */
    private fun detectScrewsFast(source:Bitmap,wanted:Set<Color>):List<Screw>{
        val scale=min(1f,420f/source.width.toFloat())
        val w=max(1,(source.width*scale).roundToInt());val h=max(1,(source.height*scale).roundToInt())
        val b=if(w==source.width)source else Bitmap.createScaledBitmap(source,w,h,true)
        val x1=(w*.02f).toInt();val x2=(w*.98f).toInt();val y1=(h*.275f).toInt();val y2=(h*.79f).toInt()
        val coarseStep=max(5,w/78)
        val seeds=mutableListOf<Pair<Int,Int>>()

        // Cheap color-only pass. Test 1 pixel + 4 neighbors; no circles, no trig.
        for(y in y1 until y2 step coarseStep)for(x in x1 until x2 step coarseStep){
            val hsv=rgbToHsv(b.getPixel(x,y));val c=canonical(hsv)
            if(c !in wanted||hsv.s<.28f||hsv.v<.25f)continue
            var matches=0
            val d=max(2,coarseStep/2)
            val pts=arrayOf(x-d to y,x+d to y,x to y-d,x to y+d)
            for((px,py) in pts)if(px in 0 until w&&py in 0 until h&&canonical(rgbToHsv(b.getPixel(px,py)))==c)matches++
            if(matches>=2)seeds+=x to y
        }

        // Merge nearby seeds so a single screw is refined only once.
        val clustered=mutableListOf<Pair<Int,Int>>()
        val mergeDist=max(12,(w*.038f).toInt())
        for(s in seeds){
            val idx=clustered.indexOfFirst{(it.first-s.first).toFloat().pow(2)+(it.second-s.second).toFloat().pow(2)<mergeDist*mergeDist}
            if(idx<0)clustered+=s
        }

        val found=mutableListOf<Screw>()
        val radii=intArrayOf(max(10,(w*.027f).toInt()),max(14,(w*.038f).toInt()))
        val local=max(4,coarseStep/2)
        for(seed in clustered){
            var best:Screw?=null
            // Tiny local refinement grid around the coarse color hit.
            for(y in seed.second-local..seed.second+local step max(2,local/2))for(x in seed.first-local..seed.first+local step max(2,local/2)){
                for(r in radii){
                    if(x-r<0||x+r>=w||y-r<0||y+r>=h)continue
                    val ring=ringHsvFast(b,x,y,r);val color=canonical(ring)
                    if(color !in wanted||ring.s<.24f||ring.v<.24f)continue
                    val center=rgbToHsv(b.getPixel(x,y));val darkness=(ring.v-center.v).coerceAtLeast(0f)
                    if(darkness<.055f)continue
                    val cross=crossConfidence(b,x,y,r,ring.v)
                    val score=darkness*2.4f+ring.s*.55f+cross*.62f
                    if(score<.43f)continue
                    val candidate=Screw(x,y,ring,color,score)
                    if(best==null||candidate.score>best!!.score)best=candidate
                }
            }
            val c=best?:continue
            val sep=w*.052f
            val dup=found.indexOfFirst{(it.x-c.x).toFloat().pow(2)+(it.y-c.y).toFloat().pow(2)<sep*sep}
            if(dup<0)found+=c else if(c.score>found[dup].score)found[dup]=c
        }
        val inv=source.width.toFloat()/w
        val mapped=found.sortedByDescending{it.score}.take(40).map{it.copy(x=(it.x*inv).roundToInt(),y=(it.y*inv).roundToInt())}
        if(b!==source)b.recycle()
        return mapped
    }

    private fun ringHsvFast(b:Bitmap,cx:Int,cy:Int,r:Int):Hsv{
        // 8 samples instead of V14's 20.
        val vals=ArrayList<Hsv>(8)
        val k=.7071f
        val pts=arrayOf(
            cx+r to cy,cx-r to cy,cx to cy+r,cx to cy-r,
            (cx+r*k).roundToInt() to (cy+r*k).roundToInt(),
            (cx-r*k).roundToInt() to (cy+r*k).roundToInt(),
            (cx+r*k).roundToInt() to (cy-r*k).roundToInt(),
            (cx-r*k).roundToInt() to (cy-r*k).roundToInt()
        )
        for((x,y) in pts)if(x in 0 until b.width&&y in 0 until b.height)vals+=rgbToHsv(b.getPixel(x,y))
        if(vals.isEmpty())return Hsv(0f,0f,0f)
        return Hsv(circularHue(vals),vals.map{it.s}.average().toFloat(),vals.map{it.v}.average().toFloat())
    }

    private fun crossConfidence(b:Bitmap,cx:Int,cy:Int,r:Int,ringV:Float):Float{
        val d=max(2,r/5);var v=0f;var n=0
        val pts=arrayOf(cx-d to cy,cx+d to cy,cx to cy-d,cx to cy+d)
        for((x,y) in pts)if(x in 0 until b.width&&y in 0 until b.height){v+=rgbToHsv(b.getPixel(x,y)).v;n++}
        return if(n==0)0f else ((ringV-v/n)*3.2f).coerceIn(0f,1f)
    }

    private fun sampleTrayBorder(b:Bitmap,cx:Int):Hsv{
        val yTop=(b.height*.108f).toInt();val yBottom=(b.height*.216f).toInt();val half=(b.width*.105f).toInt();val left=cx-half;val right=cx+half;val strip=max(5,(b.width*.013f).toInt());val values=mutableListOf<Hsv>()
        fun add(x:Int,y:Int){if(x in 0 until b.width&&y in 0 until b.height){val h=rgbToHsv(b.getPixel(x,y));if(h.v>.16f&&h.s>.18f)values+=h}}
        for(x in left+strip until right-strip step 6)for(y in yTop until min(yTop+strip*2,b.height) step 4)add(x,y)
        for(y in yTop+strip until yBottom step 6){for(x in left until min(left+strip*2,b.width) step 4)add(x,y);for(x in max(0,right-strip*2) until min(right,b.width) step 4)add(x,y)}
        for(x in left+strip until right-strip step 6)for(y in max(0,yBottom-strip*2) until yBottom step 4)add(x,y)
        if(values.isEmpty())return Hsv(0f,0f,0f);val strongest=values.sortedByDescending{it.s*1.2f+it.v*.15f}.take(max(6,values.size*2/3));return Hsv(circularHue(strongest),strongest.map{it.s}.average().toFloat(),strongest.map{it.v}.average().toFloat())
    }

    private fun traySlotSamples(b:Bitmap,cx:Int):List<Hsv>{val pts=listOf(cx to (b.height*.149f).toInt(),(cx-b.width*.044f).toInt() to (b.height*.179f).toInt(),(cx+b.width*.044f).toInt() to (b.height*.179f).toInt());return pts.map{(x,y)->samplePatch(b,x,y,max(3,(b.width*.009f).toInt()))}}
    private fun samplePatch(b:Bitmap,cx:Int,cy:Int,r:Int):Hsv{val vals=mutableListOf<Hsv>();for(y in cy-r..cy+r step max(2,r))for(x in cx-r..cx+r step max(2,r))if(x in 0 until b.width&&y in 0 until b.height)vals+=rgbToHsv(b.getPixel(x,y));if(vals.isEmpty())return Hsv(0f,0f,0f);return Hsv(circularHue(vals),vals.map{it.s}.average().toFloat(),vals.map{it.v}.average().toFloat())}

    fun canonical(h:Hsv):Color{if(h.v<.12f)return Color.UNKNOWN;if(h.s<.16f)return Color.GRAY;val x=h.h;return when{x<12f||x>=345f->Color.RED;x<28f->if(h.v<.48f)Color.BROWN else Color.ORANGE;x<60f->Color.YELLOW;x<155f->Color.GREEN;x<195f->Color.CYAN;x<250f->Color.BLUE;x<292f->Color.PURPLE;x<345f->Color.PINK;else->Color.UNKNOWN}}
    private fun circularHue(v:List<Hsv>):Float{if(v.isEmpty())return 0f;var sx=0.0;var sy=0.0;for(c in v){val a=Math.toRadians(c.h.toDouble());sx+=cos(a);sy+=sin(a)};var h=Math.toDegrees(atan2(sy,sx)).toFloat();if(h<0)h+=360f;return h}
    private fun rgbToHsv(c:Int):Hsv{val r=((c shr 16)and 255)/255f;val g=((c shr 8)and 255)/255f;val bl=(c and 255)/255f;val mx=max(r,max(g,bl));val mn=min(r,min(g,bl));val d=mx-mn;var h=when{d==0f->0f;mx==r->60f*(((g-bl)/d)%6f);mx==g->60f*(((bl-r)/d)+2f);else->60f*(((r-g)/d)+4f)};if(h<0)h+=360f;return Hsv(h,if(mx==0f)0f else d/mx,mx)}
}
