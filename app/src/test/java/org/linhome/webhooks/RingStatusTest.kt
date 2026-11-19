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
package org.linhome.webhooks

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RingStatusTest {

    private val decode: (String) -> ByteArray? = { Base64.getDecoder().decode(it) }

    @Test
    fun aRingWithItsPicture() {
        val json = JSONObject("""{"success":true,"ringing":true,"door":"2N1","started_at":1795088095,"seconds":7,"snapshot":"${Base64.getEncoder().encodeToString("JPEG".toByteArray())}"}""")
        val ring = RingStatus.parse(json, decode)!!

        assertTrue(ring.ringing)
        assertEquals("2N1", ring.door)
        assertEquals(7, ring.seconds)
        assertArrayEquals("JPEG".toByteArray(), ring.snapshot)
    }

    @Test
    fun nothingRingingAndNoPicture() {
        val ring = RingStatus.parse(JSONObject("""{"success":true,"ringing":false,"door":"2N1","started_at":null,"seconds":null}"""), decode)!!

        assertFalse(ring.ringing)
        assertNull(ring.seconds)
        assertNull(ring.snapshot)
    }

    @Test
    fun aFailedOrBrokenPictureIsNoPicture() {
        assertNull(RingStatus.parse(JSONObject("""{"ringing":true,"snapshot":null,"snapshot_error":"camera busy"}"""), decode)!!.snapshot)
        assertNull(RingStatus.parse(JSONObject("""{"ringing":true,"snapshot":"%%%not base64"}"""), decode)!!.snapshot)
    }

    @Test
    fun anAnswerWithoutRingingIsNotARingStatus() {
        assertNull(RingStatus.parse(JSONObject("""{"success":true,"messages":[]}"""), decode))
        assertNull(RingStatus.parse(null, decode))
    }

    @Test
    fun openResults() {
        assertEquals(RingOpenResult(true, null, "'2N1' opened (switch 1)."), RingOpenResult.parse(JSONObject("""{"opened":true,"reason":null,"message":"'2N1' opened (switch 1)."}""")))
        assertEquals("not_ringing", RingOpenResult.parse(JSONObject("""{"opened":false,"reason":"not_ringing","message":"The door is not ringing any more."}"""))!!.reason)
        assertNull(RingOpenResult.parse(JSONObject("""{"success":true}""")))
    }
}
