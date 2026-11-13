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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The events list, the event queue, poll acks, who_answered, and the call narration. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenEventsTest {

    private class MemoryStorage(override var config: WebhookConfig?) : WebhookStorage {
        override var status: WebhookStatus = WebhookStatus.ACTIVE
        override val deviceId: String = "device-1"
        override var eventQueue: List<String> = emptyList()
        override var pendingAcks: Set<String> = emptySet()
    }

    private class FakeTransport : WebhookTransport {
        val requests = mutableListOf<WebhookRequest>()
        val answers = ArrayDeque<HttpAnswer?>()
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            val answer = if (answers.isEmpty()) HttpAnswer(200, """{"success":true,"messages":[]}""") else answers.removeFirst()
            return answer ?: throw java.io.IOException("network down")
        }

        fun events() = requests.map { it.url.substringAfterLast('/') }
        fun bodies() = requests.map { JSONObject(it.body) }
    }

    private fun link(extra: String) = JSONObject(
        """{"webhook_baseurl":"https://site.example/hooks","webhook_user":"pedro","webhook_token":"tok"$extra}"""
    )

    private val events = setOf("calls_paused", "calls_resumed", "call_ringing", "door_action", "call_summary", "registration")
    private val config = WebhookConfig("https://site.example/hooks", "pedro", "secret-token", events = events)

    private fun TestScope.engine(storage: WebhookStorage, transport: WebhookTransport, messages: MutableList<JSONObject> = mutableListOf()) =
        StandardTestDispatcher(testScheduler).let { d ->
            WebhookEngine(storage, transport, d, d, logger = { _, _ -> }, onPollMessages = { messages += it },
                appVersion = "1.5.0", clock = { "2026-11-08T12:00:00Z" })
        }

    private fun TestScope.advanceSeconds(seconds: Long) {
        testScheduler.advanceTimeBy(seconds * 1000)
        runCurrent()
    }

    // ---------- the link ----------

    @Test
    fun eventsListIsReadAndUnknownNamesIgnored() {
        val cfg = WebhookConfig.fromLinkJson(link(""","webhook_events":["calls_paused","call_summary","POLL","teleport"]"""))!!
        assertEquals(setOf("calls_paused", "call_summary", "poll"), cfg.events)
        assertEquals(PollMode.ALWAYS, cfg.pollMode)
        assertTrue(cfg.sends("call_summary"))
        assertFalse(cfg.sends("state"))
        assertFalse(cfg.sends("call_ringing"))
        assertTrue(cfg.reportsCalls)
    }

    @Test
    fun pollForegroundEventAndListOverridesOldPollField() {
        val cfg = WebhookConfig.fromLinkJson(link(""","use_webhook_to_poll":"always","webhook_events":["poll_foreground"]"""))!!
        assertEquals(PollMode.FOREGROUND_ONLY, cfg.pollMode)
        val none = WebhookConfig.fromLinkJson(link(""","use_webhook_to_poll":"always","webhook_events":[]"""))!!
        assertEquals(PollMode.NEVER, none.pollMode)
    }

    @Test
    fun linkWithoutListKeepsTheOldBehaviour() {
        val cfg = WebhookConfig.fromLinkJson(link(""","use_webhook_to_poll":"foreground_only""""))!!
        assertNull(cfg.events)
        assertTrue(cfg.sends("state"))
        assertFalse(cfg.sends("call_summary"))
        assertFalse(cfg.reportsCalls)
        assertEquals(PollMode.FOREGROUND_ONLY, cfg.pollMode)
    }

    @Test
    fun authNoneNeedsNoTokenAndSendsNoHeader() {
        val raw = JSONObject("""{"webhook_baseurl":"https://my.example/h","webhook_user":"me","webhook_auth":"none","webhook_events":["call_summary"]}""")
        val cfg = WebhookConfig.fromLinkJson(raw)!!
        assertEquals(WebhookAuth.NONE, cfg.auth)
        val request = WebhookProtocol.eventRequest(cfg, "call_summary", JSONObject(), WebhookMeta("d", "1", "t"))
        assertFalse(request.headers.containsKey("Authorization"))
        assertNull(WebhookConfig.fromLinkJson(JSONObject("""{"webhook_baseurl":"https://my.example/h","webhook_user":"me","webhook_auth":"basic"}""")))
    }

    // ---------- the switch ----------

    @Test
    fun switchUsesPausedResumedWhenListed() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config), transport)
        engine.onSwitchChanged(false)
        runCurrent()
        engine.onSwitchChanged(true)
        runCurrent()
        assertEquals(listOf("calls_paused", "calls_resumed"), transport.events())
        val body = transport.bodies().last()
        assertEquals("1.5.0", body.getString("app_version"))
        assertEquals("2026-11-08T12:00:00Z", body.getString("at"))
        assertEquals("Bearer secret-token", transport.requests.last().headers["Authorization"])
        engine.shutdown()
    }

    @Test
    fun switchSendsNothingWhenNeitherIsListed() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config.copy(events = setOf("call_summary"))), transport)
        engine.onSwitchChanged(false)
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        assertNull(engine.pendingState)
    }

    // ---------- the queue ----------

    @Test
    fun eventsAreSentInOrderAndOnlyWhenListed() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config), transport)
        engine.sendEvent("call_ringing", JSONObject().put("call_id", "c1"))
        engine.sendEvent("call_answered", JSONObject().put("call_id", "c1")) // not listed
        engine.sendEvent("call_summary", JSONObject().put("call_id", "c1"))
        runCurrent()
        assertEquals(listOf("call_ringing", "call_summary"), transport.events())
        assertEquals("c1", transport.bodies()[0].getString("call_id"))
        assertEquals("pedro", transport.bodies()[0].getString("user"))
    }

    @Test
    fun failedEventIsRetriedBeforeTheNextAndSurvivesInStorage() = runTest {
        val transport = FakeTransport()
        transport.answers.add(null)
        val storage = MemoryStorage(config)
        val engine = engine(storage, transport)
        engine.sendEvent("call_ringing", JSONObject().put("n", 1))
        engine.sendEvent("call_summary", JSONObject().put("n", 2))
        runCurrent()
        assertEquals(1, transport.requests.size)
        assertEquals(2, storage.eventQueue.size)
        assertTrue(engine.eventsWaitingForRetry)
        engine.onNetworkAvailable() // network back: retried at once
        runCurrent()
        assertEquals(listOf("call_ringing", "call_ringing", "call_summary"), transport.events())
        assertTrue(storage.eventQueue.isEmpty())
    }

    @Test
    fun appBugDropsOnlyThatEvent() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(400, """{"success":false,"code":"webhook.bad_request"}"""))
        val engine = engine(MemoryStorage(config), transport)
        engine.sendEvent("call_ringing", JSONObject())
        engine.sendEvent("door_action", JSONObject())
        runCurrent()
        assertEquals(listOf("call_ringing", "door_action"), transport.events())
    }

    @Test
    fun unauthorizedEventStopsEverything() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(401, null))
        val storage = MemoryStorage(config)
        val engine = engine(storage, transport)
        engine.sendEvent("call_ringing", JSONObject())
        runCurrent()
        assertEquals(WebhookStatus.UNAUTHORIZED, storage.status)
        engine.sendEvent("door_action", JSONObject())
        advanceSeconds(3600)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun queueIsCappedAndClearedByANewLink() = runTest {
        val transport = FakeTransport()
        transport.answers.addAll(List(300) { null })
        val storage = MemoryStorage(config)
        val engine = engine(storage, transport)
        repeat(WebhookEngine.MAX_QUEUED_EVENTS + 5) { engine.sendEvent("call_ringing", JSONObject().put("n", it)) }
        runCurrent()
        assertEquals(WebhookEngine.MAX_QUEUED_EVENTS, storage.eventQueue.size)
        assertEquals(5, JSONObject(storage.eventQueue.first()).getJSONObject("fields").getInt("n"))
        engine.onLinkApplied(config, enabled = true)
        runCurrent()
        assertTrue(storage.eventQueue.isEmpty())
        engine.shutdown()
    }

    // ---------- poll messages ----------

    @Test
    fun pollMessagesAreAcknowledgedInTheNextPoll() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(200, """{"success":true}""")) // calls_resumed at app start
        transport.answers.add(HttpAnswer(200, """{"success":true,"messages":[{"id":"m1","type":"config_update","claim_url":"https://site.example/c/x"},{"id":"m2","type":"future"}]}"""))
        val received = mutableListOf<JSONObject>()
        val storage = MemoryStorage(config.copy(pollMode = PollMode.ALWAYS, pollingIntervalSeconds = 10))
        val engine = engine(storage, transport, received)
        try {
        engine.onAppStart(true)
        runCurrent()
        assertEquals(2, received.size)
        assertEquals(setOf("m1", "m2"), storage.pendingAcks)
        advanceSeconds(10)
        val secondPoll = transport.requests.last { it.url.endsWith("/poll") }
        assertEquals(listOf("m1", "m2"), JSONArray(JSONObject(secondPoll.body).getJSONArray("ack").toString()).let { a -> (0 until a.length()).map { a.getString(it) } }.sorted())
        assertTrue(storage.pendingAcks.isEmpty())
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun configUpdateMessageMustStayOnTheWebhookHost() {
        val ok = PollMessage.parse(JSONObject("""{"id":"m","type":"config_update","claim_url":"https://site.example/api/c/abc","summary":"2 doors"}"""), "site.example")
        assertTrue(ok is PollMessage.ConfigUpdate)
        assertEquals("2 doors", (ok as PollMessage.ConfigUpdate).summary)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"config_update","claim_url":"https://evil.example/c/abc"}"""), "site.example") is PollMessage.Ignored)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"config_update","claim_url":"http://site.example/c/abc"}"""), "site.example") is PollMessage.Ignored)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"reboot"}"""), "site.example") is PollMessage.Ignored)
    }

    // ---------- who answered ----------

    @Test
    fun whoAnsweredReturnsTheName() = runTest {
        val transport = FakeTransport()
        transport.answers.add(HttpAnswer(200, """{"success":true,"answered_by":"Miguel"}"""))
        val engine = engine(MemoryStorage(config), transport)
        assertEquals("Miguel", engine.whoAnswered("c1", "Porta"))
        val body = transport.bodies().single()
        assertEquals("c1", body.getString("call_id"))
        assertEquals("Porta", body.getString("door"))
        assertTrue(transport.requests.single().url.endsWith("/who_answered"))
    }

    @Test
    fun whoAnsweredIsNotAskedWithoutCallEventsAndNullIsNull() = runTest {
        val transport = FakeTransport()
        val engine = engine(MemoryStorage(config.copy(events = setOf("calls_paused"))), transport)
        assertNull(engine.whoAnswered("c1", null))
        assertTrue(transport.requests.isEmpty())
        transport.answers.add(HttpAnswer(200, """{"success":true,"answered_by":null}"""))
        val engine2 = engine(MemoryStorage(config), transport)
        assertNull(engine2.whoAnswered("c1", null))
    }

    // ---------- narration ----------

    private class Clock(var ms: Long = 1_762_603_200_000L)

    private fun reporter(clock: Clock, sent: MutableList<Pair<String, JSONObject>>) =
        CallReporter({ _, e, f, _ -> sent += e to f }, nowMs = { clock.ms }, zone = ZoneOffset.UTC)

    @Test
    fun answeredCallNarratesEveryStepAndASummary() {
        val clock = Clock()
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val r = reporter(clock, sent)
        r.onIncoming("c1", "Porta", "sip:porta@site")
        clock.ms += 3_000; r.onPreview("c1")
        clock.ms += 1_000; r.onDoorAction("c1", "action_lightup", "5", early = true, sent = true)
        clock.ms += 2_000; r.onAnswered("c1")
        clock.ms += 20_000; r.onEnded("c1", CallOutcome.ANSWERED, byLocal = true)
        assertEquals(listOf("call_ringing", "call_preview", "door_action", "call_answered", "call_ended", "call_summary"), sent.map { it.first })
        val action = sent[2].second
        assertEquals("early", action.getString("phase"))
        assertEquals("5", action.getString("code"))
        assertEquals("Porta", action.getString("door"))
        val summary = sent.last().second
        assertEquals("answered", summary.getString("outcome"))
        assertEquals(6.0, summary.getDouble("ring_seconds"), 0.01)
        assertEquals(20.0, summary.getDouble("talk_seconds"), 0.01)
        assertEquals(5, summary.getJSONArray("steps").length())
        assertEquals("local", sent[4].second.getString("by"))
        assertFalse(r.isTracking("c1"))
    }

    @Test
    fun silentOnlyCallIsPreviewed() {
        val clock = Clock()
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val r = reporter(clock, sent)
        r.onIncoming("c1", "Porta", null)
        r.onPreview("c1")
        r.onDoorAction("c1", "action_open_door", "#", early = true, sent = true)
        clock.ms += 30_000
        r.onEnded("c1", CallOutcome.ANSWERED, byLocal = true)
        assertEquals("previewed", sent.last().second.getString("outcome"))
        assertTrue(sent.last().second.isNull("talk_seconds"))
    }

    @Test
    fun refusedCallKeepsItsReason() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val r = reporter(Clock(), sent)
        r.onIncoming("c1", "Porta", null, refused = CallOutcome.REFUSED_PAUSED)
        r.onEnded("c1", CallOutcome.DECLINED, byLocal = true)
        assertEquals("paused", sent.first().second.getString("refused"))
        assertEquals("refused_paused", sent.last().second.getString("outcome"))
    }

    @Test
    fun answeredElsewhereCarriesTheName() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val r = reporter(Clock(), sent)
        r.onIncoming("c1", "Porta", null)
        r.onEnded("c1", CallOutcome.ANSWERED_ELSEWHERE, byLocal = false, answeredBy = "Miguel")
        assertEquals("Miguel", sent.last().second.getString("answered_by"))
        assertEquals("answered_elsewhere", sent.last().second.getString("outcome"))
    }

    @Test
    fun doorActionOutsideACallHasNoCall() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        reporter(Clock(), sent).onDoorAction(null, "action_open_door", "#", early = false, sent = true)
        assertEquals("door_action", sent.single().first)
        assertTrue(sent.single().second.isNull("call_id"))
    }

    @Test
    fun registrationReportsOnlyChanges() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val r = RegistrationReporter({ e, f, _ -> sent += e to f }, zone = ZoneOffset.UTC)
        r.onOk()
        r.onFailed("401 Unauthorized")
        r.onFailed("timeout")
        r.onOk()
        r.onOk()
        assertEquals(listOf("failed", "ok"), sent.map { it.second.getString("state") })
        assertEquals("401 Unauthorized", sent.first().second.getString("reason"))
    }
}
