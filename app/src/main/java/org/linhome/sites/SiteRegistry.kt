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

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * A building this phone takes door calls for. [id] is the site's `site_id` from its setup link, or its server
 * (`host:port` of the webhook address) when the link came from an Adonis without `site_id`. [slot] never changes:
 * the site's webhook connection is stored under it, so re-keying a site (server -> site_id) moves nothing.
 */
data class Site(
    val slot: Int,
    val id: String,
    val name: String?,
    /** The site's own receive-calls switch (the phone's main switch is separate). */
    val callsEnabled: Boolean = true
) {
    /** Keyed by its server (an older Adonis's link): the next link with a `site_id` from that server re-keys it. */
    val keyedByServer: Boolean get() = ':' in id

    /** What to call it on screen: its name, else its key. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

/** Where a link's settings go: [existing] when the phone knows the site, else a new site keyed [key]. */
data class LinkSite(
    /** The site's key after the link (its `site_id`, else its server); null when the link names no site. */
    val key: String?,
    val existing: Site?,
    /** The key [existing] has now, when the link re-keys it (server -> site_id). */
    val rekeyFrom: String? = null
) {
    val isNew: Boolean get() = existing == null && key != null
}

/** What the app does with an incoming call, before it rings. */
enum class IncomingDecision {
    RING,

    /** Main switch off, or every site paused: the phone takes no calls at all (as before sites existed). */
    REFUSE_PAUSED,

    /** The calling door's site is paused here: declined (busy) and reported to that site as refused. */
    REFUSE_SITE_PAUSED
}

/** The registry's single JSON document between runs. */
interface SiteStorage {
    fun load(): String?
    fun save(json: String)
}

/**
 * The sites, their switches, and which site each door belongs to (keyed by the door's normalised SIP address, so the
 * vCards don't change). Doors added by hand belong to no site. Plain Kotlin, unit tested; [Sites] wires it to Android.
 */
class SiteRegistry(private val storage: SiteStorage) {
    private val sites = mutableListOf<Site>()
    private val doors = LinkedHashMap<String, Int>()
    private var nextSlot = 1

    /** After the upgrade: the slot the doors already on the phone join once the door list is read. */
    private var legacyDoorsSlot: Int? = null

    /** The one-time upgrade from the single-site app has run. */
    var upgraded = false
        private set

    init {
        load()
    }

    @Synchronized
    fun sites(): List<Site> = sites.toList()

    @Synchronized
    fun find(id: String?): Site? = id?.let { key -> sites.firstOrNull { it.id == key } }

    @Synchronized
    fun bySlot(slot: Int): Site? = sites.firstOrNull { it.slot == slot }

    /** The site [address]'s door belongs to, or null (a door added by hand, a phone number, an unknown caller). */
    @Synchronized
    fun siteForDoor(address: String?): Site? {
        val key = doorKey(address) ?: return null
        return doors[key]?.let { bySlot(it) }
    }

    /** The door keys of [id]'s doors. */
    @Synchronized
    fun doorsOf(id: String): List<String> {
        val slot = find(id)?.slot ?: return emptyList()
        return doors.filterValues { it == slot }.keys.toList()
    }

    /**
     * [address]'s door belongs to [siteId] from now on (null: to no site). Two sites with the same door address
     * (unlikely, door addresses are per building): the last link wins. Returns the site it belonged to before.
     */
    @Synchronized
    fun mapDoor(address: String?, siteId: String?): Site? {
        val key = doorKey(address) ?: return null
        val before = doors[key]?.let { bySlot(it) }
        val site = find(siteId)
        if (site == null) doors.remove(key) else doors[key] = site.slot
        persist()
        return before
    }

    /** Adds a site with a new slot; an existing [id] is returned as it is. */
    @Synchronized
    fun add(id: String, name: String?): Site {
        find(id)?.let { return it }
        val site = Site(nextSlot++, id, name?.trim()?.ifEmpty { null })
        sites += site
        persist()
        return site
    }

    /** The site in [slot] is keyed [newId] from now on (its connection, queue, switch and doors stay). */
    @Synchronized
    fun rekey(slot: Int, newId: String): Site? {
        val index = sites.indexOfFirst { it.slot == slot }
        if (index < 0 || find(newId)?.let { it.slot != slot } == true) return null
        sites[index] = sites[index].copy(id = newId)
        persist()
        return sites[index]
    }

    @Synchronized
    fun rename(id: String, name: String?): Site? = update(id) { it.copy(name = name?.trim()?.ifEmpty { null }) }

    /** Turns [id]'s receive-calls switch; null when there is no such site. */
    @Synchronized
    fun setCallsEnabled(id: String, enabled: Boolean): Site? = update(id) { it.copy(callsEnabled = enabled) }

    /** Forgets [id] and its door mappings; returns the site and the door keys it had. */
    @Synchronized
    fun remove(id: String): Pair<Site, List<String>>? {
        val site = find(id) ?: return null
        val keys = doors.filterValues { it == site.slot }.keys.toList()
        keys.forEach { doors.remove(it) }
        sites.remove(site)
        if (legacyDoorsSlot == site.slot) legacyDoorsSlot = null
        persist()
        return site to keys
    }

    private fun update(id: String, change: (Site) -> Site): Site? {
        val index = sites.indexOfFirst { it.id == id }
        if (index < 0) return null
        sites[index] = change(sites[index])
        persist()
        return sites[index]
    }

    /**
     * Which site a setup link is for. [siteId] is its `site_id` (already validated), [webhookBaseUrl] its webhook
     * address, [connectionUrl] the address each site's connection uses now.
     *
     * - A known `site_id`: that site.
     * - A new `site_id` from the server of a site keyed by server only: that site, re-keyed.
     * - No `site_id`: the site with that server (as its key or its connection), else a new one keyed by the server.
     * - Neither (an account-only link from an older Adonis): with exactly one site, that one, as before sites existed.
     */
    @Synchronized
    fun resolveLink(siteId: String?, webhookBaseUrl: String?, linkHasSip: Boolean, connectionUrl: (Site) -> String?): LinkSite {
        val server = webhookBaseUrl?.let { serverKey(it) }
        fun onServer(site: Site) = server != null && (site.id == server || connectionUrl(site)?.let { serverKey(it) } == server)
        if (siteId != null) {
            find(siteId)?.let { return LinkSite(siteId, it) }
            sites.firstOrNull { it.keyedByServer && onServer(it) }?.let { return LinkSite(siteId, it, it.id) }
            return LinkSite(siteId, null)
        }
        if (server != null) {
            (sites.firstOrNull { it.id == server } ?: sites.firstOrNull { onServer(it) })?.let { return LinkSite(it.id, it) }
            return LinkSite(server, null)
        }
        if (linkHasSip && sites.size == 1) return LinkSite(sites[0].id, sites[0])
        return LinkSite(null, null)
    }

    /** The `enabled` [site] is told: the phone's main switch AND the site's own. */
    fun stateFor(site: Site, mainEnabled: Boolean): Boolean = mainEnabled && site.callsEnabled

    /** SIP stays registered while the main switch is on and some site takes calls (or there are no sites). */
    @Synchronized
    fun registrationWanted(mainEnabled: Boolean): Boolean =
        mainEnabled && (sites.isEmpty() || sites.any { it.callsEnabled })

    /** The paused sites while the phone still takes calls for others (the account card's "Paused at …"). */
    @Synchronized
    fun pausedSites(mainEnabled: Boolean): List<Site> =
        if (!registrationWanted(mainEnabled)) emptyList() else sites.filter { !it.callsEnabled }

    /** Whether an incoming call from [doorSite]'s door (null: no site, phone number, unknown) rings. */
    @Synchronized
    fun decideIncoming(mainEnabled: Boolean, doorSite: Site?): IncomingDecision {
        if (!registrationWanted(mainEnabled)) return IncomingDecision.REFUSE_PAUSED
        val site = doorSite?.let { bySlot(it.slot) } ?: return IncomingDecision.RING
        return if (site.callsEnabled) IncomingDecision.RING else IncomingDecision.REFUSE_SITE_PAUSED
    }

    /**
     * Once, on the first start after the update from the single-site app: the connection it had (address
     * [legacyBaseUrl], null when it had none) becomes site 1, keyed by its server, named [legacyName];
     * [copyConnection] copies it into the new slot's storage. The doors already on the phone join it when the door
     * list is read ([claimLegacyDoors]). Does nothing on later starts, or when sites exist already.
     */
    @Synchronized
    fun upgradeFromSingleSite(legacyBaseUrl: String?, legacyName: String?, copyConnection: (slot: Int) -> Unit): Site? {
        if (upgraded) return null
        var created: Site? = null
        val key = legacyBaseUrl?.let { serverKey(it) }
        if (key != null && sites.isEmpty()) {
            val slot = nextSlot
            // Copied before anything is recorded: if it fails, the next start tries again from the start.
            copyConnection(slot)
            created = Site(slot, key, legacyName?.trim()?.ifEmpty { null })
            sites += created
            nextSlot = slot + 1
            legacyDoorsSlot = slot
        }
        upgraded = true
        persist()
        return created
    }

    /**
     * The door list was read ([addresses]): after the upgrade, the doors without a site join the upgraded site.
     * [settled] says the list is complete even if empty (the person is adding a door); an empty list read at start
     * may just not be loaded yet, so it waits for the next one.
     */
    @Synchronized
    fun claimLegacyDoors(addresses: List<String>, settled: Boolean): Int {
        val slot = legacyDoorsSlot ?: return 0
        if (addresses.isEmpty() && !settled) return 0
        var claimed = 0
        if (bySlot(slot) != null) {
            addresses.mapNotNull { doorKey(it) }.forEach { key ->
                if (key !in doors) {
                    doors[key] = slot
                    claimed++
                }
            }
        }
        legacyDoorsSlot = null
        persist()
        return claimed
    }

    @Synchronized
    fun waitingForLegacyDoors(): Boolean = legacyDoorsSlot != null

    private fun persist() {
        val json = JSONObject()
            .put("version", 1)
            .put("next_slot", nextSlot)
            .put("upgraded", upgraded)
            .put("sites", JSONArray().apply {
                sites.forEach { site ->
                    put(JSONObject()
                        .put("slot", site.slot)
                        .put("id", site.id)
                        .put("name", site.name ?: JSONObject.NULL)
                        .put("calls_enabled", site.callsEnabled))
                }
            })
            .put("doors", JSONObject().apply { doors.forEach { (key, slot) -> put(key, slot) } })
        legacyDoorsSlot?.let { json.put("legacy_doors_slot", it) }
        storage.save(json.toString())
    }

    private fun load() {
        val stored = storage.load() ?: return
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: return
        upgraded = json.optBoolean("upgraded", false)
        val array = json.optJSONArray("sites") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slot = item.optInt("slot", 0)
            val id = item.optString("id", "")
            if (slot <= 0 || id.isEmpty() || sites.any { it.slot == slot || it.id == id }) continue
            sites += Site(
                slot, id,
                if (item.isNull("name")) null else item.optString("name", "").ifEmpty { null },
                item.optBoolean("calls_enabled", true)
            )
        }
        nextSlot = maxOf(json.optInt("next_slot", 1), (sites.maxOfOrNull { it.slot } ?: 0) + 1)
        json.optJSONObject("doors")?.let { map ->
            map.keys().forEach { key ->
                val slot = map.optInt(key, 0)
                if (sites.any { it.slot == slot }) doors[key] = slot
            }
        }
        legacyDoorsSlot = if (json.has("legacy_doors_slot")) json.optInt("legacy_doors_slot") else null
    }

    companion object {
        private val SITE_ID = Regex("^[a-z0-9_-]{1,64}$")

        /** A link's `site_id` when it is a usable one (`[a-z0-9_-]`, at most 64), else null (then the server keys it). */
        fun validSiteId(raw: String?): String? = raw?.trim()?.takeIf { SITE_ID.matches(it) }

        /**
         * A site's server, `host:port`, the same notion as [org.linhome.webhooks.WebhookConfig.sameServer] (the
         * default port spelled out). Null when [url] has no host.
         */
        fun serverKey(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port != -1) uri.port else if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
            return "$host:$port"
        }

        /**
         * A door's SIP address reduced to `user@host` in lower case (no scheme, display name, port or parameters), as
         * DeviceStore matches doors (user and domain). Null when there is nothing to key.
         */
        fun doorKey(address: String?): String? {
            var value = address?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val open = value.indexOf('<')
            if (open >= 0) value = value.substring(open + 1).substringBefore('>')
            value = value.substringBefore(';').substringBefore('?').trim()
            value = when {
                value.startsWith("sips:", ignoreCase = true) -> value.substring(5)
                value.startsWith("sip:", ignoreCase = true) -> value.substring(4)
                else -> value
            }
            val user = if ('@' in value) value.substringBeforeLast('@') else ""
            var host = value.substringAfterLast('@')
            host = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
            if (host.isEmpty()) return null
            return (if (user.isEmpty()) host else "$user@$host").lowercase()
        }
    }
}
