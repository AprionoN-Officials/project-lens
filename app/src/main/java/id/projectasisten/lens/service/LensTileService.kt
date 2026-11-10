package id.projectasisten.lens.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import id.projectasisten.lens.data.AppPreferences
import id.projectasisten.lens.ui.overlay.AssistantOverlayActivity

/**
 * Method C — Quick Settings Tile ("CircleToSearch").
 * Handles Android 14+ (API 34+) PendingIntent requirement as well as Android 10–13 Intent launch.
 */
class LensTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val enabled = AppPreferences(this).isShortcutEnabled
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = AppPreferences(this)
        if (!prefs.isShortcutEnabled) {
            return
        }

        val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
