package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * One character on screen: its box in screen coordinates, the character and its colour.
 * [clickable]: it is part of a link or button (its node or the node's parent is clickable), so a tap belongs to the app.
 */
class Mark(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val cp: Int, val color: Int, val clickable: Boolean,
)

/**
 * The see-through layer: a light underline under each marked character.
 * Added by the service as TYPE_ACCESSIBILITY_OVERLAY. While only marking it has FLAG_NOT_TOUCHABLE, so every
 * touch goes through; in look mode it takes touches: a tap on a character calls [onPick] with it, a tap
 * anywhere else calls it with null.
 */
@SuppressLint("ViewConstructor")
class MarkLayer(context: Context, private val onPick: (Int?) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var marks: List<Mark> = emptyList()
    private val loc = IntArray(2)

    /** Look mode: a faint veil over the screen and a soft box under each character, to show what can be tapped. */
    var looking = false
        set(v) {
            field = v
            invalidate()
        }

    /** Whether the marks are drawn; they are kept for tap mode either way. */
    var drawMarks = true
        set(v) {
            field = v
            invalidate()
        }

    /** The character at a screen point (a tap in tap mode), with a small margin. */
    fun markAt(x: Float, y: Float): Mark? {
        val slop = 4 * density
        return marks.firstOrNull { x >= it.left - slop && x <= it.right + slop && y >= it.top - slop && y <= it.bottom + slop }
    }

    fun setMarks(list: List<Mark>) {
        marks = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (looking) canvas.drawColor(Color.argb(28, 0, 0, 0))
        if (marks.isEmpty() || !(drawMarks || looking)) return
        // Marks are in screen coordinates; the layer may not start at the screen's top left.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (m in marks) {
            val w = m.right - m.left
            val h = m.bottom - m.top
            if (w <= 0f || h <= 0f) continue
            if (looking) {
                fill.color = m.color
                fill.alpha = 56
                canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, 3 * density, 3 * density, fill)
            }
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!looking) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) onPick(hit(e.rawX, e.rawY))
        return true
    }

    /** The character under the finger, or the nearest one within a small margin. */
    private fun hit(x: Float, y: Float): Int? {
        val slop = 8 * density
        var best: Mark? = null
        var bestD = Float.MAX_VALUE
        for (m in marks) {
            if (x < m.left - slop || x > m.right + slop || y < m.top - slop || y > m.bottom + slop) continue
            val dx = x - (m.left + m.right) / 2
            val dy = y - (m.top + m.bottom) / 2
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = m
            }
        }
        return best?.cp
    }
}
