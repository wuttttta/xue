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
    val isLooking: Boolean get() = looking
    private lateinit var prefs: SharedPreferences
    @Volatile private var show = true

    @Volatile private var scanQueued = false
    private var lastScan = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            Prefs.SHOW -> {
                show = p.getBoolean(Prefs.SHOW, true)
                if (show) requestScan() else main.post { if (!looking) layer?.setMarks(emptyList()) }
            }
            Prefs.BUTTON -> main.post {
                button?.visibility = if (p.getBoolean(Prefs.BUTTON, false)) View.VISIBLE else View.GONE
            }
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
        if (on) requestScan() else if (!show) layer?.setMarks(emptyList())
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
        if (cp == null) return
        startActivity(
            Intent(this, PanelActivity::class.java)
                .putExtra(PanelActivity.EXTRA_CHAR, String(Character.toChars(cp)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (show || looking) requestScan()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        button?.invalidate()
        requestScan()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = null
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
        // With marks off, look mode still shows them while it lasts, so there is something to tap.
        if (!show && !looking) return
        if (!Freq.loaded) Freq.load(this)
        val marks = try {
            collect()
        } catch (e: Exception) {
            // Nodes can vanish mid-scan; the next event brings a new one.
            return
        }
        main.post { if (show || looking) layer?.setMarks(marks) }
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
                        s.blocks, s.withBoxes, s.chars, SystemClock.uptimeMillis() - t0,
                    )
                }
            }
            covered.op(wb, Region.Op.UNION)
        }
        return all
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, s: Scan) {
        if (depth > MAX_DEPTH || s.nodes >= MAX_NODES) return
        s.nodes++
        if (!node.isVisibleToUser) return
        val text = node.text
        if (!text.isNullOrEmpty()) markText(node, text.toString(), s)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, s)
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
            // Yours in your level's colour (warm), every other character by ƒ (blue).
            val mine = Mine.level(cp)
            val color = if (mine >= 0) Palette.level(mine, s.dark) else Palette.freq(Freq.level(cp), s.dark)
            s.out.add(Mark(r.left, r.top, r.right, r.bottom, cp, color))
        }
    }
}
