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
package org.linhome.webhooks

import org.json.JSONObject

/**
 * The site's answer to `ring` (Adonis `docs/features/adonis-open-webhooks.md`, section 6b): whether its door rings
 * now, which door (the site's name for its intercom), for how long, and, when asked for, the camera's picture as
 * JPEG bytes (null when the camera gave none). Plain Kotlin, unit tested.
 */
data class RingStatus(
    val ringing: Boolean,
    val door: String?,
    val seconds: Int?,
    val snapshot: ByteArray?
) {
    companion object {
        /** [decodeBase64] is the platform's decoder (android.util.Base64 in the app, java.util.Base64 in tests). */
        fun parse(json: JSONObject?, decodeBase64: (String) -> ByteArray?): RingStatus? {
            json ?: return null
            if (!json.has("ringing")) return null
            val picture = if (json.isNull("snapshot")) null else json.optString("snapshot", "").ifEmpty { null }
            return RingStatus(
                ringing = json.optBoolean("ringing", false),
                door = if (json.isNull("door")) null else json.optString("door", "").trim().ifEmpty { null },
                seconds = if (json.has("seconds") && !json.isNull("seconds")) json.optInt("seconds") else null,
                snapshot = picture?.let { runCatching { decodeBase64(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RingStatus && ringing == other.ringing && door == other.door && seconds == other.seconds &&
            (snapshot?.contentEquals(other.snapshot ?: ByteArray(0)) ?: (other.snapshot == null))

    override fun hashCode(): Int = listOf(ringing, door, seconds, snapshot?.contentHashCode()).hashCode()
}

/** The site's answer to `ring_open`: whether the door opened, and why not (`not_ringing`, `not_allowed`, `failed`). */
data class RingOpenResult(val opened: Boolean, val reason: String?, val message: String?) {
    companion object {
        fun parse(json: JSONObject?): RingOpenResult? {
            json ?: return null
            if (!json.has("opened")) return null
            return RingOpenResult(
                opened = json.optBoolean("opened", false),
                reason = if (json.isNull("reason")) null else json.optString("reason", "").ifEmpty { null },
                message = if (json.isNull("message")) null else json.optString("message", "").ifEmpty { null }
            )
        }
    }
}
