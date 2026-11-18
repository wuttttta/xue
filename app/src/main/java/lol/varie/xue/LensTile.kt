package lol.varie.xue

import android.service.quicksettings.TileService

/** Quick Settings tile "学 lens": closes the shade and freezes the screen with its characters marked (Lens). */
class LensTile : TileService() {
    override fun onClick() {
        XueService.running?.startLens()
    }
}
