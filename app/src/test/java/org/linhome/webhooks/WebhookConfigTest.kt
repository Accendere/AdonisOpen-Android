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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebhookConfigTest {

    private fun link(extra: String = "") = JSONObject(
        """{"sip": {"username": "u", "password": "p", "domain": "d"}, "devices": [],
           "webhook_baseurl": "https://alameda.example.org:8124/api/webhooks/adonis-open",
           "webhook_user": "pedro_7f3a", "webhook_token": "secret-token" $extra}"""
    )

    @Test
    fun readsAllFields() {
        val config = WebhookConfig.fromLinkJson(link(""", "use_webhook_to_poll": "always", "polling_interval": 30"""))!!
        assertEquals("https://alameda.example.org:8124/api/webhooks/adonis-open", config.baseUrl)
        assertEquals("pedro_7f3a", config.user)
        assertEquals("secret-token", config.token)
        assertEquals(PollMode.ALWAYS, config.pollMode)
        assertEquals(30, config.pollingIntervalSeconds)
    }

    @Test
    fun defaultsWhenPollFieldsAreMissing() {
        val config = WebhookConfig.fromLinkJson(link())!!
        assertEquals(PollMode.NEVER, config.pollMode)
        assertEquals(60, config.pollingIntervalSeconds)
    }

    @Test
    fun noBaseUrlMeansNoWebhooks() {
        assertNull(WebhookConfig.fromLinkJson(JSONObject("""{"sip": {}, "webhook_user": "a", "webhook_token": "b"}""")))
        assertNull(WebhookConfig.lastRejectionReason)
    }

    @Test
    fun refusesPlainHttpAndOddUrls() {
        for (url in listOf("http://alameda.lan:8124/api", "ftp://x/y", "not a url", "https://user:pw@host/x", "https://host/x?a=1")) {
            val root = JSONObject().put("webhook_baseurl", url).put("webhook_user", "a").put("webhook_token", "b")
            assertNull(url, WebhookConfig.fromLinkJson(root))
            assertNotNull(url, WebhookConfig.lastRejectionReason)
        }
    }

    @Test
    fun missingUserOrTokenMeansNoWebhooks() {
        val base = """"webhook_baseurl": "https://h/x""""
        assertNull(WebhookConfig.fromLinkJson(JSONObject("""{$base, "webhook_token": "t"}""")))
        assertNull(WebhookConfig.fromLinkJson(JSONObject("""{$base, "webhook_user": "u"}""")))
        assertNull(WebhookConfig.fromLinkJson(JSONObject("""{$base, "webhook_user": " ", "webhook_token": "t"}""")))
    }

    @Test
    fun pollModeValues() {
        assertEquals(PollMode.NEVER, PollMode.fromLinkValue("never"))
        assertEquals(PollMode.FOREGROUND_ONLY, PollMode.fromLinkValue("foreground_only"))
        assertEquals(PollMode.ALWAYS, PollMode.fromLinkValue(" Always "))
        assertEquals(PollMode.NEVER, PollMode.fromLinkValue("sometimes"))
        assertEquals(PollMode.NEVER, PollMode.fromLinkValue(null))
        assertEquals(PollMode.NEVER, PollMode.fromLinkValue(3))
        // Links made before poll modes existed carried a boolean.
        assertEquals(PollMode.FOREGROUND_ONLY, PollMode.fromLinkValue(true))
        assertEquals(PollMode.NEVER, PollMode.fromLinkValue(false))
        assertEquals(PollMode.FOREGROUND_ONLY, WebhookConfig.fromLinkJson(link(""", "use_webhook_to_poll": true"""))!!.pollMode)
    }

    @Test
    fun pollingIntervalIsAtLeastTenSeconds() {
        assertEquals(10, WebhookConfig.fromLinkJson(link(""", "polling_interval": 2"""))!!.pollingIntervalSeconds)
        assertEquals(10, WebhookConfig.fromLinkJson(link(""", "polling_interval": -5"""))!!.pollingIntervalSeconds)
    }

    @Test
    fun urlForEventsAndHiddenToken() {
        val config = WebhookConfig("https://h/api/webhooks/adonis-open/", "u", "secret-token")
        assertEquals("https://h/api/webhooks/adonis-open/state", config.urlFor("state"))
        assertEquals("h", config.host)
        assertFalse(config.toString().contains("secret-token"))
    }
}
