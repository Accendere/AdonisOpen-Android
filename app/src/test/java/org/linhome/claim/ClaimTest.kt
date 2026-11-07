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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linhome.webhooks.HttpAnswer
import org.linhome.webhooks.HttpUrlConnectionTransport
import org.linhome.webhooks.WebhookRequest
import org.linhome.webhooks.WebhookTransport
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClaimContractTest {
    private val url = "https://alameda.example.org:8124/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo"

    @Test
    fun validatesClaimUrls() {
        assertEquals(url, ClaimContract.validate(" $url/ "))
        assertNull(ClaimContract.validate("http://alameda.lan/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo"))
        assertNull(ClaimContract.validate("https://user:pw@host/claim/q2JxYk3aBcDeFgHiJkLmNo"))
        assertNull(ClaimContract.validate("$url?x=1"))
        assertNull(ClaimContract.validate("$url#frag"))
        assertNull(ClaimContract.validate("adonis://claim"))
        assertNull(ClaimContract.validate(""))
        assertNull(ClaimContract.validate(null))
    }

    @Test
    fun findsAClaimUrlInClipboardText() {
        assertEquals(url, ClaimContract.findInText(url))
        assertEquals(url, ClaimContract.findInText("Your link: $url (valid 48 h)"))
        assertNull("not a claim path", ClaimContract.findInText("https://example.org/some/page"))
        assertNull("code too short", ClaimContract.findInText("https://h/claim/abc"))
        assertNull("plain http", ClaimContract.findInText("http://h/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo"))
        assertNull(ClaimContract.findInText(null))
    }

    @Test
    fun requestsCarryTheDeviceAndNoSecretsInToString() {
        val fetch = ClaimContract.fetchRequest(url, "dev-1")
        assertEquals(url, fetch.url)
        val body = JSONObject(fetch.body)
        assertEquals("dev-1", body.getString("device_id"))
        assertEquals("android", body.getString("platform"))
        assertEquals("$url/confirm", ClaimContract.confirmRequest(url, "dev-1").url)
        assertFalse("the code never reaches a log", fetch.toString().contains("q2JxYk3a"))
        assertEquals("alameda.example.org", ClaimContract.hostOf(url))
        assertFalse(fetch.toWebhookRequest().headers.containsKey("Authorization"))
    }

    @Test
    fun classifiesAnswers() {
        val setup = ClaimContract.classify(HttpAnswer(200, """{"sip":{"username":"u"}}"""))
        assertTrue(setup is ClaimOutcome.Setup)
        assertFalse(setup.toString().contains("username"))
        assertTrue(ClaimContract.classify(HttpAnswer(200, "<html>")) is ClaimOutcome.Unreachable)
        assertEquals(ClaimOutcome.OtherDevice, ClaimContract.classify(HttpAnswer(409, """{"code":"claim.other_device"}""")))
        assertEquals(ClaimOutcome.Expired, ClaimContract.classify(HttpAnswer(404, """{"code":"claim.unknown"}""")))
        assertEquals(ClaimOutcome.Expired, ClaimContract.classify(HttpAnswer(410, """{"code":"claim.used_or_expired"}""")))
        assertEquals(ClaimOutcome.TooMany(20), ClaimContract.classify(HttpAnswer(429, "{}", "20")))
        assertTrue(ClaimContract.classify(HttpAnswer(502, null)) is ClaimOutcome.Unreachable)
        assertTrue(ClaimContract.call({ throw IOException("down") }, ClaimContract.fetchRequest(url, "d")) is ClaimOutcome.Unreachable)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimClientTest {
    private val url = "https://site.example/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo"

    private class Scripted(vararg answers: HttpAnswer?) : WebhookTransport {
        val queue = ArrayDeque(answers.toList())
        val urls = mutableListOf<String>()
        override fun send(request: WebhookRequest): HttpAnswer {
            urls += request.url
            return (if (queue.isEmpty()) HttpAnswer(200, """{"success":true}""") else queue.removeFirst())
                ?: throw IOException("down")
        }
    }

    @Test
    fun fetchRetriesTooManyThenGivesUp() = runTest {
        val transport = Scripted(HttpAnswer(429, "{}", "2"), HttpAnswer(429, "{}"), HttpAnswer(429, "{}"), HttpAnswer(429, "{}"))
        val client = ClaimClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        val outcome = client.fetch(url, "d")
        assertTrue(outcome is ClaimOutcome.Unreachable)
        assertEquals(4, transport.urls.size)
    }

    @Test
    fun fetchReturnsOtherFailuresAtOnce() = runTest {
        val transport = Scripted(HttpAnswer(409, "{}"))
        val client = ClaimClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        assertEquals(ClaimOutcome.OtherDevice, client.fetch(url, "d"))
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun confirmRetriesWithBackoffUntilRecorded() = runTest {
        val transport = Scripted(null, HttpAnswer(503, null), null)
        val client = ClaimClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        val result = async { client.confirm(url, "d") }
        runCurrent(); assertEquals(1, transport.urls.size)
        advanceTimeBy(10_001); assertEquals(2, transport.urls.size)
        advanceTimeBy(30_000); assertEquals(3, transport.urls.size)
        advanceTimeBy(120_000); assertEquals(4, transport.urls.size)
        assertTrue(result.await())
        assertTrue(transport.urls.all { it == "$url/confirm" })
    }

    @Test
    fun confirmStopsWhenAlreadyUsedOrBoundElsewhere() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertTrue(ClaimClient(Scripted(HttpAnswer(410, "{}")), dispatcher) { _, _ -> }.confirm(url, "d"))
        assertFalse(ClaimClient(Scripted(HttpAnswer(409, "{}")), dispatcher) { _, _ -> }.confirm(url, "d"))
    }

    @Test
    fun confirmGivesUpAfterTheCodeHasExpired() = runTest {
        val transport = object : WebhookTransport {
            var calls = 0
            override fun send(request: WebhookRequest): HttpAnswer { calls++; throw IOException("down") }
        }
        val client = ClaimClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        assertFalse(client.confirm(url, "d"))
        // 10 s + 30 s + 2 min, then every 10 min, for 48 h.
        assertEquals(291, transport.calls)
    }

    @Test
    fun fetchAndConfirmOverRealHttp() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val claimUrl = server.url("/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo").toString()
            server.enqueue(MockResponse().setBody("""{"sip":{"username":"u","password":"p","domain":"d"},"devices":[]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val client = ClaimClient(HttpUrlConnectionTransport(), StandardTestDispatcher(testScheduler)) { _, _ -> }
            val outcome = client.fetch(claimUrl, "dev-9")
            assertTrue(outcome is ClaimOutcome.Setup)
            assertTrue(client.confirm(claimUrl, "dev-9"))
            val fetch = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", fetch.method)
            assertEquals("/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo", fetch.path)
            assertEquals("dev-9", JSONObject(fetch.body.readUtf8()).getString("device_id"))
            assertEquals("/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo/confirm", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        } finally {
            server.shutdown()
        }
    }
}
