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
package org.linhome.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linhome.webhooks.WebhookConfig

class ImportPlanTest {
    private val incoming = AdonisSipConfig("pedro", "pw", "sip.example", transport = "tls")

    @Test
    fun sameAccountIsUnchangedNotReplaced() {
        val s = ImportPlan.compareSip(incoming, CurrentSip("pedro", "SIP.example", null, "TLS", "pw"))
        assertEquals(ImportChange.UNCHANGED, s.change)
        assertNull(s.replaces)
    }

    @Test
    fun onlyThePasswordChanged() {
        val s = ImportPlan.compareSip(incoming, CurrentSip("pedro", "sip.example", null, "tls", "old"))
        assertEquals(ImportChange.CHANGED, s.change)
        assertEquals(listOf("password"), s.differences)
        assertNull(s.replaces)
    }

    @Test
    fun anotherAccountIsAReplacement() {
        val s = ImportPlan.compareSip(incoming, CurrentSip("miguel", "sip.example", null, "tls", "pw"))
        assertEquals("miguel@sip.example", s.replaces)
        assertTrue("username" in s.differences)
    }

    @Test
    fun unreadablePasswordCountsAsChanged() {
        val s = ImportPlan.compareSip(incoming, CurrentSip("pedro", "sip.example", null, "tls", null))
        assertEquals(listOf("password"), s.differences)
    }

    @Test
    fun noAccountIsNew() {
        assertEquals(ImportChange.NEW, ImportPlan.compareSip(incoming, null).change)
    }

    private val door = AdonisDeviceConfig("Porta", "porta", method = "rfc4733", actions = listOf(AdonisActionConfig("action_type_open_door", "7*")))
    private val fmt: (String) -> String = { if (it.contains("@")) "sip:$it" else "sip:$it@sip.example" }

    @Test
    fun identicalDoorIsUnchangedAndNotPreselected() {
        val match = CurrentDevice("Porta", "sip:porta@sip.example", null, "method_dtmf_rfc_4733", listOf("action_open_door" to "7*"))
        val d = ImportPlan.compareDevice(0, door, match, fmt, "method_dtmf_rfc_4733", listOf("action_open_door" to "7*"))
        assertEquals(ImportChange.UNCHANGED, d.change)
        val plan = ImportPlan(null, listOf(d), null, null)
        assertTrue(plan.defaultSelection().devices.isEmpty())
        assertTrue(plan.nothingChanges)
    }

    @Test
    fun changedDoorListsWhatChanged() {
        val match = CurrentDevice("Porta", "sip:porta@sip.example", null, "method_dtmf_sip_info", listOf("action_open_door" to "#"))
        val d = ImportPlan.compareDevice(0, door, match, fmt, "method_dtmf_rfc_4733", listOf("action_open_door" to "7*"))
        assertEquals(ImportChange.CHANGED, d.change)
        assertEquals(listOf("method", "actions"), d.differences)
        assertEquals(setOf(0), ImportPlan(null, listOf(d), null, null).defaultSelection().devices)
    }

    private val hook = WebhookConfig("https://site.example/h", "pedro", "tok", events = setOf("call_summary"))

    @Test
    fun webhookComparison() {
        assertNull(ImportPlan.compareWebhook(hook, null, linkHasSip = false))
        assertEquals(ImportChange.NEW, ImportPlan.compareWebhook(hook, null, true)!!.change)
        assertEquals(ImportChange.UNCHANGED, ImportPlan.compareWebhook(hook, hook, true)!!.change)
        assertEquals(listOf("events"), ImportPlan.compareWebhook(hook.copy(events = setOf("poll")), hook, true)!!.differences)
        assertEquals(ImportChange.REMOVED, ImportPlan.compareWebhook(null, hook, true)!!.change)
        assertNull(ImportPlan.compareWebhook(null, null, true))
    }

    @Test
    fun emptySelection() {
        assertTrue(ImportSelection(false, emptySet(), false, false).isEmpty)
        assertFalse(ImportSelection(false, setOf(1), false, false).isEmpty)
    }
}
