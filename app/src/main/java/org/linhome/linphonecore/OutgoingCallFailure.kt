/*
 * Copyright (c) 2026 Adonis Open Contributors.
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
package org.linhome.linphonecore

/**
 * Why a call this phone started (the eye icon, a device's call button) failed, as the text the error dialog shows.
 *
 * Read from the SIP final response the device or its server sent (Linphone's `errorInfo.protocolCode`), else from
 * Linphone's own reason (its `Reason` name) when there was none. A busy door (486/600: a 2N runs one call at a time,
 * so it refuses a new call while a visitor's call rings) gets its own text instead of the generic network one.
 * Plain Kotlin, unit tested.
 */
enum class OutgoingCallFailure(val textKey: String) {
    /** 486 Busy Here, 600 Busy Everywhere: the device is in another call. */
    BUSY("unable_to_call_device_busy"),

    /** 603 Decline: the device (or someone at it) refused the call. */
    DECLINED("unable_to_call_device_declined"),

    /** 404, 410, 480: the SIP server knows no device registered at that address now (off, restarting, unplugged). */
    OFFLINE("unable_to_call_device_offline"),

    /** 401, 403, 407: the server refused this phone's own account. */
    FORBIDDEN("unable_to_call_device_forbidden"),

    /** Anything else (no answer from the network, 408, 5xx, an I/O error): the generic text. */
    OTHER("unable_to_call_device");

    companion object {
        fun of(protocolCode: Int, reasonName: String?): OutgoingCallFailure {
            when (protocolCode) {
                486, 600 -> return BUSY
                603 -> return DECLINED
                404, 410, 480 -> return OFFLINE
                401, 403, 407 -> return FORBIDDEN
            }
            return when (reasonName) {
                "Busy" -> BUSY
                "Declined" -> DECLINED
                "NotFound", "Gone", "TemporarilyUnavailable" -> OFFLINE
                "Forbidden", "Unauthorized" -> FORBIDDEN
                else -> OTHER
            }
        }
    }
}
