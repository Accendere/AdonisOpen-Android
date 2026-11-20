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

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.linhome.MainActivity
import org.linphone.core.tools.Log
import java.net.URLDecoder

/**
 * A claim link handed over by Google Play: the site's phone setup page links to
 * `https://play.google.com/store/apps/details?id=org.probonis&referrer=<R>`, R being the percent-encoding of
 * `adonis_claim=<percent-encoded https claim URL>`. So the install referrer the app gets is a query string with
 * an `adonis_claim` key (Play may add its own `utm_*` keys). On the first launch after install it is opened
 * exactly like `adonis://claim?url=<that URL>`; the referrer is read only once, whatever it holds.
 */
object InstallReferrerClaim {
    private const val KEY = "adonis_claim"
    private const val PREFS = "adonis_claims"
    private const val PREF_CONSUMED = "install_referrer_consumed"
    private const val PREF_ATTEMPTS = "install_referrer_attempts"

    /** Transient failures (Play service unavailable or disconnected) are retried on later launches, up to this many. */
    private const val MAX_ATTEMPTS = 5

    /**
     * The claim URL in an install referrer string, or null. Pure: unit tested.
     * Takes the `adonis_claim` value, percent-decodes it and accepts it only if [ClaimContract.validate] does
     * (https, a host, no credentials, query or fragment). Tolerates one extra level of encoding, of the whole
     * referrer or of the value, as some Play versions hand over the referrer still encoded.
     */
    fun parse(referrer: String?): String? {
        var text = referrer?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.contains("$KEY=")) {
            text = decode(text) ?: return null
        }
        val raw = text.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == KEY }
            ?.get(1) ?: return null
        var value = decode(raw) ?: return null
        if (value.startsWith("https%3A", ignoreCase = true)) {
            value = decode(value) ?: return null
        }
        return ClaimContract.validate(value)
    }

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    /**
     * First launch after install: reads the Play install referrer once and starts the claim it carries, if any.
     * [allowed] false (an account is already set up, or this launch already opened a setup link): the referrer
     * is marked consumed without being read, so an old claim never pops up later.
     */
    fun checkOnce(activity: MainActivity, allowed: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_CONSUMED, false)) return
        if (!allowed) {
            prefs.edit().putBoolean(PREF_CONSUMED, true).apply()
            return
        }
        val attempts = prefs.getInt(PREF_ATTEMPTS, 0) + 1
        prefs.edit().putInt(PREF_ATTEMPTS, attempts).apply()
        val consume = { prefs.edit().putBoolean(PREF_CONSUMED, true).apply() }
        if (attempts > MAX_ATTEMPTS) {
            consume()
            return
        }
        val client = try {
            InstallReferrerClient.newBuilder(activity.applicationContext).build()
        } catch (e: Exception) {
            Log.w("[Claim] Install referrer client unavailable: ${e.javaClass.simpleName}")
            consume()
            return
        }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val referrer = try {
                                client.installReferrer?.installReferrer
                            } catch (e: Exception) {
                                // Talking to Play failed mid-way: transient, try again on the next launch.
                                Log.w("[Claim] Reading the install referrer failed: ${e.javaClass.simpleName}")
                                endConnection(client)
                                return
                            }
                            consume()
                            endConnection(client)
                            val claimUrl = parse(referrer) ?: return
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                Log.i("[Claim] Found a claim link for ${ClaimContract.hostOf(claimUrl)} in the install referrer")
                                Claims.start(activity, claimUrl, org.linhome.utils.ImportSource.CLAIM_LINK)
                            }
                        }
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE,
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                            // Transient: not consumed, tried again on the next launch (never within this one).
                            Log.w("[Claim] Install referrer service unavailable ($responseCode)")
                            endConnection(client)
                        }
                        else -> {
                            // Not supported, developer or permission error: won't get better, don't ask again.
                            consume()
                            endConnection(client)
                        }
                    }
                }

                // Lost the connection: not reconnected here; the next launch tries again if not consumed yet.
                override fun onInstallReferrerServiceDisconnected() {}
            })
        } catch (e: Exception) {
            Log.w("[Claim] Install referrer connection failed: ${e.javaClass.simpleName}")
        }
    }

    private fun endConnection(client: InstallReferrerClient) {
        runCatching { client.endConnection() }
    }
}
