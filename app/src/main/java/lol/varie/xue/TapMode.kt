package lol.varie.xue

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.annotation.TargetApi
import android.graphics.Rect
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
 * - A touch that starts on a character comes to 学. A quick tap without moving opens the panel. Anything else
 *   (moving, holding, a second finger, a scroll still going) is handed back while the finger is still down
 *   (requestDelegating), so the app sees the scroll or the long press, only slightly late.
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
    private val onGiveUp: (String) -> Unit,
) {
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val density = service.resources.displayMetrics.density
    private var ctl: TouchInteractionController? = null
    private var held: Mark? = null
    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var failures = 0
    private var looking = false
    private var marks: List<Mark> = emptyList()
    var active = false; private set

    private val holdTooLong = Runnable { if (held != null && down) { held = null; delegate() } }

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
                main.removeCallbacks(holdTooLong)
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
            failures = 0
            updateRegion()
            true
        } catch (t: Throwable) {
            stop()
            false
        }
    }

    fun stop() {
        main.removeCallbacks(holdTooLong)
        held = null
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

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(holdTooLong)
                down = true
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
                // Held longer than a tap: give it to the app, which then sees its own long press (text selection).
                main.postDelayed(holdTooLong, 300)
            }
            MotionEvent.ACTION_MOVE -> if (held != null && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                held = null
                main.removeCallbacks(holdTooLong)
                delegate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (held != null) {
                held = null
                main.removeCallbacks(holdTooLong)
                delegate()
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(holdTooLong)
                down = false
                val m = held
                held = null
                if (m != null) {
                    failures = 0
                    onTap(m.cp)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdTooLong)
                held = null
                down = false
            }
        }
    }

    /** Hands the current touch back to the app. Only while a finger is down: after it lifts there is nothing to hand. */
    private fun delegate() {
        val c = ctl ?: return
        try {
            c.requestDelegating()
        } catch (t: Throwable) {
            if (++failures >= 5) {
                stop()
                onGiveUp("Tap mode turned itself off: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
