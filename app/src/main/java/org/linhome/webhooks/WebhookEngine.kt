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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

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

    /** Events waiting to be sent, oldest first, each a JSON object `{"event", "fields", "at"}`. Survives a restart. */
    var eventQueue: List<String>

    /** Ids of poll messages handled but not yet acknowledged to the site. */
    var pendingAcks: Set<String>
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
    private val onPollMessages: (List<JSONObject>) -> Unit = {},
    private val appVersion: String? = null,
    private val clock: () -> String = { OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
) {
    companion object {
        private const val TAG = "[Webhooks]"

        /** Waits between failed `state` sends: 10 s, 30 s, 2 min, then every 10 min. */
        val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 120_000, 600_000)

        fun retryDelayMs(attempt: Int): Long = RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.size - 1)]

        /** At most this many events wait; when full, the oldest is dropped. */
        const val MAX_QUEUED_EVENTS = 200

        /** How long `who_answered` may take before the app gives up (the site itself waits ~2 s). */
        const val WHO_ANSWERED_TIMEOUT_MS = 4_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var stateJob: Job? = null
    private var pollJob: Job? = null
    private var eventsJob: Job? = null

    /** True while a failed event send waits for its next try. */
    var eventsWaitingForRetry: Boolean = false
        private set

    /** The switch state that still has to reach the site, or null when the site has the latest one. */
    var pendingState: Boolean? = null
        private set

    /** True while a failed `state` send waits for its next try. */
    var waitingForRetry: Boolean = false
        private set

    private var foreground = false

    val status: WebhookStatus get() = storage.status

    /** The settings in use, or null when the site gave none. */
    val config: WebhookConfig? get() = storage.config

    private fun meta(at: String? = clock()) = WebhookMeta(storage.deviceId, appVersion, at)

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
        flushEvents()
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
        val previous = storage.config
        storage.config = config
        setStatus(WebhookStatus.ACTIVE)
        stateJob?.cancel()
        pendingState = null
        waitingForRetry = false
        // Events and acks belonged to the previous settings (maybe another site or person): drop them.
        eventsJob?.cancel()
        eventsJob = null
        eventsWaitingForRetry = false
        storage.eventQueue = emptyList()
        storage.pendingAcks = emptySet()
        if (config == null) {
            info("Setup link has no webhooks: calling nothing")
        } else {
            info("Setup link applied: $config")
            sendState(enabled)
            // The person's own changes are gone: tell the site, so what it showed its admins is settled.
            if (previous?.editedLocally == true && WebhookConfig.sameServer(previous.baseUrl, config.baseUrl)) {
                reportSettings(config, config.sending, emptySet(), leaving = false)
            }
        }
        updatePolling()
    }

    /** What [applyLocalEdit] did. */
    enum class LocalEdit {
        /** Saved; the events go to the same server as before, with the site's key. */
        SAVED,

        /** Saved; the events go to another server now, so the site's key was removed (a new setup code brings it back). */
        SAVED_KEY_REMOVED,

        /** The address isn't a usable `https://` address: nothing changed. */
        INVALID_ADDRESS
    }

    /**
     * The person changed the webhook settings in Settings: [events] are the ones the phone sends from now on, and
     * [baseUrl] where. They have the last word (no event is locked by the site). On the same server the site's key
     * stays; on another one it is removed, never sent there, and so are the events still queued for the old one.
     *
     * The site is told, once, with `webhook_settings` ([WebhookEvents.SETTINGS]): the events it asked for that are
     * off now, and whether the phone is leaving it (sent to the old server, before the switch). Outside the queue on
     * purpose: an older site answers 404 to an event it doesn't know, which in the queue would stop every call.
     */
    fun applyLocalEdit(events: Set<String>, baseUrl: String, enabled: Boolean): LocalEdit {
        val current = storage.config ?: return LocalEdit.INVALID_ADDRESS
        val url = WebhookConfig.normalizedHttpsUrl(baseUrl) ?: return LocalEdit.INVALID_ADDRESS
        val leaving = !WebhookConfig.sameServer(current.baseUrl, url)
        val known = events.filter { it in WebhookEvents.KNOWN }.toSet()
        val asked = current.askedBySite
        reportSettings(current, known, asked - known, leaving)
        val updated = current.copy(
            baseUrl = url,
            events = known,
            siteEvents = asked,
            editedLocally = true,
            token = if (leaving) "" else current.token,
            auth = if (leaving) WebhookAuth.NONE else current.auth
        )
        storage.config = updated
        info("Settings changed here: $updated")
        if (leaving) {
            eventsJob?.cancel()
            eventsJob = null
            eventsWaitingForRetry = false
            storage.eventQueue = emptyList()
            storage.pendingAcks = emptySet()
            setStatus(WebhookStatus.ACTIVE)
            sendState(enabled)
        }
        updatePolling()
        return if (leaving) LocalEdit.SAVED_KEY_REMOVED else LocalEdit.SAVED
    }

    /** `webhook_settings` to [config]'s server: a few tries, and whatever it answers changes nothing here. */
    private fun reportSettings(config: WebhookConfig, sending: Set<String>, off: Set<String>, leaving: Boolean) {
        val fields = JSONObject()
            .put("events", org.json.JSONArray(sending.sorted()))
            .put("events_off", org.json.JSONArray(off.sorted()))
            .put("leaving", leaving)
        val request = WebhookProtocol.eventRequest(config, WebhookEvents.SETTINGS, fields, meta())
        scope.launch {
            for (attempt in 0 until 3) {
                when (val outcome = withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }) {
                    is WebhookOutcome.Ok -> {
                        info("webhook_settings sent")
                        return@launch
                    }
                    is WebhookOutcome.Retry, is WebhookOutcome.TooMany -> delay(retryDelayMs(attempt))
                    else -> {
                        info("webhook_settings not taken by the site (${outcome.javaClass.simpleName}); nothing changes")
                        return@launch
                    }
                }
            }
            warn("webhook_settings not sent after 3 tries")
        }
    }

    /** The network came back: resend a state whose last send failed, right away. */
    fun onNetworkAvailable() {
        if (eventsWaitingForRetry) {
            info("Network is back: resending queued events")
            eventsJob?.cancel()
            eventsJob = null
            eventsWaitingForRetry = false
            flushEvents()
        }
        val state = pendingState ?: return
        if (waitingForRetry) {
            info("Network is back: resending state")
            sendState(state)
        }
    }

    /**
     * Queues [event] with its [fields] (merged over `user`, `device_id`, `platform`, `app_version`, `at`)
     * when the site asked for it, and sends the queue in order. [at] defaults to now.
     */
    fun sendEvent(event: String, fields: JSONObject, at: String? = null) {
        val config = storage.config ?: return
        if (!config.sends(event) || storage.status != WebhookStatus.ACTIVE) return
        val entry = JSONObject().put("event", event).put("fields", fields).put("at", at ?: clock())
        val queue = storage.eventQueue.toMutableList()
        queue += entry.toString()
        while (queue.size > MAX_QUEUED_EVENTS) {
            warn("Event queue full: dropping the oldest event")
            queue.removeAt(0)
        }
        storage.eventQueue = queue
        flushEvents()
    }

    /**
     * Asks the site who answered a call this phone saw answered elsewhere. Null when the site doesn't say,
     * doesn't want call events, or doesn't answer in time. Never changes the webhook status.
     */
    suspend fun whoAnswered(callId: String?, door: String?): String? {
        val config = storage.config ?: return null
        if (storage.status != WebhookStatus.ACTIVE || !config.reportsCalls) return null
        val request = WebhookProtocol.whoAnsweredRequest(config, callId, door, meta())
        val outcome = withTimeoutOrNull(WHO_ANSWERED_TIMEOUT_MS) {
            withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }
        }
        if (outcome == null) {
            info("who_answered: no answer in time")
            return null
        }
        return when (outcome) {
            is WebhookOutcome.Ok -> outcome.body?.let { body ->
                if (body.isNull("answered_by")) null else body.optString("answered_by", "").trim().ifEmpty { null }
            }
            else -> {
                info("who_answered: ${outcome.javaClass.simpleName}")
                null
            }
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
        eventsJob?.cancel()
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
        eventsJob?.cancel()
        eventsJob = null
        eventsWaitingForRetry = false
        updatePolling()
    }

    /** Sends the queued events one by one, oldest first; a failure waits and retries the same one. */
    private fun flushEvents() {
        if (!canCall() || eventsJob?.isActive == true || storage.eventQueue.isEmpty()) return
        eventsJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val config = storage.config ?: break
                if (storage.status != WebhookStatus.ACTIVE) break
                val head = storage.eventQueue.firstOrNull() ?: break
                val entry = runCatching { JSONObject(head) }.getOrNull()
                if (entry == null) {
                    dropHead(head)
                    continue
                }
                val event = entry.optString("event")
                if (!config.sends(event)) {
                    dropHead(head)
                    continue
                }
                val request = WebhookProtocol.eventRequest(
                    config, event, entry.optJSONObject("fields") ?: JSONObject(), meta(entry.optString("at").ifEmpty { null })
                )
                eventsWaitingForRetry = false
                when (val outcome = withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }) {
                    is WebhookOutcome.Ok -> {
                        info("Event $event sent")
                        dropHead(head)
                        attempt = 0
                        continue
                    }
                    is WebhookOutcome.AppBug -> {
                        error("Event $event rejected as a bug in the app: HTTP ${outcome.status} ${outcome.code ?: ""} ${outcome.message ?: ""}".trim())
                        dropHead(head)
                        attempt = 0
                        continue
                    }
                    WebhookOutcome.Unauthorized -> {
                        warn("Site answered 401 to $event: the person must scan their link again; stopping all calls")
                        eventsJob = null
                        stopAll(WebhookStatus.UNAUTHORIZED)
                        return@launch
                    }
                    WebhookOutcome.NotFound -> {
                        warn("Site answered 404 to $event: webhooks are off; stopping all calls until a new link")
                        eventsJob = null
                        stopAll(WebhookStatus.NOT_FOUND)
                        return@launch
                    }
                    is WebhookOutcome.TooMany -> {
                        val wait = maxOf(retryDelayMs(attempt), (outcome.retryAfterSeconds ?: 0) * 1000)
                        warn("Site answered 429 to $event; retrying in ${wait / 1000} s")
                        eventsWaitingForRetry = true
                        delay(wait)
                    }
                    is WebhookOutcome.Retry -> {
                        val wait = retryDelayMs(attempt)
                        warn("Event $event not sent (${outcome.reason}); retrying in ${wait / 1000} s")
                        eventsWaitingForRetry = true
                        delay(wait)
                    }
                }
                attempt++
            }
            eventsJob = null
        }
    }

    private fun dropHead(head: String) {
        val queue = storage.eventQueue.toMutableList()
        if (queue.firstOrNull() == head) queue.removeAt(0) else queue.remove(head)
        storage.eventQueue = queue
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
                val request = WebhookProtocol.stateRequest(config, enabled, meta())
                if (request == null) {
                    // The site listed neither calls_paused/calls_resumed nor state for this change.
                    pendingState = null
                    return@launch
                }
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
                val acks = storage.pendingAcks
                val request = if (config.events == null && acks.isEmpty()) {
                    WebhookProtocol.pollRequest(config, storage.deviceId)
                } else {
                    WebhookProtocol.pollRequest(config, storage.deviceId, acks, meta())
                }
                val outcome = withContext(ioDispatcher) { WebhookProtocol.call(transport, request) }
                var wait = config.pollingIntervalSeconds * 1000L
                when (outcome) {
                    is WebhookOutcome.Ok -> {
                        if (acks.isNotEmpty()) storage.pendingAcks = storage.pendingAcks - acks
                        val array = outcome.body?.optJSONArray("messages")
                        val messages = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
                        if (messages.isNotEmpty()) {
                            info("Poll returned ${messages.size} message(s)")
                            // Every message with an id is acknowledged in the next poll, whatever its type:
                            // an unknown type is ignored, but acknowledged so the site stops re-sending it.
                            val ids = messages.mapNotNull { it.optString("id", "").ifEmpty { null } }
                            if (ids.isNotEmpty()) storage.pendingAcks = storage.pendingAcks + ids
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
