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
import java.net.URI

/**
 * When the app calls the site's `poll` webhook (field `use_webhook_to_poll` of the setup link).
 */
enum class PollMode(val wireName: String) {
    /** Never poll. */
    NEVER("never"),

    /** Poll only while the app is on screen. */
    FOREGROUND_ONLY("foreground_only"),

    /** Poll whenever the app's process runs (kept alive by the call service). */
    ALWAYS("always");

    companion object {
        /**
         * Reads the link's value. Strings are the current contract; booleans come from links made
         * before poll modes existed (`false` -> never, `true` -> foreground only). Anything else -> never.
         */
        fun fromLinkValue(value: Any?): PollMode = when (value) {
            is Boolean -> if (value) FOREGROUND_ONLY else NEVER
            is String -> values().firstOrNull { it.wireName == value.trim().lowercase() } ?: NEVER
            else -> NEVER
        }

        fun fromWireName(name: String?): PollMode =
            values().firstOrNull { it.wireName == name } ?: NEVER
    }
}

/** How the app proves who it is to the receiver. */
enum class WebhookAuth(val wireName: String) {
    /** `Authorization: Bearer <token>` (Adonis). */
    BEARER("bearer"),

    /** No Authorization header, for a receiver that doesn't check (someone's own system). */
    NONE("none");

    companion object {
        fun fromWireName(name: String?): WebhookAuth? = values().firstOrNull { it.wireName == name?.trim()?.lowercase() }
    }
}

/** The event names the app knows (`webhook_events`). Unknown names in a link are ignored. */
object WebhookEvents {
    const val STATE = "state"
    const val CALLS_PAUSED = "calls_paused"
    const val CALLS_RESUMED = "calls_resumed"
    const val CALL_RINGING = "call_ringing"
    const val CALL_PREVIEW = "call_preview"
    const val DOOR_ACTION = "door_action"
    const val CALL_ANSWERED = "call_answered"
    const val CALL_DECLINED = "call_declined"
    const val CALL_ENDED = "call_ended"
    const val CALL_SUMMARY = "call_summary"
    const val REGISTRATION = "registration"
    const val POLL = "poll"
    const val POLL_FOREGROUND = "poll_foreground"

    /** Not an event the site lists: the question the app asks when a call was answered elsewhere. */
    const val WHO_ANSWERED = "who_answered"

    /**
     * Not an event the site lists: the report the app sends when the person changed the webhook settings in
     * Settings (`events`, `events_off`, `leaving`), so the site's admins know. Sent once, outside the queue, and a
     * site that doesn't know it (an older Adonis answers 404) changes nothing: see [WebhookEngine.applyLocalEdit].
     */
    const val SETTINGS = "webhook_settings"

    /** The events the person can switch on or off in Settings, in the order shown. */
    val EDITABLE = listOf(
        DOOR_ACTION, CALL_RINGING, CALL_PREVIEW, CALL_ANSWERED, CALL_DECLINED, CALL_ENDED, CALL_SUMMARY,
        CALLS_PAUSED, CALLS_RESUMED, STATE, REGISTRATION, POLL, POLL_FOREGROUND
    )

    val KNOWN = setOf(
        STATE, CALLS_PAUSED, CALLS_RESUMED, CALL_RINGING, CALL_PREVIEW, DOOR_ACTION, CALL_ANSWERED,
        CALL_DECLINED, CALL_ENDED, CALL_SUMMARY, REGISTRATION, POLL, POLL_FOREGROUND
    )

    /** Every `call_*` event and `door_action`: the ones a call produces. */
    val CALL_EVENTS = setOf(CALL_RINGING, CALL_PREVIEW, DOOR_ACTION, CALL_ANSWERED, CALL_DECLINED, CALL_ENDED, CALL_SUMMARY)
}

/**
 * The site's webhook settings, as delivered by an `adonis://configure` link.
 *
 * [events] is what the site asked for (`webhook_events`); null for a link made before the list existed,
 * which means exactly the old behaviour: `state`, and polling as [pollMode] says. With a list, [pollMode]
 * comes from `poll` / `poll_foreground` in it.
 *
 * [token] is a secret like the SIP password: never log it. [toString] hides it.
 */
