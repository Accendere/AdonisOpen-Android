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
package org.linhome.pairing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.linhome.webhooks.WebhookLog
import org.linhome.webhooks.WebhookTransport

/** Starts a pairing and waits for the site to approve it. [ioDispatcher] runs the HTTP calls. */
class PairingClient(
    private val transport: WebhookTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: WebhookLog
) {
    companion object {
        private const val TAG = "[Pairing]"

        /** Polls go on this long past the pairing's own expiry, for clock and network slack. */
        const val GRACE_MS = 30_000L

        /** The longest Retry-After honoured while waiting. */
        const val MAX_WAIT_MS = 60_000L
    }

    private fun log(level: WebhookLog.Level, message: String) = logger.log(level, "$TAG $message")

    suspend fun start(site: DiscoveredSite, deviceId: String, deviceName: String): StartOutcome {
        val outcome = withContext(ioDispatcher) { PairingContract.start(transport, site, deviceId, deviceName) }
        log(WebhookLog.Level.INFO, "Start with ${site.host}: $outcome")
        return outcome
    }

    /**
     * Polls until the site gives a final answer: [PollOutcome.Approved], [PollOutcome.Declined],
     * [PollOutcome.Expired] or [PollOutcome.OtherDevice]. A site that doesn't answer for a while is polled
     * again (the phone may be switching networks); once the pairing's time is over the answer is Expired.
     * Cancel the calling coroutine to stop (the person closed the code screen).
     */
    suspend fun waitForApproval(pairing: PendingPairing, deviceId: String): PollOutcome {
        val intervalMs = pairing.intervalSeconds * 1000L
        val deadlineMs = pairing.expiresInSeconds * 1000L + GRACE_MS
        var waited = 0L
        while (true) {
            val outcome = withContext(ioDispatcher) { PairingContract.poll(transport, pairing, deviceId) }
            val wait = when (outcome) {
                PollOutcome.Pending -> intervalMs
                is PollOutcome.Unreachable -> {
                    log(WebhookLog.Level.WARNING, "Poll to ${pairing.site.host}: ${outcome.reason}")
                    intervalMs
                }
                is PollOutcome.TooMany -> maxOf(intervalMs, minOf((outcome.retryAfterSeconds ?: 0) * 1000, MAX_WAIT_MS))
                else -> {
                    log(WebhookLog.Level.INFO, "Pairing with ${pairing.site.host}: $outcome")
                    return outcome
                }
            }
            if (waited + wait > deadlineMs) {
                log(WebhookLog.Level.INFO, "Pairing with ${pairing.site.host}: no answer in time")
                return PollOutcome.Expired
            }
            delay(wait)
            waited += wait
        }
    }
}
