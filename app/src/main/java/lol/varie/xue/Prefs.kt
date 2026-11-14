package lol.varie.xue

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    /** Marks on or off (the service stays enabled; look mode still works). */
    const val SHOW = "show"
    /** The user's list code on paste.varie.lol (read-only; never a token). */
    const val LIST_CODE = "list_code"
    const val SYNCED_AT = "synced_at"
    /** Tap mode: a plain tap on a character opens its panel (Android 13+, experimental; off until switched on). */
    const val TAP = "tap"
    /** OCR mode: also read characters in images, video and games from screenshots (Android 11+; off by default). */
    const val OCR = "ocr"
    /** Quiz mode: a character's panel asks it as a question instead of showing its card. */
    const val QUIZ = "quiz"
    /** The floating 学 button for look mode (off by default; the Quick Settings tile does the same). */
    const val BUTTON = "button"
    /** Where the floating look button was left. */
    const val BTN_X = "btn_x"
    const val BTN_Y = "btn_y"
    /** A quiz device link (#sync=code.token) waiting to sign the panel's quiz in; cleared once loaded. */
    const val PENDING_SYNC = "pending_sync"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("xue", Context.MODE_PRIVATE)
}
