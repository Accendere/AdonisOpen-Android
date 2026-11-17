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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import org.linhome.MainActivity
import org.linhome.R
import org.linhome.claim.ClaimContract
import org.linhome.customisation.Texts
import org.linphone.core.tools.Log
import java.net.URI

/** What a poll message asks for, read from its JSON (pure, for tests). */
sealed class PollMessage {
    /** New settings from the site: [claimUrl] is a claim bound to this phone; [summary] the site's one-liner. */
    data class ConfigUpdate(val id: String?, val claimUrl: String, val summary: String?) : PollMessage() {
        override fun toString() = "ConfigUpdate(id=$id, host=${ClaimContract.hostOf(claimUrl)}, summary=$summary)"
    }

    /**
     * The site switched this phone's door calls for it (its card, an admin, a schedule): [enabled] for the site whose
     * poll brought it, [by] who did it (a name to show), [at] when (ISO 8601, as sent).
     */
    /** [self]: the person changed it themselves (on the card), so the phone says nothing. */
    data class ReceiveCalls(val id: String?, val enabled: Boolean, val by: String?, val at: String?, val self: Boolean = false) : PollMessage()

    /** A type this app doesn't know, or a message it can't use: acknowledged and ignored. */
    data class Ignored(val id: String?, val why: String) : PollMessage()

    companion object {
        /**
         * Reads [json]. A `config_update` must carry an https claim URL on the same host as the webhooks
         * ([webhookHost]): the poll answer is authenticated, but a claim elsewhere is refused anyway.
         */
        fun parse(json: JSONObject, webhookHost: String?): PollMessage {
            val id = json.optString("id", "").ifEmpty { null }
            return when (val type = json.optString("type", "")) {
                "config_update" -> {
                    val claimUrl = ClaimContract.validate(json.optString("claim_url", ""))
                        ?: return Ignored(id, "config_update without a valid https claim_url")
                    val host = runCatching { URI(claimUrl).host?.lowercase() }.getOrNull()
                    if (webhookHost == null || host != webhookHost) {
                        return Ignored(id, "config_update claim on another host than the webhooks")
                    }
                    ConfigUpdate(id, claimUrl, json.optString("summary", "").trim().ifEmpty { null })
                }
                "receive_calls" -> {
                    // Only a real JSON boolean: a missing or odd value must never switch calls on or off.
                    val enabled = json.opt("enabled") as? Boolean
                        ?: return Ignored(id, "receive_calls without a boolean 'enabled'")
                    ReceiveCalls(
                        id, enabled,
                        json.optString("by", "").trim().ifEmpty { null }?.take(80),
                        json.optString("at", "").trim().ifEmpty { null },
                        json.opt("self") == true
                    )
                }
                else -> Ignored(id, "unknown type '$type'")
            }
        }
    }
}

/**
 * Handles what `poll` brought (Adonis `docs/features/adonis-open-webhooks.md`, "poll messages").
 * The engine has already queued every id for acknowledgement.
 *
 * `receive_calls`: the site switched this phone's door calls for it (see [Webhooks.applyReceiveCalls]); a quiet
 * notification says so, and by whom.
 *
 * `config_update`: a notification "<site> has new settings"; tapping it opens the claim (fetch, import
 * screen, confirm), exactly as an `adonis://claim` link. Pushed claims are remembered so that dismissing the
 * import screen tells the site ([wasPushed], [Claims.decline][org.linhome.claim.Claims.decline]), and with them
 * the site's summary of what changed, which the import screen shows on top ([pushedSummary]).
 */
object PollMessages {
    private const val CHANNEL_ID = "adonis_open_site_updates"
    private const val NOTIFICATION_ID = 7341
    private const val RECEIVE_CALLS_NOTIFICATION_ID = 7342
    private const val PREFS = "adonis_poll_messages"

    /** [host] is the webhook host of the site whose poll brought [messages], [siteId] that site. Main thread. */
    fun handle(context: Context, messages: List<JSONObject>, host: String?, siteId: String?) {
        messages.forEach { json ->
            when (val message = PollMessage.parse(json, host)) {
                is PollMessage.ConfigUpdate -> {
                    Log.i("[PollMessages] $message")
                    remember(context, message.claimUrl, message.summary)
                    notifyConfigUpdate(context, message)
                }
                is PollMessage.ReceiveCalls -> {
                    Log.i("[PollMessages] $message for site $siteId")
                    val site = siteId?.let { Webhooks.applyReceiveCalls(it, message.enabled) }
                    if (site == null) {
                        Log.w("[PollMessages] receive_calls for an unknown site, ignored")
                    } else if (!message.self) {
                        notifyReceiveCalls(context, site.displayName, message)
                    }
                }
                is PollMessage.Ignored -> Log.w("[PollMessages] Ignoring message ${message.id}: ${message.why}")
            }
        }
    }

    /** Whether [claimUrl] came from a pushed `config_update` (so dismissing it is reported). */
    fun wasPushed(context: Context, claimUrl: String?): Boolean =
        claimUrl != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("pushed", emptySet())!!.contains(claimUrl)

    /** What the site said changed in the pushed [claimUrl] (`config_update`'s summary), null when none or not pushed. */
    fun pushedSummary(context: Context, claimUrl: String?): String? {
        if (!wasPushed(context, claimUrl)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("summary", null)?.takeIf { prefs.getString("summary_claim", null) == claimUrl && it.isNotBlank() }
    }

    /** Done with [claimUrl] (applied or dismissed): forget it and clear the notification. */
    fun settled(context: Context, claimUrl: String?) {
        if (claimUrl == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pushed = prefs.getStringSet("pushed", emptySet())!!.toMutableSet()
        if (pushed.remove(claimUrl)) {
            prefs.edit().putStringSet("pushed", pushed).remove("summary").remove("summary_claim").apply()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun remember(context: Context, claimUrl: String, summary: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only the latest push matters; older ones were superseded by the site.
        val editor = prefs.edit().putStringSet("pushed", setOf(claimUrl))
        if (summary.isNullOrBlank()) {
            editor.remove("summary").remove("summary_claim")
        } else {
            editor.putString("summary", summary).putString("summary_claim", claimUrl)
        }
        editor.apply()
    }

    /**
     * Quiet (no sound, no vibration, low priority): the switch already changed, there is nothing to do, it only
     * explains why the phone's switch moved. A newer change replaces it.
     */
    private fun notifyReceiveCalls(context: Context, siteName: String, message: PollMessage.ReceiveCalls) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, RECEIVE_CALLS_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_door)
            .setContentTitle(Texts.get(if (message.enabled) "site_receive_calls_on_title" else "site_receive_calls_off_title", siteName))
            .setContentText(message.by?.let { Texts.get("site_receive_calls_by", it) } ?: Texts.get("site_receive_calls_text"))
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(RECEIVE_CALLS_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("[PollMessages] Can't show the receive-calls notice (no permission)")
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(Texts.get("site_updates_channel_name"))
                .build()
        )
    }

    private fun notifyConfigUpdate(context: Context, message: PollMessage.ConfigUpdate) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        // The claim URL travels inside the app only (explicit intent to our own activity).
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("adonis://claim").buildUpon().appendQueryParameter("url", message.claimUrl).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val site = ClaimContract.hostOf(message.claimUrl)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_door)
            .setContentTitle(Texts.get("site_updates_title", site))
            .setContentText(message.summary ?: Texts.get("site_updates_text"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("[PollMessages] Can't show the settings notification (no permission)")
        }
    }
}
