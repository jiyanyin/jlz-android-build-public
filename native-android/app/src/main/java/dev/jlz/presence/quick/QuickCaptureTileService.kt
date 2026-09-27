package dev.jlz.presence.quick

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.jlz.presence.MainActivity

class QuickCaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; label = "\u7559\u7ed9\u8001\u516c"; updateTile() }
    }
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_QUICK_CAPTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startActivityAndCollapse(intent)
        else startActivity(intent)
    }
}
