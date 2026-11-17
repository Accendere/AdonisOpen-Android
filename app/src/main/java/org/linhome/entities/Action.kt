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
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One button of a device: its [type] (a section of action_types.xml) and its [code].
 *
 * [method] null sends [code] to the device the device's way (DTMF or SIP message). [METHOD_SITE] sends nothing to the
 * device: the press only goes to the site as the `door_action` webhook, and Adonis runs the action there (a garage,
 * run by a DeviceAction row matching the code). Stored in the vCard as `type~code~site`.
 */
/**
 * A door button. [icon]: an image of the theme's `icons/` (`garage`, `light`...) instead of the type's own;
 * [label]: the text under it instead of the type's name, "" for none (icon only). Both come from the site's link.
 */
@Parcelize
data class Action(
    val type: String?,
    val code: String?,
    val method: String? = null,
    val icon: String? = null,
    val label: String? = null
) : Parcelable {

    companion object {
        const val METHOD_SITE = "site"

        /** Accepts an icon name only as a plain theme icon (`garage`, `icons/garage`), never a path elsewhere. */
        fun cleanIcon(icon: String?): String? =
            icon?.trim()?.removePrefix("icons/")?.takeIf { it.matches(Regex("[a-z0-9_]{1,40}")) }

        /** Reads [vcardValue] back (`;` already turned into `~`); null when it isn't one. */
        fun fromVcard(value: String): Action? {
            val c = value.split("~")
            if (c.size < 2 || c.size > 5) return null
            return Action(
                c[0], c[1],
                c.getOrNull(2)?.takeIf { it.isNotBlank() },
                cleanIcon(c.getOrNull(3)?.takeIf { it.isNotBlank() }),
                c.getOrNull(4)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            )
        }
    }

    /** Run by the site (Adonis), not the door: no DTMF. */
    fun isSite(): Boolean = method == METHOD_SITE

    /**
     * The vCard value: `type~code`, plus `~method` when there is one; with an icon or a label,
     * `type~code~method~icon[~label]` (empty fields kept for position, the label URL-encoded).
     */
    fun vcardValue(): String {
        if (icon == null && label == null) return listOfNotNull(type, code, method).joinToString("~")
        val fields = mutableListOf(type ?: "", code ?: "", method ?: "", icon ?: "")
        label?.let { fields.add(URLEncoder.encode(it, "UTF-8")) }
        return fields.joinToString("~")
    }

    fun typeName(): String? {
        return type?.let {
            ActionTypes.typeNameForActionType(it)
        }
    }

    fun iconName(): String? {
        icon?.let { return "icons/$it" }
        return type?.let {
            ActionTypes.iconNameForActionType(it)
        }
    }

    /** The text under the button: the site's label, else the type's name; null for none. */
    fun actionText(): String? {
        label?.let { return it.trim().ifEmpty { null } }
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