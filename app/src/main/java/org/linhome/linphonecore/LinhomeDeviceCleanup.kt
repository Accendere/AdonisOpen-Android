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

import android.content.Context
import org.linphone.core.tools.Log
import org.linphone.core.Account
import org.linphone.core.AccountDevice
import org.linphone.core.AccountManagerServicesRequest
import org.linphone.core.AccountManagerServicesRequestListenerStub
import org.linphone.core.Config
import org.linphone.core.Core
import java.util.UUID

/**
 * Keeps the SIP account's device list on Linhome's server clean, so a door call isn't forked to dead installs.
 *
 * Two parts:
 * - [applyStableInstanceId], before the core starts: the SIP instance id (`+sip.instance`, Linphone's `[misc] uuid`)
 *   is derived from ANDROID_ID, so a reinstall registers as the same device and replaces its own entry instead of
 *   adding one.
 * - [onRegistered], once a day per account after a successful registration: lists the account's devices through
 *   Linhome's account API (FlexiAPI, `accounts/me/devices`, authenticated with the account itself) and deletes the
 *   dead ones ([StaleDevices] decides which).
 *
 * Only for accounts on Linhome's SIP server: another SIP server has no such API.
 */
object LinhomeDeviceCleanup {
    private val DOMAINS = setOf("sip.linhome.org")
    private const val SECTION = "adonis"
    private const val KEY_LAST_RUN = "device_cleanup_last_run"
    private const val INTERVAL_MS = 24L * 3600 * 1000

    /** Sets `[misc] uuid` to the id derived from ANDROID_ID; leaves it alone when the phone gives no usable id. */
    fun applyStableInstanceId(context: Context, config: Config) {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        // Some old emulators and broken builds share one fixed value: not an identity.
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return
        val uuid = UUID.nameUUIDFromBytes("adonis-open-sip-instance:$androidId".toByteArray(Charsets.UTF_8)).toString()
        if (config.getString("misc", "uuid", null) != uuid) {
            Log.i("[DeviceCleanup] SIP instance id set to the stable one (${uuid.take(8)}…)")
            config.setString("misc", "uuid", uuid)
        }
    }

    /** Forget the last run, so the next registration cleans up at once (a new or edited account). */
    fun resetSchedule(config: Config) {
        config.setString(SECTION, KEY_LAST_RUN, null)
    }

    /** A registration of the person's account succeeded: clean up when it is due. */
    fun onRegistered(core: Core, account: Account) {
        val identity = account.params?.identityAddress ?: return
        if (identity.domain !in DOMAINS) return
        val ownUuid = core.config.getString("misc", "uuid", null)?.takeIf { it.isNotBlank() } ?: return
        val key = identity.asStringUriOnly()
        val now = System.currentTimeMillis()
        val last = core.config.getString(SECTION, KEY_LAST_RUN, null)?.split("|", limit = 2)
        if (last != null && last.size == 2 && last[1] == key && (now - (last[0].toLongOrNull() ?: 0L)) < INTERVAL_MS) return
        core.config.setString(SECTION, KEY_LAST_RUN, "$now|$key")

        val services = core.createAccountManagerServices()
        val listRequest = services.createGetDevicesListRequest(identity)
        listRequest.addListener(object : AccountManagerServicesRequestListenerStub() {
            override fun onDevicesListFetched(request: AccountManagerServicesRequest, accountDevices: Array<out AccountDevice>) {
                val devices = accountDevices.associateBy {
                    StaleDevices.RemoteDevice(it.uuid ?: "", it.name, StaleDevices.timestampMs(it.lastUpdateTimestamp))
                }
                val dead = StaleDevices.toDelete(devices.keys.toList(), ownUuid, System.currentTimeMillis())
                Log.i("[DeviceCleanup] ${devices.size} device(s) on the server, ${dead.size} dead: " +
                        dead.joinToString { "${it.name ?: "?"} (${describeAge(it.lastUpdateMs)})" })
                dead.forEach { device ->
                    val deleteRequest = services.createDeleteDeviceRequest(identity, devices.getValue(device))
                    deleteRequest.addListener(object : AccountManagerServicesRequestListenerStub() {
                        override fun onRequestSuccessful(request: AccountManagerServicesRequest, data: String?) {
                            Log.i("[DeviceCleanup] Deleted ${device.name ?: "?"} (${describeAge(device.lastUpdateMs)})")
                        }

                        override fun onRequestError(request: AccountManagerServicesRequest, statusCode: Int, errorMessage: String?, parameterErrors: org.linphone.core.Dictionary?) {
                            Log.w("[DeviceCleanup] Could not delete ${device.name ?: "?"}: $statusCode $errorMessage")
                        }
                    })
                    deleteRequest.submit()
                }
            }

            override fun onRequestError(request: AccountManagerServicesRequest, statusCode: Int, errorMessage: String?, parameterErrors: org.linphone.core.Dictionary?) {
                Log.w("[DeviceCleanup] Could not list the account's devices: $statusCode $errorMessage")
            }
        })
        listRequest.submit()
    }

    private fun describeAge(lastUpdateMs: Long?): String {
        lastUpdateMs ?: return "never seen"
        val days = (System.currentTimeMillis() - lastUpdateMs) / (24L * 3600 * 1000)
        return "last seen ${days}d ago"
    }
}
