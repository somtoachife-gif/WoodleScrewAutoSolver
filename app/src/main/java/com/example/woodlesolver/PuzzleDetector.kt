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

    data class Plan(
        val screw: Screw,
        val targetIndex: Int,
        val matchingVisible: Int,
        val confidence: Float
    )

    fun detect(source: Bitmap): Detection {
        val scale = min(1f, 720f / source.width.toFloat())
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val bmp = if (w == source.width) source else Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

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

        // Read both active collector colors. V4 does NOT use those colors while
        // detecting screw geometry; that was one reason V2/V3 could see nothing
        // after the first move. First find screw-shaped objects, then plan by color.
        val targets = listOfNotNull(
            sampleTargetSlot(px, w, h, 0),
            sampleTargetSlot(px, w, h, 1)
        )

        val screws = detectAllScrews(px, w, h)
        val inv = source.width.toFloat() / w
        val mapped = screws.map {
            it.copy(x = (it.x * inv).roundToInt(), y = (it.y * inv).roundToInt())
        }

        if (bmp !== source) bmp.recycle()

        val puzzle = targets.isNotEmpty() && mapped.isNotEmpty()
        return Detection(
            if (puzzle) ScreenState.PUZZLE else ScreenState.WAIT,
            targets,
            mapped
        )
    }

    /**
     * V4 planner:
     * 1. Match every visible screw against each live target color.
     * 2. Prefer a target color with >=3 visible matching screws, because Woodle's
     *    basic objective is to collect matching sets of three.
     * 3. Within that color, tap the screw with the strongest visual confidence.
     * 4. After the tap the service captures the NEW board and plans again.
     *
     * This deliberately avoids trying to predict full rigid-body physics. Replanning
     * after every move is much more robust when wooden pieces fall or rotate.
     */
    fun planMove(d: Detection): Plan? {
        if (d.state != ScreenState.PUZZLE || d.targets.isEmpty() || d.screws.isEmpty()) return null

        var bestPlan: Plan? = null
        var bestPlanScore = Float.NEGATIVE_INFINITY

        d.targets.forEachIndexed { targetIndex, target ->
            val compatible = d.screws.mapNotNull { screw ->
                val dist = colorDistance(screw.hsv, target)
                // Neutral/gray targets need a little extra tolerance.
                val limit = if (target.s < .20f) 54f else 46f
                if (dist <= limit) screw to dist else null
            }

            if (compatible.isEmpty()) return@forEachIndexed

            val count = compatible.size
            val setBonus = when {
                count >= 3 -> 75f
                count == 2 -> 28f
                else -> 0f
            }

            // We prefer a confident screw and a close target-color match.
            val chosen = compatible.maxByOrNull { (screw, dist) ->
                screw.score * 70f - dist
            } ?: return@forEachIndexed

            val screw = chosen.first
            val dist = chosen.second
            val confidence = (100f - dist + screw.score * 45f).coerceIn(0f, 150f)
            val total = setBonus + confidence

            if (total > bestPlanScore) {
                bestPlanScore = total
                bestPlan = Plan(screw, targetIndex, count, confidence)
            }
        }

        // If no active target color is confidently represented, do nothing rather
        // than guessing. The next video frame may reveal a moved/exposed screw.
        return bestPlan
    }

    fun chooseTap(d: Detection): Screw? = planMove(d)?.screw

    private fun detectAllScrews(px: IntArray, w: Int, h: Int): List<Screw> {
        val found = mutableListOf<Screw>()

        // Safe puzzle-board region measured from the supplied portrait gameplay.
        val x1 = (w * .015f).toInt()
        val x2 = (w * .985f).toInt()
        val y1 = (h * .25f).toInt()
        val y2 = (h * .80f).toInt()

        val step = max(4, w / 155)
        val radii = intArrayOf(
            max(11, (w * .021f).toInt()),
            max(14, (w * .027f).toInt()),
            max(17, (w * .033f).toInt()),
            max(20, (w * .039f).toInt())
        )

        for (y in y1 until y2 step step) {
            for (x in x1 until x2 step step) {
                var best: Screw? = null

                for (r in radii) {
                    if (x-r < 0 || x+r >= w || y-r < 0 || y+r >= h) continue

                    val ring = sampleRing(px, w, h, x, y, r)
                    val center = sampleCenter(px, w, h, x, y, r)

                    // Colored screw head surrounding a dark + / slot center.
                    val darkness = (ring.v - center.v).coerceAtLeast(0f)
                    val colorful = ring.s

                    // Very dark wood holes are filtered by ring brightness; flat
                    // colored artwork is filtered by the missing dark center.
                    if (ring.v < .24f) continue
                    if (darkness < .075f) continue
                    if (colorful < .10f && ring.v < .42f) continue

                    val symmetry = radialConsistency(px, w, h, x, y, r)
                    val score = darkness * 2.4f + colorful * .38f + symmetry * .45f
                    if (score < .31f) continue

                    val candidate = Screw(x, y, ring, score)
                    if (best == null || candidate.score > best.score) best = candidate
                }

                if (best != null) {
                    val minSep = w * .045f
                    val duplicateIndex = found.indexOfFirst {
                        val dx = it.x - best.x
                        val dy = it.y - best.y
                        dx*dx + dy*dy < minSep*minSep
                    }
                    if (duplicateIndex < 0) {
                        found += best
                    } else if (best.score > found[duplicateIndex].score) {
                        found[duplicateIndex] = best
                    }
                }
            }
        }

        return found.sortedByDescending { it.score }.take(70)
    }

    private fun radialConsistency(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Float {
        val vals = mutableListOf<Float>()
        val rr = (r * .62f).toInt()
        val dirs = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to 1, 1 to -1, -1 to -1
        )
        for ((dx,dy) in dirs) {
            val len = if (dx != 0 && dy != 0) (rr * .71f).toInt() else rr
            val x = cx + dx*len
            val y = cy + dy*len
            if (x in 0 until w && y in 0 until h) vals += rgbToHsv(px[y*w+x]).v
        }
        if (vals.size < 4) return 0f
        val avg = vals.average().toFloat()
        val dev = vals.map { abs(it-avg) }.average().toFloat()
        return (1f - dev * 2.5f).coerceIn(0f, 1f)
    }

    private fun sampleTargetSlot(px: IntArray, w: Int, h: Int, slot: Int): Hsv? {
        // The target boxes sit at the top-left. Instead of trusting one pixel,
        // sample several points around the visible screw/token inside each tray and
        // return the most chromatic representative color.
        val baseX = if (slot == 0) .135f else .375f
        val baseY = .145f
        val cx = (w * baseX).roundToInt()
        val cy = (h * baseY).roundToInt()
        val r = max(13, (w * .032f).toInt())

        val samples = listOf(
            sampleRing(px,w,h,cx,cy,r),
            sampleRing(px,w,h,cx-(w*.045f).toInt(),cy+(h*.030f).toInt(),r),
            sampleRing(px,w,h,cx+(w*.045f).toInt(),cy+(h*.030f).toInt(),r)
        ).filter { it.v > .18f }

        if (samples.isEmpty()) return null

        // Prefer saturated samples; gray is still allowed if no saturated color exists.
        val colorful = samples.maxByOrNull { it.s + it.v*.12f } ?: return null
        return colorful
    }

    private fun detectGreenLevelButton(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        val x1 = (w * .01f).toInt()
        val x2 = (w * .35f).toInt()
        val y1 = (h * .64f).toInt()
        val y2 = (h * .80f).toInt()

        var sx = 0L; var sy = 0L; var n = 0
        for (y in y1 until y2 step 2) {
            for (x in x1 until x2 step 2) {
                val c = rgbToHsv(px[y*w+x])
                if (c.h in 80f..145f && c.s > .50f && c.v > .45f) {
                    sx += x; sy += y; n++
                }
            }
        }
        val minPixels = max(180, (w*h*.0030f).toInt())
        if (n < minPixels) return null
        val cx = (sx/n).toInt(); val cy = (sy/n).toInt()
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

        val sorted = vals.sortedByDescending { it.s + it.v*.15f }
        val take = sorted.take(max(3, sorted.size/2))
        return Hsv(
            circularHueAverage(take),
            take.map{it.s}.average().toFloat(),
            take.map{it.v}.average().toFloat()
        )
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
        val satMin=min(a.s,b.s)
        val hueWeight=if(satMin<.18f) .10f else 1f
        return hueDiff(a.h,b.h)*hueWeight + abs(a.s-b.s)*52f + abs(a.v-b.v)*14f
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
