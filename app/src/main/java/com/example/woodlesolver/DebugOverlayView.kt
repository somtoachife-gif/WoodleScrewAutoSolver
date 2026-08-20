package com.example.woodlesolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Draw-only V13 diagnostic overlay. It never receives touches. */
class DebugOverlayView(context: Context) : View(context) {
    data class Marker(
        val x: Float,
        val y: Float,
        val label: String,
        val selected: Boolean,
        val pieceId: Int
    )

    data class Snapshot(
        val captureWidth: Int,
        val captureHeight: Int,
        val state: String,
        val markers: List<Marker>,
        val targetLabels: List<String>,
        val rejected: Int,
        val pieceCount: Int,
        val selectedText: String,
        val statusText: String
    )

    @Volatile private var snapshot: Snapshot? = null

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.GREEN
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 27f
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAA000000.toInt()
    }

    fun setSnapshot(value: Snapshot?) {
        snapshot = value
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        val sx = if (s.captureWidth > 0) width / s.captureWidth.toFloat() else 1f
        val sy = if (s.captureHeight > 0) height / s.captureHeight.toFloat() else 1f

        val panel = RectF(12f, 18f, width - 12f, 150f)
        canvas.drawRoundRect(panel, 18f, 18f, panelPaint)
        canvas.drawText("V13 DEBUG • ${s.state}", 28f, 52f, textPaint)
        canvas.drawText("targets: ${s.targetLabels.joinToString(" / ").ifBlank { "none" }} • screws: ${s.markers.size} • rejected: ${s.rejected} • pieces: ${s.pieceCount}", 28f, 87f, smallTextPaint)
        canvas.drawText(s.statusText.take(80), 28f, 120f, smallTextPaint)
        canvas.drawText(s.selectedText.take(80), 28f, 145f, smallTextPaint)

        for ((index, m) in s.markers.withIndex()) {
            val x = m.x * sx
            val y = m.y * sy
            val p = if (m.selected) selectedPaint else normalPaint
            val r = if (m.selected) 34f else 27f
            canvas.drawCircle(x, y, r, p)
            canvas.drawLine(x - r, y, x + r, y, p)
            canvas.drawLine(x, y - r, x, y + r, p)
            val label = "${index + 1}:${m.label} P${m.pieceId + 1}"
            canvas.drawText(label, x + r + 5f, y - 4f, smallTextPaint)
        }
    }
}
