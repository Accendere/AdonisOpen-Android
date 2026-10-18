/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linhome-android
 * (see https://www.linhome.org).
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

package org.linhome.ui.history

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.linhome.LinhomeApplication
import org.linhome.linphonecore.extensions.callLogsWithNonEmptyCallId
import org.linhome.linphonecore.extensions.historyEvent
import org.linhome.store.HistoryEventStore
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub

class HistoryViewModel : ViewModel() {
    lateinit var history: MutableLiveData<ArrayList<CallLog>>

    val editing = MutableLiveData(false)
    val selectedForDeletion: MutableLiveData<ArrayList<String>> = MutableLiveData(arrayListOf())

    private val coreListener = object : CoreListenerStub() {
        override fun onCallLogUpdated(lc: Core, newcl: CallLog) {
            super.onCallLogUpdated(lc, newcl)
            history.postValue(LinhomeApplication.coreContext.core.callLogsWithNonEmptyCallId())
        }
    }

    init {
        HistoryEventStore.pruneOldEvents(LinhomeApplication.corePreferences.historyRetentionDays)
        LinhomeApplication.coreContext.core.addListener(coreListener)
        history = MutableLiveData(LinhomeApplication.coreContext.core.callLogsWithNonEmptyCallId())
    }


    override fun onCleared() {
        LinhomeApplication.coreContext.core.removeListener(coreListener)
        super.onCleared()
    }

    fun toggleSelectAllForDeletion() {
        if (selectedForDeletion.value!!.size == history.value!!.size) {
            selectedForDeletion.value!!.clear()
        } else {
            selectedForDeletion.value!!.clear()
            history.value!!.forEach {
                selectedForDeletion.value!!.add(it.callId!!)
            }
        }
        notifyDeleteSelectionListUpdated()
    }

    fun notifyDeleteSelectionListUpdated() {
        selectedForDeletion.value = selectedForDeletion.value
    }

    fun deleteSelection() {
        val toDelete = ArrayList(selectedForDeletion.value ?: emptyList())
        if (toDelete.isEmpty()) return
        val core = LinhomeApplication.coreContext.core
        if (toDelete.size == history.value?.size) {
            core.clearCallLogs()
        } else {
            toDelete.forEach { callId ->
                core.findCallLogFromCallId(callId)?.also { log ->
                    core.removeCallLog(log)
                }
            }
        }
        HistoryEventStore.removeHistoryEventsByCallIds(toDelete)
        history.value = core.callLogsWithNonEmptyCallId()
    }

    fun deleteAll() {
        val allCallIds = history.value?.mapNotNull { it.callId } ?: emptyList()
        if (allCallIds.isEmpty()) return
        val core = LinhomeApplication.coreContext.core
        core.clearCallLogs()
        HistoryEventStore.removeHistoryEventsByCallIds(allCallIds)
        selectedForDeletion.value?.clear()
        history.value = core.callLogsWithNonEmptyCallId()
    }

    fun markEventsAsRead() {
        val events = history.value?.map { it.historyEvent() } ?: return
        HistoryEventStore.markEventsAsReadBatch(events)
    }
}

