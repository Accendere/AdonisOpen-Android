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

package org.linhome.linphonecore.extensions

import org.linhome.entities.HistoryEvent
import org.linhome.store.HistoryEventStore
import org.linphone.core.Call
import org.linphone.core.CallLog


fun CallLog.historyEvent(): HistoryEvent {
    if (userData != null) {
        val historyEvent = userData as HistoryEvent
        if (historyEvent.callId == null && callId != null) {
            historyEvent.callId = callId
            HistoryEventStore.persistHistoryEvent(historyEvent)
            userData = null
        }
        return historyEvent
    }

    callId?.let { id ->
        HistoryEventStore.findHistoryEventByCallId(id)?.also {
            return it
        }
    }

    val historyEvent = HistoryEvent()
    callId?.also {
        historyEvent.callId = it
        HistoryEventStore.persistHistoryEvent(historyEvent)
    }
    return historyEvent
}


fun CallLog.isNew(): Boolean {
    return historyEvent().let {
        dir == Call.Dir.Incoming && setOf(
            Call.Status.Missed,
            Call.Status.Declined,
            Call.Status.DeclinedElsewhere
        ).contains(status) && !it.viewedByUser
    }
}
