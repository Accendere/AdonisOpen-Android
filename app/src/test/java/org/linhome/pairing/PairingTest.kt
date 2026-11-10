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

private const val PAIR_URL = "https://alameda.example.org:8124/api/adonis-open/pair"
private const val SECRET = "pAiRiNgSeCrEt0123456789abcdefghijklmnopqrs"
private const val CLAIM = "https://alameda.example.org:8124/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"

private fun startBody(pollUrl: String = "$PAIR_URL/poll", code: String = "482913", interval: Int = 3, expiresIn: Int = 600) =
    """{"pairing_id":"$SECRET","code":"$code","poll_url":"$pollUrl","expires_in":$expiresIn,"interval":$interval}"""

class PairingContractTest {
    private val site = DiscoveredSite("Alameda", PAIR_URL)

    private fun txt(vararg pairs: Pair<String, String?>) = pairs.associate { it.first to it.second?.toByteArray() }

    @Test
    fun readsAnnouncements() {
        assertEquals(site, PairingContract.siteFromTxt(txt("v" to "1", "name" to "Alameda", "url" to "$PAIR_URL/")))
        assertEquals("no name: the host", "alameda.example.org", PairingContract.siteFromTxt(txt("v" to "1", "url" to PAIR_URL))!!.name)
        assertNull("other version", PairingContract.siteFromTxt(txt("v" to "2", "url" to PAIR_URL)))
        assertNull("no version", PairingContract.siteFromTxt(txt("url" to PAIR_URL)))
        assertNull("plain http", PairingContract.siteFromTxt(txt("v" to "1", "url" to "http://10.0.0.15/api/adonis-open/pair")))
        assertNull("credentials", PairingContract.siteFromTxt(txt("v" to "1", "url" to "https://u:p@h/pair")))
        assertNull("query", PairingContract.siteFromTxt(txt("v" to "1", "url" to "$PAIR_URL?x=1")))
        assertNull("no url", PairingContract.siteFromTxt(txt("v" to "1", "url" to null)))
    }

    @Test
    fun requestsCarryTheDeviceAndKeepTheSecretInTheBody() {
        val start = PairingContract.startRequest(site, "dev-1", "Google Pixel 8")
        assertEquals("$PAIR_URL/start", start.url)
        val body = JSONObject(start.body)
        assertEquals("dev-1", body.getString("device_id"))
        assertEquals("android", body.getString("platform"))
        assertEquals("Google Pixel 8", body.getString("device_name"))

        val pairing = (PairingContract.classifyStart(site, HttpAnswer(201, startBody())) as StartOutcome.Started).pairing
        val poll = PairingContract.pollRequest(pairing, "dev-1")
        assertEquals("$PAIR_URL/poll", poll.url)
        assertFalse("the secret is not in the URL", poll.url.contains(SECRET))
        assertEquals(SECRET, JSONObject(poll.body).getString("pairing_id"))
        assertFalse("the secret never reaches a log", pairing.toString().contains(SECRET))
    }

    @Test
    fun classifiesStartAnswers() {
        val started = PairingContract.classifyStart(site, HttpAnswer(201, startBody(interval = 0, expiresIn = 99999)))
        assertTrue(started is StartOutcome.Started)
        val pairing = (started as StartOutcome.Started).pairing
        assertEquals("482913", pairing.code)
        assertEquals(2, pairing.intervalSeconds)
        assertEquals(3600, pairing.expiresInSeconds)
        assertTrue("poll URL on another host", PairingContract.classifyStart(site, HttpAnswer(201, startBody(pollUrl = "https://evil.example/poll"))) is StartOutcome.Unreachable)
        assertTrue("poll URL on another port", PairingContract.classifyStart(site, HttpAnswer(201, startBody(pollUrl = "https://alameda.example.org/poll"))) is StartOutcome.Unreachable)
        assertTrue("bad code", PairingContract.classifyStart(site, HttpAnswer(201, startBody(code = "12ab56"))) is StartOutcome.Unreachable)
        assertTrue("too short", PairingContract.classifyStart(site, HttpAnswer(201, startBody(code = "48"))) is StartOutcome.Unreachable)
        val short = PairingContract.classifyStart(site, HttpAnswer(201, startBody(code = "482")))
        assertEquals("Adonis's 3 digits", "482", (short as StartOutcome.Started).pairing.code)
        assertEquals("482", PairingContract.displayCode("482"))
        assertEquals("482 913", PairingContract.displayCode("482913"))
        assertTrue(PairingContract.classifyStart(site, HttpAnswer(201, "<html>")) is StartOutcome.Unreachable)
        assertEquals(StartOutcome.TooMany(30), PairingContract.classifyStart(site, HttpAnswer(429, "{}", "30")))
        assertTrue(PairingContract.classifyStart(site, HttpAnswer(400, "{}")) is StartOutcome.Unreachable)
    }

