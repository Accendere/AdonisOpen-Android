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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linhome.sites.Site
import org.linhome.sites.SiteRegistry
import org.linhome.sites.SiteStorage
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SiteWebhooksTest {

    private class MemoryStorage(override var config: WebhookConfig?) : WebhookStorage {
        override var status: WebhookStatus = WebhookStatus.ACTIVE
        override val deviceId: String = "device-1"
        override var eventQueue: List<String> = emptyList()
        override var pendingAcks: Set<String> = emptySet()
    }

    private class MemorySiteStorage : SiteStorage {
        var json: String? = null
        override fun load(): String? = json
        override fun save(json: String) {
            this.json = json
        }
    }

    /** Answers 200 and records every request, all sites together (the URL says which). */
    private class FakeTransport : WebhookTransport {
        val requests = mutableListOf<WebhookRequest>()
        override fun send(request: WebhookRequest): HttpAnswer {
            requests += request
            return HttpAnswer(200, """{"success":true,"messages":[]}""")
        }

        fun to(host: String) = requests.filter { java.net.URI(it.url).host == host }
        fun events(host: String) = to(host).map { it.url.substringAfterLast('/') }
        fun states(host: String) = to(host).filter { it.url.endsWith("/state") }.map { JSONObject(it.body).getBoolean("enabled") }
    }

    private val callEvents = setOf(WebhookEvents.STATE, WebhookEvents.CALL_RINGING, WebhookEvents.CALL_SUMMARY, WebhookEvents.CALL_ENDED, WebhookEvents.REGISTRATION)
    private fun config(host: String) = WebhookConfig("https://$host/api/open", "pedro", "token-$host", events = callEvents)

    private class Setup(
        val hub: SiteWebhooks,
        val registry: SiteRegistry,
        val transport: FakeTransport,
        val storages: MutableMap<Int, MemoryStorage>,
        var main: Boolean
    )

    private fun TestScope.setup(main: Boolean = true, sites: List<Pair<String, String?>> = listOf("alameda_hass" to "alameda.example", "lumiar_hass" to "lumiar.example")): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = SiteRegistry(MemorySiteStorage())
        val transport = FakeTransport()
        val storages = mutableMapOf<Int, MemoryStorage>()
        sites.forEach { (id, host) ->
            val site = registry.add(id, id.substringBefore('_'))
            storages[site.slot] = MemoryStorage(host?.let { config(it) })
        }
        lateinit var holder: Setup
        val hub = SiteWebhooks(
            registry,
            mainEnabled = { holder.main },
            newEngine = { site: Site ->
                val storage = storages.getOrPut(site.slot) { MemoryStorage(null) }
                WebhookEngine(storage, transport, dispatcher, dispatcher, logger = { _, _ -> })
            }
        )
        holder = Setup(hub, registry, transport, storages, main)
        return holder
    }

    @Test
    fun callEventsGoOnlyToTheDoorsSite() = runTest {
        val s = setup()
        s.registry.mapDoor("sip:porta@alameda.example", "alameda_hass")
        val doorSite = s.registry.siteForDoor("sip:porta@alameda.example")?.id
        s.hub.sendCallEvent(doorSite, WebhookEvents.CALL_RINGING, JSONObject().put("call_id", "c1"))
        runCurrent()
        assertEquals(listOf("call_ringing"), s.transport.events("alameda.example"))
        assertTrue(s.transport.to("lumiar.example").isEmpty())
    }

    @Test
    fun callsFromNoSiteGoNowhere() = runTest {
        val s = setup()
        s.hub.sendCallEvent(null, WebhookEvents.CALL_RINGING, JSONObject().put("call_id", "c1"))
        s.hub.sendCallEvent("unknown_site", WebhookEvents.CALL_RINGING, JSONObject().put("call_id", "c2"))
        runCurrent()
        assertTrue(s.transport.requests.isEmpty())
        assertNull(s.hub.whoAnswered(null, "c1", "Porta"))
        assertFalse(s.hub.sends(null, WebhookEvents.CALL_RINGING))
    }

    @Test
    fun phoneEventsGoToEverySite() = runTest {
        val s = setup()
        s.hub.sendToAll(WebhookEvents.REGISTRATION, JSONObject().put("state", "failed"))
        runCurrent()
        assertEquals(listOf("registration"), s.transport.events("alameda.example"))
        assertEquals(listOf("registration"), s.transport.events("lumiar.example"))
    }

    @Test
    fun eachSiteIsToldMainAndItsOwnSwitch() = runTest {
        val s = setup()
        s.hub.start()
        runCurrent()
        assertEquals(listOf(true), s.transport.states("alameda.example"))
        assertEquals(listOf(true), s.transport.states("lumiar.example"))

        assertTrue(s.hub.setSiteCallsEnabled("alameda_hass", false))
        assertFalse(s.hub.setSiteCallsEnabled("alameda_hass", false))
        runCurrent()
        assertEquals(listOf(true, false), s.transport.states("alameda.example"))
        assertEquals(listOf(true), s.transport.states("lumiar.example"))

        s.main = false
        s.hub.onMainSwitchChanged()
        runCurrent()
        assertEquals(listOf(true, false, false), s.transport.states("alameda.example"))
        assertEquals(listOf(true, false), s.transport.states("lumiar.example"))

        // Main back on: the paused site stays paused.
        s.main = true
        s.hub.onMainSwitchChanged()
        runCurrent()
        assertEquals(false, s.transport.states("alameda.example").last())
        assertEquals(true, s.transport.states("lumiar.example").last())
        s.hub.shutdown()
    }

    @Test
    fun aRefusedCallReachesItsSiteAsRingingAndSummary() = runTest {
        val s = setup()
        s.hub.setSiteCallsEnabled("alameda_hass", false)
        runCurrent()
        s.transport.requests.clear()
        val reporter = CallReporter({ site, e, f, at -> s.hub.sendCallEvent(site, e, f, at) }, zone = ZoneOffset.UTC)
        val site = s.registry.find("alameda_hass")
        assertEquals(org.linhome.sites.IncomingDecision.REFUSE_SITE_PAUSED, s.registry.decideIncoming(true, site))
        reporter.onIncoming("c1", "Porta", "sip:porta@alameda.example", CallOutcome.REFUSED_PAUSED, site = site?.id)
        reporter.onEnded("c1", CallOutcome.DECLINED, byLocal = true)
        runCurrent()
        assertEquals(listOf("call_ringing", "call_ended", "call_summary"), s.transport.events("alameda.example"))
        val ringing = JSONObject(s.transport.to("alameda.example")[0].body)
        assertEquals("paused", ringing.getString("refused"))
        val summary = JSONObject(s.transport.to("alameda.example")[2].body)
        assertEquals("refused_paused", summary.getString("outcome"))
        assertTrue(s.transport.to("lumiar.example").isEmpty())
    }

    @Test
    fun aReporterWithoutASiteSendsNowhere() {
        val sent = mutableListOf<String?>()
        val reporter = CallReporter({ site, _, _, _ -> sent += site }, zone = ZoneOffset.UTC)
        reporter.onIncoming("c1", null, "sip:+351912345678@sip.example")
        reporter.onEnded("c1", CallOutcome.MISSED, byLocal = false)
        reporter.onIncoming("c2", "Porta", "sip:porta@a.example", site = "a")
        reporter.onDoorAction("c2", "action_open_door", "#", early = true, sent = true)
        reporter.onDoorAction("other", "action_open_door", "#", early = false, sent = true, site = "b")
        assertEquals(listOf(null, null, null, "a", "a", "b"), sent)
        assertEquals("a", reporter.siteOf("c2"))
    }

    @Test
    fun aLinkForANewSiteAddsItAndLeavesTheOthersAlone() = runTest {
        val s = setup(sites = listOf("alameda_hass" to "alameda.example"))
        val incoming = config("lumiar.example")
        val target = s.hub.resolveLink("lumiar_hass", incoming, linkHasSip = true)
        assertTrue(target.isNew)
        val site = s.hub.applyLink(target, incoming, applyWebhook = true, siteName = "Lumiar")
        runCurrent()
        assertEquals("lumiar_hass", site?.id)
        assertEquals("Lumiar", site?.name)
        assertEquals(incoming, s.hub.config("lumiar_hass"))
        assertEquals("alameda.example", s.hub.config("alameda_hass")?.host)
        assertEquals(listOf(true), s.transport.states("lumiar.example"))
        assertTrue(s.transport.to("alameda.example").isEmpty())
        s.hub.shutdown()
    }

    @Test
    fun reKeyingKeepsTheConnectionQueueAndDoors() = runTest {
        val s = setup(sites = listOf("alameda.example:443" to "alameda.example"))
        val slot = s.registry.find("alameda.example:443")!!.slot
        s.registry.mapDoor("sip:porta@alameda.example", "alameda.example:443")
        s.storages[slot]!!.eventQueue = listOf("""{"event":"call_ringing","fields":{},"at":"x"}""")
        s.storages[slot]!!.config = config("alameda.example").copy(editedLocally = true)

        val target = s.hub.resolveLink("alameda_hass", null, linkHasSip = false)
        assertTrue(target.isNew) // a devices-only link can't tell the server: it doesn't re-key
        val withServer = s.hub.resolveLink("alameda_hass", config("alameda.example"), linkHasSip = true)
        assertEquals("alameda.example:443", withServer.rekeyFrom)
        val site = s.hub.applyLink(withServer, null, applyWebhook = false, siteName = null)
        assertEquals("alameda_hass", site?.id)
        assertEquals(slot, site?.slot)
        assertNull(s.registry.find("alameda.example:443"))
        assertEquals("alameda_hass", s.registry.siteForDoor("sip:porta@alameda.example")?.id)
        assertTrue(s.hub.config("alameda_hass")!!.editedLocally)
        assertEquals(1, s.storages[slot]!!.eventQueue.size)
        s.hub.shutdown()
    }

    @Test
    fun aLinkForAKnownSiteReplacesOnlyItsConnection() = runTest {
        val s = setup()
        val incoming = config("10.0.0.15:8124".substringBefore(':')).copy(baseUrl = "https://10.0.0.15:8124/api/open")
        val target = s.hub.resolveLink("alameda_hass", incoming, linkHasSip = true)
        assertNotNull(target.existing)
        s.hub.applyLink(target, incoming, applyWebhook = true, siteName = "Alameda")
        runCurrent()
        assertEquals("https://10.0.0.15:8124/api/open", s.hub.config("alameda_hass")?.baseUrl)
        assertEquals("lumiar.example", s.hub.config("lumiar_hass")?.host)
        assertEquals(2, s.registry.sites().size)
        s.hub.shutdown()
    }

    @Test
    fun removingASiteDropsItsQueueAndDoors() = runTest {
        val s = setup()
        s.registry.mapDoor("sip:porta@alameda.example", "alameda_hass")
        val slot = s.registry.find("alameda_hass")!!.slot
        s.hub.engine(s.registry.find("alameda_hass")!!)
        s.storages[slot]!!.eventQueue = listOf("""{"event":"call_ringing","fields":{},"at":"x"}""")
        assertEquals(listOf("porta@alameda.example"), s.hub.removeSite("alameda_hass"))
        assertNull(s.storages[slot]!!.config)
        assertTrue(s.storages[slot]!!.eventQueue.isEmpty())
        assertNull(s.registry.siteForDoor("sip:porta@alameda.example"))
        assertEquals(listOf("lumiar_hass"), s.registry.sites().map { it.id })
        s.hub.sendCallEvent("alameda_hass", WebhookEvents.CALL_RINGING, JSONObject())
        runCurrent()
        // Only the farewell state; the queued ringing event and the new one never go.
        assertEquals(listOf("state"), s.transport.events("alameda.example"))
    }

    @Test
    fun removingASiteTellsItEnabledFalseThenDropsTheConnection() = runTest {
        val s = setup()
        val slot = s.registry.find("alameda_hass")!!.slot
        s.hub.removeSite("alameda_hass")
        // The connection is gone at once, yet the farewell goes with the key it had.
        assertNull(s.storages[slot]!!.config)
        runCurrent()
        assertEquals(listOf(false), s.transport.states("alameda.example"))
        assertTrue(s.transport.to("alameda.example").single().headers.values.any { it.contains("token-alameda.example") })
        // The other site hears nothing.
        assertTrue(s.transport.to("lumiar.example").isEmpty())
    }

    @Test
    fun removingASiteWithoutAConnectionSendsNothing() = runTest {
        val s = setup(sites = listOf("alameda_hass" to null, "lumiar_hass" to "lumiar.example"))
        assertEquals(emptyList<String>(), s.hub.removeSite("alameda_hass"))
        runCurrent()
        assertTrue(s.transport.requests.isEmpty())
        assertEquals(listOf("lumiar_hass"), s.registry.sites().map { it.id })
    }

    @Test
    fun theSettingsSwitchPausesOneSiteAndSipStaysUntilAllArePaused() = runTest {
        val s = setup()
        // What Webhooks.setSiteCallsEnabled(), the Settings card's switch, does.
        assertTrue(s.hub.setSiteCallsEnabled("alameda_hass", false))
        runCurrent()
        assertEquals(listOf(false), s.transport.states("alameda.example"))
        assertTrue(s.transport.states("lumiar.example").isEmpty())
        assertFalse(s.hub.config("alameda_hass") == null)
        assertTrue(s.registry.registrationWanted(true))
        assertEquals(listOf("alameda_hass"), s.registry.pausedSites(true).map { it.id })

        assertTrue(s.hub.setSiteCallsEnabled("lumiar_hass", false))
        runCurrent()
        assertFalse(s.registry.registrationWanted(true))
        assertTrue(s.registry.pausedSites(true).isEmpty())

        // And back on: only that site is told.
        assertTrue(s.hub.setSiteCallsEnabled("alameda_hass", true))
        runCurrent()
        assertEquals(listOf(false, true), s.transport.states("alameda.example"))
        assertEquals(listOf(false), s.transport.states("lumiar.example"))
        assertTrue(s.registry.registrationWanted(true))
    }

    @Test
    fun theBannerShowsTheWorstStatus() = runTest {
        val s = setup()
        assertEquals(WebhookStatus.ACTIVE, s.hub.overallStatus())
        s.storages[s.registry.find("lumiar_hass")!!.slot]!!.status = WebhookStatus.UNAUTHORIZED
        assertEquals(WebhookStatus.UNAUTHORIZED, s.hub.overallStatus())
        assertEquals(WebhookStatus.ACTIVE, s.hub.status("alameda_hass"))
    }
}
