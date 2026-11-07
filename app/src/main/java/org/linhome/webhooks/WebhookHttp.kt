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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** One webhook call, ready to send. [headers] holds the token: never log them. */
class WebhookRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>
) {
    override fun toString(): String = "WebhookRequest(url=$url, body=$body)"
}

/** What the app does with an answer (contract section 4). */
sealed class WebhookOutcome {
    /** 200: recorded. [body] is the parsed answer, if it was JSON. */
    data class Ok(val body: JSONObject?) : WebhookOutcome()

    /** 400 or 413: a bug in the app. Log it, don't resend the same body. */
    data class AppBug(val status: Int, val code: String?, val message: String?) : WebhookOutcome()

    /** 401: the user and token aren't valid any more. Stop calling; the person must scan their link again. */
    object Unauthorized : WebhookOutcome()

    /** 404: no such event, or the site turned its webhooks off. Stop calling until a new link. */
    object NotFound : WebhookOutcome()

    /** 429: too many calls. Retry later, after at least [retryAfterSeconds] when the site said so. */
    data class TooMany(val retryAfterSeconds: Long?) : WebhookOutcome()

    /** Anything else (5xx, redirects, no answer): the network or the site is down. Retry with backoff. */
    data class Retry(val reason: String) : WebhookOutcome()
}

/** The raw answer of one HTTP call. */
data class HttpAnswer(val status: Int, val body: String?, val retryAfter: String? = null)

/** Sends one request. Throws [IOException] when there is no answer at all. */
fun interface WebhookTransport {
    @Throws(IOException::class)
    fun send(request: WebhookRequest): HttpAnswer
}

object WebhookProtocol {
    const val PLATFORM = "android"
    const val EVENT_STATE = "state"
    const val EVENT_POLL = "poll"

    private fun headers(config: WebhookConfig) = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer ${config.token}"
    )

    fun stateRequest(config: WebhookConfig, enabled: Boolean, deviceId: String?): WebhookRequest {
        val body = JSONObject()
            .put("user", config.user)
            .put("enabled", enabled)
            .put("platform", PLATFORM)
        deviceId?.let { body.put("device_id", it) }
        return WebhookRequest(config.urlFor(EVENT_STATE), body.toString(), headers(config))
    }

    fun pollRequest(config: WebhookConfig, deviceId: String?): WebhookRequest {
        val body = JSONObject().put("user", config.user)
        deviceId?.let { body.put("device_id", it) }
        return WebhookRequest(config.urlFor(EVENT_POLL), body.toString(), headers(config))
    }

    fun classify(answer: HttpAnswer): WebhookOutcome {
        val json = answer.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val code = json?.optString("code")?.ifEmpty { null }
        val message = json?.optString("message")?.ifEmpty { null }
        return when (answer.status) {
            200 -> WebhookOutcome.Ok(json)
            400, 413 -> WebhookOutcome.AppBug(answer.status, code, message)
            401 -> WebhookOutcome.Unauthorized
            404 -> WebhookOutcome.NotFound
            429 -> WebhookOutcome.TooMany(answer.retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 })
            in 300..399 -> WebhookOutcome.Retry("redirect (${answer.status}) not followed")
            else -> WebhookOutcome.Retry("HTTP ${answer.status}")
        }
    }

    /** Sends and classifies; a call with no answer becomes [WebhookOutcome.Retry]. */
    fun call(transport: WebhookTransport, request: WebhookRequest): WebhookOutcome = try {
        classify(transport.send(request))
    } catch (e: IOException) {
        WebhookOutcome.Retry(e.javaClass.simpleName)
    }
}

/**
 * Plain [HttpURLConnection] transport: normal certificate checks, never follows redirects
 * (a redirect is treated as a failure, so the token can't leave for another host).
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000
) : WebhookTransport {
    override fun send(request: WebhookRequest): HttpAnswer {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = request.body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { input ->
                // The site's answers are small; cap what we read.
                val buffer = input.readBytes()
                String(buffer, 0, minOf(buffer.size, 64 * 1024), StandardCharsets.UTF_8)
            }
            return HttpAnswer(status, body, connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }
}
