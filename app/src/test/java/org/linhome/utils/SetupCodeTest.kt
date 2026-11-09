package org.linhome.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SetupCodeTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"
    private val setupJson = """{"sip":{"username":"pedro","password":"x","domain":"sip.example.org"},"devices":[]}"""

    private fun claimUrl(result: SetupCode): String {
        assertTrue("expected a claim, got $result", result is SetupCode.Claim)
        return (result as SetupCode.Claim).claimUrl
    }

    @Test
    fun configureLinkIsASetup() {
        val link = "adonis://configure?data=" + Base64.getUrlEncoder().encodeToString(setupJson.toByteArray())
        val result = SetupCode.classify(link)
        assertTrue(result is SetupCode.Configure)
        assertEquals(link, (result as SetupCode.Configure).raw)
        assertTrue(SetupCode.classify("adonis://configure?user=pedro&pass=x&domain=sip.example.org") is SetupCode.Configure)
    }

    @Test
    fun configureLinkWithOnlyAnXmlUrlIsNotASetup() {
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("adonis://configure?url=https%3A%2F%2Fexample.org%2Fprov.xml"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("adonis://configure?xml=https%3A%2F%2Fexample.org%2Fprov.xml"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("adonis://configure"))
    }

    @Test
    fun setupJsonAndItsBase64AreASetup() {
        assertTrue(SetupCode.classify(setupJson) is SetupCode.Configure)
        assertTrue(SetupCode.classify(Base64.getEncoder().encodeToString(setupJson.toByteArray())) is SetupCode.Configure)
        assertTrue(SetupCode.classify(Base64.getUrlEncoder().withoutPadding().encodeToString(setupJson.toByteArray())) is SetupCode.Configure)
    }

    @Test
    fun claimLinkGivesItsClaimUrl() {
        val url = "https://alameda.example.org:8124/api/public/adonis-open/claim/$code"
        val link = "adonis://claim?url=" + java.net.URLEncoder.encode(url, "UTF-8")
        assertEquals(url, claimUrl(SetupCode.classify(link)))
    }

    @Test
    fun claimLinkWithAnHttpUrlIsRejected() {
        val link = "adonis://claim?url=" + java.net.URLEncoder.encode("http://alameda.example.org/api/public/adonis-open/claim/$code", "UTF-8")
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify(link))
    }

    @Test
    fun installPageGivesTheClaimUrlOnTheSameHostAndPort() {
        assertEquals(
            "https://alameda.example.org:8124/api/public/adonis-open/claim/$code",
            claimUrl(SetupCode.classify("https://alameda.example.org:8124/api/public/adonis-open/open/$code"))
        )
        assertEquals(
            "https://alameda.example.org/api/public/adonis-open/claim/$code",
            claimUrl(SetupCode.classify("  https://alameda.example.org/api/public/adonis-open/open/$code/ "))
        )
    }

    @Test
    fun aScannedClaimUrlIsUsedAsIs() {
        val url = "https://alameda.example.org/api/public/adonis-open/claim/$code"
        assertEquals(url, claimUrl(SetupCode.classify(url)))
    }

    @Test
    fun httpPageIsRejected() {
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("http://alameda.example.org/api/public/adonis-open/open/$code"))
    }

    @Test
    fun otherPathsAndOldPathsAreRejected() {
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://alameda.example.org/open/$code"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://alameda.example.org/api/adonis-open/claim/$code"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://alameda.example.org/api/public/adonis-open/open/short"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://alameda.example.org/api/public/adonis-open/open/$code?x=1"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://user:pw@alameda.example.org/api/public/adonis-open/open/$code"))
    }

    @Test
    fun garbageIsNotASetup() {
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify(null))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify(""))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("hello world"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("WIFI:S:home;T:WPA;P:secret;;"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("https://www.example.org/"))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("""{"hello":"world"}"""))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("SGVsbG8gd29ybGQ="))
        assertEquals(SetupCode.NotSetupCode, SetupCode.classify("ftp://example.org/x"))
    }

    @Test
    fun theScannedContentNeverShowsInToString() {
        assertTrue(!SetupCode.classify(setupJson).toString().contains("pedro"))
        val claim = SetupCode.classify("https://alameda.example.org/api/public/adonis-open/open/$code")
        assertTrue(!claim.toString().contains(code))
        assertTrue(claim.toString().contains("alameda.example.org"))
    }
}
