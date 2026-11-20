package lol.varie.xue

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the text on screen (any app), finds the Chinese characters and their boxes, and draws a mark under each
 * on a see-through layer: your characters by your level (warm colours), the rest by frequency (ƒ, blue).
 */
class XueService : AccessibilityService() {

    companion object {
        /** The running service, for the settings screen's status line. */
        @Volatile var running: XueService? = null; private set

        private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        private const val MIN_INTERVAL_MS = 100L
        private const val MAX_CHARS_PER_NODE = 5000
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 150
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("xue-scan").apply { start() }
    private val workerHandler = Handler(worker.looper)

    private var wm: WindowManager? = null
    private var layer: MarkLayer? = null
    private var layerParams: WindowManager.LayoutParams? = null
    private var button: LookButton? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    @Volatile private var looking = false
    private var tap: TapMode? = null
    @Volatile private var tapOn = false
    @Volatile private var lastScrollAt = 0L
    private var ocr: OcrMode? = null
    private var lens: LensView? = null
    private var lensShot: android.graphics.Bitmap? = null
    private var lensParams: WindowManager.LayoutParams? = null
    /** Marks from the accessibility text, and from OCR; drawn together. */
    private var textMarks: List<Mark> = emptyList()
    private var ocrMarks: List<Mark> = emptyList()
    /** What OCR mode is doing, for the settings screen ("" when off). */
    val ocrStatus: String get() = ocr?.status.orEmpty()
    /** Tap mode is on and Android has granted it. */
    val tapActive: Boolean get() = tap?.active == true
    val isLooking: Boolean get() = looking
    private lateinit var prefs: SharedPreferences
    @Volatile private var show = true
    @Volatile private var byAge = false

    @Volatile private var scanQueued = false
    private var lastScan = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            Prefs.SHOW -> {
                show = p.getBoolean(Prefs.SHOW, true)
                main.post { layer?.drawMarks = show; StatusNote.show(this) }
                requestScan()
            }
            Prefs.BUTTON -> main.post {
                button?.visibility = if (p.getBoolean(Prefs.BUTTON, false)) View.VISIBLE else View.GONE
            }
            Prefs.TAP -> main.post { applyTapMode(); StatusNote.show(this) }
            Prefs.OCR -> main.post { applyOcr(); StatusNote.show(this) }
            Prefs.QUIZ -> main.post { StatusNote.show(this) }
            Prefs.BY_AGE -> {
                byAge = p.getBoolean(Prefs.BY_AGE, false)
                requestScan()
            }
            Prefs.LIST_CODE -> workerHandler.post { Mine.sync(this); requestScan() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        prefs = Prefs.get(this)
        show = prefs.getBoolean(Prefs.SHOW, true)
        byAge = prefs.getBoolean(Prefs.BY_AGE, false)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        addLayer()
        addButton()
        layer?.drawMarks = show
        applyTapMode()
        applyOcr()
        StatusNote.show(this)
        workerHandler.post {
            if (!Freq.loaded) Freq.load(this)
            Mine.load(this)
            Stats.load(this)
        }
        workerHandler.post(syncMine)
        workerHandler.postDelayed(heartbeat, 1000)
        requestScan()
    }

    private fun addLayer() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = MarkLayer(this, ::onPick)
        w.addView(v, lp)
        wm = w
        layer = v
        layerParams = lp
    }

