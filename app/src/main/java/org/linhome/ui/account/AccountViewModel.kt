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
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState

class AccountViewModel : ViewModel() {
    val account = LinhomeAccount.get()
    val pushGw = LinhomeAccount.pushGateway()
    val accountDesc = MutableLiveData(getDescription("account_info",account))
    val pushGWDesc = MutableLiveData(getDescription("push_account_info",pushGw))

    private val coreListener = object : CoreListenerStub() {

        override fun onAccountRegistrationStateChanged(
            core: Core,
            cfg: org.linphone.core.Account,
            state: RegistrationState?,
            message: String
        ) {
            if (cfg == account)
                accountDesc.value = getDescription("account_info",account)
            if (cfg == pushGw)
                pushGWDesc.value = getDescription("push_account_info",pushGw)
        }
    }

    init {
        LinhomeApplication.coreContext.core.addListener(coreListener)
    }

    override fun onCleared() {
        LinhomeApplication.coreContext.core.removeListener(coreListener)
        super.onCleared()
    }

    fun refreshRegisters() {
        if (!LinhomeApplication.corePreferences.appEnabled) {
            refreshDescriptions()
            return
        }
        account?.refreshRegister()
        pushGw?.refreshRegister()
    }

    fun refreshDescriptions() {
        accountDesc.value = getDescription("account_info", account)
        pushGWDesc.value = getDescription("push_account_info", pushGw)
    }

    fun getDescription(key:String, account: org.linphone.core.Account?): String? {
        if (account == null) return Texts.get("no_account_configured")
        val stateText = if (!LinhomeApplication.corePreferences.appEnabled) {
            Texts.get("registration_state_paused")
        } else {
            account.state?.toHumanReadable()
        }
        return stateText?.let {
            account.params?.identityAddress?.asStringUriOnly()?.let { it1 ->
                Texts.get(key, it1, it)
            }
        } ?: Texts.get("no_account_configured")
    }

    fun isLinhome() : Boolean {
        return LinhomeAccount.get()?.let {
            it.params.domain == LinhomeApplication.corePreferences.loginDomain
        }?:false
    }


}
