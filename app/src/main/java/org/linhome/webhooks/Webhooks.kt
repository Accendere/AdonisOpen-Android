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
import org.linhome.LinhomeApplication
import org.linhome.entities.LinhomeAccount
import org.linhome.sites.LinkSite
import org.linhome.sites.Site
import org.linhome.sites.SiteRegistry
import org.linhome.sites.Sites
import org.linhome.store.DeviceStore
import org.linphone.core.tools.Log
import java.util.UUID

/**
 * Webhook settings in the app's private storage (the same protection as the SIP password,
 * which lives in the app's private Linphone config). Kept out of that config file on purpose:
 * its content is dumped into debug logs, and the token must never reach a log.
 *
 * One file per site ([prefsName], see [org.linhome.sites.Sites.storageName]); the device id is the phone's, for every
 * site, and stays in the single-site app's file `adonis_webhooks`.
 */
class SharedPreferencesWebhookStorage(context: Context, prefsName: String = DEVICE_PREFS) : WebhookStorage {
    companion object {
        const val DEVICE_PREFS = "adonis_webhooks"
        const val KEY_DEVICE_ID = "device_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

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
                auth = WebhookAuth.fromWireName(prefs.getString("auth", null)) ?: WebhookAuth.BEARER,
                siteEvents = prefs.getString("site_events", null)?.let { stored ->
                    stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                },
                editedLocally = prefs.getBoolean("edited_locally", false)
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove("base_url").remove("user").remove("token").remove("poll_mode").remove("polling_interval")
                    .remove("events").remove("auth").remove("site_events").remove("edited_locally")
            } else {
                editor.putString("base_url", value.baseUrl)
                    .putString("user", value.user)
                    .putString("token", value.token)
                    .putString("poll_mode", value.pollMode.wireName)
                    .putInt("polling_interval", value.pollingIntervalSeconds)
                    .putString("auth", value.auth.wireName)
                if (value.events == null) editor.remove("events") else editor.putString("events", value.events.sorted().joinToString(","))
                if (value.siteEvents == null) editor.remove("site_events") else editor.putString("site_events", value.siteEvents.sorted().joinToString(","))
                editor.putBoolean("edited_locally", value.editedLocally)
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
        get() = devicePrefs.getString(KEY_DEVICE_ID, null) ?: stableDeviceId(appContext).also {
            devicePrefs.edit().putString(KEY_DEVICE_ID, it).apply()
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

/**
 * The app's webhooks: one [WebhookEngine] per site ([SiteWebhooks]), wired to Android: network changes, foreground,
 * logs. Also the API the screens use for sites (list, switch, remove).
 */
object Webhooks {
    private var hub: SiteWebhooks? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startedActivities = 0

    /** The worst status of all sites, for the "scan your link again" banner. */
    val status = MutableLiveData(WebhookStatus.ACTIVE)

    /** The sites, after every change (added, re-keyed, renamed, switched, removed): for Settings and the account card. */
    val sites = MutableLiveData<List<Site>>(emptyList())

    private val logger = WebhookLog { level, message ->
        when (level) {
            WebhookLog.Level.INFO -> Log.i(message)
            WebhookLog.Level.WARNING -> Log.w(message)
            WebhookLog.Level.ERROR -> Log.e(message)
        }
    }

    fun init(application: Application) {
        if (hub != null) return
        val created = SiteWebhooks(
            registry = Sites.registry,
            mainEnabled = { LinhomeApplication.corePreferences.appEnabled },
            newEngine = { site ->
                val storage = Sites.storage(application, site.slot)
                WebhookEngine(
                    storage = storage,
                    transport = HttpUrlConnectionTransport(),
                    mainDispatcher = Dispatchers.Main,
                    ioDispatcher = Dispatchers.IO,
                    logger = logger,
                    onStatusChanged = { mainHandler.post { refresh() } },
                    // A poll's messages concern the site that answered it: its claim must be on that site's host.
                    onPollMessages = { messages -> PollMessages.handle(application, messages, storage.config?.host) },
                    appVersion = BuildConfig.VERSION_NAME
                )
            },
            forgetStorage = { Sites.forgetStorage(application, it.slot) },
            logger = logger
        )
        hub = created

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

        created.start()
        refresh()
    }

    private fun refresh() {
        val current = hub ?: return
        status.value = current.overallStatus()
        sites.value = current.registry.sites()
    }

    /** This phone's id (made once, kept for good, the same for every site), also sent with claim links. */
    fun deviceId(context: Context): String = SharedPreferencesWebhookStorage(context.applicationContext).deviceId

    /** The phone's main receive-calls switch changed (already stored): every site hears its resulting state. */
    fun onMainSwitchChanged() = onMain { it.onMainSwitchChanged() }

    /** The sites this phone takes calls for. */
    fun siteList(): List<Site> = Sites.registry.sites()

    /** The site [address]'s door belongs to; null for a door added by hand, a phone number, an unknown caller. */
    fun siteForDoor(address: String?): Site? = Sites.registry.siteForDoor(address)

    /** [siteId]'s connection (for Settings and the import screen's comparison), or null. */
    fun config(siteId: String?): WebhookConfig? = hub?.config(siteId)

    fun status(siteId: String?): WebhookStatus = hub?.status(siteId) ?: WebhookStatus.ACTIVE

    /** Queues a call's [event] for [siteId] (the calling door's site) if it asked for it; null goes nowhere. Any thread. */
    fun sendCallEvent(siteId: String?, event: String, fields: JSONObject, at: String? = null) {
        if (siteId == null) return
        onMain { it.sendCallEvent(siteId, event, fields, at) }
    }

    /** Queues an event about the phone itself (`registration`) for every site that asked for it. Any thread. */
    fun sendToAll(event: String, fields: JSONObject, at: String? = null) = onMain { it.sendToAll(event, fields, at) }

    /** Whether [siteId] wants [event]; false for no site or nothing configured. */
    fun sends(siteId: String?, event: String): Boolean = hub?.sends(siteId, event) == true

    /** Whether [siteId] wants any call event (also gates `who_answered`). */
    fun reportsCalls(siteId: String?): Boolean = config(siteId)?.reportsCalls == true

    /** See [WebhookEngine.whoAnswered]; asked of [siteId] only. */
    suspend fun whoAnswered(siteId: String?, callId: String?, door: String?): String? = hub?.whoAnswered(siteId, callId, door)

    /** Which site a link is for (see [org.linhome.sites.SiteRegistry.resolveLink]). */
    fun resolveLink(siteId: String?, webhook: WebhookConfig?, linkHasSip: Boolean): LinkSite =
        hub?.resolveLink(siteId, webhook, linkHasSip)
            ?: Sites.registry.resolveLink(siteId, webhook?.baseUrl, linkHasSip) { null }

    /** See [SiteWebhooks.applyLink]. Main thread. */
    fun applyLink(target: LinkSite, webhook: WebhookConfig?, applyWebhook: Boolean, siteName: String?): Site? {
        val current = hub ?: return null
        val site = current.applyLink(target, webhook, applyWebhook, siteName)
        refresh()
        return site
    }

    /**
     * [address]'s door belongs to [siteId] from now on (null: to no site). When it belonged to another site, the
     * last link wins, logged.
     */
    fun mapDoor(address: String?, siteId: String?) {
        val before = Sites.registry.mapDoor(address, siteId)
        if (before != null && before.id != siteId) {
            Log.w("[Webhooks] Door ${SiteRegistry.doorKey(address)} moves from site ${before.id} to ${siteId ?: "no site"}")
        }
    }

    /** See [WebhookEngine.applyLocalEdit], for [siteId]; null when it has no connection. Main thread. */
    fun applyLocalEdit(siteId: String, events: Set<String>, baseUrl: String): WebhookEngine.LocalEdit? {
        val result = hub?.applyLocalEdit(siteId, events, baseUrl)
        refresh()
        return result
    }

    /**
     * Turns [siteId]'s own receive-calls switch: the site hears `enabled` = main AND this, its door calls are refused
     * while off, and SIP unregisters when no site takes calls any more. Main thread.
     */
    fun setSiteCallsEnabled(siteId: String, enabled: Boolean) {
        val current = hub ?: return
        if (!current.setSiteCallsEnabled(siteId, enabled)) return
        refresh()
        LinhomeAccount.applyRegistration()
    }

    /**
     * Forgets [siteId]: its connection, its queued events and its door mappings; with [removeDoors], its doors leave
     * the phone too. The SIP account stays. Main thread.
     */
    fun removeSite(siteId: String, removeDoors: Boolean) {
        val current = hub ?: return
        val keys = current.removeSite(siteId) ?: return
        if (removeDoors && keys.isNotEmpty()) {
            DeviceStore.devices.filter { SiteRegistry.doorKey(it.address) in keys }.forEach { DeviceStore.removeDevice(it) }
        }
        refresh()
        LinhomeAccount.applyRegistration()
    }

    private fun onMain(action: (SiteWebhooks) -> Unit) {
        val current = hub ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) action(current) else mainHandler.post { action(current) }
    }
}
