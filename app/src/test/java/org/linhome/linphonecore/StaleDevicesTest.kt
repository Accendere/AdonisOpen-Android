package org.linhome.linphonecore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.linhome.linphonecore.StaleDevices.RemoteDevice

class StaleDevicesTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_000L * day
    private val own = "0b7f1c2e-0000-4000-8000-000000000001"

    private fun names(devices: List<RemoteDevice>) = StaleDevices.toDelete(devices, own, now).map { it.name }

    @Test
    fun ownDeviceIsNeverDeletedEvenWhenOld() {
        assertEquals(emptyList<String>(), names(listOf(RemoteDevice("<urn:uuid:${own.uppercase()}>", "Pixel 8", now - 400 * day))))
    }

    @Test
    fun aTabletSeenThisMonthStays() {
        val devices = listOf(
            RemoteDevice(own, "Pixel 8", now),
            RemoteDevice("tablet", "Galaxy Tab", now - 29 * day),
        )
        assertEquals(emptyList<String>(), names(devices))
    }

    @Test
    fun anythingUnseenFor30DaysGoes() {
        val devices = listOf(
            RemoteDevice(own, "Pixel 8", now),
            RemoteDevice("old-tablet", "Galaxy Tab", now - 30 * day),
        )
        assertEquals(listOf("Galaxy Tab"), names(devices))
    }

    @Test
    fun anEarlierInstallOfThisPhoneGoesAfterADay() {
        val devices = listOf(
            RemoteDevice(own, "Pixel 8", now),
            RemoteDevice("reinstall-1", "Pixel 8", now - 2 * day),
            RemoteDevice("reinstall-2", "Pixel 8", now - 3600_000L),
            RemoteDevice("tablet", "Galaxy Tab", now - 2 * day),
        )
        assertEquals(listOf("Pixel 8"), names(devices))
    }

    @Test
    fun withoutOurOwnEntryOnlyTheAgeRuleApplies() {
        val devices = listOf(
            RemoteDevice("reinstall-1", "Pixel 8", now - 2 * day),
            RemoteDevice("ancient", "Pixel 8", now - 60 * day),
        )
        assertEquals(listOf("Pixel 8"), StaleDevices.toDelete(devices, own, now).map { it.name }.distinct())
        assertEquals(1, StaleDevices.toDelete(devices, own, now).size)
    }

    @Test
    fun unknownLastUpdateIsKept() {
        assertEquals(emptyList<String>(), names(listOf(RemoteDevice("x", "Pixel 8", null))))
    }

    @Test
    fun timestampsInSecondsBecomeMilliseconds() {
        assertEquals(1_700_000_000_000L, StaleDevices.timestampMs(1_700_000_000L))
        assertEquals(1_700_000_000_000L, StaleDevices.timestampMs(1_700_000_000_000L))
        assertEquals(null, StaleDevices.timestampMs(0L))
    }
}
