/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 * Modifications Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android,
 * see https://www.linhome.org).
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
package org.linhome.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Reason
import org.linphone.mediastream.Log


class NotificationBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(NotificationsManager.INTENT_NOTIF_ID, 0)
        val remoteAddress: String? =
            coreContext.notificationsManager.getSipUriForCallNotificationId(notificationId)
        val core: Core = coreContext.core
        val call = remoteAddress?.let { core.findCallFromUri(it) } ?: core.currentCall ?: core.calls.firstOrNull()

        if (call == null) {
            Log.e("[Notification Broadcast Receiver] Couldn't find call from remote address $remoteAddress")
            return
        }

        if (intent.action == NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION) {
            if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia)
                call.decline(Reason.Declined)
            else
                call.terminate()
        } else if (intent.action == NotificationsManager.INTENT_OPEN_DOOR_NOTIF_ACTION) {
            coreContext.triggerDoorActionFromNotification(call)
        }
    }

}
