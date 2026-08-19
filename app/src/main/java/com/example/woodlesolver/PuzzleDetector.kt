package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PuzzleDetector {
    enum class ScreenState { LEVEL_BUTTON, PUZZLE, WAIT }

    data class Hsv(val h: Float, val s: Float, val v: Float)
    data class Screw(val x: Int, val y: Int, val hsv: Hsv, val score: Float)
    data class Detection(
        val state: ScreenState,
        val targets: List<Hsv>,
        val screws: List<Screw>,
        val levelButtonX: Int? = null,
        val levelButtonY: Int? = null
    )

    fun detect(source: Bitmap): Detection {
        val scale = min(1f, 720f / source.width.toFloat())
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val bmp = if (w == source.width) source else Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // Home screen LEVEL button from the supplied mobile gameplay.
        val level = detectGreenLevelButton(px, w, h)
        if (level != null) {
            val inv = source.width.toFloat() / w
            if (bmp !== source) bmp.recycle()
            return Detection(
                ScreenState.LEVEL_BUTTON,
                emptyList(),
                emptyList(),
                (level.first * inv).roundToInt(),
                (level.second * inv).roundToInt()
            )
        }

        // The two live collector trays are fixed near the top-left in the mobile game.
        // Sample their colored outer edge / screw area instead of trying to infer the
        // whole box. This also supports gray targets, which have very low saturation.
        val targets = listOfNotNull(
            sampleTargetSlot(px, w, h, 0),
            sampleTargetSlot(px, w, h, 1)
        )

        val screws = detectScrews(px, w, h, targets)
        val inv = source.width.toFloat() / w
        val mapped = screws.map {
            it.copy(x = (it.x * inv).roundToInt(), y = (it.y * inv).roundToInt())
        }

        if (bmp !== source) bmp.recycle()

        return Detection(
            state = if (targets.isNotEmpty() && mapped.isNotEmpty()) ScreenState.PUZZLE else ScreenState.WAIT,
            targets = targets,
            screws = mapped
        )
    }

    fun chooseTap(d: Detection): Screw? {
        if (d.state != ScreenState.PUZZLE || d.targets.isEmpty()) return null

        return d.screws.mapNotNull { s ->
            val dist = d.targets.minOfOrNull { colorDistance(s.hsv, it) } ?: return@mapNotNull null
            // V3 is intentionally more permissive than V2. The visual screw detector
            // already requires a dark plus-shaped center surrounded by a round head.
            if (dist < 58f) s to (s.score * 60f - dist) else null
        }.maxByOrNull { it.second }?.first
    }

    private fun detectScrews(px: IntArray, w: Int, h: Int, targets: List<Hsv>): List<Screw> {
        if (targets.isEmpty()) return emptyList()

        val found = mutableListOf<Screw>()
        val x1 = (w * .015f).toInt()
        val x2 = (w * .985f).toInt()
        val y1 = (h * .28f).toInt()   // below collector trays / empty holes
        val y2 = (h * .78f).toInt()   // above tools and banner ad
        val step = max(5, w / 130)
        val radii = intArrayOf(
            max(14, (w * .026f).toInt()),
            max(17, (w * .032f).toInt()),
            max(20, (w * .038f).toInt())
        )

        for (y in y1 until y2 step step) {
            for (x in x1 until x2 step step) {
                var best: Screw? = null

                for (r in radii) {
                    if (x-r < 0 || x+r >= w || y-r < 0 || y+r >= h) continue

                    val ring = sampleRing(px, w, h, x, y, r)
                    val center = sampleCenter(px, w, h, x, y, r)
                    val targetDist = targets.minOf { colorDistance(ring, it) }
                    if (targetDist > 66f) continue

                    // Real Woodle screws have a noticeably darker plus at the center.
                    // V2 mistakenly required the CENTER pixel itself to be saturated,
                    // which rejected the actual dark plus. V3 removes that mistake.
                    val darkness = (ring.v - center.v).coerceAtLeast(0f)
                    val score = darkness * 1.9f + ring.s * .35f + (1f - min(targetDist, 70f) / 70f) * .45f
                    if (score < .32f) continue

                    if (best == null || score > best.score) best = Screw(x, y, ring, score)
                }

                if (best != null) {
                    val minSep = (w * .055f)
                    val duplicate = found.any {
                        val dx = it.x - best.x
                        val dy = it.y - best.y
                        dx*dx + dy*dy < minSep*minSep
                    }
                    if (!duplicate) found += best
                }
            }
        }

        return found.sortedByDescending { it.score }.take(40)
    }

    private fun sampleTargetSlot(px: IntArray, w: Int, h: Int, slot: Int): Hsv? {
        // Calibrated from the supplied 720x1568 portrait gameplay.
        // Slot 0 spans about x=20..180, slot 1 about x=195..355, y=175..340.
        val sx = if (slot == 0) .14f else .38f
        val sy = .15f
        val cx = (w * sx).roundToInt()
        val cy = (h * sy).roundToInt()
        val r = max(16, (w * .037f).toInt())

        // Sample the ring around the top screw icon in the collector tray.
        // If that point is empty, also try the lower-left icon position.
        val a = sampleRing(px, w, h, cx, cy, r)
        if (a.v > .20f) return a

        val cx2 = cx - (w * .045f).roundToInt()
        val cy2 = cy + (h * .032f).roundToInt()
        val b = sampleRing(px, w, h, cx2, cy2, r)
        return if (b.v > .20f) b else null
    }

    private fun detectGreenLevelButton(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        // In the actual recording the green LEVEL 15 button is on the lower-left,
        // not in the lower-middle. Yummy Town sits immediately to its right.
        val x1 = (w * .01f).toInt()
        val x2 = (w * .35f).toInt()
        val y1 = (h * .64f).toInt()
        val y2 = (h * .80f).toInt()

        var sx = 0L
        var sy = 0L
        var n = 0
        for (y in y1 until y2 step 2) {
            for (x in x1 until x2 step 2) {
                val c = rgbToHsv(px[y*w+x])
                if (c.h in 80f..145f && c.s > .50f && c.v > .45f) {
                    sx += x; sy += y; n++
                }
            }
        }

        // Require a LARGE green blob; isolated green screws/tools cannot qualify.
        val minPixels = max(180, (w*h*.0030f).toInt())
        if (n < minPixels) return null
        val cx = (sx/n).toInt()
        val cy = (sy/n).toInt()
        if (cx !in x1 until x2 || cy !in y1 until y2) return null
        return cx to cy
    }

    private fun sampleRing(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Hsv {
        val pts = arrayOf(
            cx + (r*.62f).toInt() to cy,
            cx - (r*.62f).toInt() to cy,
            cx to cy + (r*.62f).toInt(),
            cx to cy - (r*.62f).toInt(),
            cx + (r*.44f).toInt() to cy + (r*.44f).toInt(),
            cx - (r*.44f).toInt() to cy + (r*.44f).toInt(),
            cx + (r*.44f).toInt() to cy - (r*.44f).toInt(),
            cx - (r*.44f).toInt() to cy - (r*.44f).toInt()
        )
        val vals = mutableListOf<Hsv>()
        for ((x,y) in pts) if (x in 0 until w && y in 0 until h) vals += rgbToHsv(px[y*w+x])
        if (vals.isEmpty()) return Hsv(0f,0f,0f)

        // Use the most saturated/representative ring pixels instead of a plain hue
        // average, which breaks around the 0/360 red boundary.
        val sorted = vals.sortedByDescending { it.s + it.v*.15f }
        val take = sorted.take(max(3, sorted.size/2))
        return Hsv(circularHueAverage(take), take.map{it.s}.average().toFloat(), take.map{it.v}.average().toFloat())
    }

    private fun sampleCenter(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Hsv {
        var vs=0f; var ss=0f; var n=0
        val d=max(3,r/5)
        for (y in cy-d..cy+d step 2) for (x in cx-d..cx+d step 2) {
            if (x in 0 until w && y in 0 until h) {
                val c=rgbToHsv(px[y*w+x]); vs+=c.v; ss+=c.s; n++
            }
        }
        return if(n==0) Hsv(0f,0f,0f) else Hsv(0f,ss/n,vs/n)
    }

    private fun circularHueAverage(colors: List<Hsv>): Float {
        var sx=0.0; var sy=0.0
        for(c in colors){
            val a=Math.toRadians(c.h.toDouble())
            sx += kotlin.math.cos(a); sy += kotlin.math.sin(a)
        }
        var h=Math.toDegrees(kotlin.math.atan2(sy,sx)).toFloat()
        if(h<0f) h+=360f
        return h
    }

    private fun colorDistance(a: Hsv, b: Hsv): Float {
        // Hue matters less for gray/neutral colors.
        val satMin=min(a.s,b.s)
        val hueWeight=if(satMin<.18f) .12f else 1f
        val hd=hueDiff(a.h,b.h)*hueWeight
        val sd=abs(a.s-b.s)*55f
        val vd=abs(a.v-b.v)*18f
        return hd+sd+vd
    }

    private fun hueDiff(a: Float,b: Float):Float {
        val d=abs(a-b)%360f
        return min(d,360f-d)
    }

    private fun rgbToHsv(color:Int):Hsv {
        val r=((color shr 16) and 255)/255f
        val g=((color shr 8) and 255)/255f
        val b=(color and 255)/255f
        val mx=max(r,max(g,b)); val mn=min(r,min(g,b)); val d=mx-mn
        var h=when{
            d==0f->0f
            mx==r->60f*(((g-b)/d)%6f)
            mx==g->60f*(((b-r)/d)+2f)
            else->60f*(((r-g)/d)+4f)
        }
        if(h<0f)h+=360f
        return Hsv(h,if(mx==0f)0f else d/mx,mx)
    }
}
