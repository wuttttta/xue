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
        const val QUIZ = "https://varie.lol/h/"
    }

    private lateinit var web: WebView
    private var statsScript: ScriptHandler? = null
    /** Mine.syncedAt when the page was loaded: newer data means a reload on the next open. */
    private var dataAt = -1L
    private var loaded = false
    /** A character asked for while the page was signing in, shown when that is done. */
    private var after: String? = null

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
        // A grab handle; tapping it hides the sheet too.
        sheet.addView(FrameLayout(this).apply {
            addView(View(this@PanelActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(0x4a, 0x50, 0x58) else Color.rgb(0xc8, 0xcd, 0xd3))
                    cornerRadius = 2 * dp
                }
            }, FrameLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt(), Gravity.CENTER))
            setOnClickListener { hide() }
            contentDescription = "Close"
        }, LinearLayout.LayoutParams(MATCH_PARENT, (20 * dp).toInt()))

        web = WebView(this).apply {
            setBackgroundColor(sheetBg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
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
                    // After a sign-in link the page has saved the list; now show the character.
                    after?.let { ch ->
                        after = null
                        view.postDelayed({ show(ch) }, 1500)
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

        val h = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, h, Gravity.BOTTOM))
        setContentView(root)

        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val ch = intent?.getStringExtra(EXTRA_CHAR) ?: return
        if (!Freq.loaded) Freq.load(applicationContext)
        if (Mine.words == 0) Mine.load(applicationContext)
        // Newer list or results than the page has: hand them over and load it again.
        if (Mine.syncedAt != dataAt) {
            injectStats()
            dataAt = Mine.syncedAt
            loaded = false
        }
        val prefs = Prefs.get(this)
        val sync = prefs.getString(Prefs.PENDING_SYNC, null)
        if (sync != null) {
            // Sign the panel's quiz in to the user's list once: the quiz saves the link's code and key itself.
            prefs.edit().remove(Prefs.PENDING_SYNC).apply()
            after = ch
            loaded = false
            web.loadUrl(QUIZ + "#sync=" + sync)
            return
        }
        show(ch)
    }

    /** The quiz looking at [ch]: a fresh load the first time, then only the page's #, so nothing reloads. */
    private fun show(ch: String) {
        // The query only makes a new load differ from the page already open (a # alone wouldn't reload it).
        val url = QUIZ + "?d=" + dataAt + "#" + Uri.encode(ch)
        if (!loaded) web.loadUrl(url) else web.evaluateJavascript("location.hash = ${jsString(ch)}", null)
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

    private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun hide() {
        moveTaskToBack(true)
    }

    @Deprecated("Back hides the sheet; the page stays loaded for the next character.")
    override fun onBackPressed() {
        hide()
    }
}
