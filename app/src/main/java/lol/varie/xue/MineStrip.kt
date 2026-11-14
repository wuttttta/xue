package lol.varie.xue

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The top of the panel: what the app knows about the character from your list and results (read from
 * paste.varie.lol, so it shows even when the panel's quiz isn't signed in), and its ƒ.
 */
@SuppressLint("ViewConstructor")
class MineStrip(context: Context, private val dark: Boolean) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val fg = if (dark) Color.rgb(0xe6, 0xe8, 0xeb) else Color.rgb(0x1d, 0x21, 0x26)
    private val muted = if (dark) Color.rgb(0x9a, 0xa3, 0xad) else Color.rgb(0x5f, 0x68, 0x72)
    private val big = TextView(context)
    private val line1 = TextView(context)
    private val line2 = TextView(context)
    private val line3 = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (2 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        big.textSize = 30f
        big.setTextColor(fg)
        big.gravity = Gravity.CENTER
        big.setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (2 * dp).toInt())
        addView(big, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        for ((t, size) in listOf(line1 to 14f, line2 to 13f, line3 to 12f)) {
            t.textSize = size
            t.setTextColor(if (t === line1) fg else muted)
            t.maxLines = 2
            col.addView(t)
        }
        line1.typeface = Typeface.DEFAULT_BOLD
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (12 * dp).toInt() })
    }

    fun show(ch: String) {
        big.text = ch
        val cp = if (ch.isEmpty()) 0 else ch.codePointAt(0)
        val fq = Freq.level(cp)
        val fqText = "ƒ F$fq · " + arrayOf("very common", "common", "medium", "rare", "very rare")[fq - 1]
        val info = Mine.info(ch)
        // The character on its highlight (your level) with its underline colour as a thin border (ƒ).
        big.background = GradientDrawable().apply {
            cornerRadius = 6 * dp
            if (info != null) {
                val c = Palette.level(info.level, dark)
                setColor(Color.argb(96, Color.red(c), Color.green(c), Color.blue(c)))
            } else {
                setColor(Color.TRANSPARENT)
            }
            setStroke((2 * dp).toInt(), Palette.freq(fq, dark))
        }
        if (info == null) {
            line1.text = if (Mine.code(context) == null) "Your list isn't set in 学" else "Not in your list"
            line2.text = fqText
            line3.text = ""
            return
        }
        val s = info.stat
        line1.text = when {
            s == null || s.last.isEmpty() -> "New · not answered yet"
            else -> "L${info.level} · wrong ${(s.wrongShare * 100).roundToInt()}% of the last ${s.last.length}"
        }
        line1.setTextColor(if (info.level > 0) Palette.level(info.level, dark) else fg)
        val parts = ArrayList<String>()
        if (s != null && (s.right + s.wrong) > 0) parts.add("${s.right} right, ${s.wrong} wrong in all")
        parts.add(fqText)
        line2.text = parts.joinToString(" · ")
        line3.text = info.words.take(3).joinToString(" · ") { e ->
            val date = e.added.take(10)
            val ws = if (e.word != ch) Mine.stat(e.word)?.takeIf { it.last.isNotEmpty() }?.let { " L${it.level}" } ?: "" else ""
            (if (e.word == ch) "added" else e.word + ws) + (if (date.isNotEmpty()) " $date" else "")
        } + if (info.words.size > 3) " · +${info.words.size - 3}" else ""
    }
}
