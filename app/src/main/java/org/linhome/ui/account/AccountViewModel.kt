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

package org.linhome.ui.account

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.linhome.LinhomeApplication
import org.linhome.customisation.Texts
import org.linhome.entities.LinhomeAccount
import org.linhome.linphonecore.extensions.toHumanReadable
import org.linhome.utils.SiteInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState

/** The Account screen: a summary of the SIP account (who, where, how, its status). */
class AccountViewModel : ViewModel() {
    var account = LinhomeAccount.get()
        private set

    /** `user@domain`, or the "no account" text. */
    val identity = MutableLiveData<String?>()

    /** "Alameda", the transport, the status: one line each. */
    val details = MutableLiveData<String?>()

    val configured = MutableLiveData(account != null)

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            cfg: org.linphone.core.Account,
            state: RegistrationState?,
            message: String
        ) {
            if (cfg == account) refreshDescriptions()
        }
    }

    init {
        LinhomeApplication.coreContext.core.addListener(coreListener)
        refreshDescriptions()
    }

    override fun onCleared() {
        LinhomeApplication.coreContext.core.removeListener(coreListener)
        super.onCleared()
    }

    fun refreshDescriptions() {
        account = LinhomeAccount.get()
        configured.value = account != null
        val current = account
        if (current == null) {
            identity.value = Texts.get("no_account_configured")
            details.value = null
            return
        }
        val address = current.params?.identityAddress
        identity.value = listOfNotNull(address?.username, address?.domain).joinToString("@")
        val status = if (!org.linhome.sites.Sites.receivingCalls()) {
            Texts.get("registration_state_paused")
        } else {
            current.state?.toHumanReadable()
        }
        val site = SiteInfo.name(LinhomeApplication.instance)
        details.value = listOfNotNull(
            site?.let { Texts.get("account_site", it) },
            current.params?.transport?.name?.uppercase()?.let { Texts.get("account_transport", it) },
            status?.let { Texts.get("account_status", it) }
        ).joinToString("\n")
    }
}
