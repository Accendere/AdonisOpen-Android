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
package org.linhome.claim

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.linhome.MainActivity
import org.linhome.customisation.Texts
import org.linhome.utils.AdonisProvisioner
import org.linhome.webhooks.HttpUrlConnectionTransport
import org.linhome.webhooks.WebhookLog
import org.linhome.webhooks.Webhooks
import org.linphone.core.tools.Log
import java.security.MessageDigest

/** Runs claim links in the app: from `adonis://claim?url=`, or from the clipboard on first launch. */
object Claims {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = ClaimClient(HttpUrlConnectionTransport(), Dispatchers.IO) { level, message ->
        when (level) {
            WebhookLog.Level.INFO -> Log.i(message)
            WebhookLog.Level.WARNING -> Log.w(message)
            WebhookLog.Level.ERROR -> Log.e(message)
        }
    }

    /** True while a fetch runs, so the clipboard isn't read again meanwhile. */
    var inProgress = false
        private set

    /** The clipboard is read at most once per app process. */
    private var clipboardChecked = false

    /**
     * Fetches the setup data behind [claimUrl] and shows the import screen. [source] and [siteName] are for
     * its header (where the setup came from, the site's name when known).
     */
    fun start(
        activity: MainActivity,
        claimUrl: String,
        source: org.linhome.utils.ImportSource? = null,
        siteName: String? = null,
        onDefinitiveAnswer: () -> Unit = {}
    ) {
        if (inProgress) return
        inProgress = true
        val host = ClaimContract.hostOf(claimUrl)
        Toast.makeText(activity, Texts.get("claim_fetching", host), Toast.LENGTH_SHORT).show()
        val deviceId = Webhooks.deviceId(activity)
        scope.launch {
            val outcome = try {
                client.fetch(claimUrl, deviceId)
            } finally {
                inProgress = false
            }
            // The site answered for good (setup, used/expired, other phone): don't pick this link up again.
            if (outcome is ClaimOutcome.Setup || outcome == ClaimOutcome.Expired || outcome == ClaimOutcome.OtherDevice) {
                onDefinitiveAnswer()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (outcome) {
                is ClaimOutcome.Setup -> {
                    val config = AdonisProvisioner.parseJson(outcome.json)
                    if (config == null) {
                        showMessage(activity, Texts.get("claim_unreadable", host))
                    } else {
                        activity.promptAdonisAutoConfig(
                            config.copy(claimUrl = claimUrl, source = source, siteName = siteName ?: config.siteName)
                        )
                    }
                }
                ClaimOutcome.OtherDevice -> showMessage(activity, Texts.get("claim_other_device"))
                ClaimOutcome.Expired -> showMessage(activity, Texts.get("claim_expired"))
                is ClaimOutcome.TooMany, is ClaimOutcome.Unreachable ->
                    showMessage(activity, Texts.get("claim_unreachable", host), retry = { start(activity, claimUrl, source, siteName, onDefinitiveAnswer) })
            }
        }
    }

    /** After the person applied the setup: tell the site, retrying in the background until it answers. */
    fun confirm(context: Context, claimUrl: String) {
        val deviceId = Webhooks.deviceId(context.applicationContext)
        scope.launch { client.confirm(claimUrl, deviceId) }
    }

    /** The person dismissed a pushed setup: tell the site, so it stops offering it (retried like [confirm]). */
    fun decline(context: Context, claimUrl: String) {
        val deviceId = Webhooks.deviceId(context.applicationContext)
        scope.launch { client.confirm(claimUrl, deviceId, declined = true) }
    }

    /**
     * First launch with no account: if the clipboard holds a claim URL (put there by the site's
     * "Install and set up" button) that the site hasn't already answered for good, start it. Must be called while the activity
     * has the window focus (Android only lets the app on screen read the clipboard).
     */
    fun checkClipboard(activity: MainActivity) {
        if (clipboardChecked || inProgress) return
        clipboardChecked = true
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        // Look at the type first: reading the content itself shows Android's "pasted" notice.
        val description = clipboard.primaryClipDescription ?: return
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity) }.getOrNull()
        val claimUrl = ClaimContract.findInText(text) ?: return
        val prefs = activity.getSharedPreferences("adonis_claims", Context.MODE_PRIVATE)
        val fingerprint = sha256(claimUrl)
        if (prefs.getString("last_clipboard_claim", null) == fingerprint) return
        Log.i("[Claim] Found a claim link for ${ClaimContract.hostOf(claimUrl)} in the clipboard")
        // Remembered only once the site answered for good, so a link tried while offline is tried again next launch.
        start(activity, claimUrl, org.linhome.utils.ImportSource.CLAIM_LINK) {
            prefs.edit().putString("last_clipboard_claim", fingerprint).apply()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun showMessage(activity: MainActivity, message: String, retry: (() -> Unit)? = null) {
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (retry != null) {
            builder.setPositiveButton(Texts.get("claim_retry")) { _, _ -> retry() }
        }
        builder.show()
    }
}
