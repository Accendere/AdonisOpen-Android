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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.images.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.DeviceTypes
import org.linhome.customisation.Texts
import org.linhome.entities.Action
import org.linhome.entities.Device
import org.linhome.entities.HistoryEvent
import org.linhome.linphonecore.extensions.*
import org.linhome.store.DeviceStore
import org.linhome.utils.ContactsHelper
import org.linhome.utils.DialogUtil
import org.linhome.utils.extensions.existsAndIsNotEmpty
import org.linhome.utils.getImageDimension
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Reason
import org.linphone.core.tools.Log


enum class ActionFeedbackState {
    IDLE,
    IN_PROGRESS,
    COMPLETED
}

class CallViewModelFactory(private val call: Call) :
    ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CallViewModel(call) as T
    }
}

class CallViewModel(val call: Call) : ViewModel() {
    val actionFeedbackState = MutableLiveData<ActionFeedbackState>(ActionFeedbackState.IDLE)
    val actionFeedbackText = MutableLiveData<String>("")
    val device: MutableLiveData<Device?> =
        MutableLiveData(DeviceStore.findDeviceByAddress(call.remoteAddress))
    val defaultDeviceType: MutableLiveData<String?> = MutableLiveData(DeviceTypes.defaultType)
    val actions: ArrayList<Action> = device.value?.let {
        it.actions
    } ?: defaultActionsIfAny()

    val callState: MutableLiveData<Call.State> = MutableLiveData(call.state)
    val videoContent: MutableLiveData<Boolean> = MutableLiveData()
    val videoFullScreen: MutableLiveData<Boolean> = MutableLiveData(false)

    val speakerDisabled: MutableLiveData<Boolean> =
        MutableLiveData(coreContext.core.outputAudioDevice?.type != AudioDevice.Type.Speaker)
    val microphoneMuted: MutableLiveData<Boolean> = MutableLiveData(!coreContext.core.isMicEnabled)

    val videoSize = MutableLiveData<Size>()

    val isVoiceCall: MutableLiveData<Boolean> = MutableLiveData(false)
    val callerName: MutableLiveData<String> = MutableLiveData("")
    val callerAddress: MutableLiveData<String> = MutableLiveData("")
    val callDisplayDeviceType: MutableLiveData<String> = MutableLiveData(DeviceTypes.defaultType)
    val callSubtitleTextKey: MutableLiveData<String> = MutableLiveData("incoming_someone_at_your_door")

    private var historyEvent: HistoryEvent
    private var pendingActionAfterSilentAccept: Action? = null

    private var callListener = object : CallListenerStub() {
        override fun onStateChanged(call: Call, cstate: Call.State?, message: String) {
            cstate?.also { state ->
                if (call != null) {
                    historyEvent = call.callLog.historyEvent()
                }
                updateVoiceCallAndCallerInfo()
                fireActionsOnCallStateChanged(state)
                attemptSetDeviceThumbnail(state)
                call?.remoteParams?.isVideoEnabled?.also {
                    if (!videoContent.value!!)
                        call.requestNotifyNextVideoFrameDecoded()
                }
                callState.value = state
            }

        }

        override fun onNextVideoFrameDecoded(call: Call) {
            call.params.recordFile?.let {
                coreContext.core.config.setString("recording_formats",
                    it,call.currentParams.usedVideoPayloadType?.mimeType)
            }
            if (!videoContent.value!!) {
                videoContent.value = true
                updateVoiceCallAndCallerInfo()
            }
            call?.callLog?.historyEvent()?.also { event ->
                if (!event.hasVideo) {
                    event.hasVideo = true
                    event.persist()
                }
                if (!event.mediaThumbnail.existsAndIsNotEmpty()) {
                    call.takeVideoSnapshot(event.mediaThumbnail.absolutePath)
                    GlobalScope.launch(context = Dispatchers.Main) {
                        delay(500) // Snapshot availability takes a little time.
                        event.mediaThumbnail.absolutePath.getImageDimension()?.also {
                            videoSize.value = it
                            coreContext.core.config.setString("detected_video_dimensions",call.remoteAddress.asStringUriOnly(),"${it.width},${it.height}")
                        }
                    }
                }
            }
        }
    }

