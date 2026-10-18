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

package org.linhome.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.linhome.LinhomeApplication
import org.linhome.entities.HistoryEvent
import org.linhome.store.StorageManager.historyEventsXml
import org.linphone.core.Config
import org.linphone.core.Factory
import java.io.File


object HistoryEventStore {

    private var historyEventsConfig: Config

    var historyEvents: HashMap<String, HistoryEvent> // CallId / History event

    init {
        if (!StorageManager.historyEventsXml.exists())
            StorageManager.historyEventsXml.createNewFile()
        historyEventsConfig = Factory.instance().createConfig(null)
        historyEventsConfig.loadFromXmlFile(StorageManager.historyEventsXml.absolutePath)
        historyEvents = readFromXml()
    }

    fun readFromXml(): HashMap<String, HistoryEvent> {
        val result = HashMap<String, HistoryEvent>()
        historyEventsConfig.sectionsNamesList.forEach {
            val callId = historyEventsConfig.getString(it, "call_id", null)
            val mediaFile = historyEventsConfig.getString(it, "media_file_name", null)
            val thumbFile = historyEventsConfig.getString(it, "media_thumbnail_file_name", null)
            if (callId != null && mediaFile != null && thumbFile != null) {
                result[callId] = HistoryEvent(
                    it,
                    callId,
                    historyEventsConfig.getBool(it, "viewed_by_user", false),
                    mediaFile,
                    thumbFile,
                    historyEventsConfig.getBool(it, "has_video", false)
                )
            }
        }
        return result
    }

    @Synchronized
    fun sync() {
        val snapshot = HashMap(historyEvents)
        historyEventsConfig.sectionsNamesList.forEach {
            historyEventsConfig.cleanSection(it)
        }
        snapshot.forEach { entry ->
            historyEventsConfig.setBool(entry.value.id, "viewed_by_user", entry.value.viewedByUser)
            historyEventsConfig.setString(
                entry.value.id,
                "media_file_name",
                entry.value.mediaFileName
            )
            historyEventsConfig.setString(
                entry.value.id,
                "media_thumbnail_file_name",
                entry.value.mediaThumbnailFileName
            )
            historyEventsConfig.setString(entry.value.id, "call_id", entry.value.callId)
            historyEventsConfig.setBool(entry.value.id, "has_video", entry.value.hasVideo)
        }
        historyEventsXml.writeText(historyEventsConfig.dumpAsXml())
    }

    private fun syncAsync() {
        GlobalScope.launch(Dispatchers.IO) {
            sync()
        }
    }

    @Synchronized
    fun persistHistoryEvent(entry: HistoryEvent) {
        entry.callId?.let {
            historyEvents[it] = entry
            syncAsync()
        }
    }

    @Synchronized
    fun removeHistoryEvent(entry: HistoryEvent) {
        val mediaFile = entry.media
        val thumbFile = entry.mediaThumbnail
        historyEvents.remove(entry.callId)
        GlobalScope.launch(Dispatchers.IO) {
            if (mediaFile.exists()) mediaFile.delete()
            if (thumbFile.exists()) thumbFile.delete()
            sync()
        }
    }

    fun removeHistoryEventByCallId(callId: String) {
        findHistoryEventByCallId(callId)?.also {
            removeHistoryEvent(it)
        }
    }

    @Synchronized
    fun removeHistoryEventsByCallIds(callIds: Collection<String>) {
        if (callIds.isEmpty()) return
        val filesToDelete = ArrayList<File>(callIds.size * 2)
        callIds.forEach { callId ->
            historyEvents.remove(callId)?.also { entry ->
                filesToDelete.add(entry.media)
                filesToDelete.add(entry.mediaThumbnail)
            }
        }
        GlobalScope.launch(Dispatchers.IO) {
            filesToDelete.forEach { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            sync()
        }
    }

    @Synchronized
    fun markEventsAsReadBatch(events: Collection<HistoryEvent>) {
        var changed = false
        events.forEach { event ->
            if (!event.viewedByUser) {
                event.viewedByUser = true
                event.callId?.let { callId ->
                    historyEvents[callId] = event
                }
                changed = true
            }
        }
        if (changed) {
            syncAsync()
        }
    }

    fun isIntercomCall(log: org.linphone.core.CallLog): Boolean {
        val device = DeviceStore.findDeviceByAddress(log.remoteAddress)
        if (device != null) {
            return device.type != "phone_call"
        }
        val event = historyEvents[log.callId]
        if (event != null && event.hasVideo) {
            return true
        }
        return false
    }

    fun pruneOldEvents(
        intercomMaxAgeDays: Int = LinhomeApplication.corePreferences.intercomRetentionDays,
        voiceCallMaxAgeDays: Int = LinhomeApplication.corePreferences.voiceCallRetentionDays
    ) {
        if (!LinhomeApplication.contextExists()) return
        val core = LinhomeApplication.coreContext.core
        val nowSeconds = System.currentTimeMillis() / 1000L
        val intercomCutoffSeconds = if (intercomMaxAgeDays > 0) nowSeconds - (intercomMaxAgeDays.toLong() * 86400L) else null
        val voiceCallCutoffSeconds = if (voiceCallMaxAgeDays > 0) nowSeconds - (voiceCallMaxAgeDays.toLong() * 86400L) else null

        if (intercomCutoffSeconds == null && voiceCallCutoffSeconds == null) {
            return
        }

        val expiredCallIds = ArrayList<String>()
        core.callLogs.forEach { log ->
            val isIntercom = isIntercomCall(log)
            val cutoff = if (isIntercom) intercomCutoffSeconds else voiceCallCutoffSeconds
            if (cutoff != null && log.startDate < cutoff) {
                log.callId?.let { expiredCallIds.add(it) }
                core.removeCallLog(log)
            }
        }
        if (expiredCallIds.isNotEmpty()) {
            removeHistoryEventsByCallIds(expiredCallIds)
        }
    }

    fun findHistoryEventByCallId(callId: String): HistoryEvent? {
        return historyEvents[callId]
    }

    fun markAsRead(historyEventId: String) {
        historyEvents.filter { it.value.id == historyEventId }.forEach { event ->
            if (!event.value.viewedByUser) {
                event.value.viewedByUser = true
                persistHistoryEvent(event.value)
            }
        }
    }

}