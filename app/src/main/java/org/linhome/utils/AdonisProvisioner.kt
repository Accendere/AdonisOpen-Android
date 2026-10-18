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

package org.linhome.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.customisation.Texts
import org.linhome.entities.Action
import org.linhome.entities.Device
import org.linhome.entities.LinhomeAccount
import org.linhome.store.DeviceStore
import org.linphone.core.Factory
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import java.nio.charset.StandardCharsets

data class AdonisActionConfig(
    val type: String = "action_type_open_door",
    val code: String = "#"
)

data class AdonisDeviceConfig(
    val name: String,
    val address: String,
    val type: String = "device_type_video_intercom",
    val method: String = "method_dtmf_sip_info",
    val actions: List<AdonisActionConfig> = listOf(AdonisActionConfig()),
    val alternativeAddress: String? = null
)

data class AdonisSipConfig(
    val username: String,
    val password: String,
    val domain: String,
    val proxy: String? = null,
    val transport: String? = "udp",
    val expires: Int = 31536000,
    val displayName: String? = null
)

data class AdonisConfig(
    val sip: AdonisSipConfig? = null,
    val devices: List<AdonisDeviceConfig> = emptyList(),
    val remoteProvisioningUrl: String? = null
)

object AdonisProvisioner {

    private const val TAG = "[AdonisProvisioner]"

    fun isAdonisConfig(input: String?): Boolean {
        if (input.isNullOrBlank()) return false
        val trimmed = input.trim()
        if (trimmed.startsWith("adonis://", ignoreCase = true)) return true
        if (trimmed.startsWith("{") && (trimmed.contains("\"sip\"") || trimmed.contains("\"username\"") || trimmed.contains("\"devices\""))) {
            return true
        }
        val decoded = tryDecodeBase64(trimmed)
        if (decoded != null && decoded.startsWith("{") && (decoded.contains("\"sip\"") || decoded.contains("\"username\"") || decoded.contains("\"devices\""))) {
            return true
        }
        return false
    }

