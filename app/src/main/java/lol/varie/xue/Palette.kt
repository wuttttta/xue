package lol.varie.xue

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Colours from varie h/index.html. */
object Palette {
    // --fq1 … --fq5, F1 commonest
    private val FQ_LIGHT = intArrayOf(0x104281, 0x1c5cab, 0x2a78d6, 0x5598e7, 0x9ec5f4)
    private val FQ_DARK = intArrayOf(0x9ec5f4, 0x6da7ec, 0x3987e5, 0x256abf, 0x184f95)

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun freq(level: Int, dark: Boolean): Int {
        val rgb = (if (dark) FQ_DARK else FQ_LIGHT)[(level - 1).coerceIn(0, 4)]
        return Color.rgb(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff)
    }
}
