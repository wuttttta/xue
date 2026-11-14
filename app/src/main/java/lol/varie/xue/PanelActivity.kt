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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The character's panel: the quiz at varie.lol/h, opened on that character (the page's #<character>),
 * in a sheet over the bottom of the screen. A tap above the sheet or Back hides it; the page stays loaded
 * (the task goes to the back), so the next character opens at once.
 */
class PanelActivity : Activity() {
    companion object {
        const val EXTRA_CHAR = "char"
        const val QUIZ = "https://varie.lol/h/"
    }

    private lateinit var web: WebView
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
        val url = QUIZ + "#" + Uri.encode(ch)
        if (!loaded) web.loadUrl(url) else web.evaluateJavascript("location.hash = ${jsString(ch)}", null)
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
