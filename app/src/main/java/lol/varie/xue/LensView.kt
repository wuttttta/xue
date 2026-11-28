package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Lens mode, like Google Lens on the screen: a still screenshot over everything, with every character in it marked
 * (the app's text, and what OCR finds in images). Tap a character: its card ([onTap]). Drag over a sentence: the
 * quiz opens it ([onBox], with the box in screen coordinates). × or Back closes ([onClose]). Nothing reaches the app
 * underneath while it is open.
 */
@SuppressLint("ViewConstructor")
class LensView(
    context: Context,
    private val shot: Bitmap,
    private val onTap: (Int) -> Unit,
    private val onBox: (RectF) -> Unit,
    private val onClose: () -> Unit,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var marks: List<Mark> = emptyList()
    /** A line at the bottom: what Lens is doing (reading the image, nothing found…). */
    var status = ""
        set(v) {
            field = v
            invalidate()
        }
    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bar = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14 * resources.displayMetrics.scaledDensity
    }
    private val close = RectF()
    private var box: RectF? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "学 lens"
    }

    fun setMarks(list: List<Mark>) {
        marks = list
        invalidate()
    }

    fun current(): List<Mark> = marks

    override fun onDraw(canvas: Canvas) {
        if (shot.isRecycled) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        canvas.drawBitmap(shot, 0f, 0f, null)
        // A light veil, so the marks stand out and it's clear the screen is frozen.
        canvas.drawColor(Color.argb(40, 0, 0, 0))
        for (m in marks) {
            val r = 3 * dp
            fill.color = m.high ?: m.under
            fill.alpha = if (m.high != null) 110 else 50
            canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, r, r, fill)
            stroke.strokeWidth = 1.5f * dp
            stroke.color = m.under
            stroke.alpha = 230
            canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, r, r, stroke)
        }
        box?.let { b ->
            fill.color = Color.argb(48, 0x2a, 0x78, 0xd6)
            canvas.drawRoundRect(b, 6 * dp, 6 * dp, fill)
            stroke.strokeWidth = 2 * dp
            stroke.color = Color.rgb(0x2a, 0x78, 0xd6)
            canvas.drawRoundRect(b, 6 * dp, 6 * dp, stroke)
        }
        canvas.restore()

        // Top: what to do, and ×. Bottom: status.
        val top = rootWindowInsets?.stableInsetTop?.toFloat() ?: (24 * dp)
        val barH = 44 * dp
        canvas.drawRect(0f, 0f, width.toFloat(), top + barH, bar)
        canvas.drawText("Tap a character · drag over a sentence", width / 2f, top + barH / 2 + 5 * dp, label)
        val cx = width - 28 * dp
        val cy = top + barH / 2
        close.set(cx - 22 * dp, cy - 22 * dp, cx + 22 * dp, cy + 22 * dp)
        stroke.color = Color.WHITE
        stroke.alpha = 255
        stroke.strokeWidth = 2.5f * dp
        val k = 8 * dp
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
        canvas.drawLine(cx - k, cy + k, cx + k, cy - k, stroke)
        if (status.isNotEmpty()) {
            val bottom = height - (rootWindowInsets?.stableInsetBottom?.toFloat() ?: 0f)
            canvas.drawRect(0f, bottom - 36 * dp, width.toFloat(), bottom, bar)
            canvas.drawText(status, width / 2f, bottom - 13 * dp, label)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // View coordinates of the close button; marks and boxes in screen coordinates.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                box = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging || abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) {
                    dragging = true
                    box = RectF(minOf(downX, e.rawX), minOf(downY, e.rawY), maxOf(downX, e.rawX), maxOf(downY, e.rawY))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val b = box
                box = null
                invalidate()
                if (dragging && b != null) {
                    // At least a character tall, so a sideways drag takes the line it runs along.
                    if (b.height() < 24 * dp) b.inset(0f, -(24 * dp - b.height()) / 2)
                    onBox(b)
                } else if (close.contains(e.x, e.y)) {
                    onClose()
                } else {
                    hit(e.rawX, e.rawY)?.let { onTap(it.cp) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                box = null
                invalidate()
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hit(x: Float, y: Float): Mark? {
        val s = 8 * dp
        return marks.filter { x >= it.left - s && x <= it.right + s && y >= it.top - s && y <= it.bottom + s }
            .minByOrNull {
                val dx = x - (it.left + it.right) / 2
                val dy = y - (it.top + it.bottom) / 2
                dx * dx + dy * dy
            }
    }
}
