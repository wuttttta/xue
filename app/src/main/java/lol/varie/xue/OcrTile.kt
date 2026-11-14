package lol.varie.xue

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "学 OCR": reading characters in images on or off (an extra mode, off by default). */
class OcrTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val p = Prefs.get(this)
        p.edit().putBoolean(Prefs.OCR, !p.getBoolean(Prefs.OCR, false)).apply()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (Prefs.get(this).getBoolean(Prefs.OCR, false)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
