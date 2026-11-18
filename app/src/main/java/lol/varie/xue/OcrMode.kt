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
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
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
 *
 * The recognition model is not in the APK: Google Play services downloads it the first time OCR is turned on
 * (ModuleInstall) and keeps it, so later starts are immediate and offline.
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
    /** The model is on the phone. */
    @Volatile private var ready = false
    private var lastShot = 0L
    /** What it is doing, for the settings screen. */
    @Volatile var status = ""; private set

    private val tick = Runnable { capture() }

    fun start() {
        if (running) return
        running = true
        status = "Starting…"
        if (ready) kick(0) else ensureModel()
    }

    /** Checks that Play services has the model; downloads it once if not. Reading starts when it's there. */
    private fun ensureModel() {
        val client = ModuleInstall.getClient(service)
        client.areModulesAvailable(recognizer)
            .addOnSuccessListener(onWorker) { r ->
                if (r.areModulesAvailable()) {
                    ready = true
                    kick(0)
                } else {
                    download()
                }
            }
            .addOnFailureListener(onWorker) { e ->
                status = "OCR needs Google Play services (${e.message ?: e.javaClass.simpleName})"
            }
    }

    private fun download() {
        val client = ModuleInstall.getClient(service)
        status = "Downloading the OCR model (once)…"
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                update.progressInfo?.let { p ->
                    if (p.totalBytesToDownload > 0) {
                        status = "Downloading the OCR model (once): ${100 * p.bytesDownloaded / p.totalBytesToDownload}%"
                    }
                }
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                        client.unregisterListener(this)
                        ready = true
                        status = "Model ready"
                        kick(0)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        client.unregisterListener(this)
                        status = "Couldn't download the OCR model: turn OCR off and on to try again"
                    }
                }
            }
        }
        val req = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener, onWorker).build()
        client.installModules(req)
            .addOnSuccessListener(onWorker) { resp ->
                if (resp.areModulesAlreadyInstalled()) {
                    client.unregisterListener(listener)
                    ready = true
                    kick(0)
                }
            }
            .addOnFailureListener(onWorker) { e ->
                status = "Couldn't download the OCR model (${e.message ?: e.javaClass.simpleName})"
            }
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
        if (!running || !ready) return
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

    /** Runs [then] on the worker once the model is on the phone; if it isn't, starts its download and says so. */
    private fun withModel(then: () -> Unit, missing: (String) -> Unit) {
        if (ready) {
            worker.post(then)
            return
        }
        ModuleInstall.getClient(service).areModulesAvailable(recognizer)
            .addOnSuccessListener(onWorker) { r ->
                if (r.areModulesAvailable()) {
                    ready = true
                    then()
                } else {
                    download()
                    missing("Downloading the OCR model first (once); text from the app is marked meanwhile")
                }
            }
            .addOnFailureListener(onWorker) { missing("Reading images needs Google Play services") }
    }

    /**
     * Lens: every Chinese character in a still screenshot (screen coordinates, as the bitmap is the whole display).
     * [cb] gets them, or null and why not. The bitmap isn't recycled here.
     */
    fun readShot(shot: Bitmap, cb: (List<OcrChar>?, String?) -> Unit) {
        withModel({
            recognizer.process(InputImage.fromBitmap(shot, 0))
                .addOnSuccessListener(onWorker) { text ->
                    val out = ArrayList<OcrChar>()
                    for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) for (sym in el.symbols) {
                        val b = sym.boundingBox ?: continue
                        val t = sym.text
                        if (t.isEmpty()) continue
                        val cp = t.codePointAt(0)
                        if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) continue
                        out.add(OcrChar(RectF(b), cp))
                    }
                    cb(out, null)
                }
                .addOnFailureListener(onWorker) { e -> cb(null, "Recognition failed: ${e.message ?: e.javaClass.simpleName}") }
        }, { why -> cb(null, why) })
    }

    /** Lens: the text in one box of a still screenshot (spaces and line breaks removed), or null and why not. */
    fun readShotArea(shot: Bitmap, area: RectF, cb: (String?, String?) -> Unit) {
        withModel({
            val r = android.graphics.Rect(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
            if (!r.intersect(0, 0, shot.width, shot.height)) {
                cb(null, "Nothing there")
            } else {
                val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(onWorker) { text ->
                        if (crop !== shot) crop.recycle()
                        val t = text.text.replace(Regex("\\s+"), "")
                        cb(t.ifEmpty { null }, if (t.isEmpty()) "No text found there" else null)
                    }
                    .addOnFailureListener(onWorker) { e ->
                        if (crop !== shot) crop.recycle()
                        cb(null, "Recognition failed: ${e.message ?: e.javaClass.simpleName}")
                    }
            }
        }, { why -> cb(null, why) })
    }

    /**
     * Reads the text in one box of the screen once (select mode), whether OCR mode is on or not.
     * [cb] gets the text (spaces and line breaks removed: Chinese doesn't use them) or, if it failed, null and why.
     */
    fun readArea(area: RectF, cb: (String?, String?) -> Unit) {
        // A moment for the select veil to leave the screen before the screenshot.
        worker.postDelayed({
            if (ready) {
                shootArea(area, cb)
            } else {
                ModuleInstall.getClient(service).areModulesAvailable(recognizer)
                    .addOnSuccessListener(onWorker) { r ->
                        if (r.areModulesAvailable()) {
                            ready = true
                            shootArea(area, cb)
                        } else {
                            download()
                            cb(null, "Downloading the OCR model first (once): try again in a minute")
                        }
                    }
                    .addOnFailureListener(onWorker) { cb(null, "Reading images needs Google Play services") }
            }
        }, 250)
    }

    private fun shootArea(area: RectF, cb: (String?, String?) -> Unit) {
        var windowId = -1
        var dx = 0
        var dy = 0
        if (Build.VERSION.SDK_INT >= 34) {
            for (w in service.windows.sortedByDescending { it.layer }) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val b = Rect()
                w.getBoundsInScreen(b)
                if (!b.contains(area.centerX().toInt(), area.centerY().toInt())) continue
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName != service.packageName) {
                    windowId = w.id
                    dx = b.left
                    dy = b.top
                }
                break
            }
        }
        val shotCb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                val crop = try {
                    val hb = shot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    soft?.let { full ->
                        val r = Rect(
                            (area.left - dx).toInt(), (area.top - dy).toInt(),
                            (area.right - dx).toInt(), (area.bottom - dy).toInt(),
                        )
                        if (!r.intersect(0, 0, full.width, full.height)) null
                        else Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()).also { if (it !== full) full.recycle() }
                    }
                } catch (t: Throwable) {
                    null
                }
                if (crop == null) {
                    cb(null, "Couldn't read the screenshot")
                    return
                }
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(onWorker) { text ->
                        crop.recycle()
                        val t = text.text.replace(Regex("\\s+"), "")
                        cb(t.ifEmpty { null }, if (t.isEmpty()) "No text found there" else null)
                    }
                    .addOnFailureListener(onWorker) { e ->
                        crop.recycle()
                        cb(null, "Recognition failed: ${e.message ?: e.javaClass.simpleName}")
                    }
            }

            override fun onFailure(errorCode: Int) {
                cb(null, when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        "Android doesn't let 学 take screenshots yet: turn \"学 marks\" off and on in Accessibility settings"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app doesn't allow screenshots"
                    else -> "Screenshot failed ($errorCode)"
                })
            }
        }
        try {
            if (windowId >= 0 && Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, onWorker, shotCb)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, onWorker, shotCb)
        } catch (t: Throwable) {
            cb(null, "Screenshot failed: ${t.message ?: t.javaClass.simpleName}")
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
