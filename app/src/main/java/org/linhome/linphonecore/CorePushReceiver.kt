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
package org.linhome.linphonecore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.ensureCoreExists
import org.linphone.mediastream.Log

class CorePushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (LinhomeApplication.contextExists() && !LinhomeApplication.corePreferences.appEnabled) {
            Log.i("[Push Receiver] App is disabled, ignoring push notification")
            return
        }
        ensureCoreExists(context.applicationContext)
        Log.i("[Push Receiver] Push notification received")
    }
}
