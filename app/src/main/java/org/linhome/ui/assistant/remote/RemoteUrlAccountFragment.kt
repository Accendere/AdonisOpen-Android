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

package org.linhome.ui.assistant.remote

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.linhome.GenericFragment
import org.linhome.R
import org.linhome.databinding.FragmentAssistantRemoteUrlBinding
import org.linhome.entities.LinhomeAccount
import org.linhome.ui.validators.ValidatorFactory
import org.linhome.utils.DialogUtil
import org.linphone.core.ConfiguringState

class RemoteUrlAccountFragment : GenericFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val binding = FragmentAssistantRemoteUrlBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this

        val model = ViewModelProvider(this).get(RemoteAnyAccountViewModel::class.java)
        binding.model = model
        binding.validators = ValidatorFactory.Companion

        binding.apply.setOnClickListener {
            binding.url.validate()
            if (model.valid()) {
                val applyNow = {
                    binding.apply.isEnabled = false
                    hideKeyboard()
                    showProgress()
                    if (LinhomeAccount.configured()) {
                        LinhomeAccount.disconnect(deletePushAccount = true)
                    }
                    model.startRemoteProvisionning()
                }
                if (LinhomeAccount.configured()) {
                    DialogUtil.confirm(
                        "assistant_using_will_disconnect_title",
                        "assistant_using_will_disconnect_message",
                        { _: DialogInterface, _: Int ->
                            applyNow()
                        }
                    )
                } else {
                    applyNow()
                }
            }
        }

        model.configurationResult.observe(viewLifecycleOwner, Observer { status ->
            hideProgress()
            binding.apply.isEnabled = true
            when (status) {
                ConfiguringState.Failed -> {
                    DialogUtil.error("remote_configuration_failed")
                }
                ConfiguringState.Skipped -> {
                    DialogUtil.error("remote_configuration_failed")
                }
                else -> {}
            }
        })

        model.pushReady.observe(viewLifecycleOwner, Observer { status ->
            hideProgress()
            mainactivity.navController.popBackStack(R.id.navigation_devices, false)
            if (status) {
                DialogUtil.info("remote_configuration_success")
            } else
                DialogUtil.error("failed_creating_pushgateway")
        })


        return binding.root
    }

}

