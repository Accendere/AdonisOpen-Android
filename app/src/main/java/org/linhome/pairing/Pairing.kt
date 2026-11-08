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
package org.linhome.pairing

import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.linhome.MainActivity
import org.linhome.R
import org.linhome.claim.ClaimContract
import org.linhome.claim.Claims
import org.linhome.customisation.Texts
import org.linhome.entities.LinhomeAccount
import org.linhome.webhooks.HttpUrlConnectionTransport
import org.linhome.webhooks.WebhookLog
import org.linhome.webhooks.Webhooks
import org.linphone.core.tools.Log
import java.lang.ref.WeakReference

/**
 * LAN pairing in the app: find the site on the Wi-Fi, show the code, wait for approval, then continue as a
 * claim link ([Claims.start]). State lives here, not in the activity, so a rotation doesn't lose the wait:
 * MainActivity calls [attach] when it resumes and [detach] when it is destroyed.
 */
object Pairing {
    /** How long a search runs; it ends earlier once a site was found and nothing new came for [SETTLE_MS]. */
    private const val SEARCH_MS = 8_000L
    private const val SETTLE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val client = PairingClient(HttpUrlConnectionTransport(), Dispatchers.IO) { level, message ->
        when (level) {
            WebhookLog.Level.INFO -> Log.i(message)
            WebhookLog.Level.WARNING -> Log.w(message)
            WebhookLog.Level.ERROR -> Log.e(message)
        }
    }

    private var attached: WeakReference<MainActivity>? = null
    private val activity: MainActivity? get() = attached?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private var discovery: SiteDiscovery? = null
    private var searchingDialog: AlertDialog? = null
    private var codeDialog: AlertDialog? = null

    /** The pairing waiting for approval, and its polling job. */
    private var waiting: PendingPairing? = null
    private var pollJob: Job? = null

    /** The automatic search runs once per app process. */
    private var autoSearched = false

    /** True while searching, starting or waiting: nothing else should start a setup meanwhile. */
    val busy: Boolean get() = discovery != null || waiting != null

    fun attach(activity: MainActivity) {
        attached = WeakReference(activity)
        if (waiting != null && codeDialog?.isShowing != true) showCodeDialog()
    }

    fun detach(activity: MainActivity) {
        if (attached?.get() !== activity) return
        // The dialogs belong to this activity's window; the wait itself goes on and re-shows on attach.
        searchingDialog?.let { runCatching { it.dismiss() } }
        searchingDialog = null
        codeDialog?.setOnDismissListener(null)
        codeDialog?.let { runCatching { it.dismiss() } }
        codeDialog = null
        attached = null
    }

    /** First launch with no account: look quietly, and only speak up when a site was found. */
    fun searchAutomatically(activity: MainActivity) {
        if (autoSearched || busy || Claims.inProgress) return
        autoSearched = true
        attach(activity)
        search(manual = false)
    }

    /** The "Find my site on this Wi-Fi" button. Taps during the automatic search take it over. */
    fun searchFromButton(activity: MainActivity) {
        attach(activity)
        if (discovery != null) {
            if (!searchIsManual) {
                searchIsManual = true
                showSearchingDialog()
            }
            return
        }
        if (waiting != null) return
        search(manual = true)
    }

    /** Whether the running search was asked for by the person (it then reports "nothing found" too). */
    private var searchIsManual = false
    private var finishSearch: Runnable? = null

    private fun search(manual: Boolean) {
        val sites = mutableListOf<DiscoveredSite>()
        searchIsManual = manual
        val finish = Runnable { endSearch(sites, searchIsManual) }
        finishSearch = finish
        discovery = SiteDiscovery(activity ?: return) { site ->
            sites += site
            main.removeCallbacks(finish)
            main.postDelayed(finish, SETTLE_MS)
        }.also { it.start() }
        main.postDelayed(finish, SEARCH_MS)
        if (manual) showSearchingDialog()
    }

