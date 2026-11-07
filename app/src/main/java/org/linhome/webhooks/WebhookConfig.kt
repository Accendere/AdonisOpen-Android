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

/**
 * The site's webhook settings, as delivered by an `adonis://configure` link.
 *
 * [token] is a secret like the SIP password: never log it. [toString] hides it.
 */
data class WebhookConfig(
    val baseUrl: String,
    val user: String,
    val token: String,
    val pollMode: PollMode = PollMode.NEVER,
    val pollingIntervalSeconds: Int = DEFAULT_POLLING_INTERVAL_SECONDS
) {
    /** `<webhook_baseurl>/<event>`, without doubling the slash. */
    fun urlFor(event: String): String = baseUrl.trimEnd('/') + "/" + event

    /** The host every call must stay on (no redirect to another host). */
    val host: String? get() = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()

    override fun toString(): String =
        "WebhookConfig(baseUrl=$baseUrl, user=$user, token=<hidden>, pollMode=${pollMode.wireName}, pollingIntervalSeconds=$pollingIntervalSeconds)"

    companion object {
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
            val user = root.optString("webhook_user", "").trim()
            val token = root.optString("webhook_token", "").trim()
            if (user.isEmpty() || token.isEmpty()) {
                lastRejectionReason = "webhook_user or webhook_token is missing"
                return null
            }
            val pollMode = PollMode.fromLinkValue(if (root.has("use_webhook_to_poll")) root.opt("use_webhook_to_poll") else null)
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
                pollingIntervalSeconds = interval.coerceAtLeast(MIN_POLLING_INTERVAL_SECONDS)
            )
        }
    }
}
