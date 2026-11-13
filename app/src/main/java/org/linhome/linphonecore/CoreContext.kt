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
package org.linhome.linphonecore


import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_PHONE_STATE
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.PowerManager
import android.os.Vibrator
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linhome.LinhomeApplication
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.R
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.Texts
import org.linhome.entities.Action
import org.linhome.linphonecore.extensions.extendedAccept
import org.linhome.linphonecore.extensions.extendedAcceptSilent
import org.linhome.notifications.NotificationsManager
import org.linhome.store.DeviceStore
import org.linhome.ui.call.CallInProgressActivity
import org.linhome.ui.call.CallIncomingActivity
import org.linhome.ui.call.CallOutgoingActivity
import org.linhome.utils.DialogUtil
import org.linphone.compatibility.PhoneStateInterface
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.GlobalState
import org.linphone.core.Player
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.mediastream.Log
import org.linphone.mediastream.Version
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

class CoreContext(
    val context: Context,
    coreConfig: Config,
    service: CoreService? = null,
    useAutoStartDescription: Boolean = false
) {
    companion object {
        const val SILENT_ACCEPT_DURATION_MS = 30_000L
        /** After the SDK's own stopForeground (about a second after its service starts); the later ones catch slow devices. */
        val KEEP_ALIVE_CHECK_DELAYS_MS = listOf(3_000L, 10_000L, 30_000L)
    }

    var stopped = false
    val core: Core
    var closeAppUponCallFinish = false
    private var phoneStateListener: PhoneStateInterface? = null

    val appVersion: String by lazy {
        "${org.linhome.BuildConfig.VERSION_NAME} (${org.linhome.BuildConfig.VERSION_CODE}, ${org.linhome.BuildConfig.BUILD_TYPE}, ${org.linhome.BuildConfig.GIT_HASH})"
    }

    /** The Linhome release this fork started from, for the About screen. */
    val linhomeBaseVersion: String = org.linhome.BuildConfig.LINHOME_BASE_VERSION

    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        "$sdkVersion ($sdkBranch)"
    }


    val notificationsManager: NotificationsManager by lazy {
        NotificationsManager(context)
    }

    private var overlayX = 0f
    private var overlayY = 0f
    private var callOverlay: View? = null
    private var isVibrating = false
    private val silentlyAcceptedCallIds = HashSet<String>()
    private val silentAcceptJobs = HashMap<String, Job>()
    val silentAcceptRemainingMs = MutableLiveData<Long>(0L)
    val lastCallStateMessage = HashMap<String, String>()

    /** Narrates incoming calls as webhook events (only what the site listed leaves the phone). */
    val callReporter = org.linhome.webhooks.CallReporter({ event, fields, at -> org.linhome.webhooks.Webhooks.sendEvent(event, fields, at) })
    private val registrationReporter = org.linhome.webhooks.RegistrationReporter({ event, fields, at -> org.linhome.webhooks.Webhooks.sendEvent(event, fields, at) })

    /** Calls this phone hung up or declined itself, so `call_ended` can say `by: local`. */
    private val localHangups = HashSet<String>()

    /** Who answered a call this phone saw answered elsewhere: the site's answer, per call id, while it is awaited. */
    val answeredByLookups = HashMap<String, kotlinx.coroutines.Deferred<String?>>()

    fun markLocalHangup(call: Call) {
        call.callLog?.callId?.let { localHangups.add(it) }
    }

    /** The configured device name for the caller, else the caller's SIP user. */
    fun doorNameFor(call: Call): String? =
        DeviceStore.findDeviceByAddress(call.remoteAddress)?.name ?: call.remoteAddress?.username

    /** Reports an action button press (before or during the call). */
    fun reportDoorAction(call: Call, action: Action) {
        callReporter.onDoorAction(
            call.callLog?.callId, action.type, action.code,
            early = isCallSilentlyAccepted(call), sent = action.code != null
        )
    }

    private fun outcomeOf(call: Call, state: Call.State): org.linhome.webhooks.CallOutcome {
        if (state == Call.State.Error) return org.linhome.webhooks.CallOutcome.FAILED
        return when (call.callLog?.status) {
            Call.Status.Success -> org.linhome.webhooks.CallOutcome.ANSWERED
            Call.Status.Declined -> org.linhome.webhooks.CallOutcome.DECLINED
            Call.Status.AcceptedElsewhere -> org.linhome.webhooks.CallOutcome.ANSWERED_ELSEWHERE
            Call.Status.DeclinedElsewhere -> org.linhome.webhooks.CallOutcome.DECLINED_ELSEWHERE
            Call.Status.Missed, Call.Status.Aborted, Call.Status.EarlyAborted -> org.linhome.webhooks.CallOutcome.MISSED
            else -> org.linhome.webhooks.CallOutcome.MISSED
        }
    }

    /** End of a narrated call: asks the site who answered when it was answered elsewhere, then reports. */
    private fun reportCallEnded(call: Call, state: Call.State) {
        val id = call.callLog?.callId ?: return
        if (!callReporter.isTracking(id)) return
        val outcome = outcomeOf(call, state)
        val byLocal = localHangups.remove(id)
        if (outcome == org.linhome.webhooks.CallOutcome.DECLINED && byLocal) callReporter.onDeclined(id)
        val known: Boolean? = when (outcome) {
            org.linhome.webhooks.CallOutcome.ANSWERED, org.linhome.webhooks.CallOutcome.FAILED -> byLocal.takeIf { it } ?: false
            org.linhome.webhooks.CallOutcome.DECLINED -> true
            org.linhome.webhooks.CallOutcome.MISSED, org.linhome.webhooks.CallOutcome.ANSWERED_ELSEWHERE,
            org.linhome.webhooks.CallOutcome.DECLINED_ELSEWHERE -> false
            else -> null
        }
        if (outcome == org.linhome.webhooks.CallOutcome.ANSWERED_ELSEWHERE &&
            org.linhome.webhooks.Webhooks.config?.reportsCalls == true
        ) {
            val door = doorNameFor(call)
            val lookup = coroutineScope.async { org.linhome.webhooks.Webhooks.whoAnswered(id, door) }
            answeredByLookups[id] = lookup
            coroutineScope.launch {
                val name = runCatching { lookup.await() }.getOrNull()
                callReporter.onEnded(id, outcome, known, name)
                delay(30_000)
                answeredByLookups.remove(id)
            }
        } else {
            callReporter.onEnded(id, outcome, known)
        }
    }

    val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun markCallSilentlyAccepted(call: Call) {
        val id = call.callLog?.callId ?: return
        silentlyAcceptedCallIds.add(id)
        callReporter.onPreview(id)
        if (isVibrating) {
            Log.i("[Context] Stopping vibration on silent accept")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
            isVibrating = false
        }
        startOrResetSilentAcceptTimer(call)
    }

    fun startOrResetSilentAcceptTimer(call: Call) {
        val id = call.callLog?.callId ?: return
        silentAcceptJobs[id]?.cancel()

        if (!LinhomeApplication.corePreferences.silentAcceptTimerEnabled) {
            silentAcceptRemainingMs.postValue(0L)
            return
        }

        val durationMs = (LinhomeApplication.corePreferences.silentAcceptTimerSeconds * 1000L).coerceAtLeast(1000L)
        val deadline = System.currentTimeMillis() + durationMs
        silentAcceptRemainingMs.postValue(durationMs)
        silentAcceptJobs[id] = coroutineScope.launch {
            while (System.currentTimeMillis() < deadline) {
                if (!silentlyAcceptedCallIds.contains(id)) {
                    silentAcceptRemainingMs.value = 0L
                    return@launch
                }
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                silentAcceptRemainingMs.value = remaining
                delay(100)
            }
            silentAcceptRemainingMs.value = 0L
            if (silentlyAcceptedCallIds.contains(id) &&
                call.state != Call.State.End &&
                call.state != Call.State.Released &&
                call.state != Call.State.Error
            ) {
                Log.i("[Context] Auto-closing silently accepted call [$id] after timeout")
                markLocalHangup(call)
                call.terminate()
            }
        }
    }

    fun cancelSilentAcceptTimer(call: Call) {
        val id = call.callLog?.callId ?: return
        silentAcceptJobs.remove(id)?.cancel()
        silentAcceptRemainingMs.postValue(0L)
    }

    fun isCallSilentlyAccepted(call: Call): Boolean {
        val id = call.callLog?.callId ?: return false
        return silentlyAcceptedCallIds.contains(id)
    }

    fun clearSilentlyAccepted(call: Call) {
        call.callLog?.callId?.let { id ->
            silentlyAcceptedCallIds.remove(id)
            silentAcceptJobs.remove(id)?.cancel()
            silentAcceptRemainingMs.postValue(0L)
        }
    }

    fun executeActionForCall(call: Call, action: Action) {
        reportDoorAction(call, action)
        val device = DeviceStore.findDeviceByAddress(call.remoteAddress)
        val actionMethodType = device?.actionsMethodType ?: corePreferences.defaultActionsMethodType
        Log.i("[Context] Sending action DTMF/message: ${action.code} via $actionMethodType")
        when (actionMethodType) {
            "method_dtmf_sip_info" -> {
                core.useRfc2833ForDtmf = false
                core.useInfoForDtmf = true
                action.code?.let {
                    call.sendDtmfs(it)
                    DialogUtil.toast("action_sent", oneArg = action.actionText())
                }
            }
            "method_dtmf_rfc_4733" -> {
                core.useInfoForDtmf = false
                core.useRfc2833ForDtmf = true
                action.code?.let {
                    call.sendDtmfs(it)
                    DialogUtil.toast("action_sent", oneArg = action.actionText())
                }
            }
            "method_sip_message" -> {
                val params = core.createDefaultChatRoomParams()
                params.isGroupEnabled = false
                params.isEncryptionEnabled = false
                var chatRoom = core.searchChatRoom(params, call.remoteAddress, call.remoteAddress, arrayOf(call.remoteAddress!!))
                if (chatRoom == null) {
                    chatRoom = core.createChatRoom(params, call.remoteAddress, arrayOf(call.remoteAddress!!))
                }
                val message = chatRoom?.createMessageFromUtf8(action.code)
                message?.send()
                DialogUtil.toast("action_sent", oneArg = action.actionText())
            }
        }
    }

    fun triggerDoorActionFromNotification(call: Call) {
        val device = DeviceStore.findDeviceByAddress(call.remoteAddress)
        val action = device?.actions?.firstOrNull() ?: corePreferences.defaultAction1 ?: Action("action_open_door", "#")
        if (call.state == Call.State.IncomingReceived ||
            call.state == Call.State.IncomingEarlyMedia ||
            call.state == Call.State.EarlyUpdating ||
            call.state == Call.State.EarlyUpdatedByRemote
        ) {
            Log.i("[Context] Notification Open Door clicked before answer; silently accepting and sending ${action.code}")
            call.extendedAcceptSilent()
            coroutineScope.launch {
                var waited = 0
                while (waited < 4000 && call.state != Call.State.StreamsRunning && call.state != Call.State.End && call.state != Call.State.Released) {
                    delay(100)
                    waited += 100
                }
                if (call.state == Call.State.StreamsRunning || call.state == Call.State.Connected) {
                    delay(150)
                    executeActionForCall(call, action)
                }
            }
        } else {
            if (isCallSilentlyAccepted(call)) {
                startOrResetSilentAcceptTimer(call)
            }
            executeActionForCall(call, action)
        }
    }

    fun extractAnsweredElsewhereBannerText(call: Call): String? {
        val id = call.callLog?.callId
        val status = call.callLog?.status
        val reason = call.reason
        val rawMsg = (id?.let { lastCallStateMessage[it] } ?: call.errorInfo?.phrase ?: "").trim()
        val isAnsweredElsewhere = reason?.name?.contains("AnsweredElsewhere", ignoreCase = true) == true ||
                status == Call.Status.AcceptedElsewhere ||
                rawMsg.contains("completed elsewhere", ignoreCase = true) ||
                rawMsg.contains("answered elsewhere", ignoreCase = true)
        val isDeclinedElsewhere = status == Call.Status.DeclinedElsewhere
        if (!isAnsweredElsewhere && !isDeclinedElsewhere) {
            return null
        }
        if (isDeclinedElsewhere) {
            return Texts.get("call_declined_elsewhere_banner")
        }
        val customWho = call.remoteParams?.getCustomHeader("Answered-By")
            ?: call.remoteParams?.getCustomHeader("X-Answered-By")
            ?: run {
                val regex = Regex("(?:answered|taken|completed)\\s+by\\s+([\\w\\s.@+-]+)", RegexOption.IGNORE_CASE)
                regex.find(rawMsg)?.groupValues?.getOrNull(1)?.trim()
            }
        return if (!customWho.isNullOrBlank()) {
            Texts.get("call_answered_elsewhere_by_banner", customWho)
        } else {
            Texts.get("call_answered_elsewhere_banner")
        }
    }


    fun gsmCallActive() : Boolean {
        return phoneStateListener?.isInCall() == true
    }

    private val listener: CoreListenerStub = object : CoreListenerStub() {

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: org.linphone.core.Account,
            state: RegistrationState,
            message: String
        ) {
            when (state) {
                RegistrationState.Failed -> registrationReporter.onFailed(message.ifBlank { null })
                RegistrationState.Ok -> registrationReporter.onOk()
                else -> {}
            }
            if (account.params?.idkey != org.linhome.entities.LinhomeAccount.PUSH_GW_ID_KEY) {
                RegistrationMonitor.onStateChanged(state, message.ifBlank { account.errorInfo?.phrase })
                if (state == RegistrationState.Ok) {
                    runCatching { LinhomeDeviceCleanup.onRegistered(core, account) }
                        .onFailure { Log.e("[Context] Device cleanup failed: ${it.message}") }
                }
            }
        }

        override fun onPushNotificationReceived(core: Core, payload: String?) {
            org.linphone.core.tools.Log.i("[Context] Push notification received: $payload")
        }

        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("[Context] Global state changed [$state]")
            if (state == GlobalState.On) {
                Log.d("[Context] Configuration is \n : ${core.config.dump()}")
                reAssertKeepAliveForegroundServiceAfterStartup()
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Log.i("[Context] Call state changed [$state] ($message)")
            if (message.isNotBlank()) {
                call.callLog?.callId?.let { lastCallStateMessage[it] = message }
            }
            if (state == Call.State.IncomingReceived) {
                val callId = call.callLog?.callId
                if (!corePreferences.appEnabled) {
                    Log.i("[Context] App is disabled by user preference, declining incoming call")
                    callId?.let { callReporter.onIncoming(it, doorNameFor(call), call.remoteAddress?.asStringUriOnly(), org.linhome.webhooks.CallOutcome.REFUSED_PAUSED) }
                    call.decline(Reason.TemporarilyUnavailable)
                    return
                }

                if (core.callsNb > 1) {
                    callId?.let { callReporter.onIncoming(it, doorNameFor(call), call.remoteAddress?.asStringUriOnly(), org.linhome.webhooks.CallOutcome.REFUSED_BUSY) }
                    call.decline(Reason.Busy)
                    return
                    }

                callId?.let { callReporter.onIncoming(it, doorNameFor(call), call.remoteAddress?.asStringUriOnly(), gsmCallActive = gsmCallActive()) }

                if (gsmCallActive()) {
                    Log.w("[Context] Receiving a Linhome call while a GSM call is active")
                }

                if (core.callsNb == 1 && corePreferences.vibrateWhileIncomingCall) {
                    val audioManager =
                        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if ((audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
                                audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL)
                    ) {
                        val vibrator =
                            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (vibrator.hasVibrator()) {
                            Log.i("[Context] Starting incoming call vibration")
                            Compatibility.vibrate(vibrator)
                            isVibrating = true
                        }
                    }
                }

                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isInteractive) {
                    // Screen is off: acquire a wake lock to turn screen on so the notification's
                    // full-screen intent will be honored by the system.
                    // Do NOT call onIncomingReceived() here — on Android 16+ direct startActivity
                    // from background creates an invisible activity (BAL_BLOCK) that conflicts
                    // with the notification's full-screen intent launch.
                    @Suppress("DEPRECATION")
                    val wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "linhome:incoming_call"
                    )
                    wakeLock.acquire(10000)
                    Log.i("[Context] Screen was off, acquired wake lock for incoming call")
                } else if (Version.sdkStrictlyBelow(Version.API24_NOUGAT_70) || LinhomeApplication.someActivityRunning) {
                    onIncomingReceived(call)
                }

                if (corePreferences.autoAnswerEnabled) {
                    val autoAnswerDelay = corePreferences.autoAnswerDelay
                    if (autoAnswerDelay == 0) {
                        Log.w("[Context] Auto answering call immediately")
                        answerCall(call)
                    } else {
                        val timer = Timer("Auto answer scheduler")
                        Log.i("[Context] Scheduling auto answering in $autoAnswerDelay milliseconds")
                        timer.schedule(object : TimerTask() {
                            override fun run() {
                                if (!isCallSilentlyAccepted(call) && (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia)) {
                                    Log.w("[Context] Auto answering call")
                                    answerCall(call)
                                }
                            }
                        }, autoAnswerDelay.toLong())
                    }
                }
            } else if (state == Call.State.OutgoingInit || state == Call.State.OutgoingProgress) {
                onOutgoingStarted(call)
            } else if (state == Call.State.Connected) {
                // The keep-alive service runs as special use; the call needs the phone call + microphone (+ camera)
                // types, or Android silences the microphone as soon as the app leaves the screen. The SDK would
                // switch it, but it skips that because it still believes its own foreground start is active.
                if (corePreferences.keepServiceAlive && notificationsManager.service != null &&
                    Compatibility.hasPermission(context, POST_NOTIFICATIONS)) {
                    Log.i("[Context] Call connected, switching the foreground service to the call types")
                    notificationsManager.startForeground()
                }
                if (isVibrating) {
                    Log.i("[Context] Stopping vibration")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.cancel()
                    isVibrating = false
                }

                if (!isCallSilentlyAccepted(call)) {
                    call.callLog?.callId?.let { callReporter.onAnswered(it) }
                    onCallStarted(call)
                } else {
                    Log.i("[Context] Call is silently accepted for pre-answer action, staying in incoming view")
                }
            } else if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
                if (state != Call.State.Released) reportCallEnded(call, state)
                clearSilentlyAccepted(call)
                if (core.callsNb == 0) {
                    if (isVibrating) {
                        Log.i("[Context] Stopping vibration")
                        val vibrator =
                            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        vibrator.cancel()
                        isVibrating = false
                    }

                    removeCallOverlay()
                    // End/Error, not Released: Released only comes once the Call object is freed, which can be much later.
                    if (state != Call.State.Released) {
                        // Back from the call types to special use (no call = no microphone or camera type).
                        if (corePreferences.keepServiceAlive && corePreferences.appEnabled && notificationsManager.service != null &&
                            Compatibility.hasPermission(context, POST_NOTIFICATIONS)) {
                            notificationsManager.startForeground()
                        }
                        ensureKeepAliveForeground("call ended")
                    }
                }
            }
            if (state == Call.State.Error && call.callLog?.dir == Call.Dir.Outgoing) {
                GlobalScope.launch(context = Dispatchers.Main) {
                    delay(250)
                    DialogUtil.error("unable_to_call_device")
                }
            }
        }
    }

    init {
        if (service != null) {
            org.linphone.core.tools.Log.i("[Context] Starting foreground service")
            notificationsManager.startForegroundToKeepAppAlive(service, useAutoStartDescription)
        }
        LinhomeDeviceCleanup.applyStableInstanceId(context, coreConfig)
        core = Factory.instance().createCoreWithConfig(coreConfig, context)
        stopped = false
        Log.i("[Context] Ready")
    }


    fun start(isPush: Boolean = false, startService: Boolean = true) {
        Log.i("[Context] Starting")

        notificationsManager.onCoreReady()

        core.addListener(listener)
        if (isPush) {
            Log.i("[Context] Push received, assume in background")
            core.enterBackground()
        }
        core.config.setBool("net", "use_legacy_push_notification_params", true)
        core.start()

        configureCore()

        initPhoneStateListener()

        if (corePreferences.keepServiceAlive && corePreferences.appEnabled && Compatibility.hasPermission(context,
                POST_NOTIFICATIONS)) {
            org.linphone.core.tools.Log.i("[Context] Background mode setting is enabled, starting Service")
            notificationsManager.startForeground()
        }

    }

    fun stop() {
        Log.i("[Context] Stopping")

        notificationsManager.destroy()

        core.stop()
        core.removeListener(listener)
        stopped = true
    }

    private fun configureCore() {
        Log.i("[Context] Configuring Core")

        core.zrtpSecretsFile = context.filesDir.absolutePath + "/zrtp_secrets"

        initUserCertificates()

        computeUserAgent()

        Log.i("[Context] Core configured")
    }

    private fun computeUserAgent() {
        val deviceName: String = corePreferences.deviceName
        val appName: String = context.resources.getString(R.string.app_name)
        val appVersion = org.linhome.BuildConfig.VERSION_NAME
        val userAgent = "$deviceName $appName $appVersion linphone-sdk"
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkUserAgent = "$sdkVersion ($sdkBranch)"
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    private fun initUserCertificates() {
        val userCertsPath = context.filesDir.absolutePath + "/user-certs"
        val f = File(userCertsPath)
        if (!f.exists()) {
            if (!f.mkdir()) {
                Log.e("[Context] $userCertsPath can't be created.")
            }
        }
        core.userCertificatesPath = userCertsPath
    }

    /* Call related functions */

    fun answerCall(call: Call) {
        Log.i("[Context] Answering call $call")
        call.extendedAccept()
    }

    fun declineCall(call: Call) {
        Log.i("[Context] Declining call $call")
        markLocalHangup(call)
        call.decline(Reason.Declined)
    }

    fun terminateCall(call: Call) {
        Log.i("[Context] Terminating call $call")
        markLocalHangup(call)
        call.terminate()
    }

    fun transferCallTo(addressToCall: String) {
        val currentCall = core.currentCall ?: core.calls.first()
        if (currentCall == null) {
            Log.e("[Context] Couldn't find a call to transfer")
        } else {
            Log.i("[Context] Transferring current call to $addressToCall")
            currentCall.transfer(addressToCall)
        }
    }

    fun pixelsToDp(pixels: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            pixels,
            context.resources.displayMetrics
        )
    }

    fun createCallOverlay() {
        if (!corePreferences.showCallOverlay || callOverlay != null) {
            return
        }

        if (overlayY == 0f) overlayY = pixelsToDp(40f)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Compatibility.getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.x = overlayX.toInt()
        params.y = overlayY.toInt()
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val overlay = LayoutInflater.from(context).inflate(R.layout.call_overlay, null)

        var initX = overlayX
        var initY = overlayY
        overlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x - event.rawX
                    initY = params.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = (event.rawX + initX).toInt()
                    val y = (event.rawY + initY).toInt()

                    params.x = x
                    params.y = y
                    windowManager.updateViewLayout(overlay, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(overlayX - params.x) < 5 && abs(overlayY - params.y) < 5) {
                        core.currentCall?.also {
                            if (isCallSilentlyAccepted(it)) {
                                onIncomingReceived(it)
                            } else {
                                onCallStarted(it)
                            }
                        }
                    }
                    overlayX = params.x.toFloat()
                    overlayY = params.y.toFloat()
                }
                else -> false
            }
            true
        }

        callOverlay = overlay
        windowManager.addView(overlay, params)
    }

    fun removeCallOverlay() {
        if (callOverlay != null) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(callOverlay)
            callOverlay = null
        }
    }

    private fun reAssertKeepAliveForegroundServiceAfterStartup() {
        ensureKeepAliveForeground("startup")
    }

    /**
     * With "Receive calls" on, the service must stay in the foreground, or Android cuts the app's network
     * once it's in the background and the SIP registration fails (DNS "Operation not permitted").
     * The SDK fights this: when its CoreService starts with no call, it starts the service as foreground
     * and, about a second later, calls Service.stopForeground(true) itself, which skips our
     * hideForegroundServiceNotification() override. A call ending can also leave the service without
     * its keep-alive notification. So check a few times after these moments and start it again if it dropped.
     */
    fun ensureKeepAliveForeground(reason: String) {
        if (!corePreferences.keepServiceAlive || !Compatibility.hasPermission(context, POST_NOTIFICATIONS)) {
            return
        }
        for (delayMs in KEEP_ALIVE_CHECK_DELAYS_MS) {
            coroutineScope.launch {
                delay(delayMs)
                if (!corePreferences.keepServiceAlive || !corePreferences.appEnabled || core.callsNb > 0) {
                    return@launch
                }
                val service = notificationsManager.service
                if (service != null && isRunningInForeground(service)) {
                    return@launch
                }
                org.linphone.core.tools.Log.w("[Context] Keep alive foreground service is not running ($reason, after ${delayMs} ms), starting it again")
                notificationsManager.startForeground()
            }
        }
    }

    private fun isRunningInForeground(service: android.app.Service): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            service.foregroundServiceType != 0
        } else {
            false // can't tell before Android 10; starting it again is harmless
        }

    fun checkIfForegroundServiceNotificationCanBeRemovedAfterDelay(delayInMs: Long) {
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                delay(delayInMs)
                withContext(Dispatchers.Main) {
                    if (core.defaultAccount != null && core.defaultAccount?.state == RegistrationState.Ok) {
                        org.linphone.core.tools.Log.i("[Context] Default account is registered, cancel foreground service notification if possible")
                        notificationsManager.stopForegroundNotificationIfPossible()
                    }
                }
            }
        }
    }

    /* Start call related activities */

    private fun onIncomingReceived(call: Call) {
        val intent = Intent(context, CallIncomingActivity::class.java)
        intent.putExtra("callId", call.callLog.callId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun onOutgoingStarted(call: Call) {
        closeAppUponCallFinish = false
        val intent = Intent(context, CallOutgoingActivity::class.java)
        intent.putExtra("callId", call.callLog.callId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    fun onCallStarted(call: Call) {
        val intent = Intent(context, CallInProgressActivity::class.java)
        intent.putExtra("callId", call.callLog.callId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // Player

    var sharedPlayer : Player? = null

    fun getPlayer() : Player? {
        var speakerCard: String? = null
        var earpieceCard: String? = null
        for (device in LinhomeApplication.coreContext.core.audioDevices) {
            if (device.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) {
                if (device.type == AudioDevice.Type.Speaker) {
                    speakerCard = device.id
                } else if (device.type == AudioDevice.Type.Earpiece) {
                    earpieceCard = device.id
                }
            }
        }

        sharedPlayer = LinhomeApplication.coreContext.core.createLocalPlayer(
            speakerCard ?: earpieceCard,
            null,
            null
        )

        return sharedPlayer
    }

    fun initPhoneStateListener() {
        Log.i("[Context] Registering notification_phone state listener")
        if (Compatibility.hasPermission(context,READ_PHONE_STATE)) {
            try {
                phoneStateListener =
                    Compatibility.createPhoneListener(
                        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    )
            } catch (exception: SecurityException) {
                val hasReadPhoneStatePermission = Compatibility.hasPermission(context,READ_PHONE_STATE)
                Log.e("[Context] Failed to create phone state listener: $exception, READ_PHONE_STATE permission status is $hasReadPhoneStatePermission")
            }
        } else {
            Log.w("[Context] Can't create phone state listener, READ_PHONE_STATE permission isn't granted")
        }
    }
}