    private fun showSearchingDialog() {
        val a = activity ?: return
        searchingDialog = MaterialAlertDialogBuilder(a, R.style.LindoorDialogTheme)
            .setView(progressView(a, Texts.get("pair_searching")))
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finishSearch?.let { main.removeCallbacks(it) }
                stopDiscovery()
            }
            .setCancelable(false)
            .show()
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        finishSearch = null
        searchingDialog?.let { runCatching { it.dismiss() } }
        searchingDialog = null
    }

    private fun endSearch(sites: List<DiscoveredSite>, manual: Boolean) {
        if (discovery == null) return // cancelled
        stopDiscovery()
        val a = activity ?: return
        // The automatic search stays silent when it found nothing, or when the app got set up meanwhile.
        if (!manual && (sites.isEmpty() || LinhomeAccount.configured() || Claims.inProgress || a.autoConfigPending)) return
        when (sites.size) {
            0 -> message(Texts.get("pair_none_found"))
            1 -> MaterialAlertDialogBuilder(a, R.style.LindoorDialogTheme)
                .setMessage(Texts.get("pair_found", sites[0].name))
                .setPositiveButton(Texts.get("pair_set_up")) { _, _ -> startPairing(sites[0]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> MaterialAlertDialogBuilder(a, R.style.LindoorDialogTheme)
                .setTitle(Texts.get("pair_choose_site"))
                .setItems(sites.map { "${it.name} (${it.host})" }.toTypedArray()) { _, which -> startPairing(sites[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startPairing(site: DiscoveredSite) {
        val a = activity ?: return
        val deviceId = Webhooks.deviceId(a)
        scope.launch {
            when (val outcome = client.start(site, deviceId, deviceName())) {
                is StartOutcome.Started -> {
                    waiting = outcome.pairing
                    showCodeDialog()
                    pollJob = scope.launch { onFinalAnswer(outcome.pairing, client.waitForApproval(outcome.pairing, deviceId)) }
                }
                is StartOutcome.TooMany -> message(Texts.get("pair_too_many"))
                is StartOutcome.Unreachable -> message(Texts.get("claim_unreachable", site.host), retry = { startPairing(site) })
            }
        }
    }

    private fun onFinalAnswer(pairing: PendingPairing, outcome: PollOutcome) {
        if (waiting !== pairing) return
        waiting = null
        pollJob = null
        codeDialog?.setOnDismissListener(null)
        codeDialog?.let { runCatching { it.dismiss() } }
        codeDialog = null
        val a = activity
        when (outcome) {
            is PollOutcome.Approved -> {
                if (a == null) {
                    // No screen to show the dialog on: the claim stays valid (48 h), keep it for the next attach.
                    Log.w("[Pairing] Approved while the app had no screen; the setup is fetched when it returns")
                    pendingClaim = outcome.claimUrl
                } else {
                    Claims.start(a, outcome.claimUrl)
                }
            }
            PollOutcome.Declined -> message(Texts.get("pair_declined"))
            else -> message(Texts.get("pair_expired"), retry = { startPairing(pairing.site) })
        }
    }

    /** An approval that arrived while no activity was attached. */
    private var pendingClaim: String? = null

    /** Called by MainActivity when it resumes, after [attach]. */
    fun resumePendingClaim(activity: MainActivity) {
        val claimUrl = pendingClaim ?: return
        pendingClaim = null
        if (ClaimContract.validate(claimUrl) != null) Claims.start(activity, claimUrl)
    }

    private fun cancelWaiting() {
        pollJob?.cancel()
        pollJob = null
        waiting?.let { Log.i("[Pairing] Cancelled the pairing with ${it.site.host}") }
        waiting = null
        codeDialog = null
    }

    private fun showCodeDialog() {
        val a = activity ?: return
        val pairing = waiting ?: return
        val view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(a, 24)
            setPadding(pad, dp(a, 20), pad, dp(a, 8))
            addView(TextView(a).apply {
                text = Texts.get("pair_code_message")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(a).apply {
                text = "${pairing.code.substring(0, 3)} ${pairing.code.substring(3)}"
                contentDescription = pairing.code.toCharArray().joinToString(" ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                setPadding(0, dp(a, 20), 0, dp(a, 12))
                setTextIsSelectable(false)
            })
            addView(progressView(a, Texts.get("pair_waiting")))
        }
        val haPackage = pairing.approvePath?.let { homeAssistantPackage(a) }
        val builder = MaterialAlertDialogBuilder(a, R.style.LindoorDialogTheme)
            .setTitle(pairing.site.name)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
        if (haPackage != null) builder.setNeutralButton(Texts.get("pair_open_home_assistant"), null)
        codeDialog = builder.create()
            .also { dialog ->
                dialog.setOnDismissListener { if (codeDialog === dialog) cancelWaiting() }
                dialog.show()
                // Set after show() so the tap doesn't close the dialog: the wait goes on while the person
                // approves in Home Assistant, and the hand-off happens when they come back.
                if (haPackage != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        openHomeAssistant(a, haPackage, pairing.approvePath!!)
                    }
                }
            }
    }

    /** The installed Home Assistant app's package (full or minimal build), or null. */
    private fun homeAssistantPackage(a: MainActivity): String? =
        HOME_ASSISTANT_PACKAGES.firstOrNull { pkg ->
            runCatching { a.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }

    private fun openHomeAssistant(a: MainActivity, pkg: String, path: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("homeassistant://navigate$path"))
            .setPackage(pkg)
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Log.w("[Pairing] Couldn't open Home Assistant: ${e.javaClass.simpleName}")
            a.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { a.startActivity(it) } }
        }
    }

    private val HOME_ASSISTANT_PACKAGES = listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")

    private fun progressView(a: MainActivity, label: String) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(a, 24)
        setPadding(pad, dp(a, 16), pad, dp(a, 8))
        addView(ProgressBar(a).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)))
        addView(TextView(a).apply {
            text = label
            setPadding(dp(a, 16), 0, 0, 0)
        })
    }

    private fun message(text: String, retry: (() -> Unit)? = null) {
        val a = activity ?: return
        val builder = MaterialAlertDialogBuilder(a, R.style.LindoorDialogTheme).setMessage(text)
        if (retry != null) {
            builder.setNegativeButton(android.R.string.cancel, null)
            builder.setPositiveButton(Texts.get("claim_retry")) { _, _ -> retry() }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun dp(a: MainActivity, value: Int) = (value * a.resources.displayMetrics.density).toInt()

    /** "Google Pixel 8": what the approver sees next to the code. */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifEmpty { "Android" }
    }
}
