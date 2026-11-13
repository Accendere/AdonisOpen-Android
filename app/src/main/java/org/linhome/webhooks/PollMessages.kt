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
                else -> Ignored(id, "unknown type '$type'")
            }
        }
    }
}

/**
 * Handles what `poll` brought (Adonis `docs/features/adonis-open-webhooks.md`, "poll messages").
 * The engine has already queued every id for acknowledgement.
 *
 * `config_update`: a notification "<site> has new settings"; tapping it opens the claim (fetch, import
 * screen, confirm), exactly as an `adonis://claim` link. Pushed claims are remembered so that dismissing the
 * import screen tells the site ([wasPushed], [Claims.decline][org.linhome.claim.Claims.decline]).
 */
object PollMessages {
    private const val CHANNEL_ID = "adonis_open_site_updates"
    private const val NOTIFICATION_ID = 7341
    private const val PREFS = "adonis_poll_messages"

    /** [host] is the webhook host of the site whose poll brought [messages]. */
    fun handle(context: Context, messages: List<JSONObject>, host: String?) {
        messages.forEach { json ->
            when (val message = PollMessage.parse(json, host)) {
                is PollMessage.ConfigUpdate -> {
                    Log.i("[PollMessages] $message")
                    remember(context, message.claimUrl)
                    notifyConfigUpdate(context, message)
                }
                is PollMessage.Ignored -> Log.w("[PollMessages] Ignoring message ${message.id}: ${message.why}")
            }
        }
    }

    /** Whether [claimUrl] came from a pushed `config_update` (so dismissing it is reported). */
    fun wasPushed(context: Context, claimUrl: String?): Boolean =
        claimUrl != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("pushed", emptySet())!!.contains(claimUrl)

    /** Done with [claimUrl] (applied or dismissed): forget it and clear the notification. */
    fun settled(context: Context, claimUrl: String?) {
        if (claimUrl == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pushed = prefs.getStringSet("pushed", emptySet())!!.toMutableSet()
        if (pushed.remove(claimUrl)) {
            prefs.edit().putStringSet("pushed", pushed).apply()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun remember(context: Context, claimUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only the latest push matters; older ones were superseded by the site.
        prefs.edit().putStringSet("pushed", setOf(claimUrl)).apply()
    }

    private fun notifyConfigUpdate(context: Context, message: PollMessage.ConfigUpdate) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(Texts.get("site_updates_channel_name"))
                .build()
        )
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