    /** The floating 学 button, above the layer: a tap turns look mode on or off, a drag moves it. */
    private fun addButton() {
        val w = wm ?: return
        val prefs = Prefs.get(this)
        val dm = resources.displayMetrics
        val b = LookButton(this, onTap = { setLooking(!looking) }, onMove = { dx, dy -> moveButton(dx, dy) }, onDrop = {
            buttonParams?.let { prefs.edit().putInt(Prefs.BTN_X, it.x).putInt(Prefs.BTN_Y, it.y).apply() }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt(Prefs.BTN_X, dm.widthPixels - b.sizePx - (8 * dm.density).toInt())
        lp.y = prefs.getInt(Prefs.BTN_Y, (dm.heightPixels * 0.62f).toInt())
        b.visibility = if (prefs.getBoolean(Prefs.BUTTON, false)) View.VISIBLE else View.GONE
        w.addView(b, lp)
        button = b
        buttonParams = lp
    }

    private fun moveButton(dx: Int, dy: Int) {
        val lp = buttonParams ?: return
        val dm = resources.displayMetrics
        val size = button?.sizePx ?: 0
        lp.x = (lp.x + dx).coerceIn(0, dm.widthPixels - size)
        lp.y = (lp.y + dy).coerceIn(0, dm.heightPixels - size)
        button?.let { runCatching { wm?.updateViewLayout(it, lp) } }
    }

    /** Look mode: the layer takes touches, so a tap picks a character instead of reaching the app. */
    private fun setLooking(on: Boolean) {
        if (looking == on) return
        looking = on
        layer?.looking = on
        layerTakesTouches(on)
        button?.looking = on
        requestScan()
        LookTile.refresh(this)
    }

    /** The layer takes touches (look mode) or lets them all through. */
    private fun layerTakesTouches(on: Boolean) {
        val lp = layerParams ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        layer?.let { runCatching { wm?.updateViewLayout(it, lp) } }
        // Tap mode lets everything through to the layer meanwhile.
        if (Build.VERSION.SDK_INT >= 33) tap?.setLooking(on)
    }

    /**
     * Lens (from the notification or the "学 lens" tile), like Google Lens on the screen: the shade closes, a still
     * screenshot covers the screen with every character in it marked (the app's text now, and OCR of the picture),
     * and taps or drags go to it alone. Closed with × or Back; hidden while a card or sentence it opened is up.
     */
    fun startLens() = main.post {
        if (lens != null) return@post
        dailySync()
        if (Build.VERSION.SDK_INT < 30) {
            toast("Lens needs Android 11 or newer")
            return@post
        }
        setLooking(false)
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // Wait for the shade to go, hide 学's own marks so they aren't in the picture, then take it.
        main.postDelayed({
            layer?.visibility = View.INVISIBLE
            button?.visibility = View.INVISIBLE
            main.postDelayed(::shootLens, 120)
        }, 450)
    }

    /** The app in front (the top application window that isn't 学), for the history. */
    fun topApp(): String? = runCatching {
        windows.sortedByDescending { it.layer }
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != packageName }
            ?.root?.packageName?.toString()
    }.getOrNull()

    @android.annotation.TargetApi(30)
    private fun shootLens() {
        val marks = textMarks
        val pkg = topApp()
        fun restore() {
            layer?.visibility = View.VISIBLE
            button?.visibility = if (prefs.getBoolean(Prefs.BUTTON, false)) View.VISIBLE else View.GONE
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                restore()
                val shot = runCatching {
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    r.hardwareBuffer.close()
                    soft
                }.getOrNull()
                if (shot == null) toast("Lens couldn't read the screen") else showLens(shot, marks, pkg)
            }

            override fun onFailure(code: Int) {
                restore()
                toast(
                    if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                        "Lens needs the screenshot permission: turn \"学 marks\" off and on in Accessibility settings"
                    } else if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        "This app doesn't allow screenshots"
                    } else {
                        "Lens couldn't take a screenshot ($code)"
                    },
                )
            }
        })
    }

    @android.annotation.TargetApi(30)
    private fun showLens(shot: android.graphics.Bitmap, marks: List<Mark>, pkg: String?) {
        val w = wm ?: return
        val v = LensView(
            this, shot,
            onTap = { cp -> openPanel(cp) },
            onBox = { b -> lensRead(b) },
            onClose = ::closeLens,
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lens = v
        lensShot = shot
        lensParams = lp
        lensShown(true)
        v.setMarks(marks)
        v.status = "Reading the picture…"
        // Tap mode lets every touch through to Lens meanwhile.
        if (Build.VERSION.SDK_INT >= 33) tap?.setLooking(true)
        val o = ocr ?: OcrMode(this, workerHandler) { chars -> main.post { setOcr(chars) } }.also { ocr = it }
        o.readShot(shot) { chars, why ->
            main.post {
                if (lens !== v) return@post
                if (chars != null) {
                    if (pkg != null) workerHandler.post { Stats.recordPicture(pkg, chars.map { it.cp }) }
                    val dark = Palette.isDark(this)
                    val extra = chars.map { toMark(it, dark) }.filter { o2 -> marks.none { overlaps(it, o2) } }
                    v.setMarks(marks + extra)
                    v.status = if (marks.isEmpty() && extra.isEmpty()) "No Chinese found on this screen" else ""
                } else {
                    v.status = why ?: ""
                }
            }
        }
    }

    /** Lens window on or off screen (it is taken away while a card it opened is up, so the card can be used). */
    private fun lensShown(on: Boolean) {
        val v = lens ?: return
        val lp = lensParams ?: return
        val attached = v.isAttachedToWindow
        if (on && !attached) {
            runCatching { wm?.addView(v, lp) }
            v.requestFocus()
        } else if (!on && attached) {
            runCatching { wm?.removeView(v) }
        }
    }

    fun closeLens() {
        lensShown(false)
        lens = null
        lensParams = null
        lensShot?.recycle()
        lensShot = null
        if (Build.VERSION.SDK_INT >= 33) tap?.setLooking(looking)
    }

    /** The panel went away: back to Lens, if it was open. */
    fun onPanelHidden() = main.post { if (lens != null) lensShown(true) }

    /** A box dragged in Lens: the characters marked in it, or else OCR of that part of the picture. */
    @android.annotation.TargetApi(30)
    private fun lensRead(b: android.graphics.RectF) {
        val v = lens ?: return
        val inside = v.current().filter { b.contains((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
        if (inside.isNotEmpty()) {
            openText(readingOrder(inside))
            return
        }
        val shot = lensShot ?: return
        v.status = "Reading…"
        (ocr ?: return).readShotArea(shot, b) { text, why ->
            main.post {
                if (lens !== v) return@post
                v.status = ""
                if (text != null) openText(text) else v.status = why ?: "Nothing found there"
            }
        }
    }

    /** The text in a box: the characters marked there (exact), or else read from the pixels (OCR). */
    private fun readArea(area: android.graphics.RectF) {
        val inside = (layer?.current() ?: emptyList()).filter {
            area.contains((it.left + it.right) / 2, (it.top + it.bottom) / 2)
        }
        if (inside.isNotEmpty()) {
            openText(readingOrder(inside))
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            toast("No Chinese marked there")
            return
        }
        val o = ocr ?: OcrMode(this, workerHandler) { chars -> main.post { setOcr(chars) } }.also { ocr = it }
        o.readArea(area) { text, why -> main.post { if (text != null) openText(text) else toast(why ?: "Nothing found") } }
    }

    /** Characters in reading order: lines top to bottom (a new line when one starts below the last's middle), left to right. */
    private fun readingOrder(ms: List<Mark>): String {
        val lines = ArrayList<ArrayList<Mark>>()
        for (m in ms.sortedBy { it.top }) {
            val line = lines.lastOrNull()
            if (line != null && m.top < line.maxOf { (it.top + it.bottom) / 2 }) line.add(m) else lines.add(arrayListOf(m))
        }
        return lines.joinToString("") { l -> l.sortedBy { it.left }.joinToString("") { String(Character.toChars(it.cp)) } }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun openText(text: String) {
        lensShown(false)
        startActivity(
            Intent(this, PanelActivity::class.java)
                .putExtra(PanelActivity.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** From the Quick Settings tile: look mode on, and the shade out of the way so the screen can be tapped. */
    fun lookFromTile() = main.post {
        setLooking(true)
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    /** A tap in look mode: on a character, open its panel; anywhere else, leave look mode. */
    private fun onPick(cp: Int?) {
        // The layer covers the panel too, so it must stop taking touches before the panel shows.
        setLooking(false)
        if (cp != null) openPanel(cp)
    }

    /** Tap mode on or off, as set in 学's settings (Android 13+). */
    private fun applyTapMode() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = prefs.getBoolean(Prefs.TAP, false)
        if (want) {
            val t = tap ?: TapMode(
                this, main,
                markAt = { x, y -> layer?.markAt(x, y) },
                onTap = ::openPanel,
                onBox = { b -> layer?.setBox(b) },
                onSelect = { b -> readArea(b) },
            ).also { tap = it }
            t.start()
        } else {
            tap?.stop()
        }
        tapOn = tapActive
        requestScan()
    }

    /** OCR mode on or off, as set in 学's settings or its tile (Android 11+). */
    private fun applyOcr() {
        if (Build.VERSION.SDK_INT < 30) return
        if (prefs.getBoolean(Prefs.OCR, false)) {
            val o = ocr ?: OcrMode(this, workerHandler) { chars -> main.post { setOcr(chars) } }.also { ocr = it }
            o.start()
        } else {
            ocr?.stop()
        }
    }

    /** Characters read by OCR, coloured like the others; never a link (a tap on one opens the panel). */
    private fun setOcr(chars: List<OcrChar>) {
        val dark = Palette.isDark(this)
        ocrMarks = chars.map { toMark(it, dark) }
        publish()
        // Into the history too (subtitles drawn into a video, text in images): under the app they were read from.
        ocr?.lastPkg?.let { pkg -> workerHandler.post { Stats.recordPicture(pkg, chars.map { it.cp }) } }
    }

    private fun toMark(c: OcrChar, dark: Boolean): Mark {
        return Mark(
            c.box.left, c.box.top, c.box.right, c.box.bottom, c.cp,
            under = Palette.freq(Freq.level(c.cp), dark),
            high = Mine.highlight(c.cp, dark, byAge),
        )
    }

    /** An OCR mark lying on a text mark (its middle inside it): the text one is kept, its box is better. */
    private fun overlaps(t: Mark, o: Mark): Boolean {
        val cx = (o.left + o.right) / 2
        val cy = (o.top + o.bottom) / 2
        return cx >= t.left && cx <= t.right && cy >= t.top && cy <= t.bottom
    }

    /** Draws text marks plus the OCR marks that aren't already on a text mark (text gives the better boxes). */
    private fun publish() {
        val all = if (ocrMarks.isEmpty()) textMarks else textMarks + ocrMarks.filter { o -> textMarks.none { overlaps(it, o) } }
        layer?.setMarks(all)
        if (Build.VERSION.SDK_INT >= 33) tap?.setMarks(all)
    }

    private fun openPanel(cp: Int) {
        lensShown(false)
        dailySync()
        startActivity(
            Intent(this, PanelActivity::class.java)
                .putExtra(PanelActivity.EXTRA_CHAR, String(Character.toChars(cp)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollAt = SystemClock.uptimeMillis()
                ocr?.invalidate()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> ocr?.invalidate()
        }
        if (show || looking || tapOn) requestScan()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        button?.invalidate()
        requestScan()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = null
        StatusNote.hide(this)
        closeLens()
        if (Build.VERSION.SDK_INT >= 33) tap?.stop()
        if (Build.VERSION.SDK_INT >= 30) ocr?.close()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        layer?.let { runCatching { wm?.removeView(it) } }
        button?.let { runCatching { wm?.removeView(it) } }
        layer = null
        button = null
        worker.quitSafely()
        super.onDestroy()
    }

    /** Your list and results: fetched on the first use of the day (the saved copy is used the rest of the day). */
    private val syncMine = Runnable {
        if (Mine.code(this@XueService) != null && Mine.stale(this@XueService)) {
            Mine.sync(this@XueService)
            requestScan()
        }
    }

    /** Opened something of 学's (the panel, Lens): a new day means a fresh copy first. */
    fun dailySync() {
        workerHandler.post(syncMine)
    }

    /** After the panel closes: the list may have new words, the results new answers (the quiz sends those ~25 s later). */
    fun syncSoon() {
        for (delay in longArrayOf(3000, 35000)) {
            workerHandler.postDelayed({
                if (Mine.code(this) != null) {
                    Mine.sync(this)
                    requestScan()
                }
            }, delay)
        }
    }

    /** Redraws after the settings screen changed something. */
    fun refresh() = requestScan()

    /** Coalesces events: at most one scan every MIN_INTERVAL_MS, always one after the last event. */
    private fun requestScan() {
        if (scanQueued) return
        scanQueued = true
        val wait = (lastScan + MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        workerHandler.postDelayed(::scan, wait)
    }

    private fun scan() {
        scanQueued = false
        lastScan = SystemClock.uptimeMillis()
        // With marks off they are still found (not drawn) for tap mode, and drawn while look mode lasts.
        if (!show && !looking && !tapOn) return
        if (!Freq.loaded) Freq.load(this)
        val marks = try {
            collect()
        } catch (e: Exception) {
            // Nodes can vanish mid-scan; the next event brings a new one.
            return
        }
        main.post {
            textMarks = marks
            publish()
        }
    }

    private class Scan(val dark: Boolean, val covered: Region, val win: Rect) {
        val out = ArrayList<Mark>()
        val seen = HashSet<Long>()
        var nodes = 0
        var blocks = 0
        var withBoxes = 0
        var chars = 0
    }

    private fun collect(): List<Mark> {
        val dark = Palette.isDark(this)
        val covered = Region()
        val all = ArrayList<Mark>()
        // Top window first; a character is marked only where no window above covers it.
        for (w in windows.sortedByDescending { it.layer }) {
            val wb = Rect()
            w.getBoundsInScreen(wb)
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val root = w.root
                // 学's own screens (settings, the panel) are not marked; they still hide what is under them.
                if (root != null && root.packageName != packageName) {
                    val t0 = SystemClock.uptimeMillis()
                    val s = Scan(dark, covered, wb)
                    walk(root, 0, s)
                    all.addAll(s.out)
                    Stats.record(
                        root.packageName?.toString() ?: "?",
                        s.blocks, s.withBoxes, SystemClock.uptimeMillis() - t0,
                        s.out.map { it.cp },
                    )
                }
            }
            covered.op(wb, Region.Op.UNION)
        }
        return all
    }

    /**
     * Children first: some apps (Jetpack Compose cards, React Native) also give a container the joined text of what
     * is inside it, and report boxes for that copy in the wrong places, which put marks over unrelated text.
     * A node whose Chinese characters all appear in its descendants' text is such a copy and is skipped.
     * Returns the Chinese characters of the text in this subtree.
     */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, s: Scan): String {
        if (depth > MAX_DEPTH || s.nodes >= MAX_NODES) return ""
        s.nodes++
        if (!node.isVisibleToUser) return ""
        val inside = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            inside.append(walk(child, depth + 1, s))
        }
        val text = node.text
        if (!text.isNullOrEmpty()) {
            val t = text.toString()
            val han = hanOf(t)
            if (han.isNotEmpty()) {
                val kids = inside.toString()
                val copy = kids.isNotEmpty() && han.codePoints().allMatch { cp -> kids.contains(String(Character.toChars(cp))) }
                if (!copy) markText(node, t, s)
                inside.append(han)
            }
        }
        return inside.toString()
    }

    private fun hanOf(t: String): String {
        val b = StringBuilder()
        t.codePoints().forEach { if (isHan(it)) b.appendCodePoint(it) }
        return b.toString()
    }

    /** A rescan every second while the screen is on, for apps that move their content without telling Android. */
    private val heartbeat = object : Runnable {
        override fun run() {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if ((show || looking || tapOn) && pm?.isInteractive != false) requestScan()
            Stats.saveSoon(this@XueService)
            workerHandler.postDelayed(this, 1000)
        }
    }

    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun markText(node: AccessibilityNodeInfo, text: String, s: Scan) {
        var first = -1
        var last = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isHan(cp)) {
                if (first < 0) first = i
                last = i
            }
            i += Character.charCount(cp)
        }
        if (first < 0) return
        s.blocks++
        if (!node.availableExtraData.contains(KEY)) return

        val len = (last - first + 1).coerceAtMost(MAX_CHARS_PER_NODE)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, first)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, len)
        }
        if (!node.refreshWithExtraData(KEY, args)) return
        @Suppress("DEPRECATION")
        val boxes = node.extras.getParcelableArray(KEY) ?: return
        s.withBoxes++

        val nodeBox = Rect()
        node.getBoundsInScreen(nodeBox)
        if (!nodeBox.intersect(s.win)) return

        var k = 0
        while (k < boxes.size) {
            val idx = first + k
            if (idx >= text.length) break
            val cp = text.codePointAt(idx)
            val r = boxes[k] as? RectF
            k++
            if (Character.isLowSurrogate(text[idx]) || !isHan(cp) || r == null || r.isEmpty) continue
            val cx = r.centerX().toInt()
            val cy = r.centerY().toInt()
            // Outside the node's visible part (scrolled away) or under another window.
            if (!nodeBox.contains(cx, cy) || s.covered.contains(cx, cy)) continue
            val key = (r.left.toInt().toLong() shl 32) or (r.top.toInt().toLong() and 0xffffffffL)
            if (!s.seen.add(key)) continue
            s.chars++
            // Underline by ƒ for every character; highlight by your level for yours.
            s.out.add(Mark(
                r.left, r.top, r.right, r.bottom, cp,
                under = Palette.freq(Freq.level(cp), s.dark),
                high = Mine.highlight(cp, s.dark, byAge),
            ))
        }
    }
}
