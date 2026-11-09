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

package org.linhome.ui.assistant.loginsip

import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.entities.LinhomeAccount
import org.linhome.store.DeviceStore
import org.linhome.ui.assistant.shared.CreatorAssistantViewModel
import org.linhome.ui.assistant.shared.FlexiApiPushAccountCreationViewModel
import org.linphone.core.Account
import org.linphone.core.AccountCreator
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log

class LoginSipAccountViewModel :
    FlexiApiPushAccountCreationViewModel(corePreferences.sipAccountDefaultValuesPath) {

    var username: Pair<MutableLiveData<String>, MutableLiveData<Boolean>> =
        Pair(MutableLiveData(), MutableLiveData<Boolean>(false))
    var domain: Pair<MutableLiveData<String>, MutableLiveData<Boolean>> =
        Pair(MutableLiveData(corePreferences.loginDomain ?: "sip.linhome.org"), MutableLiveData<Boolean>(true))
    var pass1: Pair<MutableLiveData<String>, MutableLiveData<Boolean>> =
        Pair(MutableLiveData(), MutableLiveData<Boolean>(false))
    var transport: MutableLiveData<Int> = MutableLiveData<Int>(TransportType.Tls.toInt())
    var proxy: Pair<MutableLiveData<String?>, MutableLiveData<Boolean>> =
        Pair(MutableLiveData(), MutableLiveData<Boolean>(false))
    var expiration: Pair<MutableLiveData<String>, MutableLiveData<Boolean>> = Pair(
        MutableLiveData<String>(
            corePreferences.config.getString(
                "proxy_default_values",
                "reg_expires",
                "31536000"
            )
        ), MutableLiveData<Boolean>(false)
    )

    val moreOptionsOpened = MutableLiveData(false)
    val sipRegistered = MutableLiveData<Boolean>()
    var coreListener : CoreListenerStub? = null

    protected fun transportToString(transport: TransportType) : String {
        when (transport) {
            TransportType.Tls -> return "tls"
            TransportType.Tcp -> return "tcp"
            TransportType.Udp -> return "udp"
            TransportType.Dtls -> return "dtls"
        }
    }

    fun sipAccountLogin(
        proxy: String?,
        expiration: String,
        sipRegistered: MutableLiveData<Boolean>
    ) {
        val transports = arrayOf("udp","tcp","tls")
        accountCreator.createAccountInCore()
        if (LinhomeApplication.coreContext.core.accountList.isEmpty()) {
            sipRegistered.value = false
            return
        }
        LinhomeApplication.coreContext.core.accountList.first()?.also { account ->
            Log.i("[Account] created Account with domain ${account.params.domain}")
            account.params.clone().also { newParams ->
                newParams.expires = expiration.toInt()
                newParams.transport = accountCreator.transport
                newParams.pushNotificationAllowed = true
                if (!TextUtils.isEmpty(proxy)) {
                    val cleanProxy = if (proxy?.startsWith("sip") == true) proxy.replace("sip:","")
                        .replace("sips:","") else proxy
                    Factory.instance().createAddress((if (accountCreator.transport == TransportType.Tls)  "sips:" else "sip:") + cleanProxy!! + ";transport="+transportToString(accountCreator.transport))?.also {
                        Log.i("[Account] Setting route address to ${it.asString()}")
                        newParams.setRoutesAddresses(arrayOf(it))
                    }
                }
                account.params = newParams
            }

            coreListener = object : CoreListenerStub() {
                override fun onAccountRegistrationStateChanged(
                    core: Core,
                    account: Account,
                    state: RegistrationState?,
                    message: String
                ) {
                    if (state == RegistrationState.Ok) {
                        LinhomeApplication.coreContext.core.removeListener(coreListener)
                        sipRegistered.value = true
                        handlePushAccount()
                        GlobalScope.launch(context = Dispatchers.Main) {
                            DeviceStore.fetchVCards()
                        }
                    }
                    if (state == RegistrationState.Failed) {
                        LinhomeApplication.coreContext.core.removeListener(coreListener)
                        sipRegistered.value = false
                    }
                }
            }
            LinhomeApplication.coreContext.core.addListener(coreListener)
            account.refreshRegister()
        }
    }

    fun valid(): Boolean {
        return username.second.value!! && domain.second.value!! && pass1.second.value!! && expiration.second.value!!
    }

    // ---------- Edit account (Account > Edit account): the same form, saving the account in place ----------

    enum class EditResult { SAVED, NEED_PASSWORD, INVALID }

    /** Fills the form with the current account. The password stays empty: empty keeps the current one. */
    var prefilled = false
        private set

    fun prefillFromCurrentAccount() {
        prefilled = true
        val params = LinhomeAccount.get()?.params ?: return
        val identity = params.identityAddress
        username.first.value = identity?.username
        username.second.value = !identity?.username.isNullOrEmpty()
        domain.first.value = identity?.domain
        domain.second.value = !identity?.domain.isNullOrEmpty()
        pass1.second.value = true
        transport.value = (params.transport ?: TransportType.Udp).toInt()
        proxy.first.value = params.routesAddresses?.firstOrNull()?.let { route ->
            val host = route.domain ?: return@let null
            if (route.port > 0) "$host:${route.port}" else host
        }
        proxy.second.value = true
        if (params.expires > 0) expiration.first.value = params.expires.toString()
        expiration.second.value = true
        moreOptionsOpened.value = true
    }

    /**
     * Saves the form into the current account without removing it (devices, history and the webhooks stay as
     * they are). An empty [newPassword] keeps the current one; a new user name or domain needs a password
     * when the current one can't be read back.
     */
    fun saveEdits(newPassword: String?): EditResult {
        val core = coreContext.core
        val account = LinhomeAccount.get() ?: return EditResult.INVALID
        val user = username.first.value?.trim().orEmpty()
        val dom = domain.first.value?.trim().orEmpty()
        if (user.isEmpty() || dom.isEmpty()) return EditResult.INVALID
        val transportType = TransportType.fromInt(transport.value ?: TransportType.Udp.toInt())
        val oldIdentity = account.params.identityAddress
        val oldAuth = account.findAuthInfo() ?: core.findAuthInfo(null, oldIdentity?.username ?: user, oldIdentity?.domain ?: dom)
        val identityChanged = user != oldIdentity?.username || dom != oldIdentity?.domain
        val typedPassword = newPassword?.takeIf { it.isNotEmpty() }
        val password = typedPassword ?: oldAuth?.password?.takeIf { it.isNotEmpty() }
        if (password == null && identityChanged) return EditResult.NEED_PASSWORD

        val identity = Factory.instance().createAddress("sip:$user@$dom") ?: return EditResult.INVALID
        oldIdentity?.displayName?.let { identity.displayName = it }
        val server = Factory.instance().createAddress("sip:$dom") ?: return EditResult.INVALID
        server.transport = transportType

        val callsEnabled = corePreferences.appEnabled
        val params = account.params.clone()
        params.identityAddress = identity
        params.serverAddress = server
        params.transport = transportType
        val cleanProxy = proxy.first.value?.trim()?.removePrefix("sips:")?.removePrefix("sip:")
        if (cleanProxy.isNullOrEmpty()) {
            params.setRoutesAddresses(emptyArray())
        } else {
            Factory.instance().createAddress(
                (if (transportType == TransportType.Tls) "sips:" else "sip:") + cleanProxy + ";transport=" + transportToString(transportType)
            )?.also { params.setRoutesAddresses(arrayOf(it)) }
        }
        val wantedExpires = expiration.first.value?.toIntOrNull()?.takeIf { it > 0 } ?: params.expires
        params.isRegisterEnabled = callsEnabled
        params.expires = if (callsEnabled) wantedExpires else 0

        if (typedPassword != null || identityChanged) {
            oldAuth?.let { core.removeAuthInfo(it) }
            core.addAuthInfo(Factory.instance().createAuthInfo(user, null, password, null, null, dom))
        }
        account.params = params
        if (identityChanged && dom != oldIdentity?.domain) {
            // Another server: the site name remembered with the old one no longer applies.
            org.linhome.utils.SiteInfo.setName(LinhomeApplication.instance, null)
        }
        org.linhome.linphonecore.RegistrationMonitor.reset(org.linphone.core.RegistrationState.Progress)
        if (callsEnabled) account.refreshRegister()
        Log.i("[Account] Saved the account in place (${transportToString(transportType)})")
        return EditResult.SAVED
    }

}
