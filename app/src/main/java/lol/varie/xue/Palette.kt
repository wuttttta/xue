package lol.varie.xue

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Colours from varie h/index.html. */
object Palette {
    // --fq1 … --fq5, F1 commonest
    private val FQ_LIGHT = intArrayOf(0x104281, 0x1c5cab, 0x2a78d6, 0x5598e7, 0x9ec5f4)
    private val FQ_DARK = intArrayOf(0x9ec5f4, 0x6da7ec, 0x3987e5, 0x256abf, 0x184f95)

    // --l0 New, --l1 … --l5 (L5 wrong most often)
    private val LV_LIGHT = intArrayOf(0x9aa3ad, 0xf6d38a, 0xf2a65a, 0xe8793f, 0xd14a32, 0xa8232b)
    private val LV_DARK = intArrayOf(0x6b7480, 0x6b5a2e, 0x96602a, 0xc2672d, 0xe56f42, 0xff8a6b)

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun freq(level: Int, dark: Boolean): Int = rgb((if (dark) FQ_DARK else FQ_LIGHT)[(level - 1).coerceIn(0, 4)])

    /** Your level, 0 New … 5. */
    fun level(level: Int, dark: Boolean): Int = rgb((if (dark) LV_DARK else LV_LIGHT)[level.coerceIn(0, 5)])

    private fun rgb(c: Int) = Color.rgb(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)
}
