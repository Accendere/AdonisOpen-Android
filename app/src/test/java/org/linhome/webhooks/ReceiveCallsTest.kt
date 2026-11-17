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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linhome.sites.IncomingDecision
import org.linhome.sites.IncomingGate
import org.linhome.sites.ReceiveCallsPlan
import org.linhome.sites.Site
import org.linhome.sites.SiteRegistry
import org.linhome.sites.SiteStorage
import java.util.concurrent.CountDownLatch

/** `receive_calls`: parsing, what it switches, the call-time poll, and the incoming-call decision around it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveCallsTest {

    private class MemoryStorage(override var config: WebhookConfig?) : WebhookStorage {
        override var status: WebhookStatus = WebhookStatus.ACTIVE
        override val deviceId: String = "device-1"
        override var eventQueue: List<String> = emptyList()
        override var pendingAcks: Set<String> = emptySet()
        override var stateChangedAt: String? = null
    }

    private class MemorySiteStorage : SiteStorage {
        var json: String? = null
        override fun load(): String? = json
        override fun save(json: String) {
            this.json = json
        }
    }

    /** Answers poll with the messages queued for that host (once), everything else 200; records every request. */
    private class FakeTransport : WebhookTransport {
        val requests = mutableListOf<WebhookRequest>()
        val pollMessages = mutableMapOf<String, MutableList<JSONObject>>()
        val pollStatus = mutableMapOf<String, Int>()
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            val host = java.net.URI(request.url).host
            if (request.url.endsWith("/poll")) {
                pollStatus[host]?.let { return HttpAnswer(it, null) }
                val messages = pollMessages.remove(host) ?: emptyList<JSONObject>()
                return HttpAnswer(200, JSONObject().put("success", true).put("messages", org.json.JSONArray(messages)).toString())
            }
            return HttpAnswer(200, """{"success":true}""")
        }

        fun to(host: String) = requests.filter { java.net.URI(it.url).host == host }
        fun events(host: String) = to(host).map { it.url.substringAfterLast('/') }
        fun bodies(host: String, event: String) = to(host).filter { it.url.endsWith("/$event") }.map { JSONObject(it.body) }
    }

    private val events = setOf(WebhookEvents.CALLS_PAUSED, WebhookEvents.CALLS_RESUMED, WebhookEvents.CALL_RINGING)
    private fun config(host: String, poll: PollMode = PollMode.FOREGROUND_ONLY) =
        WebhookConfig("https://$host/api/open", "pedro", "token-$host", pollMode = poll, events = events)

    private fun receiveCalls(id: String, enabled: Boolean, by: String? = "Pedro") =
        JSONObject().put("id", id).put("type", "receive_calls").put("enabled", enabled).put("at", "2026-11-14T10:00:00+00:00")
            .also { json -> by?.let { json.put("by", it) } }

    private class Setup(
        val hub: SiteWebhooks,
        val registry: SiteRegistry,
        val transport: FakeTransport,
        val storages: MutableMap<Int, MemoryStorage>,
        var main: Boolean
    ) {
        val gate = IncomingGate(registry, { main }, { hub.canAskBeforeDeclining(it) }, { hub.askBeforeDeclining(it, 2_500) })
        fun site(id: String) = registry.find(id)!!
    }

    /** As the app wires it: a poll's `receive_calls` switches the site whose poll brought it. */
    private fun TestScope.setup(
        main: Boolean = true,
        sites: List<Pair<String, String>> = listOf("alameda_hass" to "alameda.example", "lumiar_hass" to "lumiar.example"),
        poll: PollMode = PollMode.FOREGROUND_ONLY
    ): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = SiteRegistry(MemorySiteStorage())
        val transport = FakeTransport()
        val storages = mutableMapOf<Int, MemoryStorage>()
        sites.forEach { (id, host) ->
            val site = registry.add(id, id.substringBefore('_'))
            storages[site.slot] = MemoryStorage(config(host, poll))
        }
        lateinit var holder: Setup
        val hub = SiteWebhooks(
            registry,
            mainEnabled = { holder.main },
            newEngine = { site: Site ->
                val storage = storages.getOrPut(site.slot) { MemoryStorage(null) }
                WebhookEngine(storage, transport, dispatcher, dispatcher, logger = { _, _ -> },
                    onPollMessages = { messages ->
                        messages.map { PollMessage.parse(it, storage.config?.host) }
                            .filterIsInstance<PollMessage.ReceiveCalls>()
                            .forEach { holder.hub.applyReceiveCalls(registry.bySlot(site.slot)!!.id, it.enabled) }
                    })
            },
            setMainEnabled = { holder.main = it }
        )
        holder = Setup(hub, registry, transport, storages, main)
        return holder
    }

    // --- parsing ---

    @Test
    fun receiveCallsIsParsed() {
        val on = PollMessage.parse(receiveCalls("m_1", true, " Pedro "), "site.example")
        assertEquals(PollMessage.ReceiveCalls("m_1", true, "Pedro", "2026-11-14T10:00:00+00:00"), on)
        val off = PollMessage.parse(receiveCalls("m_2", false, null), "site.example") as PollMessage.ReceiveCalls
        assertFalse(off.enabled)
        assertNull(off.by)
        // The site's host doesn't matter (unlike a claim): the poll answer is the site's own.
        assertTrue(PollMessage.parse(receiveCalls("m_3", true), null) is PollMessage.ReceiveCalls)
    }

    @Test
    fun selfIsOnlyARealTrue() {
        assertFalse((PollMessage.parse(receiveCalls("m_1", true), "s") as PollMessage.ReceiveCalls).self)
        assertTrue((PollMessage.parse(receiveCalls("m_2", true).put("self", true), "s") as PollMessage.ReceiveCalls).self)
        assertFalse((PollMessage.parse(receiveCalls("m_3", true).put("self", "true"), "s") as PollMessage.ReceiveCalls).self)
        assertFalse((PollMessage.parse(receiveCalls("m_4", true).put("self", false), "s") as PollMessage.ReceiveCalls).self)
    }

    @Test
    fun receiveCallsWithoutARealBooleanIsIgnored() {
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"receive_calls"}"""), "s") is PollMessage.Ignored)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"receive_calls","enabled":"true"}"""), "s") is PollMessage.Ignored)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"receive_calls","enabled":1}"""), "s") is PollMessage.Ignored)
        assertTrue(PollMessage.parse(JSONObject("""{"id":"m","type":"receive_calls","enabled":null}"""), "s") is PollMessage.Ignored)
    }

    // --- the plan ---

    private fun registry(vararg sites: Pair<String, Boolean>): SiteRegistry {
        val registry = SiteRegistry(MemorySiteStorage())
        sites.forEach { (id, on) ->
            registry.add(id, null)
            registry.setCallsEnabled(id, on)
        }
        return registry
    }

    @Test
    fun onWithTheMainSwitchOnTurnsOnlyThatSiteOn() {
        val r = registry("a" to false, "b" to true)
        assertEquals(ReceiveCallsPlan(true, mapOf("a" to true)), r.planReceiveCalls("a", true, mainEnabled = true))
        assertEquals(ReceiveCallsPlan(true, emptyMap()), r.planReceiveCalls("b", true, mainEnabled = true))
    }

    @Test
    fun onWithTheMainSwitchOffTurnsMainOnAndEveryOtherSiteOff() {
        val r = registry("a" to true, "b" to true, "c" to false)
        assertEquals(ReceiveCallsPlan(true, mapOf("b" to false)), r.planReceiveCalls("a", true, mainEnabled = false))
        assertEquals(ReceiveCallsPlan(true, mapOf("c" to true, "a" to false, "b" to false)), r.planReceiveCalls("c", true, mainEnabled = false))
    }

    @Test
    fun offTurnsTheSiteOffWhileOtherSitesTakeCalls() {
        val r = registry("a" to true, "b" to true)
        assertEquals(ReceiveCallsPlan(true, mapOf("a" to false)), r.planReceiveCalls("a", false, mainEnabled = true))
    }

    @Test
    fun offForTheOnlySiteTakingCallsTurnsTheMainSwitchOff() {
        assertEquals(ReceiveCallsPlan(false, emptyMap()), registry("a" to true).planReceiveCalls("a", false, mainEnabled = true))
        assertEquals(ReceiveCallsPlan(false, emptyMap()), registry("a" to true, "b" to false).planReceiveCalls("a", false, mainEnabled = true))
    }

    @Test
    fun offWhileTheMainSwitchIsOffOnlyTurnsTheSiteOff() {
        val r = registry("a" to true, "b" to true)
        assertEquals(ReceiveCallsPlan(false, mapOf("a" to false)), r.planReceiveCalls("a", false, mainEnabled = false))
        assertEquals(ReceiveCallsPlan(false, emptyMap()), registry("a" to false).planReceiveCalls("a", false, mainEnabled = false))
        assertNull(r.planReceiveCalls("nope", true, true))
    }

    /** Whatever the start, only the messaging site's effective state may change, and it ends as asked. */
    @Test
    fun noOtherSitesEffectiveStateEverChanges() {
        val states = listOf(true, false)
        for (main in states) for (a in states) for (b in states) for (c in states) for (enabled in states) {
            val r = registry("a" to a, "b" to b, "c" to c)
            val before = r.sites().associate { it.id to r.stateFor(it, main) }
            val plan = r.planReceiveCalls("a", enabled, main)!!
            plan.switches.forEach { (id, on) -> r.setCallsEnabled(id, on) }
            val after = r.sites().associate { it.id to r.stateFor(it, plan.mainEnabled) }
            val case = "main=$main a=$a b=$b c=$c enabled=$enabled"
            assertEquals(case, enabled, after["a"])
            assertEquals(case, before["b"], after["b"])
            assertEquals(case, before["c"], after["c"])
        }
    }

    // --- applying it ---

    @Test
    fun onWhileMainIsOffSwitchesAndOnlyThatSiteHearsWithCauseSite() = runTest {
        val s = setup(main = false)
        s.registry.setCallsEnabled("lumiar_hass", true)
        s.hub.applyReceiveCalls("alameda_hass", true)
        runCurrent()
        assertTrue(s.main)
        assertTrue(s.site("alameda_hass").callsEnabled)
        assertFalse(s.site("lumiar_hass").callsEnabled)
        val resumed = s.transport.bodies("alameda.example", "calls_resumed").single()
        assertEquals("site", resumed.getString("cause"))
        assertTrue(resumed.getBoolean("enabled"))
        assertTrue(s.transport.to("lumiar.example").isEmpty())
    }

    @Test
    fun singleSiteOffTurnsTheMainSwitchOffAndSaysPausedWithCauseSite() = runTest {
        val s = setup(sites = listOf("alameda_hass" to "alameda.example"))
        s.hub.applyReceiveCalls("alameda_hass", false)
        runCurrent()
        assertFalse(s.main)
        assertTrue(s.site("alameda_hass").callsEnabled)
        assertEquals("site", s.transport.bodies("alameda.example", "calls_paused").single().getString("cause"))
        // And back on: main on, the site takes calls again.
        s.hub.applyReceiveCalls("alameda_hass", true)
        runCurrent()
        assertTrue(s.main)
        assertEquals("site", s.transport.bodies("alameda.example", "calls_resumed").single().getString("cause"))
    }

    @Test
    fun aMessageThatChangesNothingStillSettlesOnTheSite() = runTest {
        val s = setup()
        s.hub.applyReceiveCalls("alameda_hass", true)
        runCurrent()
        assertEquals(1, s.transport.bodies("alameda.example", "calls_resumed").size)
    }

    @Test
    fun thePersonsOwnChangeHasNoCause() = runTest {
        val s = setup()
        s.hub.setSiteCallsEnabled("alameda_hass", false)
        runCurrent()
        assertFalse(s.transport.bodies("alameda.example", "calls_paused").single().has("cause"))
    }

    // --- the call-time poll ---

    @Test
    fun pollNowAppliesTheMessagesAndQueuesTheirAcks() = runTest {
        val s = setup()
        s.registry.setCallsEnabled("alameda_hass", false)
        s.transport.pollMessages["alameda.example"] = mutableListOf(receiveCalls("m_1", true))
        val result = async { s.hub.askBeforeDeclining("alameda_hass", 2_500) }
        runCurrent()
        assertTrue(result.await())
        assertTrue(s.site("alameda_hass").callsEnabled)
        assertEquals(setOf("m_1"), s.storages[s.site("alameda_hass").slot]!!.pendingAcks)
    }

    @Test
    fun aSiteThatNeverPollsIsNotAsked() = runTest {
        val s = setup(poll = PollMode.NEVER)
        assertFalse(s.hub.canAskBeforeDeclining("alameda_hass"))
        assertFalse(s.hub.askBeforeDeclining("alameda_hass", 2_500))
        assertTrue(s.transport.requests.isEmpty())
        assertFalse(s.hub.canAskBeforeDeclining(null))
        assertFalse(s.hub.canAskBeforeDeclining("unknown"))
    }

    @Test
    fun aSiteWithoutWorkingWebhooksIsNotAsked() = runTest {
        val s = setup()
        s.storages[s.site("alameda_hass").slot]!!.status = WebhookStatus.UNAUTHORIZED
        assertFalse(s.hub.canAskBeforeDeclining("alameda_hass"))
    }

    @Test
    fun a404ToTheCallTimePollStopsTheSitesCallsLikeAnyPoll() = runTest {
        val s = setup()
        s.transport.pollStatus["alameda.example"] = 404
        val result = async { s.hub.askBeforeDeclining("alameda_hass", 2_500) }
        runCurrent()
        assertFalse(result.await())
        assertEquals(WebhookStatus.NOT_FOUND, s.hub.status("alameda_hass"))
    }

    /** A site that doesn't answer in time: false, and its late answer is still applied. */
    @Test
    fun pollNowTimesOutButStillHandlesALateAnswer() = runTest {
        val release = CountDownLatch(1)
        val requests = mutableListOf<WebhookRequest>()
        val transport = WebhookTransport { request ->
            requests += request
            release.await()
            HttpAnswer(200, JSONObject().put("messages", org.json.JSONArray(listOf(receiveCalls("m_late", true)))).toString())
        }
        val handed = mutableListOf<JSONObject>()
        val storage = MemoryStorage(config("site.example"))
        val main = StandardTestDispatcher(testScheduler)
        val engine = WebhookEngine(storage, transport, main, Dispatchers.IO, logger = { _, _ -> }, onPollMessages = { handed += it })
        val answered = async { engine.pollNow(timeoutMs = 2_500) }
        runCurrent()
        testScheduler.advanceTimeBy(2_600)
        runCurrent()
        assertFalse(answered.await())
        assertTrue(handed.isEmpty())
        release.countDown()
        repeat(100) {
            if (handed.isEmpty()) {
                Thread.sleep(10)
                runCurrent()
            }
        }
        assertEquals("m_late", handed.single().getString("id"))
        assertEquals(setOf("m_late"), storage.pendingAcks)
        assertEquals(1, requests.size)
        engine.shutdown()
    }

    // --- `at` of state reports ---

    private class Clock(var now: String = "2026-11-14T10:00:00+00:00")

    /** Fails the first [failures] sends (no answer), then answers 200; records every request. */
    private class FlakyTransport(var failures: Int = 0) : WebhookTransport {
        val requests = mutableListOf<WebhookRequest>()
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            if (failures > 0) {
                failures--
                throw java.io.IOException("network down")
            }
            return HttpAnswer(200, """{"success":true,"messages":[]}""")
        }

        fun stateAts() = requests.filter { it.url.endsWith("/calls_paused") || it.url.endsWith("/calls_resumed") }
            .map { JSONObject(it.body).getString("at") }
    }

    private fun TestScope.clockedEngine(storage: WebhookStorage, transport: WebhookTransport, clock: Clock): WebhookEngine {
        val d = StandardTestDispatcher(testScheduler)
        return WebhookEngine(storage, transport, d, d, logger = { _, _ -> }, clock = { clock.now })
    }

    @Test
    fun aFreshChangeSendsAndRemembersItsOwnTime() = runTest {
        val clock = Clock("2026-11-14T10:00:00+00:00")
        val storage = MemoryStorage(config("site.example", PollMode.NEVER))
        val transport = FlakyTransport()
        val engine = clockedEngine(storage, transport, clock)
        engine.onSwitchChanged(false)
        runCurrent()
        assertEquals(listOf("2026-11-14T10:00:00+00:00"), transport.stateAts())
        assertEquals("2026-11-14T10:00:00+00:00", storage.stateChangedAt)
        engine.shutdown()
    }

    /** The card switched the phone on at 10:05; an app start at 10:06 repeats the phone's 10:00 state, not a 10:06 one. */
    @Test
    fun anAppStartRepeatsTheStateWithTheTimeOfTheLastRealChange() = runTest {
        val clock = Clock("2026-11-14T10:06:00+00:00")
        val storage = MemoryStorage(config("site.example", PollMode.NEVER)).apply { stateChangedAt = "2026-11-14T10:00:00+00:00" }
        val transport = FlakyTransport()
        val engine = clockedEngine(storage, transport, clock)
        engine.onAppStart(enabled = false)
        runCurrent()
        assertEquals(listOf("2026-11-14T10:00:00+00:00"), transport.stateAts())
        assertEquals("2026-11-14T10:00:00+00:00", storage.stateChangedAt)
        engine.shutdown()
    }

    @Test
    fun withoutAKnownChangeTheReportSaysNow() = runTest {
        val clock = Clock("2026-11-14T10:06:00+00:00")
        val transport = FlakyTransport()
        val engine = clockedEngine(MemoryStorage(config("site.example", PollMode.NEVER)), transport, clock)
        engine.onAppStart(enabled = true)
        runCurrent()
        assertEquals(listOf("2026-11-14T10:06:00+00:00"), transport.stateAts())
        engine.shutdown()
    }

    @Test
    fun retriesKeepTheTimeOfTheChange() = runTest {
        val clock = Clock("2026-11-14T10:00:00+00:00")
        val transport = FlakyTransport(failures = 2)
        val engine = clockedEngine(MemoryStorage(config("site.example", PollMode.NEVER)), transport, clock)
        engine.onSwitchChanged(true, WebhookEngine.CAUSE_SITE)
        runCurrent()
        clock.now = "2026-11-14T10:00:10+00:00"
        testScheduler.advanceTimeBy(10_000); runCurrent()
        clock.now = "2026-11-14T10:01:00+00:00"
        engine.onNetworkAvailable() // resends at once
        runCurrent()
        assertEquals(List(3) { "2026-11-14T10:00:00+00:00" }, transport.stateAts())
        // The cause survives the retries too.
        assertEquals("site", JSONObject(transport.requests.last().body).getString("cause"))
        assertNull(engine.pendingState)
        engine.shutdown()
    }

    @Test
    fun aNewLinkRepeatsTheStateWithTheTimeOfTheLastRealChange() = runTest {
        val clock = Clock("2026-11-14T11:00:00+00:00")
        val storage = MemoryStorage(null).apply { stateChangedAt = "2026-11-14T10:00:00+00:00" }
        val transport = FlakyTransport()
        val engine = clockedEngine(storage, transport, clock)
        engine.onLinkApplied(config("site.example", PollMode.NEVER), enabled = true)
        runCurrent()
        assertEquals(listOf("2026-11-14T10:00:00+00:00"), transport.stateAts())
        engine.shutdown()
    }

    @Test
    fun aSitesReceiveCallsIsAFreshChangeForThatSiteOnly() = runTest {
        val s = setup()
        val alameda = s.storages[s.site("alameda_hass").slot]!!
        val lumiar = s.storages[s.site("lumiar_hass").slot]!!
        s.hub.applyReceiveCalls("alameda_hass", false)
        runCurrent()
        assertNotNull(alameda.stateChangedAt)
        assertEquals(alameda.stateChangedAt, s.transport.bodies("alameda.example", "calls_paused").single().getString("at"))
        assertNull(lumiar.stateChangedAt) // its state didn't change
    }

    // --- the decision ---

    @Test
    fun aPausedSiteThatSwitchedThePhoneOnRingsAfterAsking() = runTest {
        val s = setup()
        s.registry.setCallsEnabled("alameda_hass", false)
        s.transport.pollMessages["alameda.example"] = mutableListOf(receiveCalls("m_1", true))
        val look = s.gate.firstLook(s.site("alameda_hass"))
        assertTrue(look is IncomingGate.FirstLook.AskSite)
        assertEquals(IncomingDecision.REFUSE_SITE_PAUSED, (look as IncomingGate.FirstLook.AskSite).decisionNow)
        val decided = async { s.gate.decideAfterAsking(look.site) }
        runCurrent()
        assertEquals(IncomingDecision.RING to true, decided.await())
        // Only the calling site was asked.
        assertEquals(listOf("poll", "calls_resumed"), s.transport.events("alameda.example"))
        assertTrue(s.transport.to("lumiar.example").isEmpty())
    }

    @Test
    fun aMainSwitchOffPhoneAsksTheDoorsSiteToo() = runTest {
        val s = setup(main = false, sites = listOf("alameda_hass" to "alameda.example"))
        s.transport.pollMessages["alameda.example"] = mutableListOf(receiveCalls("m_1", true))
        val look = s.gate.firstLook(s.site("alameda_hass")) as IncomingGate.FirstLook.AskSite
        assertEquals(IncomingDecision.REFUSE_PAUSED, look.decisionNow)
        val decided = async { s.gate.decideAfterAsking(look.site) }
        runCurrent()
        assertEquals(IncomingDecision.RING, decided.await().first)
        assertTrue(s.main)
    }

    @Test
    fun aSiteWithNothingToSayStillDeclines() = runTest {
        val s = setup()
        s.registry.setCallsEnabled("alameda_hass", false)
        val look = s.gate.firstLook(s.site("alameda_hass")) as IncomingGate.FirstLook.AskSite
        val decided = async { s.gate.decideAfterAsking(look.site) }
        runCurrent()
        assertEquals(IncomingDecision.REFUSE_SITE_PAUSED to true, decided.await())
    }

    @Test
    fun aSiteThatFailsDeclinesAsBefore() = runTest {
        val s = setup()
        s.registry.setCallsEnabled("alameda_hass", false)
        s.transport.pollStatus["alameda.example"] = 503
        val look = s.gate.firstLook(s.site("alameda_hass")) as IncomingGate.FirstLook.AskSite
        val decided = async { s.gate.decideAfterAsking(look.site) }
        runCurrent()
        assertEquals(IncomingDecision.REFUSE_SITE_PAUSED to false, decided.await())
    }

    @Test
    fun aRingingCallOrADoorWithoutSiteAsksNobody() = runTest {
        val s = setup()
        assertEquals(IncomingGate.FirstLook.Decided(IncomingDecision.RING), s.gate.firstLook(s.site("alameda_hass")))
        s.main = false
        assertEquals(IncomingGate.FirstLook.Decided(IncomingDecision.REFUSE_PAUSED), s.gate.firstLook(null))
        assertTrue(s.transport.requests.isEmpty())
    }

    @Test
    fun aSiteThatCantBeAskedIsDeclinedAtOnce() = runTest {
        val s = setup(poll = PollMode.NEVER)
        s.registry.setCallsEnabled("alameda_hass", false)
        assertEquals(IncomingGate.FirstLook.Decided(IncomingDecision.REFUSE_SITE_PAUSED), s.gate.firstLook(s.site("alameda_hass")))
    }
}
