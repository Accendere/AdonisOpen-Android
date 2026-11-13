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
import org.linhome.sites.LinkSite
import org.linhome.sites.Site
import org.linhome.sites.SiteRegistry

/**
 * One [WebhookEngine] per site, and where each event goes: a call's events only to its door's site (a call from no
 * site's door goes nowhere), the phone's own events (`registration`, the switch's `state`) to every site. Each
 * site's `state` is the main switch AND its own switch.
 *
 * Plain Kotlin, unit tested; [Webhooks] wires it to Android. Every method must be called from the same thread.
 */
class SiteWebhooks(
    val registry: SiteRegistry,
    private val mainEnabled: () -> Boolean,
    private val newEngine: (Site) -> WebhookEngine,
    /** Deletes a removed site's stored connection, queue and acks. */
    private val forgetStorage: (Site) -> Unit = {},
    private val logger: WebhookLog = WebhookLog { _, _ -> }
) {
    private val engines = HashMap<Int, WebhookEngine>()
    private var foreground = false

    private fun info(message: String) = logger.log(WebhookLog.Level.INFO, "[Webhooks] $message")

    /** [site]'s engine, made on first use. */
    fun engine(site: Site): WebhookEngine = engines.getOrPut(site.slot) {
        newEngine(site).also { it.setForeground(foreground) }
    }

    private fun engineFor(siteId: String?): WebhookEngine? = registry.find(siteId)?.let { engine(it) }

    /** What [site] is told in `state`. */
    fun stateFor(site: Site): Boolean = registry.stateFor(site, mainEnabled())

    /** App start: every site gets its current state; polling starts where asked. */
    fun start() {
        registry.sites().forEach { engine(it).onAppStart(stateFor(it)) }
    }

    /** The phone's main switch changed: every site hears its own resulting state. */
    fun onMainSwitchChanged() {
        registry.sites().forEach { engine(it).onSwitchChanged(stateFor(it)) }
    }

    /** Turns [siteId]'s own switch; the site hears the result. False when there is no such site or nothing changed. */
    fun setSiteCallsEnabled(siteId: String, enabled: Boolean): Boolean {
        val before = registry.find(siteId) ?: return false
        if (before.callsEnabled == enabled) return false
        val site = registry.setCallsEnabled(siteId, enabled) ?: return false
        info("Site ${site.id}: calls ${if (enabled) "on" else "paused"}")
        engine(site).onSwitchChanged(stateFor(site))
        return true
    }

    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        engines.values.forEach { it.setForeground(isForeground) }
    }

    fun onNetworkAvailable() = engines.values.forEach { it.onNetworkAvailable() }

    /** A call's event: only to [siteId] (the calling door's site); null or unknown goes nowhere. */
    fun sendCallEvent(siteId: String?, event: String, fields: JSONObject, at: String? = null) {
        engineFor(siteId)?.sendEvent(event, fields, at)
    }

    /** An event about the phone itself (`registration`): to every site. */
    fun sendToAll(event: String, fields: JSONObject, at: String? = null) {
        registry.sites().forEach { engine(it).sendEvent(event, fields, at) }
    }

    fun config(siteId: String?): WebhookConfig? = engineFor(siteId)?.config

    fun status(siteId: String?): WebhookStatus = engineFor(siteId)?.status ?: WebhookStatus.ACTIVE

    /** The worst status of all sites, for the one "scan your link again" banner. */
    fun overallStatus(): WebhookStatus {
        val statuses = registry.sites().map { engine(it).status }
        return when {
            WebhookStatus.UNAUTHORIZED in statuses -> WebhookStatus.UNAUTHORIZED
            WebhookStatus.NOT_FOUND in statuses -> WebhookStatus.NOT_FOUND
            else -> WebhookStatus.ACTIVE
        }
    }

    /** Whether [siteId] wants [event]; false for no site. */
    fun sends(siteId: String?, event: String): Boolean = config(siteId)?.sends(event) == true

    /** See [WebhookEngine.whoAnswered]: asked of [siteId] only. */
    suspend fun whoAnswered(siteId: String?, callId: String?, door: String?): String? =
        engineFor(siteId)?.whoAnswered(callId, door)

    /** Which site [config]'s link is for (see [SiteRegistry.resolveLink]). */
    fun resolveLink(siteId: String?, webhook: WebhookConfig?, linkHasSip: Boolean): LinkSite =
        registry.resolveLink(siteId, webhook?.baseUrl, linkHasSip) { config(it.id)?.baseUrl }

    /**
     * A setup link for [target] was applied: adds the site when new, re-keys it when the link brings its `site_id`,
     * keeps its name up to date, and when [applyWebhook] replaces that site's connection only with [webhook] (null:
     * none), with the engine's usual rules (see [WebhookEngine.onLinkApplied]). Returns the site, or null when the
     * link names none.
     */
    fun applyLink(target: LinkSite, webhook: WebhookConfig?, applyWebhook: Boolean, siteName: String?): Site? {
        val key = target.key ?: return null
        val existing = target.existing
        var site = when {
            existing == null -> registry.add(key, siteName).also { info("New site ${it.id}") }
            target.rekeyFrom != null -> (registry.rekey(existing.slot, key) ?: registry.bySlot(existing.slot))
                ?.also { info("Site ${target.rekeyFrom} is ${it.id} from now on") }
            else -> registry.bySlot(existing.slot)
        } ?: return null
        val name = siteName?.trim()?.ifEmpty { null }
        if (name != null && name != site.name) site = registry.rename(site.id, name) ?: site
        if (applyWebhook) engine(site).onLinkApplied(webhook, stateFor(site))
        return site
    }

    /** See [WebhookEngine.applyLocalEdit], for [siteId]'s connection; null when it has none. */
    fun applyLocalEdit(siteId: String, events: Set<String>, baseUrl: String): WebhookEngine.LocalEdit? {
        val site = registry.find(siteId) ?: return null
        val engine = engine(site)
        if (engine.config == null) return null
        return engine.applyLocalEdit(events, baseUrl, stateFor(site))
    }

    /**
     * Forgets [siteId]: its connection, its queued events (dropped, never sent) and its door mappings. Returns the
     * door keys it had (the caller removes those doors from the phone when asked to).
     */
    fun removeSite(siteId: String): List<String>? {
        val site = registry.find(siteId) ?: return null
        engines.remove(site.slot)?.let { engine ->
            engine.onLinkApplied(null, false)
            engine.shutdown()
        }
        val removed = registry.remove(siteId) ?: return null
        forgetStorage(site)
        info("Site ${site.id} removed (${removed.second.size} door(s))")
        return removed.second
    }

    fun shutdown() = engines.values.forEach { it.shutdown() }
}
