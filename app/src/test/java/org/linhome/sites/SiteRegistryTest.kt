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
package org.linhome.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRegistryTest {

    private class MemoryStorage(var json: String? = null) : SiteStorage {
        override fun load(): String? = json
        override fun save(json: String) {
            this.json = json
        }
    }

    @Test
    fun doorKeysIgnoreSchemeDisplayNamePortAndCase() {
        assertEquals("porta@site.example", SiteRegistry.doorKey("sip:Porta@Site.Example"))
        assertEquals("porta@site.example", SiteRegistry.doorKey("\"Porta\" <sips:porta@site.example:5061;transport=tls>"))
        assertEquals("porta@10.0.0.6", SiteRegistry.doorKey("porta@10.0.0.6:5060"))
        assertNull(SiteRegistry.doorKey("  "))
        assertNull(SiteRegistry.doorKey(null))
    }

    @Test
    fun serverKeyIsHostAndPort() {
        assertEquals("site.example:443", SiteRegistry.serverKey("https://Site.Example/api/open"))
        assertEquals("10.0.0.15:8124", SiteRegistry.serverKey("https://10.0.0.15:8124/hooks"))
        assertNull(SiteRegistry.serverKey("not a url"))
    }

    @Test
    fun siteIdsAreValidatedLikeTheLinkContract() {
        assertEquals("alameda_hass", SiteRegistry.validSiteId(" alameda_hass "))
        assertNull(SiteRegistry.validSiteId("Alameda"))
        assertNull(SiteRegistry.validSiteId("a".repeat(65)))
        assertNull(SiteRegistry.validSiteId("site.example:443"))
        assertNull(SiteRegistry.validSiteId(""))
    }

    @Test
    fun doorsRouteToTheirSiteAndOthersToNone() {
        val registry = SiteRegistry(MemoryStorage())
        val alameda = registry.add("alameda_hass", "Alameda")
        val lumiar = registry.add("lumiar_hass", "Lumiar")
        registry.mapDoor("sip:porta@alameda.example", alameda.id)
        registry.mapDoor("sip:portao@lumiar.example", lumiar.id)
        assertEquals("alameda_hass", registry.siteForDoor("<sip:porta@alameda.example:5060>")?.id)
        assertEquals("lumiar_hass", registry.siteForDoor("sip:portao@lumiar.example")?.id)
        assertNull(registry.siteForDoor("sip:+351912345678@sip.example"))
        // Last link wins a door both sites claim.
        assertEquals("alameda_hass", registry.mapDoor("sip:porta@alameda.example", lumiar.id)?.id)
        assertEquals("lumiar_hass", registry.siteForDoor("sip:porta@alameda.example")?.id)
    }

    @Test
    fun upgradeTurnsTheSingleConnectionIntoSiteOneOnce() {
        val storage = MemoryStorage()
        val registry = SiteRegistry(storage)
        val copied = mutableListOf<Int>()
        val site = registry.upgradeFromSingleSite("https://alameda.example/api/open", "Alameda") { copied += it }
        assertEquals("alameda.example:443", site?.id)
        assertEquals("Alameda", site?.name)
        assertTrue(site!!.keyedByServer)
        assertEquals(listOf(1), copied)
        // Runs once, also after a restart.
        assertNull(registry.upgradeFromSingleSite("https://alameda.example/api/open", "Alameda") { copied += it })
        assertNull(SiteRegistry(storage).upgradeFromSingleSite("https://alameda.example/api/open", "Alameda") { copied += it })
        assertEquals(listOf(1), copied)

        // The doors already there join it when the list is read; an empty first read waits.
        assertEquals(0, registry.claimLegacyDoors(emptyList(), settled = false))
        assertTrue(registry.waitingForLegacyDoors())
        assertEquals(2, registry.claimLegacyDoors(listOf("sip:porta@alameda.example", "sip:garagem@alameda.example"), settled = false))
        assertFalse(registry.waitingForLegacyDoors())
        // A door added by hand later belongs to no site.
        assertEquals(0, registry.claimLegacyDoors(listOf("sip:porta@alameda.example", "sip:mine@home.example"), settled = true))
        val reloaded = SiteRegistry(storage)
        assertEquals("alameda.example:443", reloaded.siteForDoor("sip:garagem@alameda.example")?.id)
        assertNull(reloaded.siteForDoor("sip:mine@home.example"))
    }

    @Test
    fun upgradeWithoutAConnectionMakesNoSite() {
        val registry = SiteRegistry(MemoryStorage())
        assertNull(registry.upgradeFromSingleSite(null, "Alameda") { error("nothing to copy") })
        assertTrue(registry.upgraded)
        assertTrue(registry.sites().isEmpty())
        assertFalse(registry.waitingForLegacyDoors())
    }

    @Test
    fun aFailedCopyRecordsNothingAndRunsAgain() {
        val storage = MemoryStorage()
        val registry = SiteRegistry(storage)
        runCatching { registry.upgradeFromSingleSite("https://alameda.example", null) { error("disk full") } }
        assertFalse(registry.upgraded)
        assertTrue(registry.sites().isEmpty())
        assertEquals(1, registry.upgradeFromSingleSite("https://alameda.example", null) {}?.slot)
    }

    @Test
    fun aSiteIdFromTheSameServerReKeysTheServerKeyedSite() {
        val registry = SiteRegistry(MemoryStorage())
        val old = registry.upgradeFromSingleSite("https://alameda.example/api/open", "Alameda") {}!!
        registry.claimLegacyDoors(listOf("sip:porta@alameda.example"), settled = true)
        val target = registry.resolveLink("alameda_hass", "https://alameda.example/api/open", linkHasSip = true) { "https://alameda.example/api/open" }
        assertEquals(old, target.existing)
        assertEquals("alameda.example:443", target.rekeyFrom)
        assertFalse(target.isNew)
        val rekeyed = registry.rekey(old.slot, "alameda_hass")!!
        assertEquals(old.slot, rekeyed.slot)
        assertFalse(rekeyed.keyedByServer)
        assertEquals("alameda_hass", registry.siteForDoor("sip:porta@alameda.example")?.id)
        // Through the LAN address, the same site by its id.
        val lan = registry.resolveLink("alameda_hass", "https://10.0.0.15:8124/api/open", linkHasSip = true) { null }
        assertEquals(rekeyed, lan.existing)
        assertNull(lan.rekeyFrom)
    }

    @Test
    fun linksResolveToKnownOrNewSites() {
        val registry = SiteRegistry(MemoryStorage())
        val alameda = registry.add("alameda_hass", "Alameda")
        val connections = mapOf("alameda_hass" to "https://alameda.example/api/open")
        fun resolve(siteId: String?, url: String?, sip: Boolean = true) = registry.resolveLink(siteId, url, sip) { connections[it.id] }
        assertTrue(resolve("lumiar_hass", "https://lumiar.example").isNew)
        assertEquals("lumiar_hass", resolve("lumiar_hass", "https://lumiar.example").key)
        // An older Adonis (no site_id) on a known server: that site, not a new one.
        assertEquals(alameda, resolve(null, "https://alameda.example/other").existing)
        val byServer = resolve(null, "https://lumiar.example")
        assertTrue(byServer.isNew)
        assertEquals("lumiar.example:443", byServer.key)
        // Account only, no site named: with one site it's that one (as before sites); devices only: none.
        assertEquals(alameda, resolve(null, null).existing)
        assertNull(resolve(null, null, sip = false).key)
        registry.add("lumiar_hass", "Lumiar")
        assertNull(resolve(null, null).key)
    }

    @Test
    fun stateAndRegistrationFollowBothSwitches() {
        val registry = SiteRegistry(MemoryStorage())
        assertTrue(registry.registrationWanted(mainEnabled = true))
        assertFalse(registry.registrationWanted(mainEnabled = false))
        val a = registry.add("a", "Alameda")
        val b = registry.add("b", "Lumiar")
        registry.setCallsEnabled("a", false)
        val pausedA = registry.find("a")!!
        assertFalse(registry.stateFor(pausedA, mainEnabled = true))
        assertTrue(registry.stateFor(b, mainEnabled = true))
        assertFalse(registry.stateFor(b, mainEnabled = false))
        assertTrue(registry.registrationWanted(mainEnabled = true))
        assertEquals(listOf("Alameda"), registry.pausedSites(mainEnabled = true).map { it.displayName })
        registry.setCallsEnabled("b", false)
        assertFalse(registry.registrationWanted(mainEnabled = true))
        // Every site paused reads as the phone paused, not as "paused at" each one.
        assertTrue(registry.pausedSites(mainEnabled = true).isEmpty())
        assertEquals(a.slot, pausedA.slot)
    }

    @Test
    fun incomingCallsAreRefusedWhereTheirSiteIsPaused() {
        val registry = SiteRegistry(MemoryStorage())
        val a = registry.add("a", "Alameda")
        val b = registry.add("b", "Lumiar")
        assertEquals(IncomingDecision.RING, registry.decideIncoming(true, a))
        assertEquals(IncomingDecision.RING, registry.decideIncoming(true, null))
        registry.setCallsEnabled("a", false)
        assertEquals(IncomingDecision.REFUSE_SITE_PAUSED, registry.decideIncoming(true, a))
        assertEquals(IncomingDecision.RING, registry.decideIncoming(true, b))
        assertEquals(IncomingDecision.RING, registry.decideIncoming(true, null))
        assertEquals(IncomingDecision.REFUSE_PAUSED, registry.decideIncoming(false, b))
        registry.setCallsEnabled("b", false)
        // Every site paused: the phone takes no calls, from anyone.
        assertEquals(IncomingDecision.REFUSE_PAUSED, registry.decideIncoming(true, null))
        assertEquals(IncomingDecision.REFUSE_PAUSED, SiteRegistry(MemoryStorage()).decideIncoming(false, null))
    }

    @Test
    fun removingASiteForgetsItsDoorsAndKeepsSlotsUnique() {
        val storage = MemoryStorage()
        val registry = SiteRegistry(storage)
        val a = registry.add("a", "Alameda")
        registry.mapDoor("sip:porta@a.example", "a")
        val (removed, doors) = registry.remove("a")!!
        assertEquals(a, removed)
        assertEquals(listOf("porta@a.example"), doors)
        assertNull(registry.siteForDoor("sip:porta@a.example"))
        assertEquals(2, SiteRegistry(storage).add("c", null).slot)
    }
}
