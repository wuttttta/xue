package lol.varie.xue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The signal in the status bar: a small 学 while the service is on. Pulled down, it says which modes are on and
 * has buttons for them: Select area, Tap on/off, OCR on/off. Needs the notification permission (asked by the
 * settings screen on Android 13+); without it nothing shows and nothing else changes.
 */
object StatusNote {
    private const val CHANNEL = "status"
    private const val ID = 1
    const val ACTION_SELECT = "lol.varie.xue.SELECT"
    const val ACTION_TAP = "lol.varie.xue.TAP"
    const val ACTION_OCR = "lol.varie.xue.OCR"

    fun show(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "学 is on", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The 学 sign in the status bar while 学 is on, with buttons for its modes"
                setShowBadge(false)
            },
        )
        val p = Prefs.get(context)
        val tap = Build.VERSION.SDK_INT >= 33 && p.getBoolean(Prefs.TAP, false)
        val ocr = Build.VERSION.SDK_INT >= 30 && p.getBoolean(Prefs.OCR, false)
        val modes = buildList {
            add(if (p.getBoolean(Prefs.SHOW, true)) "marks" else "marks hidden")
            if (tap) add("tap")
            if (ocr) add("OCR")
            if (p.getBoolean(Prefs.QUIZ, false)) add("quiz")
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_xue)
            .setContentTitle("学 is on")
            .setContentText(modes)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(context, ACTION_SELECT, R.drawable.ic_select, "Select area"))
        if (Build.VERSION.SDK_INT >= 33) {
            b.addAction(action(context, ACTION_TAP, R.drawable.ic_tile, if (tap) "Tap off" else "Tap on"))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            b.addAction(action(context, ACTION_OCR, R.drawable.ic_ocr, if (ocr) "OCR off" else "OCR on"))
        }
        runCatching { nm.notify(ID, b.build()) }
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun action(context: Context, what: String, icon: Int, label: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, what.hashCode(),
            Intent(context, NoteReceiver::class.java).setAction(what),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), label, pi).build()
    }
}

/** The notification's buttons. */
class NoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs.get(context)
        when (intent.action) {
            StatusNote.ACTION_SELECT -> XueService.running?.startSelect()
            StatusNote.ACTION_TAP -> p.edit().putBoolean(Prefs.TAP, !p.getBoolean(Prefs.TAP, false)).apply()
            StatusNote.ACTION_OCR -> p.edit().putBoolean(Prefs.OCR, !p.getBoolean(Prefs.OCR, false)).apply()
        }
    }
}
