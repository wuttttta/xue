package lol.varie.xue

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executor

/** A character read from the screen's pixels: its box in screen coordinates. */
class OcrChar(val box: RectF, val cp: Int)

/**
 * OCR mode (extra, off by default): finds Chinese characters in images, video and games, where the accessibility
 * tree has no text. It takes a screenshot through the accessibility service itself (no screen-recording prompt;
 * Android 14+ captures the app's window alone, without 学's own marks), reads it with ML Kit's on-device Chinese
 * text recognition, and hands the characters to the service, which marks them like any other.
 *
 * It reads again every [EVERY_MS] while on, and soon after a scroll stops (marks from before a scroll are dropped
 * at once, since they no longer match the screen).
 */
@TargetApi(30)
class OcrMode(
    private val service: XueService,
    private val worker: Handler,
    private val onResult: (List<OcrChar>) -> Unit,
) {
    companion object {
        const val EVERY_MS = 1500L
        const val AFTER_SCROLL_MS = 450L
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val onWorker = Executor { worker.post(it) }
    @Volatile private var running = false
    private var busy = false
    private var lastShot = 0L
    /** What it is doing, for the settings screen. */
    @Volatile var status = ""; private set

    private val tick = Runnable { capture() }

    fun start() {
        if (running) return
        running = true
        status = "Starting…"
        kick(0)
    }

    fun stop() {
        running = false
        worker.removeCallbacks(tick)
        status = ""
        onResult(emptyList())
    }

    fun close() {
        stop()
        runCatching { recognizer.close() }
    }

    /** The screen moved: drop the old characters now, read again once it settles. */
    fun invalidate() {
        if (!running) return
        onResult(emptyList())
        kick(AFTER_SCROLL_MS)
    }

    private fun kick(delay: Long) {
        if (!running) return
        worker.removeCallbacks(tick)
        // Android allows about three screenshots a second.
        val wait = maxOf(delay, lastShot + 400 - SystemClock.uptimeMillis(), 0)
        worker.postDelayed(tick, wait)
    }

    private fun capture() {
        if (!running || busy) return
        // Android 14+: the top app's window alone. Older: the whole screen (with 学's marks on it).
        var offsetX = 0
        var offsetY = 0
        val covered = Region()
        var windowId = -1
        if (Build.VERSION.SDK_INT >= 34) {
            for (w in service.windows.sortedByDescending { it.layer }) {
                val b = Rect()
                w.getBoundsInScreen(b)
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    if (w.root?.packageName == service.packageName) {
                        // 学's own panel or settings are on top: nothing to read.
                        status = "Paused while 学 is open"
                        onResult(emptyList())
                        kick(EVERY_MS)
                        return
                    }
                    windowId = w.id
                    offsetX = b.left
                    offsetY = b.top
                    break
                }
                covered.op(b, Region.Op.UNION)
            }
            if (windowId < 0) {
                kick(EVERY_MS)
                return
            }
        }
        busy = true
        lastShot = SystemClock.uptimeMillis()
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                val bmp = try {
                    val hb = shot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    soft
                } catch (t: Throwable) {
                    null
                }
                if (bmp == null) {
                    done("Couldn't read the screenshot", null)
                    return
                }
                read(bmp, offsetX, offsetY, covered)
            }

            override fun onFailure(errorCode: Int) {
                done(
                    when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                            "Android doesn't let 学 take screenshots yet: turn \"学 marks\" off and on in Accessibility settings"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app doesn't allow screenshots"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> status
                        else -> "Screenshot failed ($errorCode)"
                    },
                    null,
                )
            }
        }
        try {
            if (windowId >= 0 && Build.VERSION.SDK_INT >= 34) {
                service.takeScreenshotOfWindow(windowId, onWorker, cb)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, onWorker, cb)
            }
        } catch (t: Throwable) {
            done("Screenshot failed: ${t.message ?: t.javaClass.simpleName}", null)
        }
    }

    private fun read(bmp: Bitmap, dx: Int, dy: Int, covered: Region) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener(onWorker) { text ->
                val out = ArrayList<OcrChar>()
                for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) for (sym in el.symbols) {
                    val b = sym.boundingBox ?: continue
                    val t = sym.text
                    if (t.isEmpty()) continue
                    val cp = t.codePointAt(0)
                    if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) continue
                    val r = RectF(b)
                    r.offset(dx.toFloat(), dy.toFloat())
                    // Under a window above the one read (a dialog, the status bar): not visible.
                    if (covered.contains(r.centerX().toInt(), r.centerY().toInt())) continue
                    out.add(OcrChar(r, cp))
                }
                bmp.recycle()
                done(if (out.isEmpty()) "Reading: no Chinese characters in view" else "Reading: ${out.size} characters in view", out)
            }
            .addOnFailureListener(onWorker) { e ->
                bmp.recycle()
                done("Recognition failed: ${e.message ?: e.javaClass.simpleName}", null)
            }
    }

    private fun done(msg: String, chars: List<OcrChar>?) {
        busy = false
        status = msg
        if (!running) return
        if (chars != null) onResult(chars)
        kick(if (chars != null) EVERY_MS else 3000)
    }
}
