package org.linhome.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountStatusTest {
    private fun decide(
        registration: Registration,
        failing: Boolean = false,
        progressSince: Long? = 1_000L,
        now: Long = 1_000L,
        configured: Boolean = true,
        callsEnabled: Boolean = true
    ) = AccountStatus.decide(configured, callsEnabled, registration, failing, progressSince, now)

    @Test
    fun noAccountShowsTheSetupChoices() {
        assertEquals(AccountCardMode.NOT_CONFIGURED, decide(Registration.NONE, configured = false))
    }

    @Test
    fun registeredIsConnected() {
        assertEquals(AccountCardMode.CONNECTED, decide(Registration.OK, progressSince = null))
    }

    @Test
    fun registeringShowsNothingForThreeSecondsThenConnecting() {
        assertEquals(AccountCardMode.QUIET, decide(Registration.PROGRESS, now = 1_000L))
        assertEquals(AccountCardMode.QUIET, decide(Registration.PROGRESS, now = 3_999L))
        assertEquals(AccountCardMode.CONNECTING, decide(Registration.PROGRESS, now = 4_000L))
        assertEquals(AccountCardMode.CONNECTING, decide(Registration.NONE, now = 60_000L))
    }

    @Test
    fun unknownStartIsQuiet() {
        assertEquals(AccountCardMode.QUIET, decide(Registration.PROGRESS, progressSince = null, now = 99_000L))
    }

    @Test
    fun failedShowsTheBannerUntilItWorksAgain() {
        assertEquals(AccountCardMode.FAILED, decide(Registration.FAILED, failing = true, progressSince = null))
        // Linphone retries: in progress again, still failing.
        assertEquals(AccountCardMode.FAILED, decide(Registration.PROGRESS, failing = true, progressSince = null, now = 50_000L))
        assertEquals(AccountCardMode.CONNECTED, decide(Registration.OK, failing = false, progressSince = null))
    }

    @Test
    fun pausedCallsAreNotAProblem() {
        assertEquals(AccountCardMode.PAUSED, decide(Registration.CLEARED, callsEnabled = false))
        assertEquals(AccountCardMode.PAUSED, decide(Registration.OK, callsEnabled = false))
        assertEquals(AccountCardMode.PAUSED, decide(Registration.PROGRESS, callsEnabled = false, now = 60_000L))
    }

    @Test
    fun anErrorIsNeverHiddenBehindThePause() {
        assertEquals(AccountCardMode.FAILED, decide(Registration.FAILED, failing = true, callsEnabled = false))
        // Paused after it started failing: unregistered (Cleared), the error stays until a registration works.
        assertEquals(AccountCardMode.FAILED, decide(Registration.CLEARED, failing = true, callsEnabled = false))
    }

    @Test
    fun nextCheckIsWhenTheQuietPeriodEnds() {
        assertEquals(2_500L, AccountStatus.nextCheckInMs(AccountCardMode.QUIET, 1_000L, 1_500L))
        assertEquals(0L, AccountStatus.nextCheckInMs(AccountCardMode.QUIET, 1_000L, 9_000L))
        assertNull(AccountStatus.nextCheckInMs(AccountCardMode.CONNECTING, 1_000L, 9_000L))
        assertNull(AccountStatus.nextCheckInMs(AccountCardMode.CONNECTED, null, 9_000L))
        assertNull(AccountStatus.nextCheckInMs(AccountCardMode.QUIET, null, 9_000L))
    }

    @Test
    fun siteLabelWithNoOneOrSeveralSites() {
        // None: the name remembered with the account, if any.
        assertEquals("Old name", AccountStatus.siteLabel(emptyList(), "Old name"))
        assertNull(AccountStatus.siteLabel(emptyList(), null))
        // One: its name, whatever the remembered one says.
        assertEquals("Alameda", AccountStatus.siteLabel(listOf("Alameda"), "Lumiar"))
        // Several: joined; a site without a name is left out.
        assertEquals("Alameda · Lumiar", AccountStatus.siteLabel(listOf("Alameda", "Lumiar"), "Old name"))
        assertEquals("Alameda · Lumiar", AccountStatus.siteLabel(listOf("Alameda", null, " ", "Lumiar"), null))
        // Sites with no names at all: as with none.
        assertEquals("Old name", AccountStatus.siteLabel(listOf(null), "Old name"))
    }

    @Test
    fun theCardTitleCarriesTheSiteLabel() {
        val label = AccountStatus.siteLabel(listOf("Alameda", "Lumiar"), null)
        assertEquals("pedro · Alameda · Lumiar", AccountStatus.title("pedro", label, "sip.example.org"))
        assertEquals("pedro · Alameda", AccountStatus.title("pedro", AccountStatus.siteLabel(listOf("Alameda"), null), "sip.example.org"))
        assertEquals("pedro · sip.example.org", AccountStatus.title("pedro", AccountStatus.siteLabel(emptyList(), null), "sip.example.org"))
    }

    @Test
    fun titleIsUserAndSiteElseDomain() {
        assertEquals("pedro · Alameda", AccountStatus.title("pedro", "Alameda", "sip.example.org"))
        assertEquals("pedro · sip.example.org", AccountStatus.title("pedro", null, "sip.example.org"))
        assertEquals("pedro · sip.example.org", AccountStatus.title("pedro", " ", "sip.example.org"))
        assertEquals("sip.example.org", AccountStatus.title(null, null, "sip.example.org"))
    }
}
