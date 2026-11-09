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

import org.linhome.claim.ClaimContract
import java.net.URI
import java.net.URLDecoder
import kotlin.io.encoding.Base64

/**
 * What a scanned code (or any text handed to "Scan a setup code") is. Pure: no Android, so it is unit tested.
 * The scanned text holds secrets (a setup's password, a claim code): callers never log it.
 */
sealed class SetupCode {
    /** Setup data: an `adonis://configure` link, its JSON, or that JSON in base64. Parsed by [AdonisProvisioner.parse]. */
    class Configure(val raw: String) : SetupCode() {
        override fun toString() = "Configure(<hidden>)"
    }

    /** A claim URL (https, `/claim/<code>`): fetched by [org.linhome.claim.Claims]. */
    class Claim(val claimUrl: String) : SetupCode() {
        override fun toString() = "Claim(host=${ClaimContract.hostOf(claimUrl)})"
    }

    /** Anything else: not an Adonis Open setup code. */
    object NotSetupCode : SetupCode()

    companion object {
        /** The site's install page, `https://<host>[:port]/api/public/adonis-open/open/<code>`. */
        private const val PAGE_PATH = "/api/public/adonis-open/open/"

        /** The claim URL behind that page, on the same host and port. */
        private const val CLAIM_PATH = "/api/public/adonis-open/claim/"

        /** A claim code: base64url, at least 16 characters (the site uses 43). */
        private val CODE = Regex("[A-Za-z0-9_-]{16,}")

        /** Query parameters that make an `adonis://configure` link carry a setup. */
        private val CONFIGURE_PARAMS = setOf(
            "data", "config", "payload", "user", "username", "device_name", "device_address", "device_sip"
        )

        fun classify(input: String?): SetupCode {
            val text = input?.trim().orEmpty()
            if (text.isEmpty()) return NotSetupCode
            if (text.startsWith("adonis://", ignoreCase = true)) return classifyAdonisLink(text)
            if (text.startsWith("https://", ignoreCase = true)) return classifyHttps(text)
            if (text.contains("://")) return NotSetupCode
            if (looksLikeSetupJson(text)) return Configure(text)
            decodeBase64Json(text)?.let { if (looksLikeSetupJson(it)) return Configure(text) }
            return NotSetupCode
        }

        private fun classifyAdonisLink(text: String): SetupCode {
            val uri = runCatching { URI(text) }.getOrNull() ?: return NotSetupCode
            val params = queryParams(uri.rawQuery)
            if ("claim".equals(uri.host, ignoreCase = true)) {
                return ClaimContract.validate(params["url"])?.let { Claim(it) } ?: NotSetupCode
            }
            // Only setup data counts: a link carrying only url= (Linhome's XML provisioning) is not a setup.
            return if (params.keys.any { it.lowercase() in CONFIGURE_PARAMS }) Configure(text) else NotSetupCode
        }

        private fun classifyHttps(text: String): SetupCode {
            val uri = runCatching { URI(text) }.getOrNull() ?: return NotSetupCode
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return NotSetupCode
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val authority = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
            for (prefix in listOf(PAGE_PATH, CLAIM_PATH)) {
                if (path.startsWith(prefix)) {
                    val code = path.removePrefix(prefix)
                    if (!CODE.matches(code)) return NotSetupCode
                    return ClaimContract.validate("https://$authority$CLAIM_PATH$code")?.let { Claim(it) } ?: NotSetupCode
                }
            }
            return NotSetupCode
        }

        private fun queryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return rawQuery.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=')
                if (key.isEmpty()) return@mapNotNull null
                val value = pair.substringAfter('=', "")
                val decode = { s: String -> runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s) }
                decode(key) to decode(value)
            }.toMap()
        }

        private fun looksLikeSetupJson(text: String): Boolean =
            text.startsWith("{") && text.endsWith("}") &&
                (text.contains("\"sip\"") || text.contains("\"username\"") || text.contains("\"devices\""))

        private fun decodeBase64Json(text: String): String? {
            val clean = text.replace("\n", "").replace("\r", "")
            val codecs = listOf(
                Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
                Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            )
            for (codec in codecs) {
                val decoded = runCatching { codec.decode(clean).toString(Charsets.UTF_8).trim() }.getOrNull() ?: continue
                if (decoded.startsWith("{") && decoded.endsWith("}")) return decoded
            }
            return null
        }
    }
}
