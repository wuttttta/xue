package lol.varie.xue

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.annotation.TargetApi
import kotlin.math.abs

/**
 * Tap mode (Android 13+, experimental): a plain tap on a character opens its panel, like Lens.
 *
 * Android gives no way to watch taps in other apps and still let them through, so the service asks for touch
 * exploration and handles touches itself through a TouchInteractionController. Every touch that does not start
 * on a character is handed back to the app at once (requestDelegating, on the first event); one that starts on a
 * character is held until it is clearly a tap (lifted quickly, without moving) or not (moved, held, a second
 * finger), and then handed back too. Links and buttons keep working: a character in a clickable element
 * is never held.
 *
 * Safety: if handing back fails three times (events keep coming after requestDelegating), tap mode turns itself
 * off, so a bug can't leave the screen unusable.
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
    private var ctl: TouchInteractionController? = null
    private var held: Mark? = null
    private var downX = 0f
    private var downY = 0f
    private var delegatedAt = -1L
    private var delegatedDown = -1L
    private var failures = 0
    var active = false; private set

    private val holdTooLong = Runnable { if (held != null) { held = null; delegate() } }

    private val callback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(e: MotionEvent) {
            try {
                handle(e)
            } catch (t: Throwable) {
                held = null
                delegate()
            }
        }

        override fun onStateChanged(state: Int) {
            if (state == TouchInteractionController.STATE_CLEAR) {
                held = null
                main.removeCallbacks(holdTooLong)
            }
        }
    }

    fun start(): Boolean {
        if (active) return true
        val info = service.serviceInfo ?: return false
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        service.serviceInfo = info
        return try {
            val c = service.getTouchInteractionController(Display.DEFAULT_DISPLAY)
            c.registerCallback(null, callback)
            ctl = c
            active = true
            failures = 0
            true
        } catch (t: Throwable) {
            stop()
            false
        }
    }

    fun stop() {
        main.removeCallbacks(holdTooLong)
        held = null
        runCatching { ctl?.unregisterAllCallbacks() }
        ctl = null
        active = false
        val info = service.serviceInfo ?: return
        info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        runCatching { service.serviceInfo = info }
    }

    private fun handle(e: MotionEvent) {
        // Still getting this touch well after handing it back: the hand-back failed.
        if (e.downTime == delegatedDown && SystemClock.uptimeMillis() - delegatedAt > 300) {
            delegatedDown = -1
            if (++failures >= 3) {
                stop()
                onGiveUp("Tap mode turned itself off: Android didn't pass touches on to the app.")
                return
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(holdTooLong)
                val m = if (scrolling()) null else markAt(e.x, e.y)
                if (m == null || m.clickable) {
                    held = null
                    delegate(e.downTime)
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
                delegate(e.downTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (held != null) {
                held = null
                main.removeCallbacks(holdTooLong)
                delegate(e.downTime)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(holdTooLong)
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
            }
        }
    }

    private fun delegate(downTime: Long = -1) {
        val c = ctl ?: return
        delegatedAt = SystemClock.uptimeMillis()
        delegatedDown = downTime
        try {
            c.requestDelegating()
        } catch (t: Throwable) {
            if (++failures >= 3) {
                stop()
                onGiveUp("Tap mode turned itself off: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
