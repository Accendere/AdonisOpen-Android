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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Whether the app may call the site's webhooks. */
enum class WebhookStatus(val wireName: String) {
    /** Calling normally (or nothing configured). */
    ACTIVE("active"),

    /** The site answered 401: the person must scan their link again. No calls until a new link. */
    UNAUTHORIZED("unauthorized"),

    /** The site answered 404: webhooks off. No calls until a new link (or one try at the next app start). */
    NOT_FOUND("not_found");

    companion object {
        fun fromWireName(name: String?): WebhookStatus = values().firstOrNull { it.wireName == name } ?: ACTIVE
    }
}

/** Where the webhook settings live between runs. */
interface WebhookStorage {
    var config: WebhookConfig?
    var status: WebhookStatus

    /** A random id made once on first use and kept for good (a new link doesn't reset it). */
    val deviceId: String
}

/** Log sink; messages never contain the token. */
fun interface WebhookLog {
    fun log(level: Level, message: String)

    enum class Level { INFO, WARNING, ERROR }
}

/**
 * Sends `state` and runs the `poll` timer, following the contract in Adonis's
 * `docs/features/adonis-open-webhooks.md`.
 *
 * Every public method must be called from the same thread (the main thread in the app);
 * [mainDispatcher] runs the logic, [ioDispatcher] the HTTP calls.
 */
