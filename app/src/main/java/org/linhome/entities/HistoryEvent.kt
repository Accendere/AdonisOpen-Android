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

package org.linhome.entities

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import org.linhome.store.HistoryEventStore
import org.linhome.store.StorageManager
import org.linhome.utils.extensions.existsAndIsNotEmpty
import org.linhome.utils.extensions.xDigitsUUID
import java.io.File


@Parcelize
data class HistoryEvent(
    var id: String = xDigitsUUID(), // HistoryEvent for outgoing call must be created before the call is created to set recording path so it can't be the callID
    var callId: String? = null,
    var viewedByUser: Boolean = false,
    var mediaFileName: String = File(StorageManager.callsRecordingsDir, "${id}.mkv").absolutePath,
    var mediaThumbnailFileName: String = File(
        StorageManager.callsRecordingsDir,
        "${id}.jpg"
    ).absolutePath,
    var hasVideo: Boolean = false,
    /** How the call ended on this phone: a [org.linhome.webhooks.CallOutcome] wire name; null while it runs (or before 2.1). */
    var result: String? = null,
    /** Who took it when it was answered elsewhere, as the site said. */
    var answeredBy: String? = null,
    /** The icons of the buttons pressed on this phone during the call, in order (`icons/door`...). */
    var actionIcons: List<String> = emptyList()
) : Parcelable {

    /** A door button was pressed on this phone (the door was opened, most of the time). */
    fun actionPressed(): Boolean = actionIcons.isNotEmpty()


    val media: File
        get() {
            return File(mediaFileName)
        }

    val mediaThumbnail: File
        get() {
            return File(mediaThumbnailFileName)
        }

    fun hasMedia(): Boolean {
        return media.let {
            it.existsAndIsNotEmpty()
        }
    }

    fun hasMediaThumbnail(): Boolean {
        return mediaThumbnail.let {
            it.existsAndIsNotEmpty()
        }
    }

    fun persist() {
        HistoryEventStore.persistHistoryEvent(this)
    }


}
