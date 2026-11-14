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
import android.text.InputType
import android.widget.Button
import android.widget.EditText
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
    private lateinit var mine: TextView
    private lateinit var codeIn: EditText
    private var tapStatus: TextView? = null
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
        col.addView(text("Marks the Chinese characters on screen, in any app: yours by your level, the rest by how common they are (ƒ).", 15f, muted()))

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

        col.addView(heading("Look up"))
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(Switch(this).apply {
                text = "Tap a character to look it up (experimental)"
                textSize = 16f
                setTextColor(fg())
                isChecked = prefs.getBoolean(Prefs.TAP, false)
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.TAP, on).apply() }
            })
            col.addView(text(
                "A plain tap on a character opens its card; everything else (scrolling, typing, links, buttons) goes " +
                    "to the app as usual. It uses Android's touch exploration, like TalkBack, so some apps may act as " +
                    "if a screen reader were on. The \"学 tap\" Quick Settings tile turns it on and off.", 13f, muted()
            ))
            tapStatus = text("", 13f).also { col.addView(it) }
            col.addView(button("Add the ${LookTile.label} tile") {
                val sbm = getSystemService(android.app.StatusBarManager::class.java)
                sbm.requestAddTileService(
                    android.content.ComponentName(this, LookTile::class.java), LookTile.label,
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor,
                ) { }
            })
            col.addView(text(
                "Without tap mode: turn on the floating 学 button below, tap it, then tap a character.", 13f, muted()
            ).apply { setPadding(0, (10 * dp).toInt(), 0, 0) })
        } else {
            col.addView(text(
                "Pull down Quick Settings and tap \"学 look\", then tap a character: its card from the quiz opens over " +
                    "the app. A tap anywhere else goes back to normal.", 14f
            ))
            col.addView(text("To add it: open Quick Settings, edit the tiles and drag \"学 look\" in.", 13f, muted()))
        }
        col.addView(text(
            "The panel's quiz keeps its own sign-in: paste the quiz's \"Edit list\" link under Your list to use your list there.",
            13f, muted()
        ))

        col.addView(Switch(this).apply {
            text = "Show marks"
            textSize = 16f
            setTextColor(fg())
            isChecked = prefs.getBoolean(Prefs.SHOW, true)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.SHOW, on).apply() }
        })
        col.addView(Switch(this).apply {
            text = "Floating 学 button for look up"
            textSize = 16f
            setTextColor(fg())
            isChecked = prefs.getBoolean(Prefs.BUTTON, false)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.BUTTON, on).apply() }
        })

        col.addView(heading("Your list"))
        codeIn = EditText(this).apply {
            hint = "List code or link from varie.lol/h"
            setText(Mine.code(this@MainActivity) ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextColor(fg())
            setHintTextColor(muted())
        }
        col.addView(codeIn)
        col.addView(LinearLayout(this).apply {
            addView(button("Save") { saveCode() })
            addView(button("Sync now") { sync() }.apply {
                (layoutParams as LinearLayout.LayoutParams).leftMargin = (8 * dp).toInt()
            })
        })
        mine = text("", 14f)
        col.addView(mine)

        col.addView(heading("Colours"))
        col.addView(text("Highlight, on your characters: your level, how often you get it wrong", 13f, muted()))
        col.addView(Legend(this, dark, listOf("New", "L1", "L2", "L3", "L4", "L5"), null, highlight = true) { Palette.level(it, dark) })
        col.addView(text("Underline, on every character: ƒ, how common", 13f, muted()).apply { setPadding(0, (10 * dp).toInt(), 0, 0) })
        col.addView(Legend(this, dark, listOf("F1", "F2", "F3", "F4", "F5"), listOf("的", "们", "习", "璀", "璨")) {
            Palette.freq(it + 1, dark)
        })
        col.addView(text("F1 commonest … F5 rarest. Characters missing from the frequency list count as F5.", 13f, muted()))

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
        Thread {
            if (!Freq.loaded) Freq.load(applicationContext)
            Mine.load(applicationContext)
            ui.post { refresh() }
        }.start()
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

        tapStatus?.let { t ->
            val want = Prefs.get(this).getBoolean(Prefs.TAP, false)
            t.text = when {
                !want -> ""
                !on -> "Tap mode starts when the service is on."
                XueService.running?.tapActive == true -> "● Tap mode is on."
                else -> "Tap mode is waiting for Android: turn \"学 marks\" off and on again in Accessibility settings."
            }
            t.setTextColor(if (XueService.running?.tapActive == true) Palette.freq(3, dark) else fg())
        }

        val code = Mine.code(this)
        mine.text = when {
            code == null -> "Not set. In the quiz: person icon → account → share \"Edit list\" and paste that link here (it also signs the panel's quiz in to your list). \"Raw text\" or \"Word list\" work too, for the colours only."
            Mine.syncedAt == 0L -> Mine.error?.let { "$code: $it" } ?: "$code: not synced yet"
            else -> buildString {
                append("$code: ${Mine.words} words")
                append(
                    if (Mine.statsCode == null) ", no results online yet (all New until you answer once in the quiz)"
                    else ", results for ${Mine.answered} characters"
                )
                append(" · synced ")
                append(DateUtils.getRelativeTimeSpanString(Mine.syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                Mine.error?.let { append("\nLast try: $it") }
            }
        }

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

    private fun saveCode() {
        val raw = codeIn.text.toString()
        val prefs = Prefs.get(this)
        if (raw.isBlank()) {
            prefs.edit().remove(Prefs.LIST_CODE).apply()
            Thread { Mine.clear(applicationContext); ui.post { refresh(); XueService.running?.refresh() } }.start()
            return
        }
        val code = Mine.extractCode(raw)
        if (code == null) {
            mine.text = "That isn't a list code: 8 letters/digits like AB12CD34, or a link with one."
            return
        }
        codeIn.setText(code)
        // The "Edit list" link also signs the panel's quiz in (once, the next time it opens); its key stays on the phone.
        Mine.extractSync(raw)?.let { prefs.edit().putString(Prefs.PENDING_SYNC, it).apply() }
        // The service, when on, syncs on this change itself; sync here too so the screen updates when it's off.
        prefs.edit().putString(Prefs.LIST_CODE, code).apply()
        sync()
    }

    private fun sync() {
        mine.text = "Syncing…"
        Thread {
            Mine.sync(applicationContext)
            ui.post { refresh(); XueService.running?.refresh() }
        }.start()
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

    /** Swatches drawn the way the layer draws them: an underline, with a sample character when given. */
    private class Legend(
        context: Context,
        dark: Boolean,
        private val labels: List<String>,
        private val samples: List<String>?,
        private val highlight: Boolean = false,
        private val colorOf: (Int) -> Int,
    ) : View(context) {
        private val dp = resources.displayMetrics.density
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
        private val box = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineY = (if (samples != null) 38 else 12) * dp

        init {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ((if (samples != null) 64 else 40) * dp).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val n = labels.size
            val cell = width / n.toFloat()
            for (i in 0 until n) {
                val cx = cell * i + cell / 2
                samples?.let { canvas.drawText(it[i], cx, 30 * dp, glyph) }
                if (highlight) {
                    box.color = colorOf(i)
                    box.alpha = 96
                    canvas.drawRoundRect(cx - 11 * dp, lineY - 8 * dp, cx + 11 * dp, lineY + 4 * dp, 3 * dp, 3 * dp, box)
                } else {
                    line.color = colorOf(i)
                    line.alpha = 220
                    canvas.drawLine(cx - 10 * dp, lineY, cx + 10 * dp, lineY, line)
                }
                canvas.drawText(labels[i], cx, lineY + 20 * dp, label)
            }
        }
    }
}
