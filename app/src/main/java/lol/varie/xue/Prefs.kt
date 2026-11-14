package lol.varie.xue

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    /** Marks on or off (the service stays enabled). */
    const val SHOW = "show"
    /** The user's list code on paste.varie.lol (read-only; never a token). */
    const val LIST_CODE = "list_code"
    const val SYNCED_AT = "synced_at"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("xue", Context.MODE_PRIVATE)
}
