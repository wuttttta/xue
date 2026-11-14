package lol.varie.xue

import android.accessibilityservice.AccessibilityService
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the text on screen (any app), finds the Chinese characters and their boxes, and draws a mark under each
 * on a see-through layer. Step 1 of the plan: colour by frequency (ƒ).
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
    private lateinit var prefs: SharedPreferences
    @Volatile private var show = true

    @Volatile private var scanQueued = false
    private var lastScan = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == Prefs.SHOW) {
            show = p.getBoolean(Prefs.SHOW, true)
            if (show) requestScan() else main.post { layer?.setMarks(emptyList()) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        prefs = Prefs.get(this)
        show = prefs.getBoolean(Prefs.SHOW, true)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        addLayer()
        workerHandler.post {
            if (!Freq.loaded) Freq.load(this)
        }
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
        val v = MarkLayer(this)
        w.addView(v, lp)
        wm = w
        layer = v
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (show) requestScan()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestScan()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = null
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        layer?.let { runCatching { wm?.removeView(it) } }
        layer = null
        worker.quitSafely()
        super.onDestroy()
    }

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
        if (!show) return
        if (!Freq.loaded) Freq.load(this)
        val marks = try {
            collect()
        } catch (e: Exception) {
            // Nodes can vanish mid-scan; the next event brings a new one.
            return
        }
        main.post { if (show) layer?.setMarks(marks) }
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
                if (root != null) {
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
            s.out.add(Mark(r.left, r.top, r.right, r.bottom, Palette.freq(Freq.level(cp), s.dark)))
        }
    }
}
