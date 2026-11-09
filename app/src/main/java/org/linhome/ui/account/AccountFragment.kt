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

package org.linhome.ui.account

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import org.linhome.LinhomeApplication
import org.linhome.MainActivity
import org.linhome.R
import org.linhome.customisation.Theme
import org.linhome.databinding.FragmentAccountBinding
import org.linhome.entities.LinhomeAccount
import org.linhome.utils.DialogUtil
import org.linhome.utils.SiteInfo

/**
 * The Account screen: the account's summary, Edit account (the SIP form, saving in place), new settings from
 * the site (scan, Wi-Fi), and Forget account. The password is changed in Edit account.
 */
class AccountFragment : Fragment() {

    private var accountViewModel: AccountViewModel? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentAccountBinding.inflate(inflater, container, false)
        val model = ViewModelProvider(this).get(AccountViewModel::class.java)
        accountViewModel = model
        binding.model = model
        binding.lifecycleOwner  = viewLifecycleOwner

        LinhomeAccount.appEnabledLiveData.observe(viewLifecycleOwner) {
            model.refreshDescriptions()
        }

        binding.editAccount.setOnClickListener {
            findNavController().navigate(R.id.navigation_account_edit)
        }

        binding.setupSip.setOnClickListener {
            findNavController().navigate(R.id.navigation_assistant_login_sip)
        }

        binding.scanSetupCode.setOnClickListener {
            (requireActivity() as MainActivity).openScanner()
        }

        binding.findSite.setOnClickListener {
            org.linhome.pairing.Pairing.searchFromButton(requireActivity() as MainActivity)
        }

        Theme.roundRectButtonBackgroundStates("decline_call_button")?.also { binding.forgetAccount.background = it }
        binding.forgetAccount.setOnClickListener {
            DialogUtil.confirm(
                "account_forget",
                "account_forget_confirm",
                { _: DialogInterface, _: Int ->
                    LinhomeAccount.disconnect()
                    SiteInfo.setName(LinhomeApplication.instance, null)
                    org.linhome.linphonecore.RegistrationMonitor.reset(null)
                    (requireActivity() as MainActivity).showDoors()
                },
                confirmTextKey = "account_forget"
            )
        }

        // The theme's text style sets the size on the first binding pass: make the account stand out after it.
        binding.executePendingBindings()
        binding.identity.textSize = 20f

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        accountViewModel?.refreshDescriptions()
    }
}
