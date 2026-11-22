package lol.varie.xue

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The character's panel: the quiz at varie.lol/h, opened on that character (the page's #<character>),
 * in a sheet over the bottom of the screen. A tap above the sheet or Back hides it; the page stays loaded
 * (the task goes to the back), so the next character opens at once.
 *
 * The quiz here can't use a passkey, so it gets your data from the app instead, and shows it in its own card:
 * - your list: when the quiz isn't signed in it studies default_list.txt; that request is answered with your
 *   list file as the app last downloaded it (same format), so your words, their dates and meanings are there;
 * - your results: merged into the page's h.stats.v1 before its scripts run (item by item, the copy with more
 *   answers wins, as the quiz merges devices), so error rates and levels show.
 * Signed in with the "Edit list" link, the quiz syncs itself and this changes nothing it relies on.
 */
class PanelActivity : Activity() {
    companion object {
        const val EXTRA_CHAR = "char"
        /** Text from select mode: opened as the quiz opens text shared to it (search, its words to pick). */
        const val EXTRA_TEXT = "text"
        const val QUIZ = "https://varie.lol/h/"
        /** The sheet's height as a share of the screen: the range the handle can drag it in. */
        const val MIN_SHARE = 0.3f
        const val MAX_SHARE = 0.95f
    }

    private lateinit var web: WebView
    private var statsScript: ScriptHandler? = null
    /** Mine.syncedAt when the page was loaded: newer data means a reload on the next open. */
    private var dataAt = -1L
    private var loaded = false
    /** What to do once the page being loaded has finished. */
    private var pending: (() -> Unit)? = null
    private var signedAt = -1L

    /** Loads so far: each one gets its own address, so it really loads. */
    private var loads = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val dark = Palette.isDark(this)