data class WebhookConfig(
    val baseUrl: String,
    val user: String,
    val token: String,
    val pollMode: PollMode = PollMode.NEVER,
    val pollingIntervalSeconds: Int = DEFAULT_POLLING_INTERVAL_SECONDS,
    val events: Set<String>? = null,
    val auth: WebhookAuth = WebhookAuth.BEARER,
    /** What the site asked for in its link; [events] differs when the person changed it here. Null = [events]. */
    val siteEvents: Set<String>? = null,
    /** The person changed these settings in Settings (a new setup code replaces them, after a warning). */
    val editedLocally: Boolean = false
) {
    /** The events the site asked for: its link's list, else the old default (`state`). */
    val askedBySite: Set<String> get() = siteEvents ?: events ?: setOf(WebhookEvents.STATE)

    /** The events the phone sends now. */
    val sending: Set<String> get() = events ?: setOf(WebhookEvents.STATE)

    /** Whether the site asked for [event]. An event it didn't list is never sent. */
    fun sends(event: String): Boolean = events?.contains(event) ?: (event == WebhookEvents.STATE)

    /** Whether any event a call produces is wanted (also gates `who_answered`). */
    val reportsCalls: Boolean get() = WebhookEvents.CALL_EVENTS.any { sends(it) }

    /** The domain the events go to, for the import screen. */
    val displayHost: String get() = host ?: baseUrl

    /** `<webhook_baseurl>/<event>`, without doubling the slash. */
    fun urlFor(event: String): String = baseUrl.trimEnd('/') + "/" + event

    /** The host every call must stay on (no redirect to another host). */
    val host: String? get() = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()

    override fun toString(): String =
        "WebhookConfig(baseUrl=$baseUrl, user=$user, token=<hidden>, auth=${auth.wireName}, events=${events?.sorted()}, pollMode=${pollMode.wireName}, pollingIntervalSeconds=$pollingIntervalSeconds, editedLocally=$editedLocally)"

    companion object {
        /**
         * [url] as an address events may go to: `https://`, a host, no credentials, query or fragment; trailing
         * slashes dropped. Null when it isn't one.
         */
        fun normalizedHttpsUrl(url: String): String? {
            val trimmed = url.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            return trimmed
        }

        /**
         * Whether [a] and [b] are the same server (host and port): the site's key (the token) is only good there. A
         * different path on the same server keeps it.
         */
        fun sameServer(a: String, b: String): Boolean {
            val ua = runCatching { URI(a.trim()) }.getOrNull() ?: return false
            val ub = runCatching { URI(b.trim()) }.getOrNull() ?: return false
            fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("http", ignoreCase = true)) 80 else 443
            return ua.host?.lowercase() == ub.host?.lowercase() && ua.host != null && port(ua) == port(ub)
        }

        const val MIN_POLLING_INTERVAL_SECONDS = 10
        const val DEFAULT_POLLING_INTERVAL_SECONDS = 60

        /** Why a link's webhook fields were not used, for the log (never contains the token). */
        var lastRejectionReason: String? = null
            private set

        /**
         * Reads the webhook fields from the link's top-level JSON object.
         * Returns null when the link has no usable webhook: no `webhook_baseurl`, a base URL that
         * isn't `https://`, or a missing user or token. Null means the app calls nothing.
         */
        fun fromLinkJson(root: JSONObject): WebhookConfig? {
            lastRejectionReason = null
            val baseUrl = root.optString("webhook_baseurl", "").trim()
            if (baseUrl.isEmpty()) {
                return null
            }
            val uri = runCatching { URI(baseUrl) }.getOrNull()
            if (uri == null || !"https".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrBlank()) {
                lastRejectionReason = "webhook_baseurl is not a valid https:// URL"
                return null
            }
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
                lastRejectionReason = "webhook_baseurl must not contain a query, fragment or credentials"
                return null
            }
            val auth = if (root.has("webhook_auth")) {
                WebhookAuth.fromWireName(root.optString("webhook_auth")) ?: run {
                    lastRejectionReason = "webhook_auth is neither bearer nor none"
                    return null
                }
            } else {
                WebhookAuth.BEARER
            }
            val user = root.optString("webhook_user", "").trim()
            val token = root.optString("webhook_token", "").trim()
            if (user.isEmpty() || (auth == WebhookAuth.BEARER && token.isEmpty())) {
                lastRejectionReason = "webhook_user or webhook_token is missing"
                return null
            }
            val events = root.optJSONArray("webhook_events")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, "").trim().lowercase().ifEmpty { null } }
                    .filter { it in WebhookEvents.KNOWN }
                    .toSet()
            }
            val pollMode = if (events != null) {
                when {
                    WebhookEvents.POLL in events -> PollMode.ALWAYS
                    WebhookEvents.POLL_FOREGROUND in events -> PollMode.FOREGROUND_ONLY
                    else -> PollMode.NEVER
                }
            } else {
                PollMode.fromLinkValue(if (root.has("use_webhook_to_poll")) root.opt("use_webhook_to_poll") else null)
            }
            val interval = if (root.has("polling_interval")) {
                root.optInt("polling_interval", DEFAULT_POLLING_INTERVAL_SECONDS)
            } else {
                DEFAULT_POLLING_INTERVAL_SECONDS
            }
            return WebhookConfig(
                baseUrl = baseUrl,
                user = user,
                token = token,
                pollMode = pollMode,
                pollingIntervalSeconds = interval.coerceAtLeast(MIN_POLLING_INTERVAL_SECONDS),
                events = events,
                auth = auth
            )
        }
    }
}