    init {
        historyEvent = call.callLog.historyEvent()
        videoContent.value = historyEvent.hasVideo
        updateVoiceCallAndCallerInfo()
        call.addListener(callListener)
        fireActionsOnCallStateChanged(call.state)
        if (!videoContent.value!!) {
            call?.remoteParams?.isVideoEnabled?.also {
                if (it)
                    call.requestNotifyNextVideoFrameDecoded()
            }
        }
        if (LinhomeApplication.instance.tablet()) {
            coreContext.core.forceSpeakerAudioRoute()
        } else if (isVoiceCall.value == true) {
            speakerDisabled.value = true
        }
        coreContext.core.config.getString("detected_video_dimensions",call.remoteAddress.asStringUriOnly(),null)?.split(",")?.also {
            videoSize.value = Size(it.first().toInt(),it.last().toInt())
        }
    }

    fun updateVoiceCallAndCallerInfo() {
        val d = device.value
        val isAudioDevice = d?.type?.let { !DeviceTypes.supportsVideo(it) } ?: false
        val hasVideoOffer = (call.remoteParams?.isVideoEnabled == true) ||
                (call.currentParams?.isVideoEnabled == true) ||
                (call.params?.isVideoEnabled == true) ||
                (videoContent.value == true)
        val voice = isAudioDevice || (d == null && !hasVideoOffer)
        isVoiceCall.value = voice

        // Display device type for icon
        val displayType = when {
            d?.type != null -> d.type
            voice -> "phone_call"
            else -> defaultDeviceType.value ?: "device_audio_intercom"
        }
        callDisplayDeviceType.value = displayType

        // Subtitle text key under icon
        callSubtitleTextKey.value = if (voice) "incoming_voice_call" else "incoming_someone_at_your_door"

        // Caller name and clean number
        val cleanNum = call.remoteAddress.getCleanNumber()
        val displayName = call.remoteAddress.displayName?.trim()

        if (d != null) {
            callerName.value = d.name
            callerAddress.value = if (!d.address.isNullOrBlank()) d.address else cleanNum
        } else {
            val contactName = ContactsHelper.findContactNameByNumber(cleanNum)
            if (!contactName.isNullOrEmpty()) {
                callerName.value = contactName
                callerAddress.value = if (cleanNum.isNotEmpty()) cleanNum else (if (voice) (Texts.get("call_type_voice_call") ?: "Voice Call") else call.remoteAddress.asStringUriOnly())
            } else if (!displayName.isNullOrEmpty() && !displayName.equals(cleanNum, ignoreCase = true)) {
                callerName.value = displayName
                callerAddress.value = if (cleanNum.isNotEmpty()) cleanNum else (if (voice) (Texts.get("call_type_voice_call") ?: "Voice Call") else call.remoteAddress.asStringUriOnly())
            } else {
                callerName.value = if (cleanNum.isNotEmpty()) cleanNum else (call.remoteAddress.username ?: "")
                callerAddress.value = if (voice) (Texts.get("call_type_voice_call") ?: "Voice Call") else call.remoteAddress.asStringUriOnly()
            }
        }
    }

