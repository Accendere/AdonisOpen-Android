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
package org.linhome.sites

/**
 * Whether an incoming call rings, asking the calling door's site first when the phone would refuse it.
 *
 * The phone stays registered while paused, so a site that switched it back on (`receive_calls`, from its card) can
 * reach it before its next poll: a call the phone would refuse, from the door of a site it can ask, waits for one
 * poll of that site (short timeout), whose messages are applied, and is decided again. Asked or not, a call that is
 * still refused is declined as before. Plain Kotlin, unit tested; CoreContext holds the call (no ringing, no
 * notification, no screen) while [decideAfterAsking] runs.
 */
class IncomingGate(
    private val registry: SiteRegistry,
    private val mainEnabled: () -> Boolean,
    /** Whether [siteId] can be asked now (a working connection that polls). */
    private val canAsk: (siteId: String) -> Boolean,
    /** One poll of [siteId], its messages applied before it returns; true when it answered in time. */
    private val ask: suspend (siteId: String) -> Boolean
) {
    /** What to do the moment the call arrives. */
    sealed class FirstLook {
        data class Decided(val decision: IncomingDecision) : FirstLook()

        /** Hold the call and ask [site] (see [decideAfterAsking]). */
        data class AskSite(val site: Site, val decisionNow: IncomingDecision) : FirstLook()
    }

    /** [doorSite] is the calling door's site (null: no site, a phone number, an unknown caller). */
    fun firstLook(doorSite: Site?): FirstLook {
        val decision = registry.decideIncoming(mainEnabled(), doorSite)
        if (decision == IncomingDecision.RING || doorSite == null) return FirstLook.Decided(decision)
        val site = registry.bySlot(doorSite.slot) ?: return FirstLook.Decided(decision)
        return if (canAsk(site.id)) FirstLook.AskSite(site, decision) else FirstLook.Decided(decision)
    }

    /** Asks [site] once, then decides again with whatever it changed; returns the decision and whether it answered. */
    suspend fun decideAfterAsking(site: Site): Pair<IncomingDecision, Boolean> {
        val answered = try {
            ask(site.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false // a failed poll declines as before
        }
        return registry.decideIncoming(mainEnabled(), registry.bySlot(site.slot) ?: site) to answered
    }
}
