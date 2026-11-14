package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The floating button that turns look mode on and off. Drag it anywhere; a tap toggles.
 * [onMove] gets the finger's movement in pixels while dragging, [onDrop] when the drag ends.
 */
@SuppressLint("ViewConstructor")
class LookButton(
    context: Context,
    private val onTap: () -> Unit,
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onDrop: () -> Unit,
) : View(context) {
    private val dp = resources.displayMetrics.density
    val sizePx = (44 * dp).toInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = Color.WHITE
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 22 * dp
    }

    var looking = false
        set(v) {
            field = v
            invalidate()
        }

    init {
        contentDescription = "学: look up a character"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val dark = Palette.isDark(context)
        val r = sizePx / 2f
        disc.color = if (looking) Palette.level(4, dark) else Palette.freq(3, dark)
        disc.alpha = if (looking) 255 else 190
        canvas.drawCircle(r, r, r - 1 * dp, disc)
        if (looking) canvas.drawCircle(r, r, r - 3.5f * dp, ring)
        val fm = glyph.fontMetrics
        canvas.drawText("学", r, r - (fm.ascent + fm.descent) / 2, glyph)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                if (dragging) {
                    onMove((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX += (e.rawX - lastX).toInt()
                    lastY += (e.rawY - lastY).toInt()
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) onDrop() else onTap()
            MotionEvent.ACTION_CANCEL -> if (dragging) onDrop()
        }
        return true
    }
}