    private fun fireActionsOnCallStateChanged(cstate: Call.State) {
        GlobalScope.launch(context = Dispatchers.Main) {
            if (cstate == Call.State.IncomingReceived) {
                call.extendedAcceptEarlyMedia()
            } else if (cstate == Call.State.Connected || cstate == Call.State.StreamsRunning) {
                if (coreContext.isCallSilentlyAccepted(call)) {
                    coreContext.core.stopRinging()
                    coreContext.core.stopDtmf()
                    coreContext.core.isMicEnabled = false
                    call.setSpeakerMuted(true)
                    call.setMicrophoneMuted(true)
                    microphoneMuted.value = true
                    call.muteAudioPLayBack()
                    if (cstate == Call.State.StreamsRunning) {
                        val actionToRun = pendingActionAfterSilentAccept
                        pendingActionAfterSilentAccept = null
                        if (actionToRun != null) {
                            delay(150)
                            executeActionNow(actionToRun)
                        }
                    }
                } else if (cstate == Call.State.StreamsRunning && call.callLog?.dir == Call.Dir.Outgoing) {
                    if (corePreferences.saveCallVideos && !call.isRecording) {
                        call.startRecording()
                    }
                    if (isVoiceCall.value != true || LinhomeApplication.instance.tablet()) {
                        coreContext.core.forceSpeakerAudioRoute()
                        speakerDisabled.value = false
                    } else {
                        val currentType = coreContext.core.outputAudioDevice?.type
                        if (currentType != AudioDevice.Type.Bluetooth &&
                            currentType != AudioDevice.Type.Headphones &&
                            currentType != AudioDevice.Type.Headset) {
                            coreContext.core.forceEarpieceAudioRoute()
                            speakerDisabled.value = true
                        }
                    }
                } else if (cstate == Call.State.StreamsRunning && call.callLog?.dir == Call.Dir.Incoming) {
                    if (isVoiceCall.value == true && !LinhomeApplication.instance.tablet()) {
                        val currentType = coreContext.core.outputAudioDevice?.type
                        if (currentType != AudioDevice.Type.Bluetooth &&
                            currentType != AudioDevice.Type.Headphones &&
                            currentType != AudioDevice.Type.Headset) {
                            coreContext.core.forceEarpieceAudioRoute()
                            speakerDisabled.value = true
                        }
                    } else if (LinhomeApplication.instance.tablet()) {
                        coreContext.core.forceSpeakerAudioRoute()
                        speakerDisabled.value = false
                    }
                }
            }
        }
    }


