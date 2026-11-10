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
 * Which of a SIP account's devices on the server are dead installs, to delete (no Linphone types, so it is unit
 * tested; [LinhomeDeviceCleanup] does the requests).
 *
 * The server forks every door call to every device the account has (each install registered its own, for a year),
 * and dead ones make the door wait for answers that never come. One account on several devices is normal (a phone
 * and a tablet), so a device is only deleted when it is clearly dead:
 * - any device not seen for [STALE_AFTER_MS] (a live one re-registers whenever its network changes), or
 * - an earlier install of this same phone: the same device name as ours, not seen for [SAME_NAME_STALE_AFTER_MS].
 * Ours (the same instance uuid) never is.
 */
object StaleDevices {
    const val STALE_AFTER_MS = 30L * 24 * 3600 * 1000
    const val SAME_NAME_STALE_AFTER_MS = 24L * 3600 * 1000

    data class RemoteDevice(val uuid: String, val name: String?, val lastUpdateMs: Long?)

    /** `<urn:uuid:ABC>`, `urn:uuid:ABC` and `abc` are the same device. */
    fun normalizeUuid(uuid: String): String =
        uuid.trim().removePrefix("<").removeSuffix(">").removePrefix("urn:uuid:").lowercase()

    /** The server's timestamp in ms: seconds when it is small enough to be seconds. Null when unknown. */
    fun timestampMs(raw: Long): Long? = when {
        raw <= 0L -> null
        raw < 100_000_000_000L -> raw * 1000
        else -> raw
    }

    fun toDelete(devices: List<RemoteDevice>, ownUuid: String, nowMs: Long): List<RemoteDevice> {
        val own = normalizeUuid(ownUuid)
        val ownName = devices.firstOrNull { normalizeUuid(it.uuid) == own }?.name?.trim()?.takeIf { it.isNotEmpty() }
        return devices.filter { device ->
            if (normalizeUuid(device.uuid) == own) return@filter false
            val age = nowMs - (device.lastUpdateMs ?: return@filter false)
            age >= STALE_AFTER_MS || (ownName != null && device.name?.trim() == ownName && age >= SAME_NAME_STALE_AFTER_MS)
        }
    }
}
