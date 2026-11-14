package lol.varie.xue

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    /** Marks on or off (the service stays enabled). */
    const val SHOW = "show"
    /** The user's list code on paste.varie.lol (read-only; never a token). */
    const val LIST_CODE = "list_code"
    const val SYNCED_AT = "synced_at"
    /** Where the floating look button was left. */
    const val BTN_X = "btn_x"
    const val BTN_Y = "btn_y"
    /** A quiz device link (#sync=code.token) waiting to sign the panel's quiz in; cleared once loaded. */
    const val PENDING_SYNC = "pending_sync"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("xue", Context.MODE_PRIVATE)
}
