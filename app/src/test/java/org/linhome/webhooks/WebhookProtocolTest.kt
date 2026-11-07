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

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebhookProtocolTest {

    private val config = WebhookConfig("https://site.example/api/webhooks/adonis-open", "pedro_7f3a", "secret-token")

    @Test
    fun stateRequest() {
        val request = WebhookProtocol.stateRequest(config, enabled = false, deviceId = "5f0c2a6e-8b1d")
        assertEquals("https://site.example/api/webhooks/adonis-open/state", request.url)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        val body = JSONObject(request.body)
        assertEquals(setOf("user", "enabled", "device_id", "platform"), body.keys().asSequence().toSet())
        assertEquals("pedro_7f3a", body.getString("user"))
        assertEquals(false, body.get("enabled")) // a JSON boolean, not "false"
        assertEquals("5f0c2a6e-8b1d", body.getString("device_id"))
        assertEquals("android", body.getString("platform"))
        assertFalse("the token goes only in the header", request.body.contains("secret-token"))
        assertFalse(request.url.contains("secret-token"))
        assertFalse(request.toString().contains("secret-token"))
    }

    @Test
    fun pollRequest() {
        val request = WebhookProtocol.pollRequest(config, deviceId = "dev-1")
        assertEquals("https://site.example/api/webhooks/adonis-open/poll", request.url)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals(setOf("user", "device_id"), body.keys().asSequence().toSet())
        assertEquals("pedro_7f3a", body.getString("user"))
        assertEquals("dev-1", body.getString("device_id"))
    }

    @Test
    fun classifiesEveryAnswerOfTheContract() {
        val ok = WebhookProtocol.classify(HttpAnswer(200, """{"success":true,"messages":[]}"""))
        assertTrue(ok is WebhookOutcome.Ok && ok.body!!.getBoolean("success"))

        val bad = WebhookProtocol.classify(HttpAnswer(400, """{"success":false,"code":"webhook.bad_request","message":"enabled must be a boolean"}"""))
        assertEquals(WebhookOutcome.AppBug(400, "webhook.bad_request", "enabled must be a boolean"), bad)
        assertEquals(WebhookOutcome.AppBug(413, "webhook.too_large", null),
            WebhookProtocol.classify(HttpAnswer(413, """{"success":false,"code":"webhook.too_large"}""")))
        assertEquals(WebhookOutcome.Unauthorized, WebhookProtocol.classify(HttpAnswer(401, """{"success":false,"code":"webhook.unauthorized"}""")))
        assertEquals(WebhookOutcome.NotFound, WebhookProtocol.classify(HttpAnswer(404, null)))
        assertEquals(WebhookOutcome.TooMany(60), WebhookProtocol.classify(HttpAnswer(429, "{}", "60")))
        assertEquals(WebhookOutcome.TooMany(null), WebhookProtocol.classify(HttpAnswer(429, "{}", "soon")))
        assertTrue(WebhookProtocol.classify(HttpAnswer(500, "<html>")) is WebhookOutcome.Retry)
        assertTrue(WebhookProtocol.classify(HttpAnswer(503, null)) is WebhookOutcome.Retry)
        assertTrue(WebhookProtocol.classify(HttpAnswer(302, null)) is WebhookOutcome.Retry)
    }

    @Test
    fun noAnswerMeansRetry() {
        val outcome = WebhookProtocol.call({ throw IOException("unreachable") }, WebhookProtocol.pollRequest(config, null))
        assertTrue(outcome is WebhookOutcome.Retry)
    }
}

/** The real HTTP transport against a local server. */
class HttpUrlConnectionTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = WebhookConfig(server.url("/api/webhooks/adonis-open").toString(), "pedro_7f3a", "secret-token")

    @Test
    fun postsTheBodyWithTheHeaders() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        val outcome = WebhookProtocol.call(HttpUrlConnectionTransport(), WebhookProtocol.stateRequest(config(), true, "dev-1"))
        assertTrue(outcome is WebhookOutcome.Ok)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/webhooks/adonis-open/state", recorded.path)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(true, body.get("enabled"))
        assertEquals("dev-1", body.getString("device_id"))
    }

    @Test
    fun readsErrorAnswers() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"code":"webhook.unauthorized"}"""))
        assertEquals(WebhookOutcome.Unauthorized,
            WebhookProtocol.call(HttpUrlConnectionTransport(), WebhookProtocol.pollRequest(config(), "dev-1")))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}"))
        assertEquals(WebhookOutcome.TooMany(30),
            WebhookProtocol.call(HttpUrlConnectionTransport(), WebhookProtocol.pollRequest(config(), "dev-1")))
    }

    @Test
    fun neverFollowsRedirects() {
        val elsewhere = MockWebServer()
        elsewhere.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/steal").toString()))
            val outcome = WebhookProtocol.call(HttpUrlConnectionTransport(), WebhookProtocol.stateRequest(config(), true, null))
            assertTrue(outcome is WebhookOutcome.Retry)
            assertEquals(1, server.requestCount)
            assertEquals("the token never reaches the other host", 0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }
    }
}
