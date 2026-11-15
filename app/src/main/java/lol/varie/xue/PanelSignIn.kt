package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Signs the panel's quiz in to your list with the quiz's "Edit list" link (#sync=CODE.KEY), at once and out of
 * sight: a hidden WebView opens the link, the quiz saves the code and key in its own storage (h.sync.v1, shared by
 * every WebView of the app, so the panel has it too), and this checks that it did. From then on the panel's quiz
 * is your list: words added there and answers given there go online like in Chrome.
 * The key never leaves the phone; the app doesn't keep it (the quiz does, in the panel's storage).
 */
object PanelSignIn {
    private const val QUIZ = "https://varie.lol/h/"

    /** [done] gets null when signed in, else what went wrong. Main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(context: Context, link: String, done: (String?) -> Unit) {
        val code = link.substringBefore('.')
        val ui = Handler(Looper.getMainLooper())
        val web = WebView(context.applicationContext)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        var finished = false
        fun finish(err: String?) {
            if (finished) return
            finished = true
            ui.post { runCatching { web.destroy() } }
            if (err == null) {
                Prefs.get(context).edit()
                    .putString(Prefs.PANEL_CODE, code)
                    .putLong(Prefs.PANEL_AT, System.currentTimeMillis())
                    .apply()
            }
            done(err)
        }
        // The quiz reads the link only after its lists have loaded: ask its storage until the code is there.
        fun check(n: Int) {
            web.evaluateJavascript("localStorage.getItem('h.sync.v1')") { raw ->
                val saved = runCatching {
                    JSONObject(JSONObject("{\"v\":$raw}").getString("v")).optString("code")
                }.getOrNull()
                when {
                    saved == code -> finish(null)
                    n >= 40 -> finish("the quiz didn't take the link (no network?)")
                    else -> ui.postDelayed({ check(n + 1) }, 500)
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!finished) ui.postDelayed({ check(0) }, 500)
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl?.startsWith(QUIZ) == true) finish("couldn't open the quiz ($description)")
            }
        }
        web.loadUrl(QUIZ + "#sync=" + link)
        // Give up after a while whatever happens.
        ui.postDelayed({ finish("timed out") }, 30000)
    }

    /** The code the panel's quiz is signed in to, or null. */
    fun code(context: Context): String? = Prefs.get(context).getString(Prefs.PANEL_CODE, null)
}
