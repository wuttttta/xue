package lol.varie.xue

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** One character on screen: its box in screen coordinates and its colour. */
class Mark(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int)

/**
 * The see-through layer: a light underline under each marked character.
 * Added by the service as TYPE_ACCESSIBILITY_OVERLAY with FLAG_NOT_TOUCHABLE, so every touch goes through.
 */
class MarkLayer(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var marks: List<Mark> = emptyList()
    private val loc = IntArray(2)

    fun setMarks(list: List<Mark>) {
        marks = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (marks.isEmpty()) return
        // Marks are in screen coordinates; the layer may not start at the screen's top left.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (m in marks) {
            val w = m.right - m.left
            val h = m.bottom - m.top
            if (w <= 0f || h <= 0f) continue
            val stroke = (h * 0.09f).coerceIn(1.5f * density, 3.5f * density)
            val inset = (w * 0.12f).coerceAtLeast(stroke / 2)
            val y = m.bottom + stroke * 0.2f
            paint.strokeWidth = stroke
            paint.color = m.color
            paint.alpha = 220
            canvas.drawLine(m.left + inset, y, m.right - inset, y, paint)
        }
        canvas.restore()
    }
}