    @Test
    fun classifiesPollAnswers() {
        val pairing = (PairingContract.classifyStart(site, HttpAnswer(201, startBody())) as StartOutcome.Started).pairing
        fun poll(status: Int, body: String?, retryAfter: String? = null) = PairingContract.classifyPoll(pairing, HttpAnswer(status, body, retryAfter))
        assertEquals(PollOutcome.Pending, poll(202, """{"status":"pending"}"""))
        val approved = poll(200, """{"claim_url":"$CLAIM"}""")
        assertTrue(approved is PollOutcome.Approved)
        assertEquals(CLAIM, (approved as PollOutcome.Approved).claimUrl)
        assertFalse("the claim never reaches a log", approved.toString().contains("q2JxYk3a"))
        assertTrue("claim on another host", poll(200, """{"claim_url":"https://evil.example/api/adonis-open/claim/q2JxYk3aBcDeFgHiJkLmNo"}""") is PollOutcome.Unreachable)
        assertTrue("no claim", poll(200, "{}") is PollOutcome.Unreachable)
        assertEquals(PollOutcome.Declined, poll(410, """{"code":"pair.declined"}"""))
        assertEquals(PollOutcome.Expired, poll(410, """{"code":"pair.expired"}"""))
        assertEquals(PollOutcome.Expired, poll(404, """{"code":"pair.unknown"}"""))
        assertEquals(PollOutcome.OtherDevice, poll(409, """{"code":"pair.other_device"}"""))
        assertEquals(PollOutcome.TooMany(9), poll(429, "{}", "9"))
        assertTrue(poll(502, null) is PollOutcome.Unreachable)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PairingClientTest {
    private val site = DiscoveredSite("Alameda", PAIR_URL)

    private class Scripted(vararg answers: HttpAnswer?) : WebhookTransport {
        val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<WebhookRequest>()
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            return (if (queue.isEmpty()) HttpAnswer(202, """{"status":"pending"}""") else queue.removeFirst())
                ?: throw IOException("down")
        }
    }

    private fun pairing(interval: Int = 3, expiresIn: Int = 600) =
        (PairingContract.classifyStart(site, HttpAnswer(201, startBody(interval = interval, expiresIn = expiresIn))) as StartOutcome.Started).pairing

    @Test
    fun pollsAtTheIntervalUntilApproved() = runTest {
        val transport = Scripted(HttpAnswer(202, "{}"), null, HttpAnswer(202, "{}"), HttpAnswer(200, """{"claim_url":"$CLAIM"}"""))
        val client = PairingClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        val result = async { client.waitForApproval(pairing(interval = 3), "d") }
        runCurrent(); assertEquals(1, transport.requests.size)
        advanceTimeBy(2_999); assertEquals(1, transport.requests.size)
        advanceTimeBy(2); assertEquals("an unreachable site is polled again", 2, transport.requests.size)
        advanceTimeBy(3_000); assertEquals(3, transport.requests.size)
        advanceTimeBy(3_000)
        val outcome = result.await()
        assertTrue(outcome is PollOutcome.Approved)
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun honoursRetryAfterOnTooMany() = runTest {
        val transport = Scripted(HttpAnswer(429, "{}", "20"), HttpAnswer(410, """{"code":"pair.declined"}"""))
        val client = PairingClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        val result = async { client.waitForApproval(pairing(interval = 3), "d") }
        runCurrent(); assertEquals(1, transport.requests.size)
        advanceTimeBy(19_999); assertEquals(1, transport.requests.size)
        advanceTimeBy(2)
        assertEquals(PollOutcome.Declined, result.await())
    }

    @Test
    fun stopsOnFinalAnswers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(PollOutcome.OtherDevice, PairingClient(Scripted(HttpAnswer(409, "{}")), dispatcher) { _, _ -> }.waitForApproval(pairing(), "d"))
        assertEquals(PollOutcome.Expired, PairingClient(Scripted(HttpAnswer(404, "{}")), dispatcher) { _, _ -> }.waitForApproval(pairing(), "d"))
    }

    @Test
    fun givesUpWhenThePairingRunsOut() = runTest {
        val transport = Scripted()
        val client = PairingClient(transport, StandardTestDispatcher(testScheduler)) { _, _ -> }
        assertEquals(PollOutcome.Expired, client.waitForApproval(pairing(interval = 5, expiresIn = 60), "d"))
        // 60 s + 30 s grace at one poll every 5 s: the first poll and 18 more.
        assertEquals(19, transport.requests.size)
    }

    @Test
    fun startAndPollOverRealHttp() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/api/adonis-open/pair").toString().trimEnd('/')
            // MockWebServer is plain http: build the site by hand (the contract only accepts https from mDNS).
            val localSite = DiscoveredSite("Test", base)
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"pairing_id":"$SECRET","code":"123456","poll_url":"$base/poll","expires_in":600,"interval":3}"""))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"pending"}"""))
            val client = PairingClient(HttpUrlConnectionTransport(), StandardTestDispatcher(testScheduler)) { _, _ -> }
            val started = client.start(localSite, "dev-9", "Pixel")
            // The poll URL is http here, so the contract refuses it: that is the https rule at work.
            assertTrue(started is StartOutcome.Unreachable)
            val start = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", start.method)
            assertEquals("/api/adonis-open/pair/start", start.path)
            assertEquals("dev-9", JSONObject(start.body.readUtf8()).getString("device_id"))
        } finally {
            server.shutdown()
        }
    }
}
