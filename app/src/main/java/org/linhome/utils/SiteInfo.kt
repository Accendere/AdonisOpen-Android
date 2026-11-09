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
package org.linhome.utils

import android.content.Context

/** The site's display name, remembered with the SIP account it came with (shown on the account card). */
object SiteInfo {
    private const val PREFS = "adonis_site"
    private const val KEY_NAME = "name"

    fun name(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)

    /** Null forgets it (an account set up by hand, a setup without a name, a forgotten account). */
    fun setName(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (name.isNullOrBlank()) remove(KEY_NAME) else putString(KEY_NAME, name.trim())
        }.apply()
    }
}
