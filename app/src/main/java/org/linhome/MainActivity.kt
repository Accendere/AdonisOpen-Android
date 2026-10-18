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

package org.linhome

import android.Manifest
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.databinding.ActivityMainBinding
import org.linhome.entities.LinhomeAccount
import org.linhome.linphonecore.extensions.extendedAccept
import org.linhome.store.StorageManager
import org.linhome.ui.tabbar.TabbarViewModel
import org.linhome.ui.toolbar.ToobarButtonClickedListener
import org.linhome.ui.toolbar.ToolbarViewModel
import org.linhome.utils.ContactsHelper
import org.linhome.utils.DialogUtil
import org.linphone.core.tools.Log
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnNeverAskAgain
import permissions.dispatcher.OnPermissionDenied
import permissions.dispatcher.RuntimePermissions


@RuntimePermissions
class MainActivity : GenericActivity() {

    lateinit var navController: NavController
    lateinit var navControllerSideMenu: NavController
    lateinit var toolbarViewModel: ToolbarViewModel
    lateinit var tabbarViewModel: TabbarViewModel

    var toobarButtonClickedListener: ToobarButtonClickedListener? = null
    lateinit var binding: ActivityMainBinding
    private var pendingAdonisConfig: org.linhome.utils.AdonisConfig? = null
    private var currentAutoConfigDialog: androidx.appcompat.app.AlertDialog? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        val decorView: View = window.decorView
        val uiOptions: Int = View.SYSTEM_UI_FLAG_VISIBLE
        decorView.systemUiVisibility = uiOptions
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        applyCommonTheme()


        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this

        toolbarViewModel = ViewModelProvider(this).get(ToolbarViewModel::class.java)
        binding.toolbarviewmodel = toolbarViewModel

        tabbarViewModel = ViewModelProvider(this).get(TabbarViewModel::class.java)
        binding.tabbarviewmodel = tabbarViewModel

        setSupportActionBar(binding.appbar.toolbar)

        navController = findNavController(R.id.navigation_host_fragment)
        navControllerSideMenu = findNavController(R.id.host_fragment_sidemenu)

        applyTheme()

