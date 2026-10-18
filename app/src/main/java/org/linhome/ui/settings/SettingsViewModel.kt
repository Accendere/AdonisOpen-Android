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

package org.linhome.ui.settings

import androidx.databinding.ViewDataBinding
import androidx.lifecycle.MutableLiveData
import org.linhome.LinhomeApplication
import org.linhome.customisation.Texts
import org.linhome.entities.LinhomeAccount
import org.linhome.store.HistoryEventStore
import org.linhome.utils.databindings.ViewModelWithTools
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.MediaEncryption

class SettingsViewModel : ViewModelWithTools() {

    val audioCodecs = ArrayList<ViewDataBinding>()
    val videCodecs = ArrayList<ViewDataBinding>()

    val enableIpv6 = MutableLiveData(core.isIpv6Enabled())
    val latestSnapshotShown = MutableLiveData(corePref.showLatestSnapshot)
    var backgroundModeEnabled = MutableLiveData(corePref.keepServiceAlive)

    // Fork settings
    val appEnabled = MutableLiveData(corePref.appEnabled)
    val saveCallVideos = MutableLiveData(corePref.saveCallVideos)
    val notifyAnsweredElsewhere = MutableLiveData(corePref.notifyAnsweredElsewhere)
    val themeChangedEvent = MutableLiveData(false)

    // Theme selector
    private val themeValues = arrayListOf("orange", "slate", "emerald", "obsidian")
    val themeIndex = MutableLiveData<Int>()
    val themeLabels = MutableLiveData<ArrayList<String>>()
    val themeListener = object : SettingListenerStub() {
        override fun onListValueChanged(position: Int) {
            val selectedTheme = themeValues[position]
            if (corePref.appTheme != selectedTheme) {
                corePref.appTheme = selectedTheme
                themeIndex.value = position
                themeChangedEvent.value = true
            }
        }
    }

    // History retention selector
    private val retentionValues = arrayListOf(0, 1, 2, 3, 7, 30, 90)
    val retentionIndex = MutableLiveData<Int>()
    val retentionLabels = MutableLiveData<ArrayList<String>>()
    val retentionListener = object : SettingListenerStub() {
        override fun onListValueChanged(position: Int) {
            val days = retentionValues[position]
            corePref.historyRetentionDays = days
            retentionIndex.value = position
            HistoryEventStore.pruneOldEvents(days)
        }
    }

    // Auto-close preview timer selector
    val autoCloseTimerEnabled = MutableLiveData(corePref.silentAcceptTimerEnabled)
    val autoCloseTimerEnabledListener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.silentAcceptTimerEnabled = newValue
        }
    }

    private val autoCloseDurationValues = arrayListOf(10, 15, 30, 45, 60, 90)
    val autoCloseDurationIndex = MutableLiveData<Int>()
    val autoCloseDurationLabels = MutableLiveData<ArrayList<String>>()
    val autoCloseDurationListener = object : SettingListenerStub() {
        override fun onListValueChanged(position: Int) {
            val seconds = autoCloseDurationValues[position]
            corePref.silentAcceptTimerSeconds = seconds
            autoCloseDurationIndex.value = position
        }
    }

    // Logs
    val enableDebugLogs = MutableLiveData(corePref.debugLogs)
    var logUploadResult = MutableLiveData<Pair<Core.LogCollectionUploadState, String>>()

    private val uploadCoreListener = object : CoreListenerStub() {
        override fun onLogCollectionUploadStateChanged(
            core: Core,
            state: Core.LogCollectionUploadState,
            url: String
        ) {
            logUploadResult.postValue(Pair(state, url))
        }
    }

    // Media Encryption
    private val encryptionValues = arrayListOf<Int>()

    val encryptionListener = object : SettingListenerStub() {
        override fun onListValueChanged(position: Int) {
            core.mediaEncryption = MediaEncryption.fromInt(encryptionValues[position])
            encryptionIndex.value = position
        }
    }
    val encryptionIndex = MutableLiveData<Int>()
    val encryptionLabels = MutableLiveData<ArrayList<String>>()

    init {
        core.addListener(uploadCoreListener)
        initEncryptionList()
        initThemeList()
        initRetentionList()
        initAutoCloseDurationList()
    }

    override fun onCleared() {
        core.removeListener(uploadCoreListener)
        super.onCleared()
    }

    val enableIpv6Listener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            core.isIpv6Enabled = newValue
        }
    }

    val showLatestSnapshot = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.showLatestSnapshot = newValue
        }
    }

    val appEnabledListener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            LinhomeAccount.setAppEnabled(newValue)
        }
    }

    val saveCallVideosListener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.saveCallVideos = newValue
        }
    }

    val notifyAnsweredElsewhereListener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.notifyAnsweredElsewhere = newValue
        }
    }

    val enableDebugLogsListener = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.debugLogs = newValue
        }
    }

    val enableBackgroundMode = object : SettingListenerStub() {
        override fun onBoolValueChanged(newValue: Boolean) {
            corePref.keepServiceAlive = newValue
            if (newValue && corePref.appEnabled) {
                LinhomeApplication.coreContext.notificationsManager.startForeground()
            } else {
                LinhomeApplication.coreContext.notificationsManager.stopForegroundNotificationIfPossible()
            }
        }
    }

    private fun initThemeList() {
        themeLabels.value = arrayListOf(
            Texts.get("theme_orange"),
            Texts.get("theme_slate"),
            Texts.get("theme_emerald"),
            Texts.get("theme_obsidian")
        )
        val idx = themeValues.indexOf(corePref.appTheme)
        themeIndex.value = if (idx >= 0) idx else 0
    }

    private fun initRetentionList() {
        retentionLabels.value = arrayListOf(
            Texts.get("retention_never"),
            Texts.get("retention_1_day"),
            Texts.get("retention_2_days"),
            Texts.get("retention_3_days"),
            Texts.get("retention_7_days"),
            Texts.get("retention_30_days"),
            Texts.get("retention_90_days")
        )
        val idx = retentionValues.indexOf(corePref.historyRetentionDays)
        retentionIndex.value = if (idx >= 0) idx else retentionValues.indexOf(2)
    }

    private fun initEncryptionList() {
        val labels = arrayListOf<String>()

        labels.add(Texts.get("none"))
        encryptionValues.add(MediaEncryption.None.toInt())

        if (core.mediaEncryptionSupported(MediaEncryption.SRTP)) {
            labels.add("SRTP")
            encryptionValues.add(MediaEncryption.SRTP.toInt())
        }

        encryptionLabels.value = labels
        encryptionIndex.value = encryptionValues.indexOf(core.mediaEncryption.toInt())
    }

    private fun initAutoCloseDurationList() {
        autoCloseDurationLabels.value = arrayListOf(
            "10s",
            "15s",
            "30s",
            "45s",
            "60s",
            "90s"
        )
        val idx = autoCloseDurationValues.indexOf(corePref.silentAcceptTimerSeconds)
        autoCloseDurationIndex.value = if (idx >= 0) idx else 2
    }

}