        val root = FrameLayout(this)
        root.addView(View(this).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setOnClickListener { hide() }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val sheetBg = if (dark) Color.rgb(0x16, 0x18, 0x1c) else Color.WHITE
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(sheetBg)
                cornerRadii = floatArrayOf(18 * dp, 18 * dp, 18 * dp, 18 * dp, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            isClickable = true // taps on the sheet's edge don't fall through to the scrim
        }
        // A grab handle: drag it to make the sheet taller or shorter (remembered); a tap hides the sheet.
        sheet.addView(FrameLayout(this).apply {
            addView(View(this@PanelActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(0x4a, 0x50, 0x58) else Color.rgb(0xc8, 0xcd, 0xd3))
                    cornerRadius = 2 * dp
                }
            }, FrameLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt(), Gravity.CENTER))
            contentDescription = "Drag to resize, tap to close"
            setOnTouchListener(Resizer(sheet))
        }, LinearLayout.LayoutParams(MATCH_PARENT, (28 * dp).toInt()))

        web = WebView(this).apply {
            setBackgroundColor(sheetBg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Lay the page out as Chrome does (its viewport tag: the screen's width), and never sideways-scrollable:
            // a page a pixel wider than the sheet made it slide left and right.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                // The quiz stays in the sheet; any other link opens in the browser.
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.host == "varie.lol" && (u.path ?: "").startsWith("/h")) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest) = ownList(req)

                override fun onPageFinished(view: WebView, url: String) {
                    loaded = true
                    pending?.let { p ->
                        pending = null
                        p()
                    }
                }
            }
        }
        // The quiz's service worker fetches the page's files itself: answer the list there too.
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = ownList(request)
            })
        }
        sheet.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    // The page and its full-screen layers (word picker, editors, stats…: each scrolls on its own), whose
                    // bottom bars can come out a pixel wider than a narrow screen (the quiz's buttons don't shrink).
                    "(function(){var s=document.createElement('style');s.textContent='html,#exportp,#editp,#statp," +
                        "#pickp,#optp,#ocrp,#svp,#snp,#sqp,#bkp,#chp,#wmp{overflow-x:hidden}';" +
                        "document.documentElement.appendChild(s);})();",
                    setOf("https://varie.lol"),
                )
            }
        }

        val share = Prefs.get(this).getFloat(Prefs.PANEL_HEIGHT, 0.72f).coerceIn(MIN_SHARE, MAX_SHARE)
        val h = (resources.displayMetrics.heightPixels * share).toInt()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, h, Gravity.BOTTOM))
        // Android 15 draws apps under the navigation bar: keep the quiz above it, and above the keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            sheet.setPadding(0, 0, 0, bottom)
            insets
        }
        setContentView(root)

        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val ch = intent?.getStringExtra(EXTRA_CHAR)
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (ch == null && text == null) return
        if (!Freq.loaded) Freq.load(applicationContext)
        if (Mine.words == 0) Mine.load(applicationContext)
        // Newer list or results than the page has: hand them over and load it again.
        if (Mine.syncedAt != dataAt) {
            injectStats()
            dataAt = Mine.syncedAt
            loaded = false
        }
        // Signed in since the page was loaded (settings did it in its own WebView): load it again to pick it up.
        val at = Prefs.get(this).getLong(Prefs.PANEL_AT, 0)
        if (at != signedAt) {
            signedAt = at
            loaded = false
        }
        go(ch, text)
    }

    private fun go(ch: String?, text: String?) {
        if (text != null) share(text) else if (ch != null) show(ch)
    }

    /** A fresh load of the quiz (the query makes each address new, so it really loads). */
    private fun fresh(hash: String = "") = web.loadUrl(QUIZ + "?d=" + dataAt + "&n=" + (++loads) + hash)

    /**
     * The quiz on [ch]. Normally its card: the page is opened plain and then moved to the character, which the
     * quiz shows as a card (a character in the address of a fresh load is asked as a question instead).
     * In quiz mode (settings) that question is what's wanted, so each one is a fresh load with it in the address.
     */
    private fun show(ch: String) {
        if (Prefs.get(this).getBoolean(Prefs.QUIZ, false)) {
            loaded = false
            fresh("#" + Uri.encode(ch))
            return
        }
        if (loaded) {
            focus(ch)
        } else {
            pending = { focus(ch) }
            fresh()
        }
    }

    /** Moves the open quiz to [ch] (its card), once the page has started. */
    private fun focus(ch: String) {
        web.evaluateJavascript(
            """
            (function f(n){
              try { if (own && (cur || shown)) { location.hash = encodeURIComponent(${jsString(ch)}); return; } } catch (e) {}
              if (n < 75) setTimeout(function(){ f(n + 1); }, 200);
            })(0);
            """.trimIndent(), null,
        )
    }

    /**
     * Text from select mode, handed to the quiz the way Android's Share gives it text (its "h-share" cache, ?share=1).
     * Its Quiz button is set to 句 (the text's sentences), which works even when every word is already in your list;
     * ‹ next to it switches back to words, and the quiz remembers that (in the panel only).
     */
    private fun share(text: String) {
        val js = "try { var k = 'h.settings.v1', s = JSON.parse(localStorage.getItem(k) || '{}') || {};" +
            " s.qQuiz = 's'; localStorage.setItem(k, JSON.stringify(s)); } catch (e) {}" +
            "caches.open('h-share').then(function(c){ return c.put('text', new Response(${jsString(text)})); })" +
            ".then(function(){ location.href = location.pathname + '?share=1'; });"
        if (loaded) {
            web.evaluateJavascript(js, null)
        } else {
            pending = { web.evaluateJavascript(js, null) }
            fresh()
        }
    }

    /** The quiz's default_list.txt, answered with your list (when the app has one). */
    private fun ownList(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != "varie.lol" || u.path != "/h/default_list.txt") return null
        val text = Mine.listText(applicationContext) ?: return null
        return WebResourceResponse(
            "text/plain", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"),
            text.byteInputStream(Charsets.UTF_8),
        )
    }

    /** Your results into the page's storage before its own scripts read it (the next load on). */
    private fun injectStats() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        statsScript?.remove()
        statsScript = null
        if (Mine.syncedAt == 0L) return
        val js = """
            (function(){try{
              var a = ${Mine.statsJson()}, k = 'h.stats.v1';
              var m = JSON.parse(localStorage.getItem(k) || '{}') || {};
              for (var x in a) { var l = m[x]; if (!l || ((l.r||0)+(l.w||0)) <= (a[x].r + a[x].w)) m[x] = a[x]; }
              localStorage.setItem(k, JSON.stringify(m));
            }catch(e){}})();
        """.trimIndent()
        statsScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(web, js, setOf("https://varie.lol"))
        }.getOrNull()
    }

    private fun jsString(s: String) = org.json.JSONObject.quote(s)

    /** The handle's touches: a drag sets the sheet's height (saved when let go), a tap without moving hides it. */
    private inner class Resizer(private val sheet: View) : View.OnTouchListener {
        private var startY = 0f
        private var startH = 0
        private var moved = false

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            val screen = resources.displayMetrics.heightPixels
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startH = sheet.height
                    moved = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (moved || kotlin.math.abs(dy) > android.view.ViewConfiguration.get(v.context).scaledTouchSlop) {
                        moved = true
                        val lp = sheet.layoutParams
                        lp.height = (startH - dy).toInt().coerceIn((screen * MIN_SHARE).toInt(), (screen * MAX_SHARE).toInt())
                        sheet.layoutParams = lp
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.get(this@PanelActivity).edit()
                            .putFloat(Prefs.PANEL_HEIGHT, sheet.layoutParams.height.toFloat() / screen).apply()
                    } else {
                        hide()
                    }
                }
            }
            return true
        }
    }

    private fun hide() {
        moveTaskToBack(true)
        // Words just added in the panel: fetch the list again so they get their colour soon.
        XueService.running?.syncSoon()
    }

    override fun onStop() {
        super.onStop()
        // Gone from the screen however it left (Back, a tap above, Home): Lens, if open, comes back.
        XueService.running?.onPanelHidden()
    }

    @Deprecated("Back hides the sheet; the page stays loaded for the next character.")
    override fun onBackPressed() {
        hide()
    }
}
