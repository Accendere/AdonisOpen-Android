package org.linhome.quicksettings

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.entities.LinhomeAccount
import org.linhome.widgets.ReceiveCallsWidgetProvider
import org.linphone.core.tools.Log

@RequiresApi(Build.VERSION_CODES.N)
class ReceiveCallsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentEnabled = if (LinhomeApplication.contextExists()) {
            LinhomeApplication.corePreferences.appEnabled
        } else {
            true
        }
        val newEnabled = !currentEnabled
        Log.i("[ReceiveCallsTile] QS Tile clicked: $currentEnabled -> $newEnabled")

        if (LinhomeApplication.contextExists()) {
            LinhomeAccount.setAppEnabled(newEnabled)
        }

        updateTileState()
        ReceiveCallsWidgetProvider.updateAllWidgets(this)
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = if (LinhomeApplication.contextExists()) {
            LinhomeApplication.corePreferences.appEnabled
        } else {
            true
        }

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = Texts.get("widget_receive_calls_title") ?: "Adonis Calls"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activeText = Texts.get("widget_calls_active") ?: "Receiving calls"
            val pausedText = Texts.get("widget_calls_paused") ?: "Calls paused"
            tile.subtitle = if (isEnabled) activeText else pausedText
        }

        tile.icon = Icon.createWithResource(
            this,
            if (isEnabled) R.drawable.ic_qs_adonis_tile else R.drawable.ic_qs_adonis_tile_off
        )

        tile.updateTile()
    }
}
