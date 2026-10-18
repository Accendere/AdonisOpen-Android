/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 * Modifications Copyright (c) 2026 Adonis Open Contributors.
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

package org.linhome.notifications

import android.app.Activity
import android.os.Bundle
import org.linhome.LinhomeApplication
import org.linhome.linphonecore.extensions.extendedAccept
import org.linphone.core.tools.Log

/**
 * Dedicated unexported Trampoline Activity (android:exported="false")
 * to handle notification action buttons (such as "Accept Call") without
 * routing sensitive actions through the exported MainActivity.
 */
class NotificationTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent?.getBooleanExtra("acceptCall", false) == true) {
                Log.i("[NotificationTrampoline] Accepting call from notification action")
                if (LinhomeApplication.contextExists()) {
                    LinhomeApplication.coreContext.core.currentCall?.extendedAccept()
                }
            }
        } catch (e: Exception) {
            Log.e("[NotificationTrampoline] Error processing notification action: $e")
        } finally {
            finish()
        }
    }
}
