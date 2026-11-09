package org.linhome.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportSourceTest {
    private val own = "org.probonis"

    @Test
    fun homeAssistantAndItsVariants() {
        assertEquals(ImportSource.HOME_ASSISTANT, ImportSource.fromReferrer("android-app://io.homeassistant.companion.android", own))
        assertEquals(ImportSource.HOME_ASSISTANT, ImportSource.fromReferrer("android-app://io.homeassistant.companion.android.minimal", own))
        assertEquals(ImportSource.HOME_ASSISTANT, ImportSource.fromReferrer("android-app://io.homeassistant.companion.android.beta/", own))
    }

    @Test
    fun anythingElseIsAnotherApp() {
        assertEquals(ImportSource.OTHER_APP, ImportSource.fromReferrer("android-app://com.android.chrome", own))
        assertEquals(ImportSource.OTHER_APP, ImportSource.fromReferrer("android-app://io.homeassistant.companion.androidx", own))
        assertEquals(ImportSource.OTHER_APP, ImportSource.fromReferrer("https://example.org/", own))
        assertEquals(ImportSource.OTHER_APP, ImportSource.fromReferrer(null, own))
    }

    @Test
    fun ourOwnIntentsAreLeftToTheCaller() {
        assertNull(ImportSource.fromReferrer("android-app://org.probonis", own))
    }

    @Test
    fun siteLabelPrefersTheSiteName() {
        assertEquals("Alameda", ImportSource.siteLabel("Alameda", "a.example.org", "b.example.org", "sip.example.org"))
        assertEquals("a.example.org", ImportSource.siteLabel(null, "a.example.org", "b.example.org", "sip.example.org"))
        assertEquals("b.example.org", ImportSource.siteLabel("", "?", "b.example.org", "sip.example.org"))
        assertEquals("sip.example.org", ImportSource.siteLabel(null, null, null, "sip.example.org"))
        assertNull(ImportSource.siteLabel(null, null, null, null))
    }
}
