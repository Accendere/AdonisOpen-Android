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
package org.linhome.ui.devices

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.linhome.LinhomeApplication
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.LinhomeAccount
import org.linhome.linphonecore.RegistrationMonitor
import org.linhome.ui.account.AccountCardMode
import org.linhome.ui.account.AccountStatus
import org.linhome.ui.account.Registration
import org.linhome.ui.widgets.LRoundRectButton
import org.linhome.utils.SiteInfo

/**
 * The account card at the top of the Doors tab. Configured: one discreet line (`pedro · Alameda`, a status dot,
 * a chevron) that opens the Account screen, "Connecting…" when registering takes more than 3 s, and a red
 * banner with Retry when the registration fails. Not configured: the setup choices.
 */
class AccountCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** What the card's buttons do; set by the fragment. */
    interface Actions {
        fun openAccount()
        fun scanSetupCode()
        fun findSiteOnWifi()
        fun enterSipAccount()
    }

    var actions: Actions? = null

    private val handler = Handler(Looper.getMainLooper())

    /** When this card first saw the registration in between, if the monitor didn't (an app started mid-registration). */
    private var seenInProgressSinceMs: Long? = null

    private var shownMode: AccountCardMode? = null
    private var shownKey: String? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
    }

    /** Re-reads the account and the registration, and redraws when something changed. */
    fun refresh() {
        handler.removeCallbacksAndMessages(null)
        val account = LinhomeAccount.get()
        val snapshot = RegistrationMonitor.snapshot.value
        val registration = account?.let { RegistrationMonitor.map(it.state) } ?: Registration.NONE
        val now = RegistrationMonitor.now()
        if (registration == Registration.OK || registration == Registration.FAILED) {
            seenInProgressSinceMs = null
        } else if (seenInProgressSinceMs == null) {
            seenInProgressSinceMs = now
        }
        val progressSince = if (snapshot?.failing == true) null else (snapshot?.progressSinceMs ?: seenInProgressSinceMs)
        val callsEnabled = LinhomeApplication.corePreferences.appEnabled
        val mode = AccountStatus.decide(
            configured = account != null,
            callsEnabled = callsEnabled,
            registration = registration,
            failing = snapshot?.failing == true,
            progressSinceMs = progressSince,
            nowMs = now
        )
        val identity = account?.params?.identityAddress
        val domain = identity?.domain
        val title = AccountStatus.title(identity?.username, SiteInfo.name(context), domain)
        val reason = snapshot?.reason ?: account?.errorInfo?.phrase?.takeIf { it.isNotBlank() }
        val key = listOf(mode.name, title, domain, reason, callsEnabled).joinToString("|")
        if (mode != shownMode || key != shownKey) {
            shownMode = mode
            shownKey = key
            draw(mode, title, domain, reason, paused = !callsEnabled)
        }
        AccountStatus.nextCheckInMs(mode, progressSince, now)?.let { delay ->
            handler.postDelayed({ refresh() }, delay + 50)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun draw(mode: AccountCardMode, title: String, domain: String?, reason: String?, paused: Boolean) {
        removeAllViews()
        setOnClickListener(null)
        isClickable = false
        when (mode) {
            AccountCardMode.NOT_CONFIGURED -> drawSetup()
            AccountCardMode.FAILED -> drawFailed(domain, reason, paused)
            else -> drawCompact(mode, title)
        }
    }

    private fun cardBackground(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun text(value: String, sizeSp: Float, colorKey: String, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Theme.getColor(colorKey))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chevron() = ImageView(context).apply {
        Theme.setIcon("icons/chevron_down", this)
        imageTintList = Theme.buildSingleColorStateList("color_g")
        rotation = -90f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dot(color: Int) = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** One line: the dot, `pedro · Alameda`, a short status when there is one, the chevron. */
    private fun drawCompact(mode: AccountCardMode, title: String) {
        background = cardBackground(Theme.getColor("color_d"))
        setPadding(dp(14), dp(10), dp(10), dp(10))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotColor = when (mode) {
            AccountCardMode.CONNECTED -> Theme.getColor("color_k")
            AccountCardMode.CONNECTING -> Theme.getColor("color_s")
            else -> Theme.getColor("color_h")
        }
        row.addView(dot(dotColor), LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        row.addView(text(title, 15f, "color_b", bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val status = when (mode) {
            AccountCardMode.CONNECTING -> Texts.get("account_card_connecting")
            AccountCardMode.PAUSED -> Texts.get("registration_state_paused")
            else -> null
        }
        status?.let { row.addView(text(it, 13f, "color_g"), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
        row.addView(chevron(), LayoutParams(dp(12), dp(12)).apply { marginStart = dp(10) })
        addView(row)
        contentDescription = listOfNotNull(Texts.get("menu_account"), title, status ?: if (mode == AccountCardMode.CONNECTED) Texts.get("registration_state_ok") else null).joinToString(", ")
        isClickable = true
        isFocusable = true
        setOnClickListener { actions?.openAccount() }
    }

    /**
     * The red banner: "Can't connect to <domain>: <reason>", Retry, and the way to the Account screen. With calls
     * paused, a "Calls paused" line under it: the pause is shown alongside the error, never instead of it.
     */
    private fun drawFailed(domain: String?, reason: String?, paused: Boolean) {
        val red = Theme.getColor("color_e")
        background = cardBackground(ColorUtils.setAlphaComponent(red, 0x1F), red)
        setPadding(dp(14), dp(12), dp(10), dp(10))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dot(red), LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        val message = Texts.get("account_card_failed", domain ?: "?") + (reason?.let { ": $it" } ?: "")
        row.addView(text(message, 14f, "color_b", bold = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chevron(), LayoutParams(dp(12), dp(12)).apply { marginStart = dp(10) })
        addView(row)
        val pausedText = if (paused) Texts.get("registration_state_paused") else null
        pausedText?.let {
            addView(text(it, 13f, "color_g"), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                marginStart = dp(20)
            })
        }
        contentDescription = listOfNotNull(message, pausedText).joinToString(", ")
        val retry = LRoundRectButton(context).apply {
            Theme.roundRectButtonBackgroundStates("decline_call_button")?.also { background = it }
            buttonText.text = Texts.get("account_card_retry")
            isClickable = true
            isFocusable = true
            setOnClickListener {
                RegistrationMonitor.reset(org.linphone.core.RegistrationState.Progress)
                LinhomeApplication.coreContext.core.refreshRegisters()
                seenInProgressSinceMs = null
                handler.postDelayed({ refresh() }, 300)
            }
        }
        addView(retry, LayoutParams(dp(140), dp(40)).apply {
            topMargin = dp(10)
            marginStart = dp(20)
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { actions?.openAccount() }
    }

    /** No account yet: how to get one. */
    private fun drawSetup() {
        background = cardBackground(Theme.getColor("color_d"))
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(text(Texts.get("account_card_setup_title"), 18f, "color_b", bold = true))
        addView(text(Texts.get("account_card_setup_text"), 14f, "color_g"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })
        fun button(textKey: String, primary: Boolean, onClick: () -> Unit) = LRoundRectButton(context).apply {
            Theme.roundRectButtonBackgroundStates(if (primary) "primary_color" else "secondary_color")?.also { background = it }
            buttonText.text = Texts.get(textKey)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val params = { LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) } }
        addView(button("scan_setup_code", true) { actions?.scanSetupCode() }, params())
        addView(button("assistant_find_site_lan", false) { actions?.findSiteOnWifi() }, params())
        addView(button("assistant_enter_sip_manually", false) { actions?.enterSipAccount() }, params())
    }
}
