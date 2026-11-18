package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * One character on screen: its box in screen coordinates and the character.
 * [under]: the underline's colour, by frequency (ƒ). [high]: the highlight's colour, by your level, for your
 * characters only (null for the rest).
 */
class Mark(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val cp: Int, val under: Int, val high: Int?,
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

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** A box being dragged in tap mode (drawn without the select veil); null to clear. */
    fun setBox(b: RectF?) {
        liveBox = b
        invalidate()
    }
    private var liveBox: RectF? = null

    /** The marks now on screen (for select mode: the characters inside the box). */
    fun current(): List<Mark> = marks

    /** Whether the marks are drawn; they are kept for tap mode either way. */
    var drawMarks = true
        set(v) {
            field = v
            invalidate()
        }

    /** The character at a screen point (a tap in tap mode), with a small margin. */
    fun markAt(x: Float, y: Float): Mark? {
        val slop = 6 * density
        return marks.firstOrNull { x >= it.left - slop && x <= it.right + slop && y >= it.top - slop && y <= it.bottom + slop }
    }

    fun setMarks(list: List<Mark>) {
        marks = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        liveBox?.let { b ->
            getLocationOnScreen(loc)
            run {
                val r = RectF(b)
                r.offset(-loc[0].toFloat(), -loc[1].toFloat())
                // Blue (the quiz's accent) shows on light and dark pages alike.
                boxPaint.style = Paint.Style.FILL
                boxPaint.color = Color.argb(48, 0x2a, 0x78, 0xd6)
                canvas.drawRoundRect(r, 6 * density, 6 * density, boxPaint)
                boxPaint.style = Paint.Style.STROKE
                boxPaint.strokeWidth = 2 * density
                boxPaint.color = Color.rgb(0x2a, 0x78, 0xd6)
                canvas.drawRoundRect(r, 6 * density, 6 * density, boxPaint)
            }
        }
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
            // Your characters: a soft highlight in your level's colour, like a highlighter pen.
            if (m.high != null) {
                fill.color = m.high
                fill.alpha = 96
                canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, 3 * density, 3 * density, fill)
            }
            val stroke = (h * 0.09f).coerceIn(1.5f * density, 3.5f * density)
            // Look mode: a thin outline too, to show what can be tapped.
            if (looking) {
                paint.strokeWidth = 1 * density
                paint.color = m.under
                paint.alpha = 160
                canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, 3 * density, 3 * density, paint)
            }
            // Every character: an underline by frequency.
            val inset = (w * 0.12f).coerceAtLeast(stroke / 2)
            val y = m.bottom + stroke * 0.2f
            paint.strokeWidth = stroke
            paint.color = m.under
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
