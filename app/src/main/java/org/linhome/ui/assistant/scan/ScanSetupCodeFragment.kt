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

package org.linhome.ui.assistant.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.linhome.GenericFragment
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.claim.Claims
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.Texts
import org.linhome.databinding.FragmentScanSetupCodeBinding
import org.linhome.utils.AdonisProvisioner
import org.linhome.utils.DialogUtil
import org.linhome.utils.ImportSource
import org.linhome.utils.SetupCode
import org.linhome.utils.extensions.invisible
import org.linhome.utils.extensions.toogleVisible
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log

/**
 * "Scan a setup code": reads the QR code of an Adonis Open setup (a setup link, a claim link, or the site's
 * install page) and opens the import screen. Anything else gets "This isn't an Adonis Open setup code" and the
 * scanner keeps going: nothing is changed, the account stays as it is, and the scanned text is never logged.
 */
class ScanSetupCodeFragment : GenericFragment() {

    private lateinit var binding: FragmentScanSetupCodeBinding
    private val handler = Handler(Looper.getMainLooper())

    /** True between a code being read and the scanner running again (Linphone reports the same code repeatedly). */
    private var handling = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startScanner()
        } else {
            DialogUtil.error("camera_permission_denied")
            mainactivity.navController.navigateUp()
        }
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onQrcodeFound(core: Core, qr: String?) {
            handler.post { onScanned(qr) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentScanSetupCodeBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.infobutton.setOnClickListener {
            binding.infotext.toogleVisible()
        }
        binding.root.setOnClickListener {
            binding.infotext.invisible()
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        coreContext.core.addListener(coreListener)
        handling = false
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        stopScanner()
        coreContext.core.removeListener(coreListener)
        super.onPause()
    }

    private fun onScanned(qr: String?) {
        if (handling || !isResumed) return
        handling = true
        stopScanner()
        when (val code = SetupCode.classify(qr)) {
            is SetupCode.Configure -> {
                val config = AdonisProvisioner.parse(code.raw)
                if (config == null) {
                    notASetupCode()
                    return
                }
                Log.i("[Scan] Read a setup code")
                mainactivity.navController.navigateUp()
                mainactivity.promptAdonisAutoConfig(config.copy(source = ImportSource.SCANNED))
            }
            is SetupCode.Claim -> {
                Log.i("[Scan] Read a claim link for ${org.linhome.claim.ClaimContract.hostOf(code.claimUrl)}")
                mainactivity.navController.navigateUp()
                Claims.start(mainactivity, code.claimUrl, ImportSource.SCANNED)
            }
            SetupCode.NotSetupCode -> notASetupCode()
        }
    }

    /** Says so on the scanner, then scans again. */
    private fun notASetupCode() {
        Log.i("[Scan] Read a code that isn't an Adonis Open setup code")
        Compatibility.vibrateActionFeedback()
        binding.scanMessage.text = Texts.get("scan_not_setup_code")
        binding.scanMessage.setTextColor(org.linhome.customisation.Theme.getColor("color_e"))
        binding.scanMessage.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isResumed) {
                handling = false
                startScanner()
            }
        }, RESCAN_DELAY_MS)
        handler.postDelayed({ binding.scanMessage.visibility = View.INVISIBLE }, MESSAGE_MS)
    }

    private fun startScanner() {
        val core = coreContext.core
        core.reloadVideoDevices()
        setBackCamera()
        core.nativePreviewWindowId = binding.qrcode
        core.isQrcodeVideoPreviewEnabled = true
        core.isVideoPreviewEnabled = true
    }

    private fun stopScanner() {
        val core = coreContext.core
        core.isQrcodeVideoPreviewEnabled = false
        core.isVideoPreviewEnabled = false
        core.nativePreviewWindowId = null
    }

    private fun setBackCamera() {
        val cameras = coreContext.core.videoDevicesList
        val back = cameras.firstOrNull { it.contains("Back") }
        val chosen = back ?: cameras.firstOrNull() ?: return
        Log.i("[Scan] Using camera: $chosen")
        coreContext.core.videoDevice = chosen
    }

    companion object {
        private const val RESCAN_DELAY_MS = 2_500L
        private const val MESSAGE_MS = 5_000L
    }
}
