/*
 * Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android,
 * see https://www.linhome.org).
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

package org.linhome.store

import android.content.Context
import androidx.lifecycle.MutableLiveData
import org.linhome.LinhomeApplication
import org.linhome.entities.Device
import org.linhome.sites.SiteRegistry

/**
 * Doors whose calls this phone doesn't take: as if the door weren't set up here, without touching the main switch,
 * the sites' pauses or anything the site sees. A call from such a door is turned down at once as busy, with no
 * ringing, notification, screen, history entry or report to the site (CoreContext), and the Doors list shows the
 * door greyed out.
 *
 * Kept apart from the doors themselves, by door address ([SiteRegistry.doorKey]): a site's config import removes
 * and adds its doors again, and the choice must survive that.
 */
object DisabledDoors {
    private const val PREFS = "adonis_disabled_doors"
    private const val KEY = "doors"

    /** Bumped on every change, for the screens that show doors. */
    val changed = MutableLiveData(0)

    private val prefs by lazy {
        LinhomeApplication.instance.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun keys(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun isDisabled(device: Device?): Boolean =
        device?.let { SiteRegistry.doorKey(it.address) }?.let { it in keys() } ?: false

    fun setDisabled(device: Device, disabled: Boolean) {
        val key = SiteRegistry.doorKey(device.address) ?: return
        val now = keys().toMutableSet()
        if (!(if (disabled) now.add(key) else now.remove(key))) return
        prefs.edit().putStringSet(KEY, now).commit()
        changed.postValue((changed.value ?: 0) + 1)
    }
}
