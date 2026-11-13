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
package org.linhome.utils

import org.linhome.sites.LinkSite
import org.linhome.webhooks.WebhookConfig

/** What the phone has now, as far as an import is concerned (read from Linphone and the device store). */
data class CurrentSip(
    val username: String,
    val domain: String,
    val proxy: String?,
    val transport: String?,
    /** Null when the stored credentials can't be read back (then a password change can't be ruled out). */
    val password: String?
) {
    val identity: String get() = "$username@$domain"
}

/** A device already on the phone, reduced to what an import can change. */
data class CurrentDevice(
    val name: String,
    val address: String,
    val alternativeAddress: String?,
    val method: String,
    val actions: List<Pair<String, String?>>
)

/** How an incoming item compares with what the phone has. */
enum class ImportChange { NEW, CHANGED, UNCHANGED, REMOVED }

/** The SIP part of an import, compared with the current account. */
data class SipImport(
    val incoming: AdonisSipConfig,
    val change: ImportChange,
    /** What differs (`username`, `domain`, `proxy`, `transport`, `password`), for CHANGED. */
    val differences: List<String>,
    /** The account it replaces, when it is another one. */
    val replaces: String?
)

data class DeviceImport(
    val index: Int,
    val incoming: AdonisDeviceConfig,
    val change: ImportChange,
    val differences: List<String>,
    val matchesName: String?
)

data class WebhookImport(
    val incoming: WebhookConfig?,
    val change: ImportChange,
    val differences: List<String>,
    /** The person changed the webhooks in Settings: importing these replaces their changes (the screen says so). */
    val replacesLocalEdits: Boolean = false
)

/**
 * The import screen's model: every part of a setup, compared with what the phone has, with what is
 * selected. Built by [ImportPlan.build]; the screen toggles [ImportSelection] and [AdonisProvisioner] applies it.
 */
class ImportPlan(
    val sip: SipImport?,
    val devices: List<DeviceImport>,
    /** Compared with the link's own site's connection (none for a new site), never another site's. */
    val webhook: WebhookImport?,
    /**
     * Which site the link is for ([LinkSite.isNew], or [LinkSite.existing] whose connection it replaces); null when
     * not resolved (tests). [LinkSite.key] null: the link names no site.
     */
    val site: LinkSite? = null
) {
    /** What is selected by default: everything that changes something, nothing that is unchanged. */
    fun defaultSelection() = ImportSelection(
        sip = sip != null && sip.change != ImportChange.UNCHANGED,
        devices = devices.filter { it.change != ImportChange.UNCHANGED }.map { it.index }.toSet(),
        webhook = webhook != null && webhook.change != ImportChange.UNCHANGED
    )

    /** Nothing in this import would change the phone. */
    val nothingChanges: Boolean
        get() = (sip == null || sip.change == ImportChange.UNCHANGED) &&
            devices.all { it.change == ImportChange.UNCHANGED } &&
            (webhook == null || webhook.change == ImportChange.UNCHANGED)

    companion object {
        private fun norm(value: String?): String = value?.trim()?.lowercase()?.removePrefix("sip:")?.removePrefix("sips:") ?: ""

        fun compareSip(incoming: AdonisSipConfig, current: CurrentSip?): SipImport {
            if (current == null) return SipImport(incoming, ImportChange.NEW, emptyList(), null)
            val differences = mutableListOf<String>()
            if (norm(incoming.username) != norm(current.username)) differences += "username"
            if (norm(incoming.domain) != norm(current.domain)) differences += "domain"
            if (norm(incoming.proxy) != norm(current.proxy)) differences += "proxy"
            if (norm(incoming.transport ?: "udp") != norm(current.transport ?: "udp")) differences += "transport"
            if (current.password == null || current.password != incoming.password) differences += "password"
            val otherAccount = "username" in differences || "domain" in differences
            return SipImport(
                incoming,
                if (differences.isEmpty()) ImportChange.UNCHANGED else ImportChange.CHANGED,
                differences,
                if (otherAccount) current.identity else null
            )
        }

        /** [formattedAddress] turns the incoming address into the stored form (`sip:user@domain`). */
        fun compareDevice(
            index: Int,
            incoming: AdonisDeviceConfig,
            match: CurrentDevice?,
            formattedAddress: (String) -> String,
            mappedMethod: String,
            mappedActions: List<Pair<String, String?>>
        ): DeviceImport {
            if (match == null) return DeviceImport(index, incoming, ImportChange.NEW, emptyList(), null)
            val differences = mutableListOf<String>()
            if (incoming.name.trim() != match.name.trim()) differences += "name"
            if (norm(formattedAddress(incoming.address)) != norm(match.address)) differences += "address"
            val incomingAlt = incoming.alternativeAddress?.trim()?.takeIf { it.isNotEmpty() }?.let(formattedAddress)
            if (norm(incomingAlt) != norm(match.alternativeAddress)) differences += "alternative_address"
            if (mappedMethod != match.method) differences += "method"
            if (mappedActions != match.actions) differences += "actions"
            return DeviceImport(
                index, incoming,
                if (differences.isEmpty()) ImportChange.UNCHANGED else ImportChange.CHANGED,
                differences, match.name
            )
        }

        /**
         * A link with a SIP account carries the webhooks (or their absence); a devices-only link leaves them
         * alone (null here).
         */
        fun compareWebhook(incoming: WebhookConfig?, current: WebhookConfig?, linkHasSip: Boolean): WebhookImport? {
            if (!linkHasSip) return null
            val edited = current?.editedLocally == true
            if (incoming == null) {
                return if (current == null) null else WebhookImport(null, ImportChange.REMOVED, emptyList(), edited)
            }
            if (current == null) return WebhookImport(incoming, ImportChange.NEW, emptyList())
            val differences = mutableListOf<String>()
            if (incoming.baseUrl.trimEnd('/') != current.baseUrl.trimEnd('/')) differences += "url"
            if (incoming.user != current.user) differences += "user"
            if (incoming.token != current.token) differences += "token"
            if (incoming.auth != current.auth) differences += "auth"
            if (incoming.events != current.events) differences += "events"
            if (incoming.pollMode != current.pollMode || incoming.pollingIntervalSeconds != current.pollingIntervalSeconds) differences += "polling"
            return WebhookImport(incoming, if (differences.isEmpty()) ImportChange.UNCHANGED else ImportChange.CHANGED, differences, edited)
        }
    }
}

/** What the person chose to import. [devices] holds [DeviceImport.index] values. */
data class ImportSelection(
    val sip: Boolean,
    val devices: Set<Int>,
    val webhook: Boolean
) {
    val isEmpty: Boolean get() = !sip && devices.isEmpty() && !webhook
}
