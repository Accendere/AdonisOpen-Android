/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 * Modifications Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

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
