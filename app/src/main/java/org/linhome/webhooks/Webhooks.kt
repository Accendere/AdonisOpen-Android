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
package org.linhome.webhooks

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.linhome.BuildConfig
import org.linphone.core.tools.Log
import java.util.UUID

/**
 * Webhook settings in the app's private storage (the same protection as the SIP password,
 * which lives in the app's private Linphone config). Kept out of that config file on purpose:
 * its content is dumped into debug logs, and the token must never reach a log.
 */
class SharedPreferencesWebhookStorage(context: Context) : WebhookStorage {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("adonis_webhooks", Context.MODE_PRIVATE)

    override var config: WebhookConfig?
        get() {
            val baseUrl = prefs.getString("base_url", null) ?: return null
            val user = prefs.getString("user", null) ?: return null
            val token = prefs.getString("token", null) ?: return null
            return WebhookConfig(
                baseUrl = baseUrl,
                user = user,
                token = token,
                pollMode = PollMode.fromWireName(prefs.getString("poll_mode", null)),
                pollingIntervalSeconds = prefs.getInt("polling_interval", WebhookConfig.DEFAULT_POLLING_INTERVAL_SECONDS)
                    .coerceAtLeast(WebhookConfig.MIN_POLLING_INTERVAL_SECONDS),
                events = prefs.getString("events", null)?.let { stored ->
                    stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                },
                auth = WebhookAuth.fromWireName(prefs.getString("auth", null)) ?: WebhookAuth.BEARER
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove("base_url").remove("user").remove("token").remove("poll_mode").remove("polling_interval")
                    .remove("events").remove("auth")
            } else {
                editor.putString("base_url", value.baseUrl)
                    .putString("user", value.user)
                    .putString("token", value.token)
                    .putString("poll_mode", value.pollMode.wireName)
                    .putInt("polling_interval", value.pollingIntervalSeconds)
                    .putString("auth", value.auth.wireName)
                if (value.events == null) editor.remove("events") else editor.putString("events", value.events.sorted().joinToString(","))
            }
            editor.apply()
        }

    override var status: WebhookStatus
        get() = WebhookStatus.fromWireName(prefs.getString("status", null))
        set(value) {
            prefs.edit().putString("status", value.wireName).apply()
        }

    override var eventQueue: List<String>
        get() {
            val stored = prefs.getString("event_queue", null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(stored)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())
        }
        set(value) {
            prefs.edit().putString("event_queue", JSONArray(value).toString()).apply()
        }

    override var pendingAcks: Set<String>
        get() = prefs.getStringSet("pending_acks", emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("pending_acks", value.toSet()).apply()
        }

    private val appContext = context.applicationContext

    /**
     * Made once and kept. A new install derives it from Android's per-app device id (ANDROID_ID: the same for
     * this app on this phone and user across reinstalls, since Android 8), so reinstalling or clearing the app's
     * data doesn't look like a new phone to the site, which counts a person as taking calls while any of their
     * phones says so. An install that already has an id keeps it, so updating the app doesn't either.
     */
    override val deviceId: String
        get() = prefs.getString("device_id", null) ?: stableDeviceId(appContext).also {
            prefs.edit().putString("device_id", it).apply()
        }
}

/** A UUID derived from ANDROID_ID (never sent as such), or a random one when the phone gives none. */
internal fun stableDeviceId(context: Context): String {
    val androidId = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull()
    // Some old emulators and broken builds share one fixed value: not an identity.
    if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return UUID.randomUUID().toString()
    return UUID.nameUUIDFromBytes("adonis-open-device:$androidId".toByteArray(Charsets.UTF_8)).toString()
}

/** The app's single [WebhookEngine], wired to Android: network changes, foreground, logs. */
object Webhooks {
    private var engine: WebhookEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startedActivities = 0

    /** Current status, for the "scan your link again" banner. */
    val status = MutableLiveData(WebhookStatus.ACTIVE)

    fun init(application: Application, callsEnabled: Boolean) {
        if (engine != null) return
        val storage = SharedPreferencesWebhookStorage(application)
        val created = WebhookEngine(
            storage = storage,
            transport = HttpUrlConnectionTransport(),
            mainDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.IO,
            logger = { level, message ->
                when (level) {
                    WebhookLog.Level.INFO -> Log.i(message)
                    WebhookLog.Level.WARNING -> Log.w(message)
                    WebhookLog.Level.ERROR -> Log.e(message)
                }
            },
            onStatusChanged = { status.postValue(it) },
            onPollMessages = { messages -> PollMessages.handle(application, messages) },
            appVersion = BuildConfig.VERSION_NAME
        )
        engine = created
        status.value = storage.status

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                created.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) created.setForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        try {
            val connectivity = application.getSystemService(ConnectivityManager::class.java)
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { created.onNetworkAvailable() }
                }
            })
        } catch (e: Exception) {
            Log.w("[Webhooks] Can't watch the network: ${e.javaClass.simpleName}")
        }

        created.onAppStart(callsEnabled)
    }

    /** This phone's id (made once, kept for good), also sent with claim links. */
    fun deviceId(context: Context): String = SharedPreferencesWebhookStorage(context.applicationContext).deviceId

    fun onSwitchChanged(enabled: Boolean) = onMain { it.onSwitchChanged(enabled) }

    /** The settings in use (for the import screen's comparison), or null. */
    val config: WebhookConfig? get() = engine?.config

    /** Queues [event] if the site asked for it (see [WebhookEngine.sendEvent]). Any thread. */
    fun sendEvent(event: String, fields: JSONObject, at: String? = null) = onMain { it.sendEvent(event, fields, at) }

    /** Whether the site wants [event]; false when nothing is configured. */
    fun sends(event: String): Boolean = engine?.config?.sends(event) == true

    /** See [WebhookEngine.whoAnswered]. */
    suspend fun whoAnswered(callId: String?, door: String?): String? = engine?.whoAnswered(callId, door)

    fun onLinkApplied(config: WebhookConfig?, callsEnabled: Boolean) = onMain { it.onLinkApplied(config, callsEnabled) }

    private fun onMain(action: (WebhookEngine) -> Unit) {
        val current = engine ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) action(current) else mainHandler.post { action(current) }
    }
}
