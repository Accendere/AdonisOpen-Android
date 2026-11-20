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
package org.linhome.claim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

class InstallReferrerClaimTest {
    private val claimUrl = "https://site.example.org/api/open/claim/AbCdEfGhIjKlMnOpQrSt_-12"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /** What Play hands the app: the decoded `referrer` parameter of the store link. */
    private val referrer = "adonis_claim=${enc(claimUrl)}"

    @Test
    fun validReferrer() {
        assertEquals(claimUrl, InstallReferrerClaim.parse(referrer))
    }

    @Test
    fun extraUtmKeysAreIgnored() {
        assertEquals(claimUrl, InstallReferrerClaim.parse("utm_source=google-play&$referrer&utm_medium=organic"))
        assertEquals(claimUrl, InstallReferrerClaim.parse("utm_source=google-play&utm_medium=organic&$referrer"))
    }

    @Test
    fun missingKey() {
        assertNull(InstallReferrerClaim.parse("utm_source=google-play&utm_medium=organic"))
        assertNull(InstallReferrerClaim.parse("adonis_claim="))
        assertNull(InstallReferrerClaim.parse("other_adonis_claim=${enc(claimUrl)}"))
        assertNull(InstallReferrerClaim.parse(""))
        assertNull(InstallReferrerClaim.parse(null))
    }

    @Test
    fun nonHttpsIsRejected() {
        assertNull(InstallReferrerClaim.parse("adonis_claim=${enc("http://site.example.org/api/open/claim/AbCdEfGhIjKlMnOp")}"))
        assertNull(InstallReferrerClaim.parse("adonis_claim=${enc("adonis://claim?url=https://site.example.org/x")}"))
        assertNull(InstallReferrerClaim.parse("adonis_claim=${enc("javascript:alert(1)")}"))
        // Credentials, query or fragment: refused like an adonis://claim link.
        assertNull(InstallReferrerClaim.parse("adonis_claim=${enc("https://user@site.example.org/claim/AbCdEfGhIjKlMnOp")}"))
        assertNull(InstallReferrerClaim.parse("adonis_claim=${enc("$claimUrl?x=1")}"))
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(InstallReferrerClaim.parse("%%%"))
        assertNull(InstallReferrerClaim.parse("adonis_claim=%E0%A4%A"))
        assertNull(InstallReferrerClaim.parse("adonis_claim=not a url"))
        assertNull(InstallReferrerClaim.parse("&&&==="))
        assertNull(InstallReferrerClaim.parse("adonis_claim=https%3A%2F%2F"))
    }

    @Test
    fun doubleEncodingIsTolerated() {
        // The whole referrer still encoded (the store link's parameter as-is).
        assertEquals(claimUrl, InstallReferrerClaim.parse(enc(referrer)))
        // The value encoded twice.
        assertEquals(claimUrl, InstallReferrerClaim.parse("adonis_claim=${enc(enc(claimUrl))}"))
        // An unencoded value works too.
        assertEquals(claimUrl, InstallReferrerClaim.parse("adonis_claim=$claimUrl"))
    }
}
