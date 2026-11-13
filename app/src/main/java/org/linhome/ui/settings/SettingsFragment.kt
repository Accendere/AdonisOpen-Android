/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 * Modifications Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android,
 * see https://www.linhome.org).
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

package org.linhome.ui.settings

import android.content.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.linhome.BR
import org.linhome.GenericFragment
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.databinding.FragmentSettingsBinding
import org.linhome.utils.DialogUtil
import org.linphone.core.Core
import org.linphone.core.PayloadType
import org.linphone.core.tools.Log


class SettingsFragment : GenericFragment() {

    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var binding: FragmentSettingsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSettingsBinding.inflate(inflater, container, false)
        settingsViewModel = ViewModelProvider(this).get(SettingsViewModel::class.java)
        initCodecsList(
            LinhomeApplication.coreContext.core.audioPayloadTypes.filter { LinhomeApplication.corePreferences.availableAudioCodecs.contains(it.mimeType.lowercase()) }.toTypedArray(),
            settingsViewModel.audioCodecs,
            true
        )
        initCodecsList(
            LinhomeApplication.coreContext.core.videoPayloadTypes,
            settingsViewModel.videCodecs
        )
        binding.model = settingsViewModel
        binding.view = this
        binding.coreContext = LinhomeApplication.coreContext
        binding.lifecycleOwner = this
        setupCards()
        setupAboutLinks()
        buildWebhooksCard()
        settingsViewModel.themeChangedEvent.observe(viewLifecycleOwner, Observer { changed ->
            if (changed) {
                settingsViewModel.themeChangedEvent.value = false
                mainactivity.applyTheme()
                mainactivity.recreate()
            }
        })
        return binding.root
    }


    /**
     * The cards open and close by tapping their header; which ones are open is kept while the app runs. Every card
     * starts closed, and its header is tinted (color_v where the theme has one, Dark's lighter slate, else the
     * accent tint color_i; the chevron color_a), so it reads as a heading and not as one more setting on the body
     * (color_d).
     */
    private fun setupCards() {
        val cards = listOf(
            Triple("calls", binding.cardCalls, binding.headerCalls) to binding.bodyCalls,
            Triple("appearance", binding.cardAppearance, binding.headerAppearance) to binding.bodyAppearance,
            Triple("history", binding.cardHistory, binding.headerHistory) to binding.bodyHistory,
            Triple("webhooks", binding.cardWebhooks, binding.headerWebhooks) to binding.bodyWebhooks,
            Triple("advanced", binding.cardAdvanced, binding.headerAdvanced) to binding.bodyAdvanced,
            Triple("diagnostics", binding.cardDiagnostics, binding.headerDiagnostics) to binding.bodyDiagnostics,
            Triple("about", binding.cardAbout, binding.headerAbout) to binding.bodyAbout
        )
        val radius = 16f * resources.displayMetrics.density
        for ((parts, body) in cards) {
            val (key, card, header) = parts
            card.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                setColor(Theme.getColor("color_d"))
            }
            card.clipToOutline = true
            val accent = Theme.getColor("color_a")
            header.cardHeader.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x40)),
                android.graphics.drawable.ColorDrawable(Theme.getColorOr("color_v", "color_i")),
                null
            )
            header.chevron.setColorFilter(accent)
            fun show(open: Boolean) {
                body.visibility = if (open) View.VISIBLE else View.GONE
                header.chevron.rotation = if (open) 180f else 0f
                header.cardHeader.contentDescription = header.cardTitle.text.toString() + ", " +
                    Texts.get(if (open) "settings_card_expanded" else "settings_card_collapsed")
            }
            show(openCards[key] ?: false)
            header.cardHeader.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                openCards[key] = open
                show(open)
            }
        }
    }

    /**
     * The webhooks: one switch per event, with what stops working when it's off, and the address they go to. The
     * person has the last word on what their phone sends (see WebhookEngine.applyLocalEdit()). Moving the address
     * to another server removes the building's key from the phone, after a warning in plain words.
     */
    private fun buildWebhooksCard() {
        val body = binding.bodyWebhooks
        body.removeAllViews()
        val ctx = requireContext()
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun text(value: String?, sizeSp: Float = 14f, colorKey: String = "color_g") = android.widget.TextView(ctx).apply {
            this.text = value
            textSize = sizeSp
            setTextColor(Theme.getColor(colorKey))
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // One card for the first site with webhooks (with one site: "the" site), until each site gets its own card.
        val site = org.linhome.webhooks.Webhooks.siteList().firstOrNull { org.linhome.webhooks.Webhooks.config(it.id) != null }
        val config = site?.let { org.linhome.webhooks.Webhooks.config(it.id) }
        if (site == null || config == null) {
            body.addView(text(Texts.get("settings_webhooks_none")))
            return
        }
        body.addView(text(Texts.get("settings_webhooks_intro", config.displayHost)))
        if (config.editedLocally) body.addView(text(Texts.get("settings_webhooks_edited")))

        val chosen = config.sending.toMutableSet()
        val shown = org.linhome.webhooks.WebhookEvents.EDITABLE.filter { it in config.askedBySite || it in config.sending }
        val save = com.google.android.material.button.MaterialButton(ctx).apply {
            this.text = Texts.get("settings_webhooks_save")
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Theme.getColor("color_a"))
            setTextColor(Theme.getColor("color_c"))
            isEnabled = false
            alpha = 0.5f
        }
        fun dirty() {
            save.isEnabled = true
            save.alpha = 1f
        }
        shown.forEach { event ->
            val row = org.linhome.databinding.SettingsWidgetSwitchBinding.inflate(layoutInflater, body, false)
            row.title = Texts.get("webhook_event_$event")
            row.subtitle = Texts.get("webhook_event_${event}_desc")
            row.checked = event in chosen
            row.lifecycleOwner = viewLifecycleOwner
            row.listener = SettingListenerStub()
            row.executePendingBindings()
            // Only now: setting the initial state above must not count as a change.
            row.listener = object : SettingListenerStub() {
                override fun onBoolValueChanged(newValue: Boolean) {
                    if (newValue) chosen += event else chosen -= event
                    dirty()
                }
            }
            row.executePendingBindings()
            // The widget scrolls its subtitle on one line; here it is a sentence to read: wrap it.
            row.settingsSubtitle.apply {
                isSingleLine = false
                maxLines = 4
                ellipsize = null
                isSelected = false
            }
            body.addView(row.root)
        }

        body.addView(text(Texts.get("settings_webhooks_address"), 14f, "color_b"))
        val address = android.widget.EditText(ctx).apply {
            setText(config.baseUrl)
            setSingleLine()
            textSize = 14f
            setTextColor(Theme.getColor("color_b"))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = dirty()
            })
        }
        body.addView(android.widget.FrameLayout(ctx).apply {
            setPadding(pad, 0, pad, 0)
            addView(address)
        })
        body.addView(android.widget.FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(save)
        })

        save.setOnClickListener {
            val newAddress = address.text.toString()
            val normalized = org.linhome.webhooks.WebhookConfig.normalizedHttpsUrl(newAddress)
            if (normalized == null) {
                DialogUtil.toast("settings_webhooks_invalid_address", long = true)
                return@setOnClickListener
            }
            fun apply() {
                val result = org.linhome.webhooks.Webhooks.applyLocalEdit(site.id, chosen.toSet(), normalized)
                when (result) {
                    org.linhome.webhooks.WebhookEngine.LocalEdit.SAVED -> DialogUtil.toast("settings_webhooks_saved")
                    org.linhome.webhooks.WebhookEngine.LocalEdit.SAVED_KEY_REMOVED -> DialogUtil.toast("settings_webhooks_saved_elsewhere", long = true)
                    else -> DialogUtil.toast("settings_webhooks_invalid_address", long = true)
                }
                buildWebhooksCard()
            }
            if (org.linhome.webhooks.WebhookConfig.sameServer(config.baseUrl, normalized)) {
                apply()
            } else {
                val newHost = runCatching { java.net.URI(normalized).host }.getOrNull() ?: normalized
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(Texts.get("settings_webhooks_elsewhere_title"))
                    .setMessage(Texts.get("settings_webhooks_elsewhere_message", newHost, config.displayHost))
                    .setPositiveButton(Texts.get("settings_webhooks_elsewhere_confirm")) { _, _ -> apply() }
                    .setNegativeButton(Texts.get("cancel"), null)
                    .show()
            }
        }
    }

    private fun setupAboutLinks() {
        fun open(urlKey: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Texts.get(urlKey))))
            } catch (e: ActivityNotFoundException) {
                Log.e(e)
            }
        }
        binding.about.sourceCode.setOnClickListener { open("about_source_url") }
        binding.about.upstreamLink.setOnClickListener { open("about_upstream_url") }
        binding.about.licence.setOnClickListener { open("license_link") }
        binding.about.privacyPolicy.setOnClickListener { open("privacy_policy_link") }
    }

    companion object {
        /** The cards the person opened or closed since the app started; the others are closed. */
        private val openCards = mutableMapOf<String, Boolean>()
    }

    private fun initCodecsList(
        payloads: Array<PayloadType>,
        target: ArrayList<ViewDataBinding>,
        showRate: Boolean = false
    ) {
        for (payload in payloads) {
            val binding = DataBindingUtil.inflate<ViewDataBinding>(
                LayoutInflater.from(requireContext()),
                R.layout.settings_widget_switch,
                null,
                false
            )
            binding.setVariable(BR.title, payload.mimeType)
            if (showRate)
                binding.setVariable(BR.subtitle, "${payload.clockRate} Hz")
            binding.setVariable(BR.checked, payload.enabled())
            binding.setVariable(BR.listener, object : SettingListenerStub() {
                override fun onBoolValueChanged(newValue: Boolean) {
                    payload.enable(newValue)
                }
            })
            binding.lifecycleOwner = this
            target.add(binding)
        }
    }


    val sendLogsListener = object : SettingListenerStub() {
        override fun onClicked() {
            showProgress()
            binding.sendLogs.root.isEnabled = false
            settingsViewModel.logUploadResult.observe(viewLifecycleOwner, Observer { result ->
                when (result.first) {
                    Core.LogCollectionUploadState.InProgress -> {
                    }
                    Core.LogCollectionUploadState.NotDelivered -> {
                        hideProgress()
                        binding.sendLogs.root.isEnabled = true
                        DialogUtil.error("log_upload_failed")
                    }
                    Core.LogCollectionUploadState.Delivered -> {
                        hideProgress()
                        binding.sendLogs.root.isEnabled = true
                        val clipboard =
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Logs url", result.second)
                        clipboard.setPrimaryClip(clip)
                        DialogUtil.toast("log_upload_success")
                        shareUploadedLogsUrl(result.second)
                    }
                }
            })
            LinhomeApplication.coreContext.core.uploadLogCollection()
        }
    }

    val clearLogsListener = object : SettingListenerStub() {
        override fun onClicked() {
            LinhomeApplication.coreContext.core.resetLogCollection()
            DialogUtil.toast("log_clear_success")
        }
    }

    private fun shareUploadedLogsUrl(info: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(
            Intent.EXTRA_EMAIL,
            arrayOf(Texts.get("support_email_android"))
        )
        intent.putExtra(Intent.EXTRA_SUBJECT, "${Texts.appName} Logs")
        intent.putExtra(Intent.EXTRA_TEXT, info)
        intent.type = "application/zip"

        try {
            startActivity(Intent.createChooser(intent, "Send mail..."))
        } catch (ex: ActivityNotFoundException) {
            Log.e(ex)
        }
    }


}
