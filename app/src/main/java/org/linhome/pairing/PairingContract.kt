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

import org.json.JSONObject
import org.linhome.claim.ClaimContract
import org.linhome.webhooks.HttpAnswer
import org.linhome.webhooks.WebhookRequest
import org.linhome.webhooks.WebhookTransport
import java.io.IOException
import java.net.URI

/**
 * LAN pairing: an app with no setup finds its site on the Wi-Fi (mDNS `_adonis-open._tcp`), shows a 6-digit
 * code, and an administrator (or the person) approves it on the site. The site then answers with a claim URL,
 * which the app handles exactly like a claim link (contract: "LAN pairing" in Adonis's
 * docs/features/adonis-open-setup-link.md).
 *
 * - start: `POST <pair url>/start` with `{"device_id","platform","device_name"}` -> 201 with the pairing.
 * - poll:  `POST <poll_url>` with `{"pairing_id","device_id"}` -> 202 pending, 200 `{"claim_url"}`, 410 over.
 */
object PairingContract {
    const val SERVICE_TYPE = "_adonis-open._tcp"
    const val PROTOCOL_VERSION = "1"

    private const val MIN_INTERVAL_SECONDS = 2
    private const val DEFAULT_INTERVAL_SECONDS = 3
    private const val DEFAULT_EXPIRES_IN_SECONDS = 600
    private const val MAX_EXPIRES_IN_SECONDS = 3600
    private val CODE = Regex("""^[0-9]{6}$""")

    /**
     * The site an mDNS announcement describes, or null when it isn't a usable one. Only the TXT `url` is used,
     * never the resolved address: the app talks to the site's public hostname, whose certificate matches.
     */
    fun siteFromTxt(attributes: Map<String, ByteArray?>): DiscoveredSite? {
        fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)?.trim()
        if (text("v") != PROTOCOL_VERSION) return null
        val url = validateHttpsUrl(text("url")) ?: return null
        val name = text("name")?.takeIf { it.isNotEmpty() }?.take(80) ?: hostOf(url)
        return DiscoveredSite(name, url)
    }

    /** `https://`, a host, no credentials, query or fragment; trailing slashes dropped. */
    fun validateHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed.trimEnd('/')
    }

    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"

    private fun sameHost(a: String, b: String): Boolean {
        val ua = runCatching { URI(a) }.getOrNull() ?: return false
        val ub = runCatching { URI(b) }.getOrNull() ?: return false
        return ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
    }

    private val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

    fun startRequest(site: DiscoveredSite, deviceId: String, deviceName: String) = WebhookRequest(
        "${site.pairUrl}/start",
        JSONObject()
            .put("device_id", deviceId)
            .put("platform", ClaimContract.PLATFORM)
            .put("device_name", deviceName.take(64))
            .toString(),
        headers
    )

    fun pollRequest(pairing: PendingPairing, deviceId: String) = WebhookRequest(
        pairing.pollUrl,
        JSONObject().put("pairing_id", pairing.pairingId).put("device_id", deviceId).toString(),
        headers
    )

    fun classifyStart(site: DiscoveredSite, answer: HttpAnswer): StartOutcome {
        val json = answer.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        return when (answer.status) {
            200, 201 -> {
                if (json == null) return StartOutcome.Unreachable("answer is not JSON")
                val pairingId = json.optString("pairing_id").trim()
                val code = json.optString("code").trim()
                // The poll carries the pairing secret: only ever to the host that was announced.
                val pollUrl = validateHttpsUrl(json.optString("poll_url"))?.takeIf { sameHost(it, site.pairUrl) }
                if (pairingId.length < 16 || !CODE.matches(code) || pollUrl == null) {
                    return StartOutcome.Unreachable("unexpected start answer")
                }
                StartOutcome.Started(
                    PendingPairing(
                        site = site,
                        pairingId = pairingId,
                        code = code,
                        pollUrl = pollUrl,
                        expiresInSeconds = json.optInt("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
                            .takeIf { it > 0 }?.coerceAtMost(MAX_EXPIRES_IN_SECONDS) ?: DEFAULT_EXPIRES_IN_SECONDS,
                        intervalSeconds = json.optInt("interval", DEFAULT_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS),
                        approvePath = approvePath(json.optString("approve_path", ""))
                    )
                )
            }
            429 -> StartOutcome.TooMany(retryAfter(answer))
            else -> StartOutcome.Unreachable("HTTP ${answer.status}")
        }
    }

    fun classifyPoll(pairing: PendingPairing, answer: HttpAnswer): PollOutcome {
        val json = answer.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        return when (answer.status) {
            202 -> PollOutcome.Pending
            200 -> {
                // The claim must come from the same site; anything else is treated as a broken answer.
                val claimUrl = ClaimContract.validate(json?.optString("claim_url"))?.takeIf { sameHost(it, pairing.site.pairUrl) }
                if (claimUrl == null) PollOutcome.Unreachable("unexpected poll answer") else PollOutcome.Approved(claimUrl)
            }
            410 -> if (json?.optString("code") == "pair.declined") PollOutcome.Declined else PollOutcome.Expired
            404 -> PollOutcome.Expired
            409 -> PollOutcome.OtherDevice
            429 -> PollOutcome.TooMany(retryAfter(answer))
            else -> PollOutcome.Unreachable("HTTP ${answer.status}")
        }
    }

    private fun retryAfter(answer: HttpAnswer) = answer.retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    fun start(transport: WebhookTransport, site: DiscoveredSite, deviceId: String, deviceName: String): StartOutcome = try {
        classifyStart(site, transport.send(startRequest(site, deviceId, deviceName)))
    } catch (e: IOException) {
        StartOutcome.Unreachable(e.javaClass.simpleName)
    }

    fun poll(transport: WebhookTransport, pairing: PendingPairing, deviceId: String): PollOutcome = try {
        classifyPoll(pairing, transport.send(pollRequest(pairing, deviceId)))
    } catch (e: IOException) {
        PollOutcome.Unreachable(e.javaClass.simpleName)
    }
}