class WebhookEngine(
    private val storage: WebhookStorage,
    private val transport: WebhookTransport,
    private val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: WebhookLog,
    private val onStatusChanged: (WebhookStatus) -> Unit = {},
    private val onPollMessages: (List<JSONObject>) -> Unit = {}
) {
    companion object {
        private const val TAG = "[Webhooks]"

        /** Waits between failed `state` sends: 10 s, 30 s, 2 min, then every 10 min. */
        val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 120_000, 600_000)

        fun retryDelayMs(attempt: Int): Long = RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.size - 1)]
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var stateJob: Job? = null
    private var pollJob: Job? = null

    /** The switch state that still has to reach the site, or null when the site has the latest one. */
    var pendingState: Boolean? = null
        private set

    /** True while a failed `state` send waits for its next try. */
    var waitingForRetry: Boolean = false
        private set

    private var foreground = false

    val status: WebhookStatus get() = storage.status

    private fun info(message: String) = logger.log(WebhookLog.Level.INFO, "$TAG $message")
    private fun warn(message: String) = logger.log(WebhookLog.Level.WARNING, "$TAG $message")
    private fun error(message: String) = logger.log(WebhookLog.Level.ERROR, "$TAG $message")

    /** App start: send the current state (it repairs one that was lost) and start polling if asked to. */
    fun onAppStart(enabled: Boolean) {
        if (storage.config == null) return
        if (storage.status == WebhookStatus.NOT_FOUND) {
            // The site may have turned its webhooks back on: one try per app start.
            info("Webhooks were off on the site (404); trying once more at app start")
            setStatus(WebhookStatus.ACTIVE)
        }
        sendState(enabled)
        updatePolling()
    }

    /** The door-call switch changed. */
    fun onSwitchChanged(enabled: Boolean) {
        sendState(enabled)
    }

    /**
     * A setup link carrying a SIP account was applied: [config] replaces the stored one,
     * or clears it when the link had no (usable) webhook fields.
     */
    fun onLinkApplied(config: WebhookConfig?, enabled: Boolean) {
        storage.config = config
        setStatus(WebhookStatus.ACTIVE)
        stateJob?.cancel()
        pendingState = null
        waitingForRetry = false
        if (config == null) {
            info("Setup link has no webhooks: calling nothing")
        } else {
            info("Setup link applied: $config")
            sendState(enabled)
        }
        updatePolling()
    }

    /** The network came back: resend a state whose last send failed, right away. */
    fun onNetworkAvailable() {
        val state = pendingState ?: return
        if (waitingForRetry) {
            info("Network is back: resending state")
            sendState(state)
        }
    }

    /** The app came to the screen or left it (for poll mode "foreground_only"). */
    fun setForeground(isForeground: Boolean) {
        if (foreground == isForeground) return
        foreground = isForeground
        updatePolling()
    }

    fun shouldPoll(): Boolean {
        val config = storage.config ?: return false
        if (storage.status != WebhookStatus.ACTIVE) return false
        return when (config.pollMode) {
            PollMode.NEVER -> false
            PollMode.FOREGROUND_ONLY -> foreground
            PollMode.ALWAYS -> true
        }
    }

    fun shutdown() {
        stateJob?.cancel()
        pollJob?.cancel()
    }

    private fun canCall(): Boolean = storage.config != null && storage.status == WebhookStatus.ACTIVE

    private fun setStatus(newStatus: WebhookStatus) {
        if (storage.status == newStatus) return
        storage.status = newStatus
        onStatusChanged(newStatus)
    }

    /** Stops every call after a 401 or 404. */
    private fun stopAll(newStatus: WebhookStatus) {
        setStatus(newStatus)
        pendingState = null
        waitingForRetry = false
        stateJob?.cancel()
        updatePolling()
    }

    /**
     * Sends [enabled], replacing any send still waiting: only the latest state matters, so a switch
     * flipped several times while offline ends in one request with the final state.
     */
    private fun sendState(enabled: Boolean) {
        if (!canCall()) return
        pendingState = enabled
        stateJob?.cancel()
        stateJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val config = storage.config ?: return@launch
                if (storage.status != WebhookStatus.ACTIVE) return@launch
                val request = WebhookProtocol.stateRequest(config, enabled, storage.deviceId)
                waitingForRetry = false
                val outcome = withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }
                when (outcome) {
                    is WebhookOutcome.Ok -> {
                        info("State sent (enabled=$enabled)")
                        pendingState = null
                        return@launch
                    }
                    is WebhookOutcome.AppBug -> {
                        error("State rejected as a bug in the app: HTTP ${outcome.status} ${outcome.code ?: ""} ${outcome.message ?: ""}".trim())
                        pendingState = null
                        return@launch
                    }
                    WebhookOutcome.Unauthorized -> {
                        warn("Site answered 401 to state: the person must scan their link again; stopping all calls")
                        stopAll(WebhookStatus.UNAUTHORIZED)
                        return@launch
                    }
                    WebhookOutcome.NotFound -> {
                        warn("Site answered 404 to state: webhooks are off; stopping all calls until a new link")
                        stopAll(WebhookStatus.NOT_FOUND)
                        return@launch
                    }
                    is WebhookOutcome.TooMany -> {
                        val wait = maxOf(retryDelayMs(attempt), (outcome.retryAfterSeconds ?: 0) * 1000)
                        warn("Site answered 429 to state; retrying in ${wait / 1000} s")
                        waitingForRetry = true
                        delay(wait)
                    }
                    is WebhookOutcome.Retry -> {
                        val wait = retryDelayMs(attempt)
                        warn("State not sent (${outcome.reason}); retrying in ${wait / 1000} s")
                        waitingForRetry = true
                        delay(wait)
                    }
                }
                attempt++
            }
        }
    }

    private fun updatePolling() {
        if (!shouldPoll()) {
            if (pollJob != null) info("Polling stopped")
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        info("Polling started")
        pollJob = scope.launch {
            while (isActive && shouldPoll()) {
                val config = storage.config ?: return@launch
                val request = WebhookProtocol.pollRequest(config, storage.deviceId)
                val outcome = withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }
                var wait = config.pollingIntervalSeconds * 1000L
                when (outcome) {
                    is WebhookOutcome.Ok -> {
                        val array = outcome.body?.optJSONArray("messages")
                        val messages = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
                        if (messages.isNotEmpty()) {
                            info("Poll returned ${messages.size} message(s)")
                            onPollMessages(messages)
                        }
                    }
                    is WebhookOutcome.AppBug -> {
                        // The poll body never changes, so resending it would fail the same way.
                        error("Poll rejected as a bug in the app: HTTP ${outcome.status} ${outcome.code ?: ""} ${outcome.message ?: ""}; polling stopped until the next app start or link".trim())
                        pollJob = null
                        return@launch
                    }
                    WebhookOutcome.Unauthorized -> {
                        warn("Site answered 401 to poll: the person must scan their link again; stopping all calls")
                        pollJob = null
                        stopAll(WebhookStatus.UNAUTHORIZED)
                        return@launch
                    }
                    WebhookOutcome.NotFound -> {
                        warn("Site answered 404 to poll: webhooks are off; stopping all calls until a new link")
                        pollJob = null
                        stopAll(WebhookStatus.NOT_FOUND)
                        return@launch
                    }
                    is WebhookOutcome.TooMany -> {
                        wait = maxOf(wait, (outcome.retryAfterSeconds ?: 0) * 1000, retryDelayMs(1))
                        warn("Site answered 429 to poll; next poll in ${wait / 1000} s")
                    }
                    is WebhookOutcome.Retry -> warn("Poll failed (${outcome.reason}); next poll in ${wait / 1000} s")
                }
                delay(wait)
            }
        }
    }
}