    private fun attemptSetDeviceThumbnail(cstate: Call.State) {
        if (cstate == Call.State.End) { // Copy call media file to device file if there is none or user needs last
            device.value?.also { d ->
                d.thumbNail.also { deviceThumb ->
                    if (corePreferences.showLatestSnapshot || !deviceThumb.existsAndIsNotEmpty()) {
                        call.callLog?.historyEvent()?.also { event ->
                            GlobalScope.launch(context = Dispatchers.Main) {
                                delay(500) // Snapshot availability takes a little time.
                                if (event.mediaThumbnail.existsAndIsNotEmpty()) {
                                    event.mediaThumbnail.copyTo(deviceThumb, true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    fun decline() {
        if (call.state == Call.State.IncomingReceived ||
            call.state == Call.State.IncomingEarlyMedia ||
            call.state == Call.State.EarlyUpdating ||
            call.state == Call.State.EarlyUpdatedByRemote
        ) {
            call.decline(Reason.Declined)
        } else {
            call.terminate()
        }
    }

    fun cancel() {
        call.terminate()
    }

    fun terminate() {
        call.terminate()
    }

    override fun onCleared() {
        call.removeListener(callListener)
        super.onCleared()
    }


    fun toggleMute() {
        val micEnabled = coreContext.core.isMicEnabled
        coreContext.core.isMicEnabled = !micEnabled
        microphoneMuted.value = micEnabled
    }


    fun toggleSpeaker() {
        val audioDevice = coreContext.core.outputAudioDevice
        if (audioDevice?.type == AudioDevice.Type.Speaker) {
            coreContext.core.forceEarpieceAudioRoute()
            speakerDisabled.value = true
        } else {
            coreContext.core.forceSpeakerAudioRoute()
            speakerDisabled.value = false
        }
    }


    fun toggleVideoFullScreen() {
        videoFullScreen.value = !videoFullScreen.value!!
    }

    fun performAction(action: Action) {
        Compatibility.vibrateActionFeedback()
        actionFeedbackState.value = ActionFeedbackState.IN_PROGRESS
        val actionTextName = Texts.get(action.typeName() ?: "") ?: Texts.get("door_opening_in_progress")
        actionFeedbackText.value = Texts.get("action_sending", actionTextName) ?: Texts.get("door_opening_in_progress")

        if (call.state == Call.State.IncomingReceived ||
            call.state == Call.State.IncomingEarlyMedia ||
            call.state == Call.State.EarlyUpdating ||
            call.state == Call.State.EarlyUpdatedByRemote
        ) {
            Log.i("[CallViewModel] Action requested before answering (${action.code}); silently accepting SIP call to stop ringing elsewhere and send action")
            pendingActionAfterSilentAccept = action
            coreContext.core.stopRinging()
            coreContext.core.stopDtmf()
            coreContext.core.isMicEnabled = false
            call.setSpeakerMuted(true)
            call.setMicrophoneMuted(true)
            call.muteAudioPLayBack()
            call.extendedAcceptSilent()
            return
        }
        if (coreContext.isCallSilentlyAccepted(call)) {
            coreContext.startOrResetSilentAcceptTimer(call)
        }
        executeActionNow(action)
    }

    private fun executeActionNow(action: Action) {
        val actionMethodType = device.value?.let { d->
            d.actionsMethodType
        } ?: corePreferences.defaultActionsMethodType
        Log.i("[CallViewModel] received user request to send DTMF: ${action.code} via $actionMethodType")

        Compatibility.vibrateActionFeedback()
        actionFeedbackState.value = ActionFeedbackState.COMPLETED
        actionFeedbackText.value = Texts.get("door_opened_confirmed")

        GlobalScope.launch(context = Dispatchers.Main) {
            delay(2500)
            if (actionFeedbackState.value == ActionFeedbackState.COMPLETED) {
                actionFeedbackState.value = ActionFeedbackState.IDLE
            }
        }

        when (actionMethodType) {
            "method_dtmf_sip_info" -> {
                coreContext.core.useRfc2833ForDtmf = false
                coreContext.core.useInfoForDtmf = true
                action.code?.let {
                    call.sendDtmfs(it)
                    coreContext.core.stopDtmf()
                    DialogUtil.toast("action_sent", oneArg = action.actionText())
                }
            }
            "method_dtmf_rfc_4733" -> {
                coreContext.core.useInfoForDtmf = false
                coreContext.core.useRfc2833ForDtmf = true
                action.code?.let {
                    call.sendDtmfs(it)
                    coreContext.core.stopDtmf()
                    DialogUtil.toast("action_sent", oneArg = action.actionText())
                }
            }
            "method_sip_message" -> {
                var params = coreContext.core.createDefaultChatRoomParams()
                params.isGroupEnabled = false
                params.isEncryptionEnabled = false
                var chatRoom = coreContext.core.searchChatRoom(params,call.remoteAddress, call.remoteAddress, arrayOf(call.remoteAddress!!))
                if (chatRoom == null) {
                    chatRoom = coreContext.core.createChatRoom(params, call.remoteAddress,  arrayOf(call.remoteAddress!!))
                }
                val message = chatRoom?.createMessageFromUtf8(action.code)
                message?.send()
                DialogUtil.toast("action_sent", oneArg = action.actionText())
            }
        }
    }

    fun extendedAccept() {
        call.extendedAccept()
        callState.value = call.state
        if (isVoiceCall.value == true && !LinhomeApplication.instance.tablet()) {
            val currentType = coreContext.core.outputAudioDevice?.type
            if (currentType != AudioDevice.Type.Bluetooth &&
                currentType != AudioDevice.Type.Headphones &&
                currentType != AudioDevice.Type.Headset) {
                coreContext.core.forceEarpieceAudioRoute()
                speakerDisabled.value = true
            }
        }
    }


    // Default actions to be displayed when no device is associated with the call. Taken from configuration.
    //[default_actions]
    //default_actions_method_type=[method_dtmf_sip_info|method_dtmf_rfc_4733|method_sip_message]
    //action_1_type=[action_open_door|action_open_gate|action_lightup|action_unlock] (match name in shared theme action_types.xml)
    //action_1_code=yyyy (dtmf code)
    //action_2... up to 3 actions

    fun defaultActionsIfAny() : ArrayList<Action> {
        var actions = ArrayList<Action>()
        corePreferences.defaultAction1?.also {actions.add(it)}
        corePreferences.defaultAction2?.also {actions.add(it)}
        corePreferences.defaultAction3?.also {actions.add(it)}
        return actions
    }

}
