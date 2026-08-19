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

        val level = detectGreenLevelButton(px, w, h)
        if (level != null) {
            val inv = source.width.toFloat() / w
            if (bmp !== source) bmp.recycle()
            return Detection(
                state = ScreenState.LEVEL_BUTTON,
                targets = emptyList(),
                screws = emptyList(),
                levelButtonX = (level.first * inv).roundToInt(),
                levelButtonY = (level.second * inv).roundToInt()
            )
        }

        val targets = listOfNotNull(
            dominantColor(px, w, h, (w * .02f).toInt(), (h * .06f).toInt(), (w * .22f).toInt(), (h * .19f).toInt()),
            dominantColor(px, w, h, (w * .22f).toInt(), (h * .06f).toInt(), (w * .42f).toInt(), (h * .19f).toInt())
        )

        val screws = mutableListOf<Screw>()
        val step = max(8, w / 85)
        val yStart = (h * .22f).toInt()
        val yEnd = (h * .82f).toInt()
        val xStart = (w * .02f).toInt()
        val xEnd = (w * .98f).toInt()

        for (y in yStart until yEnd step step) {
            for (x in xStart until xEnd step step) {
                val c = rgbToHsv(px[y * w + x])
                if (c.v < .18f || c.v > .98f) continue
                if (c.s < .12f) continue

                val r = max(8, (w * .024f).toInt())
                if (x - r < 0 || x + r >= w || y - r < 0 || y + r >= h) continue

                val ring = sampleRing(px, w, h, x, y, r)
                val center = sampleCenter(px, w, h, x, y, r)
                val score = (ring.v - center.v).coerceIn(0f, 1f) + ring.s * .35f
                if (score < .28f) continue

                if (screws.none {
                        (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) <
                            (w * .045f) * (w * .045f)
                    }) {
                    screws += Screw(x, y, ring, score)
                }
            }
        }

        val inv = source.width.toFloat() / w
        val mapped = screws.map { it.copy(x = (it.x * inv).roundToInt(), y = (it.y * inv).roundToInt()) }
        if (bmp !== source) bmp.recycle()

        val puzzleLooksReal = targets.isNotEmpty() && mapped.size >= 3
        return Detection(
            state = if (puzzleLooksReal) ScreenState.PUZZLE else ScreenState.WAIT,
            targets = targets,
            screws = mapped
        )
    }

    fun chooseTap(d: Detection): Screw? {
        if (d.state != ScreenState.PUZZLE || d.targets.isEmpty()) return null
        return d.screws
            .mapNotNull { s ->
                val best = d.targets.minOfOrNull {
                    hueDiff(s.hsv.h, it.h) + abs(s.hsv.s - it.s) * 35f
                } ?: return@mapNotNull null
                if (best < 42f) s to (s.score * 50f - best) else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun detectGreenLevelButton(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        // In the supplied gameplay, the post-level green LEVEL button appears
        // in the lower-middle of the Woodle screen. Restrict detection to that
        // small safe zone so banner ads / Yummy Town tiles are never clicked.
        val x1 = (w * .35f).toInt()
        val x2 = (w * .70f).toInt()
        val y1 = (h * .64f).toInt()
        val y2 = (h * .82f).toInt()

        var sx = 0L
        var sy = 0L
        var n = 0

        for (y in y1 until y2 step 2) {
            for (x in x1 until x2 step 2) {
                val c = rgbToHsv(px[y * w + x])
                // Bright lime/green button seen in LEVEL 16 / LEVEL 17 screen.
                if (c.h in 75f..145f && c.s > .45f && c.v > .45f) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }

        val minPixels = max(80, (w * h * .0012f).toInt())
        if (n < minPixels) return null

        val cx = (sx / n).toInt()
        val cy = (sy / n).toInt()

        // Final hard safety check: never return a tap outside the known button zone.
        if (cx !in x1 until x2 || cy !in y1 until y2) return null
        return cx to cy
    }

    private fun dominantColor(px: IntArray, w: Int, h: Int, x1: Int, y1: Int, x2: Int, y2: Int): Hsv? {
        val bins = IntArray(36)
        val sat = FloatArray(36)
        val value = FloatArray(36)
        for (y in y1.coerceAtLeast(0) until y2.coerceAtMost(h) step 2) {
            for (x in x1.coerceAtLeast(0) until x2.coerceAtMost(w) step 2) {
                val c = rgbToHsv(px[y * w + x])
                if (c.s > .38f && c.v > .25f) {
                    val b = (c.h / 10f).toInt().coerceIn(0, 35)
                    bins[b]++
                    sat[b] += c.s
                    value[b] += c.v
                }
            }
        }
        val b = bins.indices.maxByOrNull { bins[it] } ?: return null
        if (bins[b] < 12) return null
        return Hsv(b * 10f + 5f, sat[b] / bins[b], value[b] / bins[b])
    }

    private fun sampleRing(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Hsv {
        val pts = arrayOf(
            cx + r/2 to cy, cx - r/2 to cy, cx to cy + r/2, cx to cy - r/2,
            cx + r/3 to cy + r/3, cx - r/3 to cy + r/3,
            cx + r/3 to cy - r/3, cx - r/3 to cy - r/3
        )
        var hsum = 0f; var ssum = 0f; var vsum = 0f; var n = 0
        for ((x,y) in pts) if (x in 0 until w && y in 0 until h) {
            val c = rgbToHsv(px[y*w+x]); hsum += c.h; ssum += c.s; vsum += c.v; n++
        }
        return if (n == 0) Hsv(0f,0f,0f) else Hsv(hsum/n, ssum/n, vsum/n)
    }

    private fun sampleCenter(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Hsv {
        var hsum=0f; var ssum=0f; var vsum=0f; var n=0
        val d = max(2, r/5)
        for (y in cy-d..cy+d) for (x in cx-d..cx+d) if (x in 0 until w && y in 0 until h) {
            val c=rgbToHsv(px[y*w+x]); hsum+=c.h; ssum+=c.s; vsum+=c.v; n++
        }
        return if (n==0) Hsv(0f,0f,0f) else Hsv(hsum/n,ssum/n,vsum/n)
    }

    private fun hueDiff(a: Float, b: Float): Float {
        val d = abs(a-b) % 360f
        return min(d, 360f-d)
    }

    private fun rgbToHsv(color: Int): Hsv {
        val r=((color shr 16) and 255)/255f
        val g=((color shr 8) and 255)/255f
        val b=(color and 255)/255f
        val mx=max(r,max(g,b)); val mn=min(r,min(g,b)); val d=mx-mn
        var h = when {
            d==0f -> 0f
            mx==r -> 60f*(((g-b)/d)%6f)
            mx==g -> 60f*(((b-r)/d)+2f)
            else -> 60f*(((r-g)/d)+4f)
        }
        if (h<0f) h+=360f
        val s=if(mx==0f) 0f else d/mx
        return Hsv(h,s,mx)
    }
}
