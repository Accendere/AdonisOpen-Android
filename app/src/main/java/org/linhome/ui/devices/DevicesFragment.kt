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

package org.linhome.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.linhome.GenericFragment
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.databinding.FragmentDevicesBinding
import org.linhome.entities.Device
import org.linhome.entities.LinhomeAccount
import org.linhome.linphonecore.RegistrationMonitor
import org.linhome.store.DeviceStore
import org.linhome.utils.DialogUtil
import org.linphone.mediastream.Log
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.ui.ringcheck.RingCheckActivity

/** The Doors tab: the account card, then the doors with a "+" under the last one. */
class DevicesFragment : GenericFragment() {

    private lateinit var devicesViewModel: DevicesViewModel
    private lateinit var binding: FragmentDevicesBinding
    private lateinit var devicesAdapter: DevicesAdapter
    private lateinit var footer: AddDoorFooterAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentDevicesBinding.inflate(inflater, container, false)
        devicesViewModel = ViewModelProvider(this).get(DevicesViewModel::class.java)
        devicesViewModel.selectedDevice =
            MutableLiveData<Device?>() // Android bug - onCreateView called on navigateUp()
        binding.lifecycleOwner = this
        binding.model = devicesViewModel

        binding.accountCard.actions = object : AccountCardView.Actions {
            override fun openAccount() = mainactivity.navController.navigate(R.id.navigation_account)
            override fun scanSetupCode() = mainactivity.openScanner()
            override fun findSiteOnWifi() = org.linhome.pairing.Pairing.searchFromButton(mainactivity)
            override fun enterSipAccount() = mainactivity.navController.navigate(R.id.navigation_assistant_login_sip)
        }
        RegistrationMonitor.snapshot.observe(viewLifecycleOwner) { binding.accountCard.refresh() }
        LinhomeAccount.appEnabledLiveData.observe(viewLifecycleOwner) { binding.accountCard.refresh() }
        org.linhome.webhooks.Webhooks.sites.observe(viewLifecycleOwner) { binding.accountCard.refresh() }

        binding.deviceList.layoutManager =
            LinearLayoutManager(activity, RecyclerView.VERTICAL, false)

        devicesAdapter = DevicesAdapter(
            devicesViewModel.devices,
            binding.deviceList,
            devicesViewModel.selectedDevice,
            this
        )
        footer = AddDoorFooterAdapter {
            mainactivity.navController.navigate(DevicesFragmentDirections.deviceNew())
        }
        binding.deviceList.adapter = ConcatAdapter(devicesAdapter, footer)

        if (LinhomeApplication.instance.smartPhone()) {
            devicesViewModel.selectedDevice.observe(viewLifecycleOwner, Observer { device ->
                device?.also {
                    val actionDetail = DevicesFragmentDirections.deviceInfo(it)
                    mainactivity.navController.navigate(actionDetail)
                }
            })
        } else {
            devicesViewModel.selectedDevice.observe(viewLifecycleOwner, Observer { device ->
                device?.also {
                    binding.fragmentDeviceInfo?.editDevice?.visibility = if (it.isRemotelyProvisionned) View.GONE else View.VISIBLE
                }
            })
        }

        if (LinhomeApplication.instance.tablet()) {
            binding.fragmentDeviceInfo?.editDevice?.setOnClickListener {
                val actionDetail = DevicesFragmentDirections.deviceEditTablet()
                actionDetail.device = devicesViewModel.selectedDevice.value
                mainactivity.navController.navigate(actionDetail)
            }
        }

        devicesViewModel.devices.observe(viewLifecycleOwner, { devices ->
            Log.i("[DevicesFragment] $devices ")
            devicesAdapter.notifyDataSetChanged()
            footer.empty = devices.isEmpty()
            binding.swiperefresh?.isRefreshing = false
        })

        devicesViewModel.syncFailed.observe(viewLifecycleOwner, { syncFailed ->
            binding.swiperefresh?.isRefreshing = false
            if (syncFailed)
                DialogUtil.error("vcard_sync_failed")
        })

        binding.swiperefresh?.setOnRefreshListener {
            if (LinhomeApplication.coreContext.core.isNetworkReachable != true) {
                binding.swiperefresh?.isRefreshing = false
                DialogUtil.error("no_network")
            } else if (LinhomeApplication.coreContext.core.callsNb == 0) {
                DeviceStore.serverFriendList?.synchronizeFriendsFromServer()
            }
        }

        return binding.root
    }

    /** Asks the sites, while this screen is on, whether a door rings that this phone isn't ringing for. */
    private val ringScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ringWatch: Job? = null

    /** The site whose ringing door the button opens, while it shows. */
    private var ringingSite: String? = null
    private var ringingDoor: String? = null

    /** The site of the ongoing call the person opened (the button may be gone when they come back). */
    private var checkedSite: String? = null

    private val ringCheck = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Back from the ongoing call: a phone still paused reminds the person with a shake of its switch.
        if (!LinhomeApplication.corePreferences.appEnabled || checkedSite?.let { id -> org.linhome.webhooks.Webhooks.siteList().firstOrNull { it.id == id }?.callsEnabled == false } == true) {
            mainactivity.shakeCallsToggle()
        }
    }

    private fun startRingWatch() {
        ringWatch?.cancel()
        ringWatch = ringScope.launch {
            while (isActive) {
                checkRings()
                delay(RING_WATCH_MS)
            }
        }
    }

    private suspend fun checkRings() {
        if (LinhomeApplication.coreContext.core.callsNb > 0) {
            showRingCheck(null, null)
            return
        }
        for (site in org.linhome.webhooks.Webhooks.siteList()) {
            val ring = org.linhome.webhooks.Webhooks.ring(site.id, snapshot = false) ?: continue
            if (ring.ringing) {
                showRingCheck(site.id, appDoorName(site.id) ?: ring.door)
                return
            }
        }
        showRingCheck(null, null)
    }

    /** The app's name for the site's door, when the site has exactly one here. */
    private fun appDoorName(siteId: String): String? =
        DeviceStore.devices.filter { org.linhome.webhooks.Webhooks.siteForDoor(it.address)?.id == siteId }.singleOrNull()?.name

    private fun showRingCheck(siteId: String?, door: String?) {
        if (!isAdded) return
        ringingSite = siteId
        ringingDoor = door
        val button = binding.ringCheckButton ?: return
        if (siteId == null) {
            button.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        button.text = door?.let { Texts.get("ring_check_button", it) } ?: Texts.get("ring_check_button_unknown")
        button.setTextColor(Theme.getColor("color_c"))
        button.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 26f * density
            setColor(Theme.getColor("color_a"))
        }
        button.setOnClickListener {
            checkedSite = ringingSite
            ringCheck.launch(RingCheckActivity.intent(requireContext(), ringingSite, ringingDoor))
        }
        button.visibility = View.VISIBLE
    }

    override fun onPause() {
        ringWatch?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        ringWatch?.cancel()
        super.onDestroyView()
    }

    companion object {
        /** How often the Doors screen asks the sites whether a door rings. */
        const val RING_WATCH_MS = 5_000L
    }

    override fun onResume() {
        super.onResume()
        startRingWatch()
        devicesViewModel.devices.value = DeviceStore.devices
        devicesAdapter.notifyDataSetChanged()
        footer.empty = DeviceStore.devices.isEmpty()
        binding.invalidateAll()
        binding.accountCard.refresh()
        binding.swiperefresh?.isEnabled = DeviceStore.serverFriendList != null
    }

}
