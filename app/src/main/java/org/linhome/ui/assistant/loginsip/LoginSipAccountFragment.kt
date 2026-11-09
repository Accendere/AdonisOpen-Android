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

package org.linhome.ui.assistant.loginsip

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.databinding.FragmentAssistantLoginSipBinding
import org.linhome.entities.LinhomeAccount
import org.linhome.ui.assistant.shared.CreatorAssistantFragment
import org.linhome.ui.validators.ValidatorFactory
import org.linhome.utils.DialogUtil
import org.linphone.core.TransportType

class LoginSipAccountFragment : CreatorAssistantFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val binding = FragmentAssistantLoginSipBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this

        val model = ViewModelProvider(this).get(LoginSipAccountViewModel::class.java)
        binding.model = model
        binding.validators = ValidatorFactory.Companion

        binding.more.setOnClickListener {
            model.moreOptionsOpened.value = true
        }

        if (arguments?.getBoolean("edit") == true) {
            setUpEditing(binding, model)
            return binding.root
        }

        binding.loginsip.setOnClickListener {
            model.accountCreator.reset()
            binding.username.validate()
            binding.password.validate()
            binding.domain.validate()
            binding.proxy.validate()
            binding.expiration.validate()
            updateField(model.setUsername(model.username), binding.username)
            updateField(model.setPassword(model.pass1), binding.password)
            updateField(model.setDomain(model.domain), binding.domain)
            model.setTransport(TransportType.values()[model.transport.value!!])
            if (model.valid()) {
                binding.loginsip.isEnabled = false
                hideKeyboard()
                showProgress()
                model.sipAccountLogin(
                    model.proxy.first.value,
                    model.expiration.first.value!!,
                    model.sipRegistered
                )
            }
        }
        model.pushReady.observe(viewLifecycleOwner, Observer { pushready ->
            hideProgress()
            // Set up by hand: no site name to show on the account card.
            org.linhome.utils.SiteInfo.setName(requireContext(), null)
            if (pushready) {
                DialogUtil.info("sip_account_created")
                mainactivity.navController.popBackStack(R.id.navigation_devices, false)
            } else {
                DialogUtil.error("failed_creating_pushgateway")
                mainactivity.navController.popBackStack(R.id.navigation_devices, false)
            }
        })

        model.sipRegistered.observe(viewLifecycleOwner, Observer { sipRegistered ->
            if (!sipRegistered) {
                binding.loginsip.isEnabled = true
                hideProgress()
                DialogUtil.confirm(
                    null,
                    "failed_sip_login_modify_parameters",
                    { _: DialogInterface, _: Int ->
                        LinhomeAccount.disconnect()
                    },
                    cancelFunction = {_: DialogInterface, _: Int ->
                        mainactivity.navController.popBackStack(R.id.navigation_devices, false)
                    },
                    cancelTextKey = "no",
                    confirmTextKey = "yes")
            }
        })


        return binding.root
    }

    /**
     * Account > Edit account: the form prefilled with the current account, saved in place (not removed and
     * created again, so the doors, the history and the webhooks stay). An empty password keeps the current one.
     */
    private fun setUpEditing(binding: FragmentAssistantLoginSipBinding, model: LoginSipAccountViewModel) {
        if (!model.prefilled) model.prefillFromCurrentAccount()
        // Static texts are bound once, on the first pass: run it now so ours stay. The transport control's
        // default (TLS) clicks itself on that pass: select the account's transport again afterwards.
        val transport = model.transport.value ?: 0
        binding.executePendingBindings()
        binding.transport.defaultSelected = transport.coerceIn(0, 2)
        binding.title.text = Texts.get("account_edit")
        binding.loginsip.buttonText.text = Texts.get("account_save")
        binding.password.hint = "account_password_keep_hint"
        binding.password.validator = null
        binding.loginsip.setOnClickListener {
            binding.username.validate()
            binding.domain.validate()
            binding.proxy.validate()
            binding.expiration.validate()
            val typed = model.pass1.first.value
            if (!typed.isNullOrEmpty()) updateField(model.setPassword(model.pass1), binding.password)
            if (!(model.username.second.value!! && model.domain.second.value!! && model.expiration.second.value!! &&
                    (typed.isNullOrEmpty() || model.pass1.second.value!!))) return@setOnClickListener
            hideKeyboard()
            when (model.saveEdits(typed)) {
                LoginSipAccountViewModel.EditResult.SAVED -> {
                    DialogUtil.toast("account_saved")
                    mainactivity.navController.navigateUp()
                }
                LoginSipAccountViewModel.EditResult.NEED_PASSWORD -> binding.password.setError(Texts.get("account_password_needed"))
                LoginSipAccountViewModel.EditResult.INVALID -> DialogUtil.error("account_save_failed")
            }
        }
    }
}
