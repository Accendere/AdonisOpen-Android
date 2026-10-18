/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linhome-android
 * (see https://www.linhome.org).
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

package org.linhome.ui.call

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.databinding.ActivityCallIncomingBinding
import org.linhome.linphonecore.CoreContext
import org.linhome.utils.DialogUtil
import org.linphone.core.Call


class CallIncomingActivity : CallGenericActivity() {

    lateinit var binding: ActivityCallIncomingBinding
    private lateinit var callViewModel: CallViewModel
    private var showingAnsweredElsewhere = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Compatibility.setShowWhenLocked(this, true)
        Compatibility.setTurnScreenOn(this, true)
        Compatibility.requestDismissKeyguard(this)

        binding = DataBindingUtil.setContentView(
            this,
            org.linhome.R.layout.activity_call_incoming
        ) as ActivityCallIncomingBinding
        binding.lifecycleOwner = this

        binding.silentTimerProgress.progressTintList = Theme.buildSingleColorStateList("color_a")
        binding.silentTimerProgress.progressBackgroundTintList = Theme.buildSingleColorStateList("color_u")

        binding.answeredElsewhereCard.setOnClickListener {
            finish()
        }

        call?.also { activeCall ->
            callViewModel =
                ViewModelProvider(this, CallViewModelFactory(activeCall))[CallViewModel::class.java]
            binding.callmodel = callViewModel

            callViewModel.actionFeedbackState.observe(this, Observer { state ->
                when (state) {
                    ActionFeedbackState.IDLE -> {
                        binding.doorActionBanner.visibility = View.GONE
                    }
                    ActionFeedbackState.IN_PROGRESS -> {
                        binding.doorActionBanner.visibility = View.VISIBLE
                        binding.doorActionSpinner.visibility = View.VISIBLE
                        binding.doorActionIcon.visibility = View.GONE
                        binding.doorActionText.text = callViewModel.actionFeedbackText.value ?: Texts.get("door_opening_in_progress")
                    }
                    ActionFeedbackState.COMPLETED -> {
                        binding.doorActionBanner.visibility = View.VISIBLE
                        binding.doorActionSpinner.visibility = View.GONE
                        binding.doorActionIcon.visibility = View.VISIBLE
                        binding.doorActionText.text = callViewModel.actionFeedbackText.value ?: Texts.get("door_opened_confirmed")
                    }
                    null -> {
                        binding.doorActionBanner.visibility = View.GONE
                    }
                }
            })

            coreContext.silentAcceptRemainingMs.observe(this, Observer { remainingMs ->
                if (remainingMs != null && remainingMs > 0L &&
                    coreContext.isCallSilentlyAccepted(activeCall) &&
                    LinhomeApplication.corePreferences.silentAcceptTimerEnabled
                ) {
                    binding.silentTimerContainer.visibility = View.VISIBLE
                    val totalDurationMs = (LinhomeApplication.corePreferences.silentAcceptTimerSeconds * 1000L).coerceAtLeast(1000L)
                    val progressPermille = ((remainingMs * 1000L) / totalDurationMs).toInt().coerceIn(0, 1000)
                    binding.silentTimerProgress.progress = progressPermille
                    val secondsLeft = ((remainingMs + 999L) / 1000L).toInt()
                    binding.silentTimerLabel.text = Texts.get("silent_accept_auto_close_timer", secondsLeft.toString())
                } else {
                    binding.silentTimerContainer.visibility = View.GONE
                }
            })

            binding.silentTimerContainer.setOnClickListener {
                coreContext.cancelSilentAcceptTimer(activeCall)
                binding.silentTimerContainer.visibility = View.GONE
                DialogUtil.toast("timer_cancelled_preview_active")
            }

            callViewModel.callState.observe(this, Observer { callState ->
                when (callState) {
                    Call.State.IncomingEarlyMedia,
                    Call.State.IncomingReceived,
                    Call.State.EarlyUpdating,
                    Call.State.EarlyUpdatedByRemote -> return@Observer
                    Call.State.Connected,
                    Call.State.StreamsRunning,
                    Call.State.Updating,
                    Call.State.UpdatedByRemote -> {
                        if (coreContext.isCallSilentlyAccepted(activeCall)) return@Observer
                        finish()
                    }
                    else -> {
                        if (showingAnsweredElsewhere) return@Observer
                        val immediateBanner = coreContext.extractAnsweredElsewhereBannerText(activeCall)
                        if (immediateBanner != null) {
                            showAnsweredElsewhereAndFinish(immediateBanner)
                        } else {
                            GlobalScope.launch(context = Dispatchers.Main) {
                                delay(120)
                                if (showingAnsweredElsewhere) return@launch
                                val delayedBanner = coreContext.extractAnsweredElsewhereBannerText(activeCall)
                                if (delayedBanner != null) {
                                    showAnsweredElsewhereAndFinish(delayedBanner)
                                } else {
                                    finish()
                                }
                            }
                        }
                    }
                }
            })
            binding.chunkCallDeviceIconOrVideo.videotogglecollapsed.setOnClickListener {
                coreContext.core.nativeVideoWindowId = binding.videofullscreen
                callViewModel.toggleVideoFullScreen()
            }
            binding.videotogglefullscreen.setOnClickListener {
                coreContext.core.nativeVideoWindowId = binding.chunkCallDeviceIconOrVideo.videocollapsed
                callViewModel.toggleVideoFullScreen()
            }
            callViewModel.videoSize.observe(this, Observer { size ->
                binding.chunkCallVideoOrIcon.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    dimensionRatio = "H,${size.width}:${size.height}"
                    val deviceHasButtons = callViewModel.actions.isNotEmpty()
                    matchConstraintPercentWidth = computePercentageWidth(size, if (deviceHasButtons) 300 else 250) // Space for buttons and header
                }
            })
        }


    }

    private fun showAnsweredElsewhereAndFinish(bannerText: String) {
        showingAnsweredElsewhere = true
        binding.silentTimerContainer.visibility = View.GONE
        binding.actions.visibility = View.INVISIBLE
        binding.buttons.visibility = View.INVISIBLE
        binding.answeredElsewhereText.text = bannerText
        binding.answeredElsewhereCard.visibility = View.VISIBLE
        GlobalScope.launch(context = Dispatchers.Main) {
            delay(3500)
            if (!isFinishing) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::callViewModel.isInitialized) {
            coreContext.core.nativeVideoWindowId =
                if (callViewModel.videoFullScreen.value!!) binding.videofullscreen else binding.chunkCallDeviceIconOrVideo.videocollapsed
        }
    }

    override fun onPause() {
        coreContext.core.nativeVideoWindowId = null
        super.onPause()
    }

    override fun onBackPressed() {

    }

}
