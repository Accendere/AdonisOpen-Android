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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookEngineTest {

    private class MemoryStorage(override var config: WebhookConfig?) : WebhookStorage {
        override var status: WebhookStatus = WebhookStatus.ACTIVE
        override val deviceId: String = "device-1"
        override var eventQueue: List<String> = emptyList()
        override var pendingAcks: Set<String> = emptySet()
    }

    /** Answers with the queued statuses (then 200) and records every request. */
    private class FakeTransport : WebhookTransport {
        val requests = mutableListOf<WebhookRequest>()
        val answers = ArrayDeque<HttpAnswer?>() // null = no answer (network down)
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            val answer = if (answers.isEmpty()) HttpAnswer(200, """{"success":true,"messages":[]}""") else answers.removeFirst()
            return answer ?: throw java.io.IOException("network down")
        }

        fun events() = requests.map { it.url.substringAfterLast('/') }
        fun states() = requests.filter { it.url.endsWith("/state") }.map { JSONObject(it.body).getBoolean("enabled") }
    }

    private val config = WebhookConfig("https://site.example/hooks", "pedro", "secret-token")
    private val logs = mutableListOf<String>()
    private val statuses = mutableListOf<WebhookStatus>()

    private fun TestScope.engine(storage: WebhookStorage, transport: WebhookTransport): WebhookEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return WebhookEngine(storage, transport, dispatcher, dispatcher,
            logger = { _, message -> logs += message },
            onStatusChanged = { statuses += it })
    }

    private fun TestScope.advanceSeconds(seconds: Long) {
        testScheduler.advanceTimeBy(seconds * 1000)
        runCurrent()
    }

    @Test
    fun appStartSendsTheCurrentState() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config), transport)
        engine.onAppStart(enabled = true)
        runCurrent()
        assertEquals(listOf(true), transport.states())
        val body = JSONObject(transport.requests.single().body)
        assertEquals("device-1", body.getString("device_id"))
        assertNull(engine.pendingState)
        engine.shutdown()
    }

    @Test
    fun withoutWebhooksNothingIsCalled() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(null), transport)
        engine.onAppStart(true)
        engine.onSwitchChanged(false)
        engine.setForeground(true)
        advanceSeconds(3600)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun retriesWithBackoffThenSucceeds() = runTest {
        val transport = FakeTransport()
        transport.answers.addAll(listOf(null, HttpAnswer(503, null), HttpAnswer(500, "oops"), null, null))
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(true)
        runCurrent()
        assertEquals(1, transport.requests.size)
        advanceSeconds(9); assertEquals(1, transport.requests.size)
        advanceSeconds(1); assertEquals(2, transport.requests.size)    // after 10 s
        advanceSeconds(30); assertEquals(3, transport.requests.size)   // after 30 s more
        advanceSeconds(120); assertEquals(4, transport.requests.size)  // after 2 min more
        advanceSeconds(599); assertEquals(4, transport.requests.size)
        advanceSeconds(1); assertEquals(5, transport.requests.size)    // then every 10 min
        advanceSeconds(600); assertEquals(6, transport.requests.size)  // 6th answer is 200
        assertNull(engine.pendingState)
        advanceSeconds(3600); assertEquals(6, transport.requests.size)
    }

    @Test
    fun onlyTheLatestStateIsSentAfterFlipsWhileOffline() = runTest {
        val transport = FakeTransport()
        transport.answers.add(null)
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(true)
        runCurrent()
        // Offline: the person flips the switch three times.
        transport.answers.add(null)
        engine.onSwitchChanged(false)
        runCurrent()
        engine.onSwitchChanged(true)
        engine.onSwitchChanged(false)
        // ...and the two pending sends were replaced, not queued: the next try carries the final state only.
        val before = transport.requests.size
        advanceSeconds(10)
        assertEquals(before + 1, transport.requests.size)
        assertEquals(false, transport.states().last())
        assertNull(engine.pendingState)
        advanceSeconds(3600)
        assertEquals(before + 1, transport.requests.size)
    }

    @Test
    fun networkBackResendsAFailedStateRightAway() = runTest {
        val transport = FakeTransport()
        transport.answers.add(null)
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(false)
        runCurrent()
        assertTrue(engine.waitingForRetry)
        engine.onNetworkAvailable()
        runCurrent()
        assertEquals(listOf(false, false), transport.states())
        assertFalse(engine.waitingForRetry)
        // Nothing pending any more: another network change sends nothing.
        engine.onNetworkAvailable()
        runCurrent()
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun unauthorizedStopsEverythingUntilANewLink() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(401, """{"success":false,"code":"webhook.unauthorized"}"""))
        val storage = MemoryStorage(config.copy(pollMode = PollMode.ALWAYS))
        val engine = engine(storage, transport)
        engine.onAppStart(true)
        runCurrent()
        assertEquals(WebhookStatus.UNAUTHORIZED, storage.status)
        assertEquals(listOf(WebhookStatus.UNAUTHORIZED), statuses)
        val callsSoFar = transport.requests.size
        engine.onSwitchChanged(false)
        engine.onAppStart(false) // a restart doesn't retry a 401 either
        advanceSeconds(3600)
        assertEquals(callsSoFar, transport.requests.size)

        val newConfig = config.copy(token = "new-token")
        engine.onLinkApplied(newConfig, enabled = false)
        runCurrent()
        assertEquals(WebhookStatus.ACTIVE, storage.status)
        assertEquals("Bearer new-token", transport.requests.last().headers["Authorization"])
        assertEquals(false, transport.states().last())
        engine.shutdown()
    }

    @Test
    fun notFoundStopsCallsButTheNextAppStartTriesOnce() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(404, """{"success":false,"code":"webhook.not_found"}"""))
        val storage = MemoryStorage(config)
        val engine = engine(storage, transport)
        engine.onSwitchChanged(true)
        runCurrent()
        assertEquals(WebhookStatus.NOT_FOUND, storage.status)
        engine.onSwitchChanged(false)
        advanceSeconds(3600)
        assertEquals(1, transport.requests.size)

        engine.onAppStart(false)
        runCurrent()
        assertEquals(2, transport.requests.size)
        assertEquals(WebhookStatus.ACTIVE, storage.status)
    }

    @Test
    fun badRequestIsNotResent() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(400, """{"success":false,"code":"webhook.bad_request","message":"enabled must be a boolean"}"""))
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(true)
        advanceSeconds(3600)
        assertEquals(1, transport.requests.size)
        assertTrue(logs.any { it.contains("enabled must be a boolean") })
        assertFalse(logs.any { it.contains("secret-token") })
    }

    @Test
    fun tooManyWaitsForRetryAfter() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(429, "{}", "90"))
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(true)
        runCurrent()
        advanceSeconds(89); assertEquals(1, transport.requests.size)
        advanceSeconds(1); assertEquals(2, transport.requests.size)
    }

    @Test
    fun linkWithoutWebhooksClearsThem() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStorage(config)
        val engine = engine(storage, transport)
        engine.onLinkApplied(null, enabled = true)
        engine.onSwitchChanged(false)
        advanceSeconds(60)
        assertNull(storage.config)
        assertTrue(transport.requests.isEmpty())
        assertEquals("device-1", storage.deviceId)
    }

    @Test
    fun pollAlwaysRunsEveryInterval() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config.copy(pollMode = PollMode.ALWAYS, pollingIntervalSeconds = 30)), transport)
        engine.onAppStart(true)
        runCurrent()
        assertEquals(listOf("state", "poll"), transport.events())
        advanceSeconds(30)
        advanceSeconds(30)
        assertEquals(listOf("state", "poll", "poll", "poll"), transport.events())
        val poll = JSONObject(transport.requests.last().body)
        assertEquals("pedro", poll.getString("user"))
        assertEquals("device-1", poll.getString("device_id"))
        engine.shutdown()
    }

    @Test
    fun pollForegroundOnlyFollowsTheScreen() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config.copy(pollMode = PollMode.FOREGROUND_ONLY, pollingIntervalSeconds = 10)), transport)
        engine.onAppStart(true)
        advanceSeconds(60)
        assertEquals(listOf("state"), transport.events()) // in the background: no polls

        engine.setForeground(true)
        runCurrent()
        advanceSeconds(10)
        assertEquals(listOf("state", "poll", "poll"), transport.events())

        engine.setForeground(false)
        advanceSeconds(120)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun pollNeverDoesNotPoll() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config.copy(pollMode = PollMode.NEVER)), transport)
        engine.onAppStart(true)
        engine.setForeground(true)
        advanceSeconds(600)
        assertEquals(listOf("state"), transport.events())
    }

    @Test
    fun pollUnauthorizedStopsStateToo() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStorage(config.copy(pollMode = PollMode.ALWAYS, pollingIntervalSeconds = 10))
        val engine = engine(storage, transport)
        engine.onAppStart(true)
        runCurrent() // state 200, poll 200
        transport.answers.add(HttpAnswer(401, "{}"))
        advanceSeconds(10) // poll -> 401
        assertEquals(WebhookStatus.UNAUTHORIZED, storage.status)
        val callsSoFar = transport.requests.size
        engine.onSwitchChanged(false)
        advanceSeconds(600)
        assertEquals(callsSoFar, transport.requests.size)
    }

    @Test
    fun pollMessagesAreHandedOver() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(200, "{}")) // state
        transport.answers.add(HttpAnswer(200, """{"success":true,"messages":[{"type":"ring"}]}"""))
        val received = mutableListOf<JSONObject>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = WebhookEngine(MemoryStorage(config.copy(pollMode = PollMode.ALWAYS)), transport, dispatcher, dispatcher,
            logger = { _, _ -> }, onPollMessages = { received += it })
        engine.onAppStart(true)
        runCurrent()
        assertEquals("ring", received.single().getString("type"))
        engine.shutdown()
    }

    private val edited = config.copy(events = setOf(WebhookEvents.DOOR_ACTION, WebhookEvents.CALLS_PAUSED, WebhookEvents.CALL_SUMMARY))

    @Test
    fun anEditOnTheSameServerKeepsTheKeyAndTellsTheSiteWhatIsOff() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStorage(edited)
        val engine = engine(storage, transport)
        val result = engine.applyLocalEdit(setOf(WebhookEvents.CALL_SUMMARY), "https://site.example/hooks/", enabled = true)
        runCurrent()
        assertEquals(WebhookEngine.LocalEdit.SAVED, result)
        val saved = storage.config!!
        assertEquals("secret-token", saved.token)
        assertEquals(setOf(WebhookEvents.CALL_SUMMARY), saved.events)
        assertEquals(edited.events, saved.siteEvents)
        assertTrue(saved.editedLocally)
        val report = transport.requests.single()
        assertEquals("https://site.example/hooks/webhook_settings", report.url)
        val body = JSONObject(report.body)
        assertEquals("[\"call_summary\"]", body.getJSONArray("events").toString())
        assertEquals("[\"calls_paused\",\"door_action\"]", body.getJSONArray("events_off").toString())
        assertEquals(false, body.getBoolean("leaving"))
        engine.shutdown()
    }

    @Test
    fun movingToAnotherServerTellsTheOldOneAndNeverSendsItTheKey() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStorage(edited)
        storage.eventQueue = listOf("""{"event":"door_action","fields":{},"at":"2026-11-12T10:00:00Z"}""")
        storage.status = WebhookStatus.UNAUTHORIZED
        val engine = engine(storage, transport)
        val result = engine.applyLocalEdit(edited.sending, "https://mine.example:8443/in", enabled = true)
        runCurrent()
        assertEquals(WebhookEngine.LocalEdit.SAVED_KEY_REMOVED, result)
        assertEquals("", storage.config!!.token)
        assertEquals(WebhookAuth.NONE, storage.config!!.auth)
        assertTrue("queued events for the old site are dropped", storage.eventQueue.isEmpty())
        val report = transport.requests.first { it.url.endsWith("/webhook_settings") }
        assertTrue("the report goes to the old site", report.url.startsWith("https://site.example/"))
        assertEquals(true, JSONObject(report.body).getBoolean("leaving"))
        transport.requests.filter { it.url.startsWith("https://mine.example") }.forEach {
            assertFalse("no key to the new place", it.headers.containsKey("Authorization"))
        }
        engine.shutdown()
    }

    @Test
    fun aSiteThatDoesNotKnowTheReportChangesNothing() = runTest {
        val transport = FakeTransport()
        transport.answers += HttpAnswer(404, "{}")
        val storage = MemoryStorage(edited)
        val engine = engine(storage, transport)
        engine.applyLocalEdit(edited.sending, "https://site.example/hooks", enabled = true)
        runCurrent()
        assertEquals(WebhookStatus.ACTIVE, storage.status)
        engine.shutdown()
    }

    @Test
    fun aBadAddressChangesNothing() = runTest {
        val storage = MemoryStorage(edited)
        val engine = engine(storage, FakeTransport())
        assertEquals(WebhookEngine.LocalEdit.INVALID_ADDRESS, engine.applyLocalEdit(emptySet(), "http://site.example/hooks", enabled = true))
        assertEquals(edited, storage.config)
        engine.shutdown()
    }

    @Test
    fun aNewLinkAfterLocalChangesTellsTheSiteAllIsBack() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStorage(edited.copy(events = setOf(WebhookEvents.CALL_SUMMARY), siteEvents = edited.events, editedLocally = true))
        val engine = engine(storage, transport)
        engine.onLinkApplied(edited, enabled = true)
        runCurrent()
        assertFalse(storage.config!!.editedLocally)
        val report = JSONObject(transport.requests.single { it.url.endsWith("/webhook_settings") }.body)
        assertEquals(0, report.getJSONArray("events_off").length())
        engine.shutdown()
    }

    @Test
    fun theTokenIsOnlyKeptOnTheSameServer() {
        assertTrue(WebhookConfig.sameServer("https://a.example/x", "https://A.example:443/y"))
        assertFalse(WebhookConfig.sameServer("https://a.example/x", "https://a.example:8124/x"))
        assertFalse(WebhookConfig.sameServer("https://a.example/x", "https://b.example/x"))
        assertEquals(null, WebhookConfig.normalizedHttpsUrl("https://user:pw@a.example/x"))
        assertEquals("https://a.example/x", WebhookConfig.normalizedHttpsUrl(" https://a.example/x/ "))
    }
}
