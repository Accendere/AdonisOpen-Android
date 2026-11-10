package org.linhome.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linhome.entities.Action

/** A button the site runs (the garage): read from the setup link, kept in the vCard. */
class SiteButtonTest {

    @Test
    fun theSetupLinkCarriesTheMethodOfEachButton() {
        val config = AdonisProvisioner.parseJson(
            """{"devices": [{"name": "2N1", "address": "sip:alameda.porta@sip.linhome.org", "method": "method_dtmf_rfc_4733",
               "actions": [{"type": "action_type_open_door", "code": "7*"},
                           {"type": "action_type_open_garage", "code": "garage", "method": "site"}]}]}"""
        )!!
        val actions = config.devices.single().actions
        assertNull(actions[0].method)
        assertEquals("site", actions[1].method)
        assertEquals("action_open_garage", AdonisProvisioner.mapActionType(actions[1].type))
    }

    @Test
    fun aSiteButtonIsStoredWithItsMethodAndADoorButtonAsBefore() {
        val garage = Action("action_open_garage", "garage", Action.METHOD_SITE)
        val door = Action("action_open_door", "7*")
        assertTrue(garage.isSite())
        assertFalse(door.isSite())
        assertEquals("action_open_garage~garage~site", garage.vcardValue())
        assertEquals("action_open_door~7*", door.vcardValue())
    }
}
