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

package org.linhome.linphonecore.extensions

import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.store.HistoryEventStore
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.ProxyConfig
import org.linphone.core.tools.Log

val Core.firstProxyConfig: ProxyConfig?
    get() {
        return if (proxyConfigList.size > 0) proxyConfigList.get(0) else null
    }

fun Core.forceEarpieceAudioRoute() {
    for (audioDevice in coreContext.core.audioDevices) {
        if (audioDevice.type == AudioDevice.Type.Earpiece) {
            Log.i("[Call] Found earpiece audio device [${audioDevice.deviceName}], routing audio to it")
            coreContext.core.outputAudioDevice = audioDevice
            return
        }
    }
    Log.e("[Call] Couldn't find earpiece audio device")
}

/**
 * A phone call's audio on [call] itself: out to [type] (earpiece or speaker), in from the phone's own microphone,
 * microphone on and sound at full volume. Only for calls that are not from a door (CallViewModel decides); door calls
 * keep the core-wide routing above.
 */
fun Core.routeVoiceCall(call: Call, type: AudioDevice.Type) {
    audioDevices.firstOrNull { it.type == type }?.let { device ->
        call.outputAudioDevice = device
        outputAudioDevice = device
    } ?: Log.e("[CallAudio] No ${type.name} audio device")
    audioDevices.firstOrNull { it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
        ?.let { mic -> call.inputAudioDevice = mic }
    isMicEnabled = true
    call.microphoneMuted = false
    call.speakerMuted = false
    call.speakerVolumeGain = 0.0f
    logCallAudio(call, "routed to ${type.name}")
}

/** One line with the call's audio state, for the diagnostics log. */
fun Core.logCallAudio(call: Call, why: String) {
    Log.i(
        "[CallAudio] $why: out=${call.outputAudioDevice?.type ?: outputAudioDevice?.type} " +
            "in=${call.inputAudioDevice?.type ?: inputAudioDevice?.type} micEnabled=$isMicEnabled " +
            "callMicMuted=${call.microphoneMuted} callSpeakerMuted=${call.speakerMuted} gain=${call.speakerVolumeGain}"
    )
}

fun Core.forceSpeakerAudioRoute() {
    for (audioDevice in coreContext.core.audioDevices) {
        if (audioDevice.type == AudioDevice.Type.Speaker) {
            Log.i("[Call] Found speaker audio device [${audioDevice.deviceName}], routing audio to it")
            coreContext.core.outputAudioDevice = audioDevice
            return
        }
    }
    Log.e("[Call] Couldn't find speaker audio device")
}

fun Core.forceBluetoothAudioRoute() {
    for (audioDevice in coreContext.core.audioDevices) {
        if ((audioDevice.type == AudioDevice.Type.Bluetooth) && audioDevice.hasCapability(
                AudioDevice.Capabilities.CapabilityPlay
            )
        ) {
            Log.i("[Call] Found bluetooth audio device [${audioDevice.deviceName}], routing audio to it")
            coreContext.core.outputAudioDevice = audioDevice
            return
        }
    }
    Log.e("[Call] Couldn't find bluetooth audio device")
}


fun Core.callLogsWithNonEmptyCallId(): ArrayList<CallLog> {
    return (coreContext.core.callLogs.toCollection(ArrayList()) as ArrayList<CallLog>).filterNot { it.callId == null } as ArrayList<CallLog>
}


fun Core.missedCount(): Int {
    var count = 0
    coreContext.core.callLogsWithNonEmptyCallId().forEach {
        if (it.isNew())
            count++
    }
    return count
}


fun Core.cleanHistory() {
    coreContext.core.callLogs.forEach {
        if (it.callId != null)
            HistoryEventStore.removeHistoryEventByCallId(it.callId!!)
        coreContext.core.removeCallLog(it)
    }
}


