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

import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import org.linhome.ui.account.Registration
import org.linphone.core.RegistrationState

/**
 * The SIP account's registration, kept for the account card: the current state, whether it is failing
 * (from a Failed until the next Ok, the same span the `registration` webhook event reports) and why, and
 * since when it has been in between. Fed by [CoreContext]'s core listener.
 */
object RegistrationMonitor {

    data class Snapshot(
        val registration: Registration,
        val failing: Boolean,
        val reason: String?,
        /** [SystemClock.elapsedRealtime] when it entered an in-between state (not Ok, not failing); null otherwise. */
        val progressSinceMs: Long?
    )

    val snapshot = MutableLiveData(Snapshot(Registration.NONE, false, null, null))

    fun now(): Long = SystemClock.elapsedRealtime()

    fun map(state: RegistrationState?): Registration = when (state) {
        RegistrationState.Ok -> Registration.OK
        RegistrationState.Failed -> Registration.FAILED
        RegistrationState.Progress, RegistrationState.Refreshing -> Registration.PROGRESS
        RegistrationState.Cleared -> Registration.CLEARED
        else -> Registration.NONE
    }

    /** A registration state change of the person's account (not the push gateway). */
    fun onStateChanged(state: RegistrationState?, reason: String?) {
        val previous = snapshot.value ?: Snapshot(Registration.NONE, false, null, null)
        val registration = map(state)
        val next = when (registration) {
            Registration.OK -> Snapshot(registration, false, null, null)
            Registration.FAILED -> Snapshot(registration, true, reason?.takeIf { it.isNotBlank() } ?: previous.reason, null)
            else -> Snapshot(
                registration,
                previous.failing,
                previous.reason,
                previous.progressSinceMs ?: if (previous.failing) null else now()
            )
        }
        snapshot.postValue(next)
    }

    /** The account changed (created, edited, forgotten) or Retry was tapped: start over. */
    fun reset(state: RegistrationState?) {
        val registration = map(state)
        snapshot.postValue(
            Snapshot(
                registration, false, null,
                if (registration == Registration.OK || registration == Registration.FAILED) null else now()
            )
        )
    }
}
