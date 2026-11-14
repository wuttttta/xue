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

    @Volatile private var scanQueued = false
    private var lastScan = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            Prefs.SHOW -> {
                show = p.getBoolean(Prefs.SHOW, true)
                main.post { layer?.drawMarks = show }
                requestScan()
            }
            Prefs.BUTTON -> main.post {
                button?.visibility = if (p.getBoolean(Prefs.BUTTON, false)) View.VISIBLE else View.GONE
            }
            Prefs.TAP -> main.post { applyTapMode() }
            Prefs.OCR -> main.post { applyOcr() }
            Prefs.LIST_CODE -> workerHandler.post { Mine.sync(this); requestScan() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        prefs = Prefs.get(this)
        show = prefs.getBoolean(Prefs.SHOW, true)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        addLayer()
        addButton()
        layer?.drawMarks = show
        applyTapMode()
        applyOcr()
        workerHandler.post {
            if (!Freq.loaded) Freq.load(this)
            Mine.load(this)
        }
        workerHandler.post(syncMine)
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
        val lp = layerParams ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        layer?.let {
            it.looking = on
            runCatching { wm?.updateViewLayout(it, lp) }
        }
        button?.looking = on
        if (Build.VERSION.SDK_INT >= 33) tap?.setLooking(on)
        requestScan()
        LookTile.refresh(this)
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
                scrolling = { SystemClock.uptimeMillis() - lastScrollAt < 400 },
                onTap = ::openPanel,
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
        ocrMarks = chars.map { c ->
            val mine = Mine.level(c.cp)
            Mark(
                c.box.left, c.box.top, c.box.right, c.box.bottom, c.cp,
                under = Palette.freq(Freq.level(c.cp), dark),
                high = if (mine >= 0) Palette.level(mine, dark) else null,
                clickable = false,
            )
        }
        publish()
    }

    /** Draws text marks plus the OCR marks that aren't already on a text mark (text gives the better boxes). */
    private fun publish() {
        val all = if (ocrMarks.isEmpty()) textMarks else textMarks + ocrMarks.filter { o ->
            val cx = (o.left + o.right) / 2
            val cy = (o.top + o.bottom) / 2
            textMarks.none { cx >= it.left && cx <= it.right && cy >= it.top && cy <= it.bottom }
        }
        layer?.setMarks(all)
        if (Build.VERSION.SDK_INT >= 33) tap?.setMarks(all)
    }

    private fun openPanel(cp: Int) {
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

    /** Your list and results: fetched when stale, checked again every few minutes while the service runs. */
    private val syncMine = object : Runnable {
        override fun run() {
            if (Mine.code(this@XueService) != null && Mine.stale()) {
                Mine.sync(this@XueService)
                requestScan()
            }
            workerHandler.postDelayed(this, 5 * 60 * 1000L)
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
                    walk(root, 0, 99, s)
                    all.addAll(s.out)
                    Stats.record(
                        root.packageName?.toString() ?: "?",
                        s.blocks, s.withBoxes, s.chars, SystemClock.uptimeMillis() - t0,
                    )
                }
            }
            covered.op(wb, Region.Op.UNION)
        }
        return all
    }

    /**
     * [sinceClickable]: how many levels up the nearest clickable ancestor is (1 = the parent). Text in a clickable
     * node, or under one at most 3 levels up, belongs to a link or button: tap mode leaves its taps to the app.
     */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, sinceClickable: Int, s: Scan) {
        if (depth > MAX_DEPTH || s.nodes >= MAX_NODES) return
        s.nodes++
        if (!node.isVisibleToUser) return
        val text = node.text
        if (!text.isNullOrEmpty()) markText(node, text.toString(), node.isClickable || sinceClickable <= 3, s)
        val next = if (node.isClickable) 1 else sinceClickable + 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, next, s)
        }
    }

    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun markText(node: AccessibilityNodeInfo, text: String, clickable: Boolean, s: Scan) {
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
            val mine = Mine.level(cp)
            s.out.add(Mark(
                r.left, r.top, r.right, r.bottom, cp,
                under = Palette.freq(Freq.level(cp), s.dark),
                high = if (mine >= 0) Palette.level(mine, s.dark) else null,
                clickable = clickable,
            ))
        }
    }
}
