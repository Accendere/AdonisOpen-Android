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

package org.linhome.widgets

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.linhome.LinhomeApplication
import org.linhome.entities.LinhomeAccount
import org.linhome.quicksettings.ReceiveCallsTileService
import org.linphone.core.tools.Log

/**
 * Dedicated unexported BroadcastReceiver (android:exported="false")
 * for securely handling widget toggle clicks without exposing the control action
 * to third-party applications.
 */
class ReceiveCallsToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val currentEnabled = if (LinhomeApplication.contextExists()) {
            LinhomeApplication.corePreferences.appEnabled
        } else {
            true
        }
        val newEnabled = !currentEnabled
        Log.i("[ReceiveCallsToggleReceiver] Toggling receive calls from widget: $currentEnabled -> $newEnabled")

        if (LinhomeApplication.contextExists()) {
            LinhomeAccount.setAppEnabled(newEnabled)
        }
        ReceiveCallsWidgetProvider.updateAllWidgets(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.service.quicksettings.TileService.requestListeningState(
                    context,
                    ComponentName(context, ReceiveCallsTileService::class.java)
                )
            } catch (e: Exception) {
                Log.e("[ReceiveCallsToggleReceiver] Could not update TileService: ${e.message}")
            }
        }
    }
}
