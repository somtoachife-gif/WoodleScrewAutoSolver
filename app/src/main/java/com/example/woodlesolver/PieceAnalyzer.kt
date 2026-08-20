package com.example.woodlesolver

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * V7 board-structure analyzer.
 *
 * It groups nearby screws that appear to sit on the same continuous board piece,
 * estimates how exposed each screw is, and scores how likely removing it is to free
 * a piece or reveal additional board. This is intentionally visual/closed-loop:
 * after every real move ProjectionService scans the new frame again.
 */
object PieceAnalyzer {

    data class ScrewStructure(
        val pieceId: Int,
        val pieceScrewCount: Int,
        val exposure: Float,
        val overlapRisk: Float,
        val releasePotential: Float,
        val revealPotential: Float
    )

    data class Analysis(
        val byScrew: Map<PuzzleDetector.Screw, ScrewStructure>,
        val pieceCount: Int
    )

    fun analyze(frame: Bitmap, detection: PuzzleDetector.Detection): Analysis {
        val screws = detection.screws
        if (screws.isEmpty()) return Analysis(emptyMap(), 0)

        val n = screws.size
        val parent = IntArray(n) { it }

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Link screws that are spatially close AND visually connected by similar
        // material between them. This approximates "same plank/piece" without OCR.
        val maxLink = frame.width * .34f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = screws[i]
                val b = screws[j]
                val dist = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                if (dist > maxLink) continue
                val continuity = lineContinuity(frame, a.x, a.y, b.x, b.y)
                if (continuity >= .60f) union(i, j)
            }
        }

        val pieceMap = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) pieceMap.getOrPut(find(i)) { mutableListOf() }.add(i)
        val roots = pieceMap.keys.toList()
        val idByRoot = roots.withIndex().associate { it.value to it.index }

        val output = mutableMapOf<PuzzleDetector.Screw, ScrewStructure>()
        for ((root, indices) in pieceMap) {
            val pieceId = idByRoot[root] ?: 0
            val pieceSize = indices.size

            val centerX = indices.map { screws[it].x }.average().toFloat()
            val centerY = indices.map { screws[it].y }.average().toFloat()

            for (idx in indices) {
                val s = screws[idx]
                val exposure = localExposure(frame, s.x, s.y)
                val overlap = localLayerComplexity(frame, s.x, s.y)

                // A one-screw piece is very likely to become free. A two-screw
                // piece can pivot/drop after one removal. Larger groups receive a
                // smaller release bonus but can still be strategically valuable.
                val baseRelease = when (pieceSize) {
                    1 -> .96f
                    2 -> .78f
                    3 -> .58f
                    else -> .38f
                }

                // Screws farther from a piece's visual center often act as anchors;
                // removing them tends to create rotation and uncover area.
                val radial = hypot((s.x-centerX).toDouble(), (s.y-centerY).toDouble()).toFloat()
                val radialNorm = (radial / max(1f, frame.width*.30f)).coerceIn(0f,1f)
                val release = (baseRelease*.72f + radialNorm*.18f + exposure*.10f).coerceIn(0f,1f)

                // Revealing more board is most likely when the screw sits in a
                // visually layered/complex region but is itself clearly exposed.
                val reveal = (overlap*.58f + exposure*.27f + release*.15f).coerceIn(0f,1f)

                output[s] = ScrewStructure(
                    pieceId = pieceId,
                    pieceScrewCount = pieceSize,
                    exposure = exposure,
                    overlapRisk = overlap,
                    releasePotential = release,
                    revealPotential = reveal
                )
            }
        }

        return Analysis(output, pieceMap.size)
    }

    private fun lineContinuity(frame: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val samples = 11
        val values = FloatArray(samples)
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1)
            val x = (x1 + (x2 - x1) * t).toInt().coerceIn(0, frame.width - 1)
            val y = (y1 + (y2 - y1) * t).toInt().coerceIn(0, frame.height - 1)
            values[i] = luminance(frame.getPixel(x, y))
        }
        val avg = values.average().toFloat()
        val dev = values.map { abs(it - avg) }.average().toFloat()
        val range = (values.maxOrNull() ?: 0f) - (values.minOrNull() ?: 0f)
        return (1f - dev*2.6f - range*.55f).coerceIn(0f, 1f)
    }

    private fun localExposure(frame: Bitmap, cx: Int, cy: Int): Float {
        val r = max(10, (frame.width*.026f).toInt())
        val inner = sampleLuma(frame, cx, cy, r)
        val outer = sampleLuma(frame, cx, cy, r*2)
        val contrast = abs(inner-outer)
        return (contrast*2.1f + .35f).coerceIn(0f,1f)
    }

    private fun localLayerComplexity(frame: Bitmap, cx: Int, cy: Int): Float {
        val r = max(18, (frame.width*.045f).toInt())
        val pts = 16
        var changes = 0f
        var prev: Float? = null
        var first = 0f
        for (i in 0 until pts) {
            val a = Math.PI*2.0*i/pts
            val x = (cx + kotlin.math.cos(a)*r).toInt().coerceIn(0,frame.width-1)
            val y = (cy + kotlin.math.sin(a)*r).toInt().coerceIn(0,frame.height-1)
            val v = luminance(frame.getPixel(x,y))
            if (i==0) first=v
            if (prev != null) changes += abs(v-prev!!)
            prev=v
        }
        if (prev != null) changes += abs(prev!!-first)
        return (changes / pts * 2.7f).coerceIn(0f,1f)
    }

    private fun sampleLuma(frame: Bitmap, cx: Int, cy: Int, r: Int): Float {
        val pts = arrayOf(
            0 to 0, r to 0, -r to 0, 0 to r, 0 to -r,
            r/2 to r/2, -r/2 to r/2, r/2 to -r/2, -r/2 to -r/2
        )
        var sum=0f; var count=0
        for ((dx,dy) in pts) {
            val x=(cx+dx).coerceIn(0,frame.width-1)
            val y=(cy+dy).coerceIn(0,frame.height-1)
            sum += luminance(frame.getPixel(x,y)); count++
        }
        return if(count==0)0f else sum/count
    }

    private fun luminance(c:Int):Float {
        val r=((c shr 16) and 255)/255f
        val g=((c shr 8) and 255)/255f
        val b=(c and 255)/255f
        return r*.299f + g*.587f + b*.114f
    }
}
