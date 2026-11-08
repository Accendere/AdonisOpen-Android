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
package org.linhome.ui.importsettings

import android.app.Dialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.linhome.MainActivity
import org.linhome.R
import org.linhome.claim.ClaimContract
import org.linhome.customisation.Texts
import org.linhome.utils.AdonisConfig
import org.linhome.utils.DeviceImport
import org.linhome.utils.ImportChange
import org.linhome.utils.ImportPlan
import org.linhome.utils.ImportSelection
import org.linhome.webhooks.PollMode

/**
 * The full-screen "import settings" page: a summary with Apply on top, and "Show details" for people who
 * want to check every part and pick only some. Everything that changes something is pre-selected; what is
 * unchanged is listed, unselected. The setup (with the SIP password) stays in memory: never in an Intent.
 */
class ImportSettingsScreen(
    private val activity: MainActivity,
    private val config: AdonisConfig,
    private val plan: ImportPlan,
    private val onApply: (ImportSelection) -> Unit,
    private val onCancel: () -> Unit
) {
    private var sip = plan.defaultSelection().sip
    private val devices = plan.defaultSelection().devices.toMutableSet()
    private var webhook = plan.defaultSelection().webhook
    private var remote = plan.defaultSelection().remoteProvisioning

    private lateinit var dialog: Dialog
    private lateinit var applyButton: Button
    private lateinit var summary: LinearLayout

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    private fun selection() = ImportSelection(sip, devices.toSet(), webhook, remote)

    fun show(): Dialog {
        dialog = Dialog(activity, R.style.LinhomeTheme)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.color_c))
            fitsSystemWindows = true
        }
        root.addView(header())
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(20))
        }
        summary = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(summary)
        fillSummary()
        content.addView(details())
        root.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons())
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onCancel() }
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        updateApply()
        dialog.show()
        return dialog
    }

    private fun header(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(12))
        addView(ImageView(activity).apply { setImageResource(R.drawable.ic_linhome_icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(text(Texts.get("import_title"), 20f, bold = true))
            val from = config.claimUrl?.let { Texts.get("adonis_claim_from", ClaimContract.hostOf(it)) }
            from?.let { addView(text(it, 13f, secondary = true)) }
        })
    }

    // ---------- summary ----------

    private fun fillSummary() {
        summary.removeAllViews()
        if (plan.nothingChanges) {
            summary.addView(text(Texts.get("import_nothing_changes"), 15f).withMargins(top = 8))
        } else {
            summary.addView(text(Texts.get("import_summary_intro"), 15f).withMargins(top = 4, bottom = 8))
        }
        plan.sip?.let { s ->
            val line = when (s.change) {
                ImportChange.NEW -> Texts.get("import_sip_new", s.incoming.username + "@" + s.incoming.domain)
                ImportChange.UNCHANGED -> Texts.get("import_sip_unchanged", s.incoming.username + "@" + s.incoming.domain)
                else -> when {
                    s.replaces != null -> Texts.get("import_sip_replaces", s.incoming.username + "@" + s.incoming.domain, s.replaces)
                    s.differences == listOf("password") -> Texts.get("import_sip_password_changed", s.incoming.username + "@" + s.incoming.domain)
                    else -> Texts.get("import_sip_changed", s.incoming.username + "@" + s.incoming.domain, s.differences.joinToString(", ") { label(it) })
                }
            }
            summary.addView(bullet(line, warn = s.replaces != null))
        }
        if (plan.devices.isNotEmpty()) {
            val new = plan.devices.count { it.change == ImportChange.NEW }
            val changed = plan.devices.count { it.change == ImportChange.CHANGED }
            val same = plan.devices.count { it.change == ImportChange.UNCHANGED }
            // Only the counts that aren't zero: "Doors: 2 new", "Doors: 1 updated, 3 unchanged".
            val parts = listOfNotNull(
                new.takeIf { it > 0 }?.let { Texts.get("import_doors_new", it.toString()) },
                changed.takeIf { it > 0 }?.let { Texts.get("import_doors_updated", it.toString()) },
                same.takeIf { it > 0 }?.let { Texts.get("import_doors_unchanged", it.toString()) }
            )
            summary.addView(bullet(Texts.get("import_doors_summary", parts.joinToString(", "))))
        }
        plan.webhook?.let { w ->
            val line = when (w.change) {
                ImportChange.REMOVED -> Texts.get("import_webhook_removed")
                ImportChange.UNCHANGED -> Texts.get("import_webhook_unchanged", w.incoming!!.displayHost)
                else -> Texts.get("import_webhook_summary", w.incoming!!.displayHost, eventCount(w.incoming).toString())
            }
            summary.addView(bullet(line))
        }
        plan.remoteProvisioningUrl?.let { summary.addView(bullet(Texts.get("import_remote_summary", it), warn = !it.startsWith("https://", true))) }
    }

    private fun eventCount(w: org.linhome.webhooks.WebhookConfig): Int = w.events?.size ?: 1

    // ---------- details ----------

    private fun details(): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggle = text("▸ " + Texts.get("import_show_details"), 15f, accent = true).apply {
            setPadding(0, dp(18), 0, dp(6))
            isClickable = true
            isFocusable = true
        }
        toggle.setOnClickListener {
            val open = container.visibility != View.VISIBLE
            container.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = (if (open) "▾ " else "▸ ") + Texts.get(if (open) "import_hide_details" else "import_show_details")
        }
        plan.sip?.let { s ->
            container.addView(sectionTitle(Texts.get("import_section_account")))
            container.addView(check(
                "${s.incoming.username}@${s.incoming.domain}",
                listOfNotNull(
                    Texts.get("import_transport", (s.incoming.transport ?: "udp").uppercase()),
                    s.incoming.proxy?.let { Texts.get("import_proxy", it) },
                    changeNote(s.change, s.differences),
                    s.replaces?.let { Texts.get("import_replaces_account", it) }
                ),
                checked = sip, enabled = s.change != ImportChange.UNCHANGED
            ) { sip = it; updateApply() })
        }
        if (plan.devices.isNotEmpty()) {
            container.addView(sectionTitle(Texts.get("import_section_doors")))
            plan.devices.forEach { d -> container.addView(deviceRow(d)) }
        }
        plan.webhook?.let { w ->
            container.addView(sectionTitle(Texts.get("import_section_webhook")))
            if (w.change == ImportChange.REMOVED || w.incoming == null) {
                container.addView(check(Texts.get("import_webhook_removed"), emptyList(), webhook, true) { webhook = it; updateApply() })
            } else {
                val cfg = w.incoming
                val lines = mutableListOf(
                    Texts.get("import_webhook_domain", cfg.displayHost),
                    Texts.get(if (cfg.auth == org.linhome.webhooks.WebhookAuth.BEARER) "import_webhook_auth_token" else "import_webhook_auth_none")
                )
                lines += when (cfg.pollMode) {
                    PollMode.ALWAYS -> Texts.get("import_webhook_poll_always", cfg.pollingIntervalSeconds.toString())
                    PollMode.FOREGROUND_ONLY -> Texts.get("import_webhook_poll_foreground", cfg.pollingIntervalSeconds.toString())
                    PollMode.NEVER -> Texts.get("import_webhook_poll_never")
                }
                changeNote(w.change, w.differences)?.let { lines += it }
                val row = check(cfg.displayHost, lines, webhook, w.change != ImportChange.UNCHANGED) { webhook = it; updateApply() }
                container.addView(row)
                container.addView(ChipGroup(activity).apply {
                    setPadding(dp(36), 0, 0, dp(8))
                    val names = cfg.events?.sorted() ?: listOf("state")
                    names.forEach { name ->
                        addView(Chip(activity).apply {
                            text = name
                            isClickable = false
                            isCheckable = false
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        })
                    }
                })
            }
        }
        plan.remoteProvisioningUrl?.let { url ->
            container.addView(sectionTitle(Texts.get("import_section_remote")))
            val lines = mutableListOf<String>()
            if (!url.startsWith("https://", true)) lines += Texts.get("import_remote_insecure")
            container.addView(check(url, lines, remote, true) { remote = it; updateApply() })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(container)
        }
    }

    private fun deviceRow(d: DeviceImport): View {
        val dev = d.incoming
        val actions = dev.actions.joinToString(", ") { "${Texts.get(org.linhome.utils.AdonisProvisioner.mapActionType(it.type))} (${it.code})" }
        val lines = listOfNotNull(
            dev.address,
            dev.alternativeAddress?.let { Texts.get("import_door_alternative", it) },
            actions.takeIf { it.isNotBlank() },
            if (d.change == ImportChange.CHANGED && d.matchesName != null && d.matchesName != dev.name) Texts.get("import_door_replaces", d.matchesName) else null,
            changeNote(d.change, d.differences)
        )
        return check(dev.name, lines, d.index in devices, d.change != ImportChange.UNCHANGED) { on ->
            if (on) devices += d.index else devices -= d.index
            updateApply()
        }
    }

    private fun changeNote(change: ImportChange, differences: List<String>): String? = when (change) {
        ImportChange.NEW -> Texts.get("import_badge_new")
        ImportChange.UNCHANGED -> Texts.get("import_badge_unchanged")
        ImportChange.REMOVED -> null
        ImportChange.CHANGED -> Texts.get("import_badge_changed", differences.joinToString(", ") { label(it) })
    }

    private fun label(difference: String): String = Texts.get("import_field_$difference")

    // ---------- building blocks ----------

    private fun buttons(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(dp(16), dp(8), dp(16), dp(16))
        setBackgroundColor(color(R.color.color_f))
        addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = activity.getString(android.R.string.cancel)
            setOnClickListener {
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onCancel()
            }
        })
        applyButton = Button(activity).apply {
            text = Texts.get("import_apply")
            setOnClickListener {
                val chosen = selection()
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onApply(chosen)
            }
        }
        addView(applyButton)
    }

    private fun updateApply() {
        val enabled = !selection().isEmpty
        applyButton.isEnabled = enabled
        applyButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun sectionTitle(title: String) = text(title.uppercase(), 12f, bold = true, secondary = true).withMargins(top = 16, bottom = 4)

    private fun check(title: String, lines: List<String>, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
            val box = CheckBox(activity).apply {
                text = title
                isChecked = checked
                isEnabled = enabled
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.color_b))
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(box)
            lines.forEach { addView(text(it, 13f, secondary = true).apply { setPadding(dp(36), 0, 0, 0) }) }
        }

    private fun bullet(line: String, warn: Boolean = false) = text("•  $line", 15f).apply {
        if (warn) setTextColor(android.graphics.Color.parseColor("#D97706"))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, secondary: Boolean = false, accent: Boolean = false) =
        TextView(activity).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(if (accent) R.color.color_a else if (secondary) R.color.color_g else R.color.color_b))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun <T : View> T.withMargins(top: Int = 0, bottom: Int = 0): T {
        setPadding(paddingLeft, dp(top), paddingRight, dp(bottom))
        return this
    }
}
