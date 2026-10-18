/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 * Modifications Copyright (c) 2026 Adonis Open Contributors.
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

package org.linhome.linphonecore.extensions

import org.linphone.core.Address

fun Address.getCleanNumber(): String {
    val uri = asStringUriOnly()
    val afterScheme = when {
        uri.startsWith("sips:", ignoreCase = true) -> uri.substring(5)
        uri.startsWith("sip:", ignoreCase = true) -> uri.substring(4)
        else -> uri.substringAfter("sip:")
    }
    return if (afterScheme.contains("@")) {
        afterScheme.substringBefore("@")
    } else {
        val u = username
        if (!u.isNullOrEmpty()) u else afterScheme
    }
}
