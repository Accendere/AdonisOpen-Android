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
package org.linhome.claim

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.linhome.webhooks.WebhookLog
import org.linhome.webhooks.WebhookTransport

/** Fetches and confirms claim links. [ioDispatcher] runs the HTTP calls. */
class ClaimClient(
    private val transport: WebhookTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: WebhookLog
) {
    companion object {
        private const val TAG = "[Claim]"

        /** Fetch: how many times a 429 is retried, and the longest wait honoured. */
        const val FETCH_TOO_MANY_RETRIES = 3
        const val FETCH_MAX_WAIT_MS = 30_000L

        /** Confirm retries: 10 s, 30 s, 2 min, then every 10 min, until the code's 48 h are over. */
        val CONFIRM_DELAYS_MS = longArrayOf(10_000, 30_000, 120_000, 600_000)
        const val CONFIRM_GIVE_UP_AFTER_MS = 48L * 3600 * 1000
    }

    private fun log(level: WebhookLog.Level, message: String) = logger.log(level, "$TAG $message")

    /** Gets the setup data. Retries a 429 a few times; any other failure is returned at once for the person to see. */
    suspend fun fetch(claimUrl: String, deviceId: String): ClaimOutcome {
        val host = ClaimContract.hostOf(claimUrl)
        var tries = 0
        while (true) {
            val outcome = withContext(ioDispatcher) { ClaimContract.call(transport, ClaimContract.fetchRequest(claimUrl, deviceId)) }
            log(WebhookLog.Level.INFO, "Fetch from $host: ${outcome.javaClass.simpleName}")
            if (outcome !is ClaimOutcome.TooMany || tries >= FETCH_TOO_MANY_RETRIES) {
                return if (outcome is ClaimOutcome.TooMany) ClaimOutcome.Unreachable("too many requests") else outcome
            }
            tries++
            delay(minOf((outcome.retryAfterSeconds ?: 5) * 1000, FETCH_MAX_WAIT_MS))
        }
    }

    /**
     * Tells the site the setup was applied, so the code is used up. Retries with backoff until the site
     * answers; stops when it says the code is used/expired (already done) or bound to another phone.
     * Returns true when the site recorded it (or already had).
     */
    suspend fun confirm(claimUrl: String, deviceId: String, declined: Boolean = false): Boolean {
        val host = ClaimContract.hostOf(claimUrl)
        var attempt = 0
        var waited = 0L
        while (true) {
            val outcome = withContext(ioDispatcher) { ClaimContract.call(transport, ClaimContract.confirmRequest(claimUrl, deviceId, declined)) }
            when (outcome) {
                is ClaimOutcome.Setup -> {
                    log(WebhookLog.Level.INFO, "Confirmed to $host")
                    return true
                }
                ClaimOutcome.Expired -> {
                    log(WebhookLog.Level.INFO, "Confirm to $host: code already used or expired")
                    return true
                }
                ClaimOutcome.OtherDevice -> {
                    log(WebhookLog.Level.WARNING, "Confirm to $host refused: the code is bound to another phone")
                    return false
                }
                is ClaimOutcome.TooMany, is ClaimOutcome.Unreachable -> {
                    val wait = maxOf(
                        CONFIRM_DELAYS_MS[attempt.coerceAtMost(CONFIRM_DELAYS_MS.size - 1)],
                        ((outcome as? ClaimOutcome.TooMany)?.retryAfterSeconds ?: 0) * 1000
                    )
                    if (waited + wait > CONFIRM_GIVE_UP_AFTER_MS) {
                        log(WebhookLog.Level.WARNING, "Confirm to $host given up; the code expires by itself")
                        return false
                    }
                    log(WebhookLog.Level.WARNING, "Confirm to $host failed ($outcome); retrying in ${wait / 1000} s")
                    delay(wait)
                    waited += wait
                    attempt++
                }
            }
        }
    }
}