        binding.appbar.contentmain.tabbarDevices.setOnClickListener {
            if (tabBarLayoutClicked(binding.appbar.contentmain.tabbarDevices, binding.appbar.contentmain.tabbarHistory)) {
                navController.popBackStack()
                navController.navigate(R.id.navigation_devices, null)
            }
        }
        binding.appbar.contentmain.tabbarHistory.setOnClickListener {
            if (tabBarLayoutClicked(binding.appbar.contentmain.tabbarHistory, binding.appbar.contentmain.tabbarDevices)) {
                navController.popBackStack()
                navController.navigate(R.id.navigation_history, null)
            }
        }
        tabBarLayoutClicked(binding.appbar.contentmain.tabbarDevices, binding.appbar.contentmain.tabbarHistory)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (sideMenuOpened()) {
                navControllerSideMenu.navigateUp()
            }
            when (destination.id) {
                R.id.navigation_devices, R.id.navigation_history -> {
                    enterRootFragment()
                }
                else -> {
                    enterNonRootFragment()
                }
            }
            binding.appbar.toolbarTitle.text = titleForNavigationFragment(destination.id)
        }

        navControllerSideMenu.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.fragment_sidemenu -> {
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.navigation_host_fragment)
                    val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                    if (currentFragment is org.linhome.ui.history.HistoryFragment && currentFragment.isEditing) {
                        currentFragment.exitEdition()
                    }
                    enterNonRootFragment()
                    binding.appbar.toolbarTitle.text = null
                }
                else -> {
                    enterRootFragment()
                    binding.appbar.toolbarTitle.text =
                        titleForNavigationFragment(navController.currentDestination?.id)
                    if (navController.currentDestination?.id == R.id.navigation_history) {
                        val navHostFragment = supportFragmentManager.findFragmentById(R.id.navigation_host_fragment)
                        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                        if (currentFragment is org.linhome.ui.history.HistoryFragment) {
                            currentFragment.updateToolbarButtons()
                        }
                    }
                }
            }
        }

        binding.appbar.toolbarRightButton.setOnClickListener {
            toobarButtonClickedListener?.onToolbarRightButtonClicked()
        }
        binding.appbar.toolbarLeftButton.setOnClickListener {
            toobarButtonClickedListener?.onToolbarLeftButtonClicked()
        }
        binding.appbar.toolbarBurgerButton.setOnClickListener {
            onToolbarBurgerButtonClicked()
        }
        binding.appbar.toolbarBackButton.setOnClickListener {
            onToolbarBackButtonClicked()
        }
        binding.appbar.toolbarCallsToggle.setOnClickListener {
            Compatibility.vibrateActionFeedback()
            val nextState = !LinhomeApplication.corePreferences.appEnabled
            LinhomeAccount.setAppEnabled(nextState)
            updateToolbarCallsToggle()
        }
        LinhomeAccount.appEnabledLiveData.observe(this) {
            updateToolbarCallsToggle()
        }
        updateToolbarCallsToggle()

        LinhomeApplication.coreContext.closeAppUponCallFinish = false
        val isAdonisIntent = intent?.data?.scheme.equals("adonis", ignoreCase = true)
        if (isAdonisIntent) {
            LinhomeApplication.coreContext.closeAppUponCallFinish = false
            intent?.data?.let { uri ->
                pendingAdonisConfig = org.linhome.utils.AdonisProvisioner.parseUri(uri)
            }
        }

        if (!LinhomeAccount.configured() && pendingAdonisConfig == null && (savedInstanceState == null || !savedInstanceState.getBoolean("ignored_no_account"))) {
            navController.navigate(R.id.navigation_account)
        }

        if (savedInstanceState != null && savedInstanceState?.getBoolean("history_opened") == true) {
            binding.appbar.contentmain.tabbarHistory.performClick()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.appbar.contentmain.tabbar) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navBarInsets.bottom)
            insets
        }

        if (!StorageManager.storePrivately) {
            externalStorageWithPermissionCheck()
        } else {
            phoneStateWithPermissionCheck()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.data?.scheme.equals("adonis", ignoreCase = true)) {
            LinhomeApplication.coreContext.closeAppUponCallFinish = false
            intent?.data?.let { uri ->
                pendingAdonisConfig = org.linhome.utils.AdonisProvisioner.parseUri(uri)
            }
            showPendingAutoConfigDialogIfNeeded()
        }
    }

    fun promptAdonisAutoConfig(config: org.linhome.utils.AdonisConfig) {
        pendingAdonisConfig = config
        showPendingAutoConfigDialogIfNeeded()
    }

    private fun showPendingAutoConfigDialogIfNeeded() {
        if (isFinishing || isDestroyed) return
        val config = pendingAdonisConfig ?: return
        if (currentAutoConfigDialog?.isShowing == true) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_adonis_autoconfig, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.LindoorDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                currentAutoConfigDialog = null
            }
            .create()

        currentAutoConfigDialog = dialog

        // Populate SIP details
        val sipCard = dialogView.findViewById<androidx.cardview.widget.CardView>(R.id.sip_card)
        val sipOverwriteWarning = dialogView.findViewById<android.widget.TextView>(R.id.sip_overwrite_warning)
        if (config.sip != null) {
            sipCard.visibility = View.VISIBLE
            dialogView.findViewById<android.widget.TextView>(R.id.sip_username_text).text = config.sip.username
            val domainDisplay = if (!config.sip.proxy.isNullOrBlank()) "${config.sip.domain} (via ${config.sip.proxy})" else config.sip.domain
            dialogView.findViewById<android.widget.TextView>(R.id.sip_domain_text).text = domainDisplay
            dialogView.findViewById<android.widget.TextView>(R.id.sip_transport_text).text = (config.sip.transport ?: "UDP").uppercase()
            val existingAccount = LinhomeAccount.get()
            if (existingAccount != null) {
                val existingIdentity = existingAccount.params?.identityAddress?.asStringUriOnly() ?: "configured account"
                sipOverwriteWarning.text = "⚠ Will overwrite existing SIP account ($existingIdentity)"
                sipOverwriteWarning.visibility = View.VISIBLE
            } else {
                sipOverwriteWarning.visibility = View.GONE
            }
        } else {
            sipCard.visibility = View.GONE
        }

        // Populate Remote Provisioning URL details
        val remoteUrlCard = dialogView.findViewById<androidx.cardview.widget.CardView>(R.id.remote_url_card)
        if (!config.remoteProvisioningUrl.isNullOrBlank()) {
            remoteUrlCard.visibility = View.VISIBLE
            val urlText = dialogView.findViewById<android.widget.TextView>(R.id.remote_url_text)
            val warningText = dialogView.findViewById<android.widget.TextView>(R.id.remote_url_insecure_warning)
            urlText.text = config.remoteProvisioningUrl
            val isHttps = config.remoteProvisioningUrl.startsWith("https://", ignoreCase = true)
            warningText.visibility = if (isHttps) View.GONE else View.VISIBLE
        } else {
            remoteUrlCard.visibility = View.GONE
        }

        // Populate Devices details (additive by default; overwrites require explicit approval)
        val devicesCard = dialogView.findViewById<androidx.cardview.widget.CardView>(R.id.devices_card)
        val devicesListLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.devices_list_layout)
        val devicesHeader = dialogView.findViewById<android.widget.TextView>(R.id.devices_header_title)
        val devicesAdditiveNote = dialogView.findViewById<android.widget.TextView>(R.id.devices_additive_note)
        val checkboxOverwriteDevices = dialogView.findViewById<android.widget.CheckBox>(R.id.checkbox_overwrite_devices)
        val applyButton = dialogView.findViewById<android.widget.Button>(R.id.btn_apply)

        var newDeviceCount = 0
        var existingConflictCount = 0

        if (config.devices.isNotEmpty()) {
            devicesCard.visibility = View.VISIBLE
            devicesListLayout.removeAllViews()

            for (dev in config.devices) {
                val existingMatch = org.linhome.utils.AdonisProvisioner.findMatchingExistingDevice(dev, config.sip?.domain)
                if (existingMatch != null) {
                    existingConflictCount++
                } else {
                    newDeviceCount++
                }

                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, 4, 0, 8)
                }
                val badgeText = if (existingMatch != null) " [EXISTS — OVERWRITE?]" else " [NEW]"
                val nameView = android.widget.TextView(this).apply {
                    text = "• ${dev.name}$badgeText"
                    textSize = 14f
                    setTextColor(
                        if (existingMatch != null) android.graphics.Color.parseColor("#D97706")
                        else androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.color_b)
                    )
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val addrView = android.widget.TextView(this).apply {
                    val actStr = dev.actions.joinToString(", ") { "${it.type.replace("action_type_", "").replace("action_", "").replace("_", " ")}: ${it.code}" }
                    val conflictNote = if (existingMatch != null) "\n   Matches existing '${existingMatch.name}' (${existingMatch.address})" else ""
                    text = "   ${dev.address}  |  $actStr$conflictNote"
                    textSize = 12f
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.color_g))
                }
                row.addView(nameView)
                row.addView(addrView)
                devicesListLayout.addView(row)
            }

            if (existingConflictCount > 0) {
                devicesHeader.text = "DEVICES ($newDeviceCount NEW, $existingConflictCount EXISTING)"
                devicesAdditiveNote.text = "New devices are added automatically. Existing matching devices will be kept untouched unless you approve overwriting them below."
                checkboxOverwriteDevices.visibility = View.VISIBLE
                checkboxOverwriteDevices.isChecked = false
                checkboxOverwriteDevices.text = "Approve overwriting $existingConflictCount existing device(s)"
            } else {
                devicesHeader.text = "DEVICES TO ADD ($newDeviceCount NEW)"
                devicesAdditiveNote.text = "New devices will be added to your existing list."
                checkboxOverwriteDevices.visibility = View.GONE
                checkboxOverwriteDevices.isChecked = false
            }
        } else {
            devicesCard.visibility = View.GONE
            checkboxOverwriteDevices.visibility = View.GONE
            checkboxOverwriteDevices.isChecked = false
        }

        fun updateApplyButtonState() {
            val hasAnythingToApply = config.sip != null ||
                !config.remoteProvisioningUrl.isNullOrBlank() ||
                newDeviceCount > 0 ||
                (existingConflictCount > 0 && checkboxOverwriteDevices.isChecked)
            applyButton.isEnabled = hasAnythingToApply
            applyButton.alpha = if (hasAnythingToApply) 1.0f else 0.5f
        }
        checkboxOverwriteDevices.setOnCheckedChangeListener { _, _ ->
            updateApplyButtonState()
        }
        updateApplyButtonState()

        dialogView.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            pendingAdonisConfig = null
            intent?.data = null
            if (!LinhomeAccount.configured() && navController.currentDestination?.id != R.id.navigation_assistant_root) {
                try {
                    navController.navigate(R.id.navigation_assistant_root)
                } catch (e: Exception) {
                    Log.e("[MainActivity] Navigation to assistant root failed: $e")
                }
            }
        }

        applyButton.setOnClickListener {
            val approveDeviceOverwrites = checkboxOverwriteDevices.isChecked
            dialog.dismiss()
            val configToApply = pendingAdonisConfig ?: config
            pendingAdonisConfig = null
            intent?.data = null
            org.linhome.utils.AdonisProvisioner.applyConfig(
                this,
                configToApply,
                overwriteExistingDevices = approveDeviceOverwrites
            ) { success, _ ->
                if (success) {
                    try {
                        if (navController.currentDestination?.id != R.id.navigation_devices) {
                            navController.popBackStack(R.id.navigation_devices, false)
                        }
                        tabBarLayoutClicked(binding.appbar.contentmain.tabbarDevices, binding.appbar.contentmain.tabbarHistory)
                        enterRootFragment()
                        org.linhome.store.DeviceStore.mutableDevices?.postValue(org.linhome.store.DeviceStore.devices)
                    } catch (e: Exception) {
                        Log.e("[MainActivity] Navigation error after autoconfig: $e")
                    }
                }
            }
        }

        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("ignored_no_account", navController.currentDestination?.id != R.id.navigation_account)
        outState.putBoolean("history_opened", navController.currentDestination?.id == R.id.navigation_history)
    }

    private fun enterNonRootFragment() {
        toolbarViewModel.backButtonVisible.value = true
        toolbarViewModel.burgerButtonVisible.value = false
        toolbarViewModel.leftButtonVisible.value = false
        toolbarViewModel.rightButtonVisible.value = false
        binding.appbar.contentmain.tabbar.visibility = View.GONE
        updateToolbarCallsToggle()
    }

    private fun enterRootFragment() {
        binding.appbar.contentmain.tabbar.visibility = View.VISIBLE
        toolbarViewModel.backButtonVisible.value = false
        toolbarViewModel.burgerButtonVisible.value = true
        toolbarViewModel.leftButtonVisible.value = false
        toolbarViewModel.rightButtonVisible.value = false
        updateToolbarCallsToggle()
    }

    fun updateToolbarCallsToggle() {
        val isDevicesScreen = navController.currentDestination?.id == R.id.navigation_devices && !sideMenuOpened()
        binding.appbar.toolbarCallsToggle.visibility = if (isDevicesScreen) View.VISIBLE else View.GONE
        if (!isDevicesScreen) return

        val enabled = LinhomeApplication.corePreferences.appEnabled
        val density = resources.displayMetrics.density

        // Colored pill background (Emerald green when active, Crimson red when paused)
        val pillBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 17f * density
            setColor(Theme.getColor(if (enabled) "color_k" else "color_e"))
        }
        binding.appbar.toolbarCallsToggle.background = pillBg

        // Semi-transparent white track background
        val trackBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(android.graphics.Color.parseColor("#40FFFFFF"))
        }
        binding.appbar.toolbarCallsToggleTrack.background = trackBg

        // Thumb position (END when ON, START when OFF)
        val thumbParams = binding.appbar.toolbarCallsToggleThumb.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (thumbParams != null) {
            thumbParams.gravity = (if (enabled) android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL
            binding.appbar.toolbarCallsToggleThumb.layoutParams = thumbParams
        }

        // Status text
        binding.appbar.toolbarCallsToggleLabel.text =
            Texts.get(if (enabled) "toolbar_calls_active" else "toolbar_calls_paused")
                ?: if (enabled) "Calls ON" else "Paused"
    }

    fun pauseNavigation() {
        toolbarViewModel.backButtonVisible.value = false
    }

    fun resumeNavigation() {
        toolbarViewModel.backButtonVisible.value = true
        toolbarViewModel.leftButtonVisible.value = false
    }


    fun applyTheme() { //Todo move into bindings when validated
        binding.drawerLayout.setBackgroundColor(Theme.getColor("color_f"))
        binding.drawerLayout.setStatusBarBackgroundColor(Theme.getColor("color_c"))
        binding.appbar.toolbar.setBackgroundColor(Theme.getColor("color_c"))
        binding.appbar.contentmain.tabbar.setBackgroundColor(Theme.getColor("color_c"))
        binding.appbar.contentmain.unreadCount.backgroundTintList = Theme.buildSingleColorStateList("color_a")
        binding.appbar.progress.indeterminateTintList = Theme.buildSingleColorStateList("color_a")
        binding.appbar.progress.setBackgroundColor(Theme.getColor("color_i"))
        if (binding.appbar.contentmain.tabbarHistory.isSelected) {
            styleTabSelection(binding.appbar.contentmain.tabbarHistory, binding.appbar.contentmain.tabbarDevices)
        } else {
            styleTabSelection(binding.appbar.contentmain.tabbarDevices, binding.appbar.contentmain.tabbarHistory)
        }
        updateToolbarCallsToggle()
        applyCommonTheme()
    }

    private fun styleTabSelection(active: ViewGroup, inactive: ViewGroup) {
        active.background = Theme.roundRectInputBackgroundWithColorKeyAndRadius(
            "color_i",
            "round_rect_button_corner_radius"
        )
        inactive.background = null
        val isDevicesActive = active.id == R.id.tabbar_devices
        binding.appbar.contentmain.iconDevices.imageTintList =
            Theme.buildSingleColorStateList(if (isDevicesActive) "color_a" else "color_g")
        binding.appbar.contentmain.textDevices.setTextColor(
            Theme.getColor(if (isDevicesActive) "color_a" else "color_g")
        )
        binding.appbar.contentmain.iconHistory.imageTintList =
            Theme.buildSingleColorStateList(if (!isDevicesActive) "color_a" else "color_g")
        binding.appbar.contentmain.textHistory.setTextColor(
            Theme.getColor(if (!isDevicesActive) "color_a" else "color_g")
        )
        Theme.alphAnimate(active, 1f)
        Theme.alphAnimate(inactive, 0.85f)
    }

    fun tabBarLayoutClicked(clicked: ViewGroup, unclicked: ViewGroup): Boolean {
        if (clicked.isSelected)
            return false
        clicked.isSelected = true
        unclicked.isSelected = false
        styleTabSelection(clicked, unclicked)
        return true
    }


    override fun onBackPressed() {
        if (navControllerSideMenu.currentDestination?.id == R.id.fragment_sidemenu) {
            navControllerSideMenu.navigateUp()
        } else {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.navigation_host_fragment)
            val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
            if (currentFragment is org.linhome.ui.history.HistoryFragment && currentFragment.isEditing) {
                currentFragment.exitEdition()
                return
            }
            super.onBackPressed()
        }
    }

    private fun onToolbarBurgerButtonClicked() {
        navControllerSideMenu.navigate(R.id.open_side_menu, null)

    }

    private fun onToolbarBackButtonClicked() {
        if (sideMenuOpened()) {
            navControllerSideMenu.navigateUp()
        } else
            navController.navigateUp()
    }


    fun sideMenuOpened(): Boolean {
        return navControllerSideMenu.currentDestination?.id != R.id.fragment_empty
    }

    private fun titleForNavigationFragment(fragmentId: Int?): String? {
        return when (fragmentId) {
            R.id.navigation_assistant_root,
            R.id.navigation_assistant_create_linhome,
            R.id.navigation_assistant_login_linhome,
            R.id.navigation_assistant_login_sip,
            R.id.navigation_assistant_remote_root,
            R.id.navigation_assistant_remote_qr,
            R.id.navigation_assistant_remote_url -> Texts.get("assistant")
            R.id.navigation_devices, R.id.navigation_device_edit, R.id.navigation_device_info -> Texts.get(
                "devices"
            )
            R.id.navigation_history -> Texts.get("history")
            R.id.navigation_about -> Texts.get("about")
            R.id.navigation_settings -> Texts.get("settings")
            R.id.navigation_account -> Texts.get("menu_account")
            else -> null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.size > 0)
            onRequestPermissionsResult(requestCode, grantResults)
        showPendingAutoConfigDialogIfNeeded()
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun externalStorage() {
        phoneStateWithPermissionCheck()
    }

    @NeedsPermission(Manifest.permission.READ_PHONE_STATE)
    fun phoneState() {
        LinhomeApplication.coreContext.initPhoneStateListener()
        notificationsWithPermissionCheck()
    }

    @NeedsPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun notifications() {
        contactsWithPermissionCheck()
    }

    @NeedsPermission(Manifest.permission.READ_CONTACTS)
    fun contacts() {
        ContactsHelper.clearCache()
        Compatibility.checkAndRequestFullScreenIntentPermission(this)
        showPendingAutoConfigDialogIfNeeded()
    }

    @OnPermissionDenied(Manifest.permission.READ_CONTACTS)
    fun onContactsDenied() {
        Compatibility.checkAndRequestFullScreenIntentPermission(this)
        showPendingAutoConfigDialogIfNeeded()
    }

    @OnNeverAskAgain(Manifest.permission.READ_CONTACTS)
    fun onContactsDeniedNeverAsk() {
        Compatibility.checkAndRequestFullScreenIntentPermission(this)
        showPendingAutoConfigDialogIfNeeded()
    }

    // Permissions denial

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onStorageDenied() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("write_external_storage_permission_denied",{ _: DialogInterface, _: Int ->
                phoneStateWithPermissionCheck()
            })
        }
    }
    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onStorageDeniedNeverAsk() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("write_external_storage_permission_denied_dont_ask_again",{ _: DialogInterface, _: Int ->
                phoneStateWithPermissionCheck()
            })
        }
    }

    @OnPermissionDenied(Manifest.permission.READ_PHONE_STATE)
    fun onPhoneDenied() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("phone_state_permission_denied",{ _: DialogInterface, _: Int ->
                notificationsWithPermissionCheck()
            })
        }
    }
    @OnNeverAskAgain(Manifest.permission.READ_PHONE_STATE)
    fun onPhoneDeniedNeverAsk() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("phone_state_permission_denied_dont_ask_again",{ _: DialogInterface, _: Int ->
                notificationsWithPermissionCheck()
            })
        }
    }

    @OnPermissionDenied(Manifest.permission.POST_NOTIFICATIONS)
    fun onNotificationDenied() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("notifications_permission_denied", { _: DialogInterface, _: Int ->
                contactsWithPermissionCheck()
            })
        }
    }

    @OnNeverAskAgain(Manifest.permission.POST_NOTIFICATIONS)
    fun onNotificationsDeniedNeverAsk() {
        GlobalScope.launch(context = Dispatchers.Main) {
            DialogUtil.error("notifications_permission_denied_dont_ask_again", { _: DialogInterface, _: Int ->
                contactsWithPermissionCheck()
            })
        }
    }

    fun applyCommonTheme() {
        window.also { window ->
            window.statusBarColor = Theme.getColor("color_c")
            window.navigationBarColor = Theme.getColor("color_c")
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }
        }
    }


    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (sideMenuOpened()) {
            if (event != null) {
                val overFlowPct =
                    if (LinhomeApplication.instance.smartPhone()) 0.75
                    else if (LinhomeApplication.instance.landcape()) 0.3
                    else 0.5

                if (event.x.toInt() > overFlowPct * window.decorView.width) {
                    navControllerSideMenu.navigateUp()
                    return false
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (pendingAdonisConfig != null) {
            LinhomeApplication.coreContext.closeAppUponCallFinish = false
        }
        if (LinhomeApplication.coreContext.closeAppUponCallFinish) {
            LinhomeApplication.coreContext.closeAppUponCallFinish = false
            finish()
            return
        }
        updateToolbarCallsToggle()
        showPendingAutoConfigDialogIfNeeded()
    }

}
