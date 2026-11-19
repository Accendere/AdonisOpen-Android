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
package org.linhome.linphonecore

import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingCallFailureTest {

    @Test
    fun busyDoorsGetTheBusyText() {
        assertEquals(OutgoingCallFailure.BUSY, OutgoingCallFailure.of(486, "Busy"))
        assertEquals(OutgoingCallFailure.BUSY, OutgoingCallFailure.of(600, null))
        assertEquals("unable_to_call_device_busy", OutgoingCallFailure.of(486, null).textKey)
    }

    @Test
    fun theSipCodeWinsOverLinphonesReason() {
        assertEquals(OutgoingCallFailure.DECLINED, OutgoingCallFailure.of(603, "Busy"))
        assertEquals(OutgoingCallFailure.OFFLINE, OutgoingCallFailure.of(480, "Declined"))
    }

    @Test
    fun withoutACodeTheReasonDecides() {
        assertEquals(OutgoingCallFailure.BUSY, OutgoingCallFailure.of(0, "Busy"))
        assertEquals(OutgoingCallFailure.OFFLINE, OutgoingCallFailure.of(0, "NotFound"))
        assertEquals(OutgoingCallFailure.FORBIDDEN, OutgoingCallFailure.of(0, "Unauthorized"))
    }

    @Test
    fun offlineAndForbiddenCodes() {
        listOf(404, 410, 480).forEach { assertEquals(OutgoingCallFailure.OFFLINE, OutgoingCallFailure.of(it, null)) }
        listOf(401, 403, 407).forEach { assertEquals(OutgoingCallFailure.FORBIDDEN, OutgoingCallFailure.of(it, null)) }
    }

    @Test
    fun networkFailuresKeepTheGenericText() {
        assertEquals(OutgoingCallFailure.OTHER, OutgoingCallFailure.of(0, "IOError"))
        assertEquals(OutgoingCallFailure.OTHER, OutgoingCallFailure.of(408, "NotAnswered"))
        assertEquals(OutgoingCallFailure.OTHER, OutgoingCallFailure.of(503, null))
        assertEquals(OutgoingCallFailure.OTHER, OutgoingCallFailure.of(0, null))
        assertEquals("unable_to_call_device", OutgoingCallFailure.OTHER.textKey)
    }
}
