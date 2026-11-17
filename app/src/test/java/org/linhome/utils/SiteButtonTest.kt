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

    @Test
    fun aButtonMayCarryAnIconAndALabel() {
        val config = AdonisProvisioner.parseJson(
            """{"devices": [{"name": "2N1", "address": "sip:a@b",
               "actions": [{"type": "action_type_open_door", "code": "7*", "label": "Porta; rua"},
                           {"type": "action_type_lightup", "code": "5", "icon": "icons/light", "label": ""},
                           {"type": "action_type_unlock", "code": "6", "icon": "../../etc/passwd"}]}]}"""
        )!!
        val (door, light, odd) = config.devices.single().actions
        assertEquals("Porta; rua", door.label)
        assertNull(door.icon)
        assertEquals("light", light.icon)
        assertEquals("", light.label)
        assertNull(odd.icon)
        assertNull(odd.label)
    }

    @Test
    fun iconAndLabelSurviveTheVcard() {
        val plain = Action("action_open_door", "7*", null)
        assertEquals(plain, Action.fromVcard(plain.vcardValue()))
        val site = Action("action_open_garage", "garage", Action.METHOD_SITE)
        assertEquals(site, Action.fromVcard(site.vcardValue()))
        val full = Action("action_open_door", "7*", null, "garage", "Portão ~ & 1")
        assertEquals(full, Action.fromVcard(full.vcardValue()))
        val iconOnly = Action("action_lightup", "5", Action.METHOD_SITE, "light", "")
        assertEquals(iconOnly, Action.fromVcard(iconOnly.vcardValue()))
        assertNull(iconOnly.actionText())
        assertEquals("icons/light", iconOnly.iconName())
        assertEquals("Portão ~ & 1", full.actionText())
        assertNull(Action.fromVcard("only"))
    }
}
