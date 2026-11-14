package lol.varie.xue

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.annotation.TargetApi
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * Tap mode (Android 13+, experimental): a plain tap on a character opens its panel, like Lens.
 *
 * Android gives no way to watch taps in other apps and still let them through, so the service asks for touch
 * exploration and takes touches through a TouchInteractionController. To keep that out of the way:
 *
 * - The whole screen except the characters is a **passthrough region** (setTouchExplorationPassthroughRegion):
 *   Android hands those touches straight to the app itself, without asking 学. Characters in links and buttons
 *   stay in the region, so their taps keep working too.
 * - A touch that starts on a character comes to 学. Lifted without moving: the character's panel opens. Moved:
 *   it draws a box from where it started ([onBox]), and lifting the finger reads the text in the box ([onSelect]),
 *   so a sentence is one drag away. A second finger (pinch) or a scroll still going hands the touch back to the
 *   app (requestDelegating). The price: in tap mode a drag that starts on text selects instead of scrolling.
 *
 * Android still sends 学 the first event of a touch it is already passing through (the controller is then in
 * STATE_DELEGATING): those are ignored, and nothing is ever handed back twice. (Doing so made requestDelegating
 * throw, and counting those as failures switched tap mode off after a few touches: fixed in 0.1.15.)
 */
@TargetApi(33)
class TapMode(
    private val service: XueService,
    private val main: Handler,
    /** The character mark under a screen point, or null. */
    private val markAt: (Float, Float) -> Mark?,
    /** True while something on screen is scrolling (a tap then only stops the scroll). */
    private val scrolling: () -> Boolean,
    private val onTap: (Int) -> Unit,
    /** The box being dragged (screen coordinates), or null when it's gone. */
    private val onBox: (RectF?) -> Unit,
    /** A finished box: read the text in it. */
    private val onSelect: (RectF) -> Unit,
) {
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val density = service.resources.displayMetrics.density
    private var ctl: TouchInteractionController? = null
    private var held: Mark? = null
    private var down = false
    private var downX = 0f
    private var downY = 0f
    /** The touch became a drag: a selection box. */
    private var box: RectF? = null
    private var looking = false
    private var marks: List<Mark> = emptyList()
    var active = false; private set

    private val callback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(e: MotionEvent) {
            try {
                handle(e)
            } catch (t: Throwable) {
                held = null
                if (down) delegate()
            }
        }

        override fun onStateChanged(state: Int) {
            if (state == TouchInteractionController.STATE_CLEAR) {
                held = null
                down = false
                if (box != null) {
                    box = null
                    onBox(null)
                }
            }
        }
    }

    fun start(): Boolean {
        if (active) return true
        val info = service.serviceInfo ?: return false
        // Everything passes through until the first scan says where the characters are.
        passAll()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        service.serviceInfo = info
        return try {
            val c = service.getTouchInteractionController(Display.DEFAULT_DISPLAY)
            c.registerCallback(null, callback)
            ctl = c
            active = true
            updateRegion()
            true
        } catch (t: Throwable) {
            stop()
            false
        }
    }

    fun stop() {
        held = null
        if (box != null) {
            box = null
            onBox(null)
        }
        down = false
        runCatching { ctl?.unregisterAllCallbacks() }
        ctl = null
        active = false
        val info = service.serviceInfo ?: return
        info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        runCatching { service.serviceInfo = info }
        runCatching { service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region()) }
    }

    /** New marks from a scan: only their boxes are kept out of the passthrough region. */
    fun setMarks(list: List<Mark>) {
        marks = list
        updateRegion()
    }

    /** In look mode the layer takes the taps itself: let everything through to it. */
    fun setLooking(on: Boolean) {
        looking = on
        updateRegion()
    }

    private fun screen(): Rect = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    private fun passAll() {
        runCatching { service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region(screen())) }
    }

    private fun updateRegion() {
        if (!active) return
        if (looking) return passAll()
        val r = Region(screen())
        val pad = (3 * density).toInt()
        val box = Rect()
        for (m in marks) {
            if (m.clickable) continue
            box.set(m.left.toInt() - pad, m.top.toInt() - pad, m.right.toInt() + pad, m.bottom.toInt() + pad)
            r.op(box, Region.Op.DIFFERENCE)
        }
        runCatching { service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, r) }
    }

    /** Android is already passing this touch to the app (passthrough region) or exploring it: not 学's to decide. */
    private fun notOurs(): Boolean = when (ctl?.state) {
        TouchInteractionController.STATE_DELEGATING,
        TouchInteractionController.STATE_TOUCH_EXPLORING,
        TouchInteractionController.STATE_DRAGGING -> true
        else -> false
    }

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                box = null
                if (notOurs()) {
                    held = null
                    return
                }
                val m = if (scrolling()) null else markAt(e.x, e.y)
                if (m == null || m.clickable) {
                    // Not on a character after all (the screen moved since the last scan): give it back now.
                    held = null
                    delegate()
                    return
                }
                held = m
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (held != null) {
                if (box != null || abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                    val b = RectF(minOf(downX, e.x), minOf(downY, e.y), maxOf(downX, e.x), maxOf(downY, e.y))
                    // A box at least as tall as the character it started on, so one line is easy to take.
                    val h = held!!.bottom - held!!.top
                    if (b.height() < h) {
                        val cy = (held!!.top + held!!.bottom) / 2
                        b.top = minOf(b.top, cy - h / 2)
                        b.bottom = maxOf(b.bottom, cy + h / 2)
                    }
                    box = b
                    onBox(b)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (held != null) {
                // Two fingers: a pinch for the app.
                held = null
                if (box != null) {
                    box = null
                    onBox(null)
                }
                delegate()
            }
            MotionEvent.ACTION_UP -> {
                down = false
                val m = held
                val b = box
                held = null
                box = null
                if (b != null) {
                    onBox(null)
                    onSelect(b)
                } else if (m != null) {
                    onTap(m.cp)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                held = null
                down = false
                if (box != null) {
                    box = null
                    onBox(null)
                }
            }
        }
    }

    /**
     * Hands the current touch back to the app. Only while a finger is down (after it lifts there is nothing to hand),
     * and only once: if Android already passes it on, there is nothing to do. A refusal is harmless and ignored.
     */
    private fun delegate() {
        val c = ctl ?: return
        if (notOurs()) return
        runCatching { c.requestDelegating() }
    }
}
