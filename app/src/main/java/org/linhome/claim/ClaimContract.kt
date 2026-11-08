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

import org.json.JSONObject
import org.linhome.webhooks.HttpAnswer
import org.linhome.webhooks.WebhookRequest
import org.linhome.webhooks.WebhookTransport
import java.io.IOException
import java.net.URI

/**
 * Claim links: a one-time claim URL from the site gives the app its setup data
 * (contract: "Claim links" in Adonis's docs/features/adonis-open-setup-link.md).
 *
 * - fetch: `POST <claim URL>` with `{"device_id","platform"}` -> the same JSON an `adonis://configure` link carries.
 * - confirm: `POST <claim URL>/confirm` with the same body, after the person applied it -> the code is used up.
 */
object ClaimContract {
    const val PLATFORM = "android"

    /** A claim URL as it may appear in a clipboard or an `adonis://claim?url=` link. */
    private val CLAIM_URL_IN_TEXT = Regex("""https://[^\s"'<>]+/claim/[A-Za-z0-9_-]{16,}""")

    /**
     * Returns [raw] when it is a usable claim URL: `https://`, a host, no credentials, query or fragment.
     * The URL is a secret until used (it holds the code): never log it, log [hostOf] instead.
     */
    fun validate(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed.trimEnd('/')
    }

    /** The first claim URL in a block of text (the clipboard), or null. Only `/claim/<code>` URLs count. */
    fun findInText(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        return CLAIM_URL_IN_TEXT.find(text)?.value?.let { validate(it) }
    }

    fun hostOf(claimUrl: String): String = runCatching { URI(claimUrl).host }.getOrNull() ?: "?"

    private fun body(deviceId: String, declined: Boolean = false): String {
        val body = JSONObject().put("device_id", deviceId).put("platform", PLATFORM)
        if (declined) body.put("declined", true)
        return body.toString()
    }

    private val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

    fun fetchRequest(claimUrl: String, deviceId: String) = ClaimRequest(claimUrl, body(deviceId))

    /** [declined]: the person dismissed the setup (a pushed config update); the site stops offering it. */
    fun confirmRequest(claimUrl: String, deviceId: String, declined: Boolean = false) = ClaimRequest("$claimUrl/confirm", body(deviceId, declined))

    /** Wraps the claim URL so it never reaches a log through toString(). */
    class ClaimRequest(val url: String, val body: String) {
        fun toWebhookRequest() = WebhookRequest(url, body, headers)
        override fun toString() = "ClaimRequest(host=${hostOf(url)})"
    }

    fun classify(answer: HttpAnswer): ClaimOutcome {
        val json = answer.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        return when (answer.status) {
            200 -> if (json != null) ClaimOutcome.Setup(answer.body!!) else ClaimOutcome.Unreachable("answer is not JSON")
            409 -> ClaimOutcome.OtherDevice
            404, 410 -> ClaimOutcome.Expired
            429 -> ClaimOutcome.TooMany(answer.retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 })
            else -> ClaimOutcome.Unreachable("HTTP ${answer.status}")
        }
    }

    fun call(transport: WebhookTransport, request: ClaimRequest): ClaimOutcome = try {
        classify(transport.send(request.toWebhookRequest()))
    } catch (e: IOException) {
        ClaimOutcome.Unreachable(e.javaClass.simpleName)
    }
}

sealed class ClaimOutcome {
    /** 200: [json] is the setup data, the same as an `adonis://configure` link's decoded `data`. */
    data class Setup(val json: String) : ClaimOutcome() {
        override fun toString() = "Setup(<hidden>)"
    }

    /** 409: the link was already opened on another phone. */
    object OtherDevice : ClaimOutcome()

    /** 404 / 410: unknown, used or expired. */
    object Expired : ClaimOutcome()

    /** 429: too many calls; retry after [retryAfterSeconds] when given. */
    data class TooMany(val retryAfterSeconds: Long?) : ClaimOutcome()

    /** No answer, or an unexpected one. */
    data class Unreachable(val reason: String) : ClaimOutcome()
}
