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

package org.linhome.entities

import androidx.lifecycle.MutableLiveData
import org.linhome.webhooks.Webhooks
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.linphonecore.extensions.cleanHistory
import org.linhome.store.DeviceStore
import org.linphone.core.Account
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log

object LinhomeAccount {

    const val PUSH_GW_ID_KEY = "linhome_pushgateway"

    val appEnabledLiveData = MutableLiveData<Boolean>()

    private lateinit var coreListener : CoreListenerStub

    fun configured(): Boolean {
        return coreContext.core.accountList.isNotEmpty()
    }

    fun get(): Account? {
        coreContext.core.accountList.forEach {
            if (PUSH_GW_ID_KEY != it.params.idkey)
                return it
        }
        return null
    }

    fun pushGateway(): Account? {
        return coreContext.core.getAccountByIdkey(PUSH_GW_ID_KEY)
    }

    fun linkProxiesWithPushAccount(pushReady: MutableLiveData<Boolean>) {
        pushGateway()?.also { pushAccount ->
            coreContext.core.accountList.forEach {
                if (it.params.idkey != PUSH_GW_ID_KEY) {
                    it.dependency = pushAccount
                    pushAccount.params.clone().also { clonedParams ->
                        clonedParams.expires = it.params.expires
                        pushAccount.params = clonedParams
                        pushAccount.refreshRegister()
                    }
                }
            }
            enablePushAccount(pushAccount)
        }
        pushReady.value = true
    }


    fun disconnect(deletePushAccount:Boolean = false) {
        coreContext.core.accountList.forEach {
            if (!deletePushAccount && it.params?.idkey == PUSH_GW_ID_KEY) {
                disablePushAccount(it)
            } else {
                it.params.clone().also { newParams ->
                    newParams.expires = 0
                    it.params = newParams
                }
                it.refreshRegister()
                coreContext.core.removeAccount(it)
            }
        }
        coreContext.core.cleanHistory()
        coreContext.core.provisioningUri = null
        coreContext.core.config.setString("misc","config-uri",null)
        coreContext.core.stop()
        LinhomeApplication.ensureCoreExists(coreContext.context, force = true, startService = false)
        DeviceStore.clearRemoteProvisionnedDevicesUponLogout()
    }

     fun disablePushAccount(pushAccount:Account) {
        pushAccount.params.clone().also {
            it.expires = 0
            pushAccount.params = it
            pushAccount.refreshRegister()
        }
    }

    fun enablePushAccount(pushAccount:Account) {
        pushAccount.params.clone().also {
            it.expires = corePreferences.config.getInt( "proxy_default_values","reg_expires", 31536000)
            pushAccount.params = it
            pushAccount.refreshRegister()
        }
    }

    /** The phone's main receive-calls switch, changed on the phone: every site hears its resulting state. */
    fun setAppEnabled(enabled: Boolean) {
        storeAppEnabled(enabled)
        Webhooks.onMainSwitchChanged()
        applyRegistration()
    }

    /**
     * Stores the main switch and tells the screens, nothing else: for a site's `receive_calls`, whose own site hears
     * the result with `cause: site` and whose other sites' state doesn't change (see SiteWebhooks.applyReceiveCalls).
     */
    fun storeAppEnabled(enabled: Boolean) {
        corePreferences.appEnabled = enabled
        appEnabledLiveData.postValue(enabled)
    }

    /**
     * Keeps SIP registered, paused or not: a paused phone declines the call when it arrives (CoreContext, before any
     * ringing), so a site that switches the phone back on reaches it with its very next call, not only after the
     * phone's next poll. (Until 2.0.3 a phone paused everywhere unregistered.) The keep-alive service follows the
     * Background mode setting alone, for the same reason: without it Android cuts a background app's network and the
     * registration lapses. Its notification says it keeps the app running, not that calls ring, so it isn't wrong on
     * a paused phone. Also refreshes the tile and the widget after a switch changed.
     */
    fun applyRegistration() {
        registerAll(coreContext.core)
        if (corePreferences.keepServiceAlive) {
            coreContext.notificationsManager.startForeground()
        }
        try {
            org.linhome.widgets.ReceiveCallsWidgetProvider.updateAllWidgets(LinhomeApplication.instance)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.service.quicksettings.TileService.requestListeningState(
                    LinhomeApplication.instance,
                    android.content.ComponentName(LinhomeApplication.instance, org.linhome.quicksettings.ReceiveCallsTileService::class.java)
                )
            }
        } catch (e: Exception) {
            Log.e("[LinhomeAccount] Error notifying widget/tile: ${e.message}")
        }
    }

    /**
     * Registers every account that isn't (an account paused by an older version has registration off and expires 0).
     * Only with a SIP account: after a logout only the push gateway account is left, unregistered on purpose.
     * Also at every start, so a phone updated while paused registers again.
     */
    fun registerAll(core: org.linphone.core.Core) {
        if (core.accountList.none { it.params.idkey != PUSH_GW_ID_KEY }) return
        val defaultExpires = corePreferences.config.getInt("proxy_default_values", "reg_expires", 31536000)
        core.accountList.forEach { account ->
            if (account.params.isRegisterEnabled && account.params.expires != 0) return@forEach
            Log.i("[LinhomeAccount] Registering an account that was unregistered (paused phones stay registered now)")
            account.params.clone().also { newParams ->
                newParams.isRegisterEnabled = true
                newParams.expires = defaultExpires
                account.params = newParams
            }
            account.refreshRegister()
        }
    }

}
