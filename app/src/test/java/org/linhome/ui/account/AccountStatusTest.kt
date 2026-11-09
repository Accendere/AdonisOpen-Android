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
        assertEquals(AccountCardMode.PAUSED, decide(Registration.FAILED, failing = true, callsEnabled = false))
        assertEquals(AccountCardMode.PAUSED, decide(Registration.CLEARED, callsEnabled = false))
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
    fun titleIsUserAndSiteElseDomain() {
        assertEquals("pedro · Alameda", AccountStatus.title("pedro", "Alameda", "sip.example.org"))
        assertEquals("pedro · sip.example.org", AccountStatus.title("pedro", null, "sip.example.org"))
        assertEquals("pedro · sip.example.org", AccountStatus.title("pedro", " ", "sip.example.org"))
        assertEquals("sip.example.org", AccountStatus.title(null, null, "sip.example.org"))
    }
}
