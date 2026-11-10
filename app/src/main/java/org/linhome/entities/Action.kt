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
import org.linhome.customisation.ActionTypes

/**
 * One button of a device: its [type] (a section of action_types.xml) and its [code].
 *
 * [method] null sends [code] to the device the device's way (DTMF or SIP message). [METHOD_SITE] sends nothing to the
 * device: the press only goes to the site as the `door_action` webhook, and Adonis runs the action there (a garage,
 * run by a DeviceAction row matching the code). Stored in the vCard as `type~code~site`.
 */
@Parcelize
data class Action(val type: String?, val code: String?, val method: String? = null) : Parcelable {

    companion object {
        const val METHOD_SITE = "site"
    }

    /** Run by the site (Adonis), not the door: no DTMF. */
    fun isSite(): Boolean = method == METHOD_SITE

    /** The vCard value: `type~code`, plus `~method` when there is one. */
    fun vcardValue(): String = listOfNotNull(type, code, method).joinToString("~")

    fun typeName(): String? {
        return type?.let {
            ActionTypes.typeNameForActionType(it)
        }
    }

    fun iconName(): String? {
        return type?.let {
            ActionTypes.iconNameForActionType(it)
        }
    }

    fun actionText(): String? {
        return type?.let {
            ActionTypes.typeNameForActionType(it)
        }
    }

    fun actionTextWithCode(): String? {
        return type?.let {
            "${ActionTypes.typeNameForActionType(it)} - ${code}"
        }
    }


}