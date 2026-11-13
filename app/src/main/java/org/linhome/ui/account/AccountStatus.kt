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
package org.linhome.ui.account

/** The SIP registration as the account card sees it (Linphone's states, reduced; no Linphone types, so it is unit tested). */
enum class Registration { NONE, PROGRESS, OK, FAILED, CLEARED }

/** What the account card on the Doors tab shows. */
enum class AccountCardMode {
    /** No account: the card offers the setup choices. */
    NOT_CONFIGURED,

    /** Registered: compact, green dot. */
    CONNECTED,

    /** Registering for less than [AccountStatus.CONNECTING_DELAY_MS]: compact, no status yet (most registrations finish by then). */
    QUIET,

    /** Still registering after the delay: compact, "Connecting…". */
    CONNECTING,

    /**
     * Gave up (Linphone's Failed, until it recovers): a red banner with the reason and Retry. Wins over PAUSED: a
     * paused account that can't reach its server shows the error too, with a "Calls paused" note, never just the pause.
     */
    FAILED,

    /** The person paused calls: compact, grey dot, nothing to fix. */
    PAUSED
}

object AccountStatus {
    const val CONNECTING_DELAY_MS = 3_000L

    /**
     * @param failing true from a Failed registration until the next Ok (Linphone retries in between, going
     *   through Progress again: the banner stays until it really works).
     * @param progressSinceMs when the registration last left Ok/Failed for an in-between state; null if unknown.
     */
    fun decide(
        configured: Boolean,
        callsEnabled: Boolean,
        registration: Registration,
        failing: Boolean,
        progressSinceMs: Long?,
        nowMs: Long
    ): AccountCardMode {
        if (!configured) return AccountCardMode.NOT_CONFIGURED
        if (registration == Registration.OK && callsEnabled) return AccountCardMode.CONNECTED
        if (failing || registration == Registration.FAILED) return AccountCardMode.FAILED
        if (!callsEnabled) return AccountCardMode.PAUSED
        val since = progressSinceMs ?: return AccountCardMode.QUIET
        return if (nowMs - since >= CONNECTING_DELAY_MS) AccountCardMode.CONNECTING else AccountCardMode.QUIET
    }

    /** When to look again (the QUIET -> CONNECTING switch), or null when nothing changes by itself. */
    fun nextCheckInMs(mode: AccountCardMode, progressSinceMs: Long?, nowMs: Long): Long? {
        if (mode != AccountCardMode.QUIET || progressSinceMs == null) return null
        return (progressSinceMs + CONNECTING_DELAY_MS - nowMs).coerceAtLeast(0)
    }

    /**
     * What the card calls the place: the site's name with one site, the names joined with " · " with several (sites
     * without a name are left out). With none, [fallback] (the name remembered with the account, if any).
     */
    fun siteLabel(siteNames: List<String?>, fallback: String?): String? =
        siteNames.mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: fallback

    /** The card's one line: "pedro · Alameda" (the site's name when known, else the SIP domain). */
    fun title(username: String?, siteName: String?, domain: String?): String {
        val where = siteName?.takeIf { it.isNotBlank() } ?: domain.orEmpty()
        val who = username.orEmpty()
        return listOf(who, where).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
