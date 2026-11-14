package lol.varie.xue

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    /** Marks on or off (the service stays enabled). */
    const val SHOW = "show"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("xue", Context.MODE_PRIVATE)
}
