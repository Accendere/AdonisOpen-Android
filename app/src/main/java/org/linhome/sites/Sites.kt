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
package org.linhome.sites

import android.content.Context
import org.linhome.LinhomeApplication
import org.linhome.utils.SiteInfo
import org.linhome.webhooks.SharedPreferencesWebhookStorage
import org.linhome.webhooks.WebhookStorage
import org.linphone.core.tools.Log

/** [SiteRegistry] in the app's private storage (prefs `adonis_sites`, one JSON document, written synchronously). */
private class SharedPreferencesSiteStorage(context: Context) : SiteStorage {
    private val prefs = context.getSharedPreferences(Sites.PREFS, Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString("state", null)
    override fun save(json: String) {
        prefs.edit().putString("state", json).commit()
    }
}

/**
 * The app's [SiteRegistry], and the storage of each site's webhook connection: prefs file `adonis_webhooks_<slot>`
 * (the fields of the single-site app's `adonis_webhooks`, which keeps only the phone's device id from now on).
 * Made on first use (the call handling may need it before the webhooks start); the upgrade runs then.
 */
object Sites {
    const val PREFS = "adonis_sites"
    private const val LEGACY_WEBHOOK_PREFS = "adonis_webhooks"

    val registry: SiteRegistry by lazy { create(LinhomeApplication.instance.applicationContext) }

    fun storageName(slot: Int) = "adonis_webhooks_$slot"

    fun storage(context: Context, slot: Int): WebhookStorage =
        SharedPreferencesWebhookStorage(context.applicationContext, storageName(slot))

    /** Deletes [slot]'s stored connection, queue and acks. */
    fun forgetStorage(context: Context, slot: Int) {
        context.applicationContext.getSharedPreferences(storageName(slot), Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The main switch AND some site taking calls (or no site at all): whether SIP should be registered. */
    fun receivingCalls(): Boolean {
        val main = LinhomeApplication.corePreferences.appEnabled
        return registry.registrationWanted(main)
    }

    /** The door list was read (see [SiteRegistry.claimLegacyDoors]). */
    fun onDoorsRead(addresses: List<String>, settled: Boolean) {
        if (!registry.waitingForLegacyDoors()) return
        val claimed = registry.claimLegacyDoors(addresses, settled)
        if (claimed > 0 || settled || addresses.isNotEmpty()) Log.i("[Sites] Upgrade: $claimed existing door(s) joined the site")
    }

    private fun create(context: Context): SiteRegistry {
        val registry = SiteRegistry(SharedPreferencesSiteStorage(context))
        if (!registry.upgraded) {
            try {
                val legacy = SharedPreferencesWebhookStorage(context, LEGACY_WEBHOOK_PREFS).config
                val site = registry.upgradeFromSingleSite(legacy?.baseUrl, SiteInfo.name(context)) { slot ->
                    copyLegacyConnection(context, slot)
                }
                Log.i("[Sites] Upgrade from the single-site app: ${site?.let { "connection is site ${it.id}" } ?: "no connection to keep"}")
            } catch (e: Exception) {
                // Nothing was recorded: this run has no sites, the next start tries again.
                Log.e("[Sites] Upgrade from the single-site app failed: ${e.javaClass.simpleName}")
            }
        }
        return registry
    }

    /**
     * Copies everything the single-site app stored in `adonis_webhooks` (connection, status, queue, acks) into
     * [slot]'s file, except the device id, which stays there for the whole phone. The old keys are left in place
     * (never read again): nothing is lost if this is interrupted, and it simply runs again at the next start.
     */
    private fun copyLegacyConnection(context: Context, slot: Int) {
        val from = context.getSharedPreferences(LEGACY_WEBHOOK_PREFS, Context.MODE_PRIVATE)
        val editor = context.getSharedPreferences(storageName(slot), Context.MODE_PRIVATE).edit().clear()
        from.all.forEach { (key, value) ->
            if (key == SharedPreferencesWebhookStorage.KEY_DEVICE_ID) return@forEach
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        check(editor.commit()) { "could not copy the webhook settings" }
    }
}
