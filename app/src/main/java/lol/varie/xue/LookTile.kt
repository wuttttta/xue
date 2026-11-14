package lol.varie.xue

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "学 look": turns look mode on (the next tap on a character opens its panel). */
class LookTile : TileService() {
    companion object {
        /** Asks the system to redraw the tile (on when looking). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, LookTile::class.java)) }
        }
    }

    override fun onStartListening() = update()

    override fun onClick() {
        val s = XueService.running
        if (s == null) {
            // The service is off: open 学's settings, which say how to turn it on.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        s.lookFromTile()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (XueService.running?.isLooking == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
