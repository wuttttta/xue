package lol.varie.xue

import android.service.quicksettings.TileService

/** Quick Settings tile "学 select": closes the shade and lets you drag a box over text to read it in the quiz. */
class SelectTile : TileService() {
    override fun onClick() {
        XueService.running?.startSelect()
    }
}