/** A site found on the Wi-Fi: its display name and its pairing base URL (`https://…/api/adonis-open/pair`). */
data class DiscoveredSite(val name: String, val pairUrl: String) {
    val host: String get() = PairingContract.hostOf(pairUrl)
}

/**
 * `approve_path`: a Home Assistant dashboard path (`/dashboard-porta/campainha`) where the code is approved.
 * Only a plain absolute path is kept (letters, digits, `-`, `_`, `/`, `.`), never a URL: the app turns it
 * into `homeassistant://navigate<path>`.
 */
fun approvePath(raw: String?): String? {
    val path = raw?.trim().orEmpty()
    if (path.isEmpty() || !path.startsWith("/") || path.startsWith("//") || path.length > 200) return null
    return path.takeIf { Regex("^[A-Za-z0-9/_.-]+$").matches(it) && !it.contains("..") }
}

/** A pairing the site accepted and is waiting to approve. [pairingId] is a secret: hidden from toString(). */
class PendingPairing(
    val site: DiscoveredSite,
    val pairingId: String,
    val code: String,
    val pollUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    /** Where the code is approved in Home Assistant (see [approvePath]); null when the site didn't say. */
    val approvePath: String? = null
) {
    override fun toString() = "PendingPairing(host=${site.host}, code=$code)"
}

sealed class StartOutcome {
    class Started(val pairing: PendingPairing) : StartOutcome() {
        override fun toString() = "Started($pairing)"
    }
    data class TooMany(val retryAfterSeconds: Long?) : StartOutcome()
    data class Unreachable(val reason: String) : StartOutcome()
}

sealed class PollOutcome {
    object Pending : PollOutcome()

    /** Approved: continue as a claim link. [claimUrl] is a secret: hidden from toString(). */
    class Approved(val claimUrl: String) : PollOutcome() {
        override fun toString() = "Approved(host=${PairingContract.hostOf(claimUrl)})"
    }

    object Declined : PollOutcome()
    object Expired : PollOutcome()
    object OtherDevice : PollOutcome()
    data class TooMany(val retryAfterSeconds: Long?) : PollOutcome()
    data class Unreachable(val reason: String) : PollOutcome()
}
