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
import android.view.View
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
import org.linhome.webhooks.WebhookStatus
import org.linhome.webhooks.Webhooks
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
    lateinit var toolbarViewModel: ToolbarViewModel
    lateinit var tabbarViewModel: TabbarViewModel

    var toobarButtonClickedListener: ToobarButtonClickedListener? = null
    lateinit var binding: ActivityMainBinding
    private var pendingAdonisConfig: org.linhome.utils.AdonisConfig? = null
    private var currentAutoConfigDialog: android.app.Dialog? = null


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

        applyTheme()

        binding.appbar.contentmain.tabbarDevices.setOnClickListener { selectTab(R.id.navigation_devices) }
        binding.appbar.contentmain.tabbarHistory.setOnClickListener { selectTab(R.id.navigation_history) }
        binding.appbar.contentmain.tabbarSettings.setOnClickListener { selectTab(R.id.navigation_settings) }
        styleTabs(R.id.navigation_devices)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id in ROOT_DESTINATIONS) {
                enterRootFragment()
                styleTabs(destination.id)
            } else {
                enterNonRootFragment()
            }
            binding.appbar.toolbarTitle.text = titleForNavigationFragment(destination.id)
        }

        binding.appbar.toolbarRightButton.setOnClickListener {
            toobarButtonClickedListener?.onToolbarRightButtonClicked()
        }
        binding.appbar.toolbarLeftButton.setOnClickListener {
            toobarButtonClickedListener?.onToolbarLeftButtonClicked()
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

        binding.appbar.webhookRelinkBanner.setOnClickListener { openScanner() }
        Webhooks.status.observe(this) { updateWebhookRelinkBanner(it) }

        LinhomeApplication.coreContext.closeAppUponCallFinish = false
        val isAdonisIntent = intent?.data?.scheme.equals("adonis", ignoreCase = true)
        var claimStarted = false
        if (isAdonisIntent) {
            LinhomeApplication.coreContext.closeAppUponCallFinish = false
            intent?.data?.let { uri ->
                claimStarted = handleAdonisUri(uri)
            }
        }

        if (!LinhomeAccount.configured() && pendingAdonisConfig == null && !claimStarted && savedInstanceState == null) {
            // First run (or no account): the welcome screen with the setup choices.
            navController.navigate(R.id.navigation_assistant_root)
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
                handleAdonisUri(uri)
            }
            showPendingAutoConfigDialogIfNeeded()
        }
    }

    /**
     * `adonis://claim?url=<https claim URL>` starts a claim link (returns true);
     * anything else is a setup link parsed as before.
     */
    private fun handleAdonisUri(uri: android.net.Uri): Boolean {
        // Who opened the link, for the import screen's header (Home Assistant, another app; null: ourselves).
        val source = org.linhome.utils.ImportSource.fromReferrer(referrer?.toString(), packageName)
        if (uri.host.equals("claim", ignoreCase = true)) {
            val claimUrl = org.linhome.claim.ClaimContract.validate(uri.getQueryParameter("url"))
            if (claimUrl == null) {
                Log.w("[MainActivity] Ignoring a claim link without a valid https URL")
                return false
            }
            org.linhome.claim.Claims.start(this, claimUrl, source ?: org.linhome.utils.ImportSource.CLAIM_LINK)
            return true
        }
        pendingAdonisConfig = org.linhome.utils.AdonisProvisioner.parseUri(uri)?.copy(source = source)
        if (pendingAdonisConfig == null) Log.w("[MainActivity] Ignoring an adonis:// link without setup data")
        return false
    }

    /** "Scan a setup code" (the scanner asks for the camera itself). */
    fun openScanner() {
        if (navController.currentDestination?.id != R.id.navigation_scan_setup_code) {
            navController.navigate(R.id.navigation_scan_setup_code)
        }
    }

    /** Goes back to the Doors tab, dropping whatever is above it. */
    fun showDoors() {
        try {
            if (!navController.popBackStack(R.id.navigation_devices, false)) {
                navController.navigate(R.id.navigation_devices)
            }
        } catch (e: Exception) {
            Log.e("[MainActivity] Navigation to the doors failed: $e")
        }
    }

    private fun selectTab(destination: Int) {
        if (navController.currentDestination?.id == destination) return
        val historyFragment = supportFragmentManager.findFragmentById(R.id.navigation_host_fragment)
            ?.childFragmentManager?.fragments?.firstOrNull() as? org.linhome.ui.history.HistoryFragment
        if (historyFragment?.isEditing == true) historyFragment.exitEdition()
        navController.popBackStack(R.id.navigation_devices, false)
        if (destination != R.id.navigation_devices) navController.navigate(destination)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // First launch with no account: pick up a claim link the site's page put on the clipboard.
        if (hasFocus && !LinhomeAccount.configured() && pendingAdonisConfig == null && currentAutoConfigDialog == null) {
            org.linhome.claim.Claims.checkClipboard(this)
            // No link on the clipboard: look for the site on the Wi-Fi (LAN pairing).
            org.linhome.pairing.Pairing.searchAutomatically(this)
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

        val plan = org.linhome.utils.AdonisProvisioner.buildImportPlan(config)
        val pushed = org.linhome.webhooks.PollMessages.wasPushed(this, config.claimUrl)
        val shown = if (pushed) config.copy(source = org.linhome.utils.ImportSource.SITE_UPDATE) else config
        currentAutoConfigDialog = org.linhome.ui.importsettings.ImportSettingsScreen(
            this, shown, plan,
            onApply = { selection ->
                currentAutoConfigDialog = null
                val configToApply = pendingAdonisConfig ?: config
                pendingAdonisConfig = null
                intent?.data = null
                org.linhome.utils.AdonisProvisioner.applySelection(this, configToApply, selection) { success, _ ->
                    if (success) {
                        configToApply.claimUrl?.let {
                            org.linhome.claim.Claims.confirm(this, it)
                            org.linhome.webhooks.PollMessages.settled(this, it)
                        }
                        showDoors()
                        org.linhome.store.DeviceStore.mutableDevices?.postValue(org.linhome.store.DeviceStore.devices)
                    }
                }
            },
            onCancel = {
                currentAutoConfigDialog = null
                pendingAdonisConfig = null
                intent?.data = null
                // A pushed update the person dismissed: tell the site, so it stops offering it.
                if (pushed) {
                    config.claimUrl?.let {
                        org.linhome.claim.Claims.decline(this, it)
                        org.linhome.webhooks.PollMessages.settled(this, it)
                    }
                }
                if (!LinhomeAccount.configured() && navController.currentDestination?.id != R.id.navigation_assistant_root) {
                    try {
                        navController.navigate(R.id.navigation_assistant_root)
                    } catch (e: Exception) {
                        Log.e("[MainActivity] Navigation to assistant root failed: $e")
                    }
                }
            }
        ).show()
    }


    private fun enterNonRootFragment() {
        toolbarViewModel.backButtonVisible.value = true
        toolbarViewModel.leftButtonVisible.value = false
        toolbarViewModel.rightButtonVisible.value = false
        binding.appbar.contentmain.tabbar.visibility = View.GONE
        updateToolbarCallsToggle()
    }

    private fun enterRootFragment() {
        binding.appbar.contentmain.tabbar.visibility = View.VISIBLE
        toolbarViewModel.backButtonVisible.value = false
        toolbarViewModel.leftButtonVisible.value = false
        toolbarViewModel.rightButtonVisible.value = false
        updateToolbarCallsToggle()
    }

    fun updateToolbarCallsToggle() {
        val isDevicesScreen = navController.currentDestination?.id == R.id.navigation_devices
        binding.appbar.toolbarCallsToggle.visibility = if (isDevicesScreen) View.VISIBLE else View.GONE
        if (!isDevicesScreen) return

        val enabled = LinhomeApplication.corePreferences.appEnabled
        val density = resources.displayMetrics.density
        val accent = Theme.getColor("color_a")
        // White on the accent, unless the accent is too light for it (e.g. the amber theme): then the dark text color.
        val onAccent = Theme.getColor("color_c").let { light ->
            if (androidx.core.graphics.ColorUtils.calculateContrast(light, accent or 0xFF000000.toInt()) >= 3.0) light
            else Theme.getColor("color_b")
        }

        // A 36dp pill inside the 48dp tap target, in the theme's accent color:
        // filled while taking calls, outlined (accent border and text) while paused.
        val pill = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            if (enabled) {
                setColor(accent)
            } else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((2f * density).toInt(), accent)
            }
        }
        val inset = (6f * density).toInt()
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(if (enabled) onAccent else accent, 0x40)),
            android.graphics.drawable.InsetDrawable(pill, 0, inset, 0, inset),
            null
        )
        binding.appbar.toolbarCallsToggle.background = ripple

        val foreground = if (enabled) onAccent else accent
        binding.appbar.toolbarCallsToggleIcon.setImageResource(
            if (enabled) R.drawable.ic_qs_receive_calls else R.drawable.ic_qs_receive_calls_off
        )
        binding.appbar.toolbarCallsToggleIcon.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        binding.appbar.toolbarCallsToggleLabel.setTextColor(foreground)
        binding.appbar.toolbarCallsToggleLabel.text =
            Texts.get(if (enabled) "toolbar_calls_active" else "toolbar_calls_paused")
        binding.appbar.toolbarCallsToggle.contentDescription =
            Texts.get(if (enabled) "toolbar_calls_toggle_desc_active" else "toolbar_calls_toggle_desc_paused")
    }

    private fun updateWebhookRelinkBanner(status: WebhookStatus?) {
        val banner = binding.appbar.webhookRelinkBanner
        if (status != WebhookStatus.UNAUTHORIZED) {
            banner.visibility = View.GONE
            return
        }
        banner.text = Texts.get("webhook_relink_banner")
        banner.setTextColor(Theme.getColor("color_b"))
        banner.setBackgroundColor(Theme.getColor("color_i"))
        banner.visibility = View.VISIBLE
    }

    fun pauseNavigation() {
        toolbarViewModel.backButtonVisible.value = false
    }

    fun resumeNavigation() {
        toolbarViewModel.backButtonVisible.value = true
        toolbarViewModel.leftButtonVisible.value = false
    }


    fun applyTheme() { //Todo move into bindings when validated
        binding.root.setBackgroundColor(Theme.getColor("color_f"))
        binding.appbar.toolbar.setBackgroundColor(Theme.getColor("color_d"))
        binding.appbar.contentmain.tabbar.setBackgroundColor(Theme.getColor("color_d"))
        binding.appbar.contentmain.unreadCount.backgroundTintList = Theme.buildSingleColorStateList("color_a")
        binding.appbar.progress.indeterminateTintList = Theme.buildSingleColorStateList("color_a")
        binding.appbar.progress.setBackgroundColor(Theme.getColor("color_i"))
        styleTabs(navController.currentDestination?.id?.takeIf { it in ROOT_DESTINATIONS } ?: R.id.navigation_devices)
        updateToolbarCallsToggle()
        applyCommonTheme()
    }

    /** Highlights the tab of [destination] (Doors, History or Settings). */
    private fun styleTabs(destination: Int) {
        val c = binding.appbar.contentmain
        val tabs = listOf(
            Triple(c.tabbarDevices, c.iconDevices, c.textDevices) to R.id.navigation_devices,
            Triple(c.tabbarHistory, c.iconHistory, c.textHistory) to R.id.navigation_history,
            Triple(c.tabbarSettings, c.iconSettings, c.textSettings) to R.id.navigation_settings
        )
        for ((views, id) in tabs) {
            val (tab, icon, label) = views
            val active = id == destination
            tab.isSelected = active
            tab.background = if (active) Theme.roundRectInputBackgroundWithColorKeyAndRadius(
                "color_i",
                "round_rect_button_corner_radius"
            ) else null
            icon.imageTintList = Theme.buildSingleColorStateList(if (active) "color_a" else "color_g")
            label.setTextColor(Theme.getColor(if (active) "color_a" else "color_g"))
            Theme.alphAnimate(tab, if (active) 1f else 0.85f)
        }
    }

    override fun onBackPressed() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.navigation_host_fragment)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
        if (currentFragment is org.linhome.ui.history.HistoryFragment && currentFragment.isEditing) {
            currentFragment.exitEdition()
            return
        }
        super.onBackPressed()
    }

    private fun onToolbarBackButtonClicked() {
        navController.navigateUp()
    }

    private fun titleForNavigationFragment(fragmentId: Int?): String? {
        return when (fragmentId) {
            R.id.navigation_assistant_root,
            R.id.navigation_assistant_login_sip -> Texts.get("assistant")
            R.id.navigation_scan_setup_code -> Texts.get("scan_setup_code")
            R.id.navigation_account_edit -> Texts.get("account_edit")
            R.id.navigation_devices, R.id.navigation_device_edit, R.id.navigation_device_info -> Texts.get("tab_doors")
            R.id.navigation_history -> Texts.get("tab_history")
            R.id.navigation_settings -> Texts.get("tab_settings")
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
            val bars = Theme.getColor("color_d")
            window.statusBarColor = bars
            window.navigationBarColor = bars
            // Dark icons on a light bar, light icons on a dark one (the dark theme).
            val lightBars = androidx.core.graphics.ColorUtils.calculateLuminance(bars) > 0.5
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.isAppearanceLightStatusBars = lightBars
                controller.isAppearanceLightNavigationBars = lightBars
            }
        }
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
        org.linhome.pairing.Pairing.attach(this)
        org.linhome.pairing.Pairing.resumePendingClaim(this)
    }

    override fun onDestroy() {
        org.linhome.pairing.Pairing.detach(this)
        super.onDestroy()
    }

    /** True while the "Import Configuration" dialog is up or waiting to be shown. */
    val autoConfigPending: Boolean
        get() = pendingAdonisConfig != null || currentAutoConfigDialog?.isShowing == true

    companion object {
        /** The bottom bar's tabs: Doors, History, Settings. */
        val ROOT_DESTINATIONS = setOf(R.id.navigation_devices, R.id.navigation_history, R.id.navigation_settings)
    }

}
