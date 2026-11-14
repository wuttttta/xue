package lol.varie.xue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Settings: turn the service on, marks on/off, the ƒ legend, data refresh, and which apps give boxes. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var enable: Button
    private lateinit var data: TextView
    private lateinit var apps: TextView
    private var dp = 1f
    private var dark = false
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun fg() = if (dark) Color.rgb(0xe6, 0xe8, 0xeb) else Color.rgb(0x1d, 0x21, 0x26)
    private fun muted() = if (dark) Color.rgb(0x9a, 0xa3, 0xad) else Color.rgb(0x5f, 0x68, 0x72)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        dark = Palette.isDark(this)
        val prefs = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
        }
        col.addView(text("学", 34f, bold = true))
        col.addView(text("Marks the Chinese characters on screen, in any app, by how common they are (ƒ).", 15f, muted()))

        col.addView(heading("Service"))
        status = text("", 15f)
        col.addView(status)
        enable = button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(enable)
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(text(
                "Installed from a file, Android may grey out \"学 marks\" (restricted setting). Then: App info → ⋮ → " +
                    "Allow restricted settings, and try again.", 13f, muted()))
            col.addView(button("Open app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        col.addView(Switch(this).apply {
            text = "Show marks"
            textSize = 16f
            setTextColor(fg())
            isChecked = prefs.getBoolean(Prefs.SHOW, true)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.SHOW, on).apply() }
        })

        col.addView(heading("Colours: ƒ, how common"))
        col.addView(Legend(this, dark))
        col.addView(text("F1 commonest … F5 rarest. Characters missing from the list count as F5.", 13f, muted()))

        col.addView(heading("Data"))
        data = text("", 14f)
        col.addView(data)
        col.addView(button("Update from varie.lol/h") { update() })

        col.addView(heading("Apps seen (character boxes)"))
        apps = text("", 14f).apply { typeface = Typeface.MONOSPACE }
        col.addView(apps)
        col.addView(button("Clear") { Stats.clear(); refresh() })

        col.addView(text(
            "Frequencies: jieba's dict.txt (MIT), via varie.lol/h. Nothing you read leaves the phone.", 12f, muted()
        ).apply { setPadding(0, (24 * dp).toInt(), 0, 0) })

        setContentView(ScrollView(this).apply { addView(col) })
        Thread { if (!Freq.loaded) Freq.load(applicationContext); ui.post { refresh() } }.start()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val on = XueService.running != null
        status.text = if (on) "● On" else "○ Off: turn on \"学 marks\" in Accessibility"
        status.setTextColor(if (on) Palette.freq(3, dark) else fg())
        enable.text = if (on) "Accessibility settings" else "Turn on in accessibility settings"

        data.text = if (!Freq.loaded) "Loading…" else
            "%,d characters with ƒ, %s copy".format(Freq.size, if (Freq.fromDownload) "downloaded" else "bundled")

        val list = Stats.snapshot()
        apps.text = if (list.isEmpty()) {
            "None yet. Open an app with Chinese text, then come back."
        } else list.joinToString("\n\n") { a ->
            val ago = DateUtils.getRelativeTimeSpanString(a.lastSeen, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
            val verdict = when {
                a.withBoxes == 0 -> "no boxes"
                a.withBoxes < a.blocks -> "some boxes"
                else -> "boxes ✓"
            }
            "${a.pkg}\n  $verdict: ${a.withBoxes}/${a.blocks} text blocks, ${a.chars} chars marked, ${a.scanMs} ms · $ago"
        }
    }

    private fun update() {
        data.text = "Downloading…"
        Thread {
            val err = Freq.download(applicationContext)
            ui.post {
                refresh()
                if (err != null) data.text = "Update failed: $err"
            }
        }.start()
    }

    private fun text(s: String, size: Float, color: Int = fg(), bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun heading(s: String) = text(s, 13f, muted(), bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.06f
        setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
    }

    /** F1 … F5 drawn the way the layer draws them: a sample character with its underline. */
    private class Legend(context: Context, private val dark: Boolean) : View(context) {
        private val dp = resources.displayMetrics.density
        private val samples = listOf("的", "们", "习", "璀", "璨")
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26 * dp
            textAlign = Paint.Align.CENTER
            color = if (dark) Color.rgb(0xe6, 0xe8, 0xeb) else Color.rgb(0x1d, 0x21, 0x26)
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * dp
            textAlign = Paint.Align.CENTER
            color = if (dark) Color.rgb(0x9a, 0xa3, 0xad) else Color.rgb(0x5f, 0x68, 0x72)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3 * dp
        }

        init {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (64 * dp).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val cell = width / 5f
            for (i in 0 until 5) {
                val cx = cell * i + cell / 2
                canvas.drawText(samples[i], cx, 30 * dp, glyph)
                line.color = Palette.freq(i + 1, dark)
                line.alpha = 220
                canvas.drawLine(cx - 10 * dp, 38 * dp, cx + 10 * dp, 38 * dp, line)
                canvas.drawText("F${i + 1}", cx, 58 * dp, label)
            }
        }
    }
}