    private fun tryDecodeBase64(raw: String): String? {
        val clean = raw.trim().replace("\n", "").replace("\r", "")
        for (flags in intArrayOf(Base64.URL_SAFE or Base64.NO_WRAP, Base64.DEFAULT, Base64.NO_PADDING or Base64.NO_WRAP)) {
            try {
                val bytes = Base64.decode(clean, flags)
                val str = String(bytes, StandardCharsets.UTF_8).trim()
                if (str.startsWith("{") && str.endsWith("}")) {
                    return str
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parse(input: String): AdonisConfig? {
        val trimmed = input.trim()
        if (trimmed.startsWith("adonis://", ignoreCase = true)) {
            val uri = Uri.parse(trimmed)
            return parseUri(uri)
        }
        val directJson = if (trimmed.startsWith("{")) trimmed else tryDecodeBase64(trimmed)
        if (directJson != null) {
            return parseJson(directJson)
        }
        return null
    }

    private fun sanitizeUriForLog(uri: Uri): String {
        return try {
            val builder = uri.buildUpon().clearQuery()
            for (param in uri.queryParameterNames) {
                val lower = param.lowercase()
                if (lower.contains("pass") || lower.contains("pwd") || lower.contains("data") ||
                    lower.contains("payload") || lower.contains("config") || lower.contains("key") || lower.contains("secret")) {
                    builder.appendQueryParameter(param, "REDACTED")
                } else {
                    builder.appendQueryParameter(param, uri.getQueryParameter(param))
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            "${uri.scheme}://${uri.host ?: "configure"}..."
        }
    }

    fun parseUri(uri: Uri): AdonisConfig? {
        Log.i("$TAG Parsing URI: ${sanitizeUriForLog(uri)}")
        // Check for base64 encoded data parameter: adonis://configure?data=... or ?config=...
        val rawData = uri.getQueryParameter("data") ?: uri.getQueryParameter("config") ?: uri.getQueryParameter("payload")
        if (!rawData.isNullOrBlank()) {
            val decodedJson = tryDecodeBase64(rawData) ?: run {
                // If it wasn't base64, maybe it was directly URL-encoded JSON
                if (rawData.trim().startsWith("{")) rawData.trim() else null
            }
            if (decodedJson != null) {
                return parseJson(decodedJson)
            }
        }

        // Check for remote XML provisioning URL: adonis://configure?url=...
        val remoteUrl = uri.getQueryParameter("url") ?: uri.getQueryParameter("xml")
        if (!remoteUrl.isNullOrBlank()) {
            val trimmedUrl = remoteUrl.trim()
            val parsedUrl = try { Uri.parse(trimmedUrl) } catch (_: Exception) { null }
            val scheme = parsedUrl?.scheme?.lowercase()
            if (scheme == "https" || scheme == "http") {
                return AdonisConfig(remoteProvisioningUrl = trimmedUrl)
            } else {
                Log.w("$TAG Rejected remote provisioning URL with unsupported scheme: $scheme")
                return null
            }
        }

        // Direct query parameters: adonis://configure?user=...&pass=...&domain=...
        val user = uri.getQueryParameter("user") ?: uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password")
        val domain = uri.getQueryParameter("domain") ?: uri.getQueryParameter("host")

        val sipConfig = if (!user.isNullOrBlank() && !pass.isNullOrBlank() && !domain.isNullOrBlank()) {
            AdonisSipConfig(
                username = user,
                password = pass,
                domain = domain,
                proxy = uri.getQueryParameter("proxy"),
                transport = uri.getQueryParameter("transport") ?: "udp",
                expires = uri.getQueryParameter("expires")?.toIntOrNull() ?: 31536000,
                displayName = uri.getQueryParameter("display_name") ?: uri.getQueryParameter("name")
            )
        } else null

        // Device in query parameters
        val devName = uri.getQueryParameter("device_name")
        val devAddr = uri.getQueryParameter("device_address") ?: uri.getQueryParameter("device_sip")
        val devicesList = mutableListOf<AdonisDeviceConfig>()
        if (!devName.isNullOrBlank() && !devAddr.isNullOrBlank()) {
            val devType = uri.getQueryParameter("device_type") ?: "device_type_video_intercom"
            val devMethod = uri.getQueryParameter("device_method") ?: "method_dtmf_sip_info"
            val actType = uri.getQueryParameter("action_type") ?: "action_type_open_door"
            val actCode = uri.getQueryParameter("action_code") ?: "#"
            devicesList.add(
                AdonisDeviceConfig(
                    name = devName,
                    address = devAddr,
                    type = devType,
                    method = devMethod,
                    actions = listOf(AdonisActionConfig(actType, actCode))
                )
            )
        }

        return if (sipConfig != null || devicesList.isNotEmpty()) {
            AdonisConfig(sip = sipConfig, devices = devicesList)
        } else {
            null
        }
    }

    fun parseJson(jsonString: String): AdonisConfig? {
        return try {
            val root = JSONObject(jsonString)

            var sipConfig: AdonisSipConfig? = null
            val sipObj = root.optJSONObject("sip") ?: if (root.has("username") || root.has("user")) root else null
            if (sipObj != null) {
                val user = sipObj.optString("username").ifEmpty { sipObj.optString("user") }
                val pass = sipObj.optString("password").ifEmpty { sipObj.optString("pass") }
                val domain = sipObj.optString("domain").ifEmpty { sipObj.optString("host") }
                if (user.isNotEmpty() && pass.isNotEmpty() && domain.isNotEmpty()) {
                    sipConfig = AdonisSipConfig(
                        username = user,
                        password = pass,
                        domain = domain,
                        proxy = sipObj.optString("proxy").ifEmpty { null },
                        transport = sipObj.optString("transport", "udp"),
                        expires = sipObj.optInt("expiration", sipObj.optInt("expires", 31536000)),
                        displayName = sipObj.optString("display_name").ifEmpty { sipObj.optString("displayName").ifEmpty { null } }
                    )
                }
            }

            val devicesList = mutableListOf<AdonisDeviceConfig>()
            val devicesArray = root.optJSONArray("devices")
            if (devicesArray != null) {
                for (i in 0 until devicesArray.length()) {
                    val devObj = devicesArray.getJSONObject(i)
                    val name = devObj.optString("name").ifEmpty { "Intercom ${i + 1}" }
                    val address = devObj.optString("address").ifEmpty { devObj.optString("sip") }
                    if (address.isNotEmpty()) {
                        val type = devObj.optString("type", "device_type_video_intercom")
                        val method = devObj.optString("method", devObj.optString("actions_method_type", "method_dtmf_sip_info"))
                        val actionsList = mutableListOf<AdonisActionConfig>()
                        val actionsArray = devObj.optJSONArray("actions")
                        if (actionsArray != null) {
                            for (j in 0 until actionsArray.length()) {
                                val actObj = actionsArray.getJSONObject(j)
                                val actType = actObj.optString("type", "action_type_open_door")
                                val actCode = actObj.optString("code", "#")
                                actionsList.add(AdonisActionConfig(actType, actCode))
                            }
                        }
                        if (actionsList.isEmpty()) {
                            actionsList.add(AdonisActionConfig())
                        }
                        val altAddress = devObj.optString("alternative_address").ifEmpty { devObj.optString("alternativeAddress").ifEmpty { null } }
                        devicesList.add(
                            AdonisDeviceConfig(
                                name = name,
                                address = address,
                                type = type,
                                method = method,
                                actions = actionsList,
                                alternativeAddress = altAddress
                            )
                        )
                    }
                }
            }

            val remoteUrl = root.optString("url").ifEmpty { root.optString("xml") }.trim()
            val validRemoteUrl = if (remoteUrl.isNotEmpty()) {
                val parsedUrl = try { Uri.parse(remoteUrl) } catch (_: Exception) { null }
                val scheme = parsedUrl?.scheme?.lowercase()
                if (scheme == "https" || scheme == "http") remoteUrl else null
            } else null

            AdonisConfig(
                sip = sipConfig,
                devices = devicesList,
                remoteProvisioningUrl = validRemoteUrl
            )
        } catch (e: Exception) {
            Log.e("$TAG Error parsing JSON config: $e")
            null
        }
    }

    fun applyConfig(context: Context, config: AdonisConfig, onComplete: ((Boolean, String) -> Unit)? = null) {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                // Case 1: Remote XML Provisioning URL
                if (!config.remoteProvisioningUrl.isNullOrBlank()) {
                    Log.i("$TAG Starting remote provisioning with URL: ${config.remoteProvisioningUrl}")
                    coreContext.core.provisioningUri = config.remoteProvisioningUrl
                    coreContext.core.stop()
                    coreContext.core.start()
                    val msg = "Remote provisioning applied"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(true, msg)
                    return@launch
                }

                // Case 2: SIP Account Configuration
                var sipConfigured = false
                config.sip?.let { sip ->
                    Log.i("$TAG Configuring SIP account for ${sip.username}@${sip.domain}")
                    val core = coreContext.core
                    // Clean up any existing non-push accounts without stopping the core
                    core.accountList.toList().forEach { account ->
                        if (account.params?.idkey != LinhomeAccount.PUSH_GW_ID_KEY) {
                            account.params.clone().also { newParams ->
                                newParams.expires = 0
                                account.params = newParams
                            }
                            account.refreshRegister()
                            core.removeAccount(account)
                        }
                    }
                    core.clearAllAuthInfo()

                    val cleanDomain = sip.domain.trim()
                    val cleanUsername = sip.username.trim()
                    val transportType = when (sip.transport?.lowercase()?.trim()) {
                        "tcp" -> TransportType.Tcp
                        "tls" -> TransportType.Tls
                        "dtls" -> TransportType.Dtls
                        else -> TransportType.Udp
                    }

                    // Use AccountCreator so AuthInfo, Account, and credentials are saved and persisted
                    val accountCreator = core.createAccountCreator(corePreferences.sipAccountDefaultValuesPath)
                    accountCreator.username = cleanUsername
                    accountCreator.password = sip.password
                    accountCreator.domain = cleanDomain
                    accountCreator.transport = transportType

                    val account = accountCreator.createAccountInCore()
                    if (account != null) {
                        account.params.clone().also { newParams ->
                            newParams.expires = sip.expires
                            newParams.transport = transportType
                            newParams.pushNotificationAllowed = true
                            if (!sip.proxy.isNullOrBlank()) {
                                val cleanProxy = sip.proxy.trim().removePrefix("sip:").removePrefix("sips:")
                                val proxyTransport = when (transportType) {
                                    TransportType.Tls -> "tls"
                                    TransportType.Tcp -> "tcp"
                                    TransportType.Dtls -> "dtls"
                                    else -> "udp"
                                }
                                val routeUri = (if (transportType == TransportType.Tls) "sips:" else "sip:") + cleanProxy + ";transport=" + proxyTransport
                                Factory.instance().createAddress(routeUri)?.also { routeAddr ->
                                    newParams.setRoutesAddresses(arrayOf(routeAddr))
                                }
                            }
                            account.params = newParams
                        }
                        core.defaultAccount = account
                        account.refreshRegister()
                        sipConfigured = true

                        if (LinhomeAccount.pushGateway() != null) {
                            LinhomeAccount.linkProxiesWithPushAccount(androidx.lifecycle.MutableLiveData())
                        }
                        Log.i("$TAG SIP account created successfully and registration refreshed")
                    } else {
                        Log.e("$TAG Failed to create account in core using AccountCreator")
                    }
                }

                // Case 3: Device(s) Configuration
                var devicesCount = 0
                for (dev in config.devices) {
                    var formattedAddress = dev.address.trim()
                    if (!formattedAddress.startsWith("sip:", ignoreCase = true) && !formattedAddress.startsWith("sips:", ignoreCase = true)) {
                        formattedAddress = "sip:$formattedAddress"
                    }
                    if (!formattedAddress.contains("@")) {
                        val domain = config.sip?.domain ?: "local"
                        formattedAddress = "$formattedAddress@$domain"
                    }

                    val mappedType = when (dev.type.lowercase().trim()) {
                        "camera", "security_camera", "device_security_camera", "device_type_camera", "device_type_security_camera" -> "device_security_camera"
                        "internal_unit", "monitor", "device_internal_unit", "device_type_internal_unit" -> "device_internal_unit"
                        "audio", "audio_intercom", "device_audio_intercom", "device_type_audio_intercom" -> "device_audio_intercom"
                        else -> "device_video_intercom"
                    }

                    val mappedMethod = when (dev.method.lowercase().trim()) {
                        "rfc2833", "rfc4733", "method_dtmf_rfc_4733", "rfc_4733", "rfc_2833", "dtmf" -> "method_dtmf_rfc_4733"
                        "sip_message", "sipmessage", "method_sip_message", "message" -> "method_sip_message"
                        else -> "method_dtmf_sip_info"
                    }

                    val actionsList = ArrayList<Action>()
                    for (act in dev.actions) {
                        val actType = when (act.type.lowercase().trim()) {
                            "open_gate", "gate", "gate_open", "action_open_gate", "action_type_open_gate" -> "action_open_gate"
                            "lightup", "light", "light_up", "action_lightup", "action_type_lightup" -> "action_lightup"
                            "unlock", "action_unlock", "action_type_unlock" -> "action_unlock"
                            else -> "action_open_door"
                        }
                        actionsList.add(Action(actType, act.code))
                    }
                    if (actionsList.isEmpty()) {
                        actionsList.add(Action("action_open_door", "#"))
                    }

                    DeviceStore.findDeviceByAddress(formattedAddress)?.let { existing ->
                        DeviceStore.removeDevice(existing)
                    }

                    var formattedAltAddress = dev.alternativeAddress?.trim()
                    if (!formattedAltAddress.isNullOrBlank()) {
                        if (!formattedAltAddress.startsWith("sip:") && !formattedAltAddress.startsWith("sips:")) {
                            formattedAltAddress = "sip:$formattedAltAddress"
                        }
                        if (!formattedAltAddress.contains("@")) {
                            val domain = config.sip?.domain ?: "local"
                            formattedAltAddress = "$formattedAltAddress@$domain"
                        }
                    } else {
                        formattedAltAddress = null
                    }

                    val newDevice = Device(
                        mappedType,
                        dev.name,
                        formattedAddress,
                        mappedMethod,
                        actionsList,
                        false,
                        formattedAltAddress
                    )
                    DeviceStore.persistDevice(newDevice)
                    devicesCount++
                }

                if (devicesCount > 0) {
                    DeviceStore.saveLocalDevices()
                    DeviceStore.readDevicesFromFriends()
                    DeviceStore.mutableDevices?.postValue(DeviceStore.devices)
                }

                val successMsg = Texts.get("adonis_autoconfig_success", devicesCount.toString())
                Toast.makeText(context, successMsg, Toast.LENGTH_LONG).show()
                Log.i("$TAG Auto-configuration applied successfully (sip: $sipConfigured, devices: $devicesCount)")
                onComplete?.invoke(true, successMsg)

            } catch (e: Exception) {
                Log.e("$TAG Failed to apply auto-configuration: $e")
                val errorMsg = Texts.get("adonis_autoconfig_error", e.message ?: "Unknown error")
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                onComplete?.invoke(false, errorMsg)
            }
        }
    }

    fun provisionFromRaw(raw: String, context: Context, onComplete: ((Boolean, String) -> Unit)? = null): Boolean {
        val cfg = parse(raw)
        return if (cfg != null) {
            applyConfig(context, cfg, onComplete)
            true
        } else {
            false
        }
    }

}
