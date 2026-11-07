/*
 * Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android).
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
package org.linhome.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import org.linphone.core.tools.Log
import java.util.concurrent.Executor

/**
 * Finds Adonis Open sites on the Wi-Fi: browses mDNS for [PairingContract.SERVICE_TYPE] and reads each
 * service's TXT record. [onFound] runs on the main thread, once per distinct site. Call [stop] when done.
 */
class SiteDiscovery(context: Context, private val onFound: (DiscoveredSite) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val found = mutableSetOf<String>()
    private var running = false

    // Before Android 14 a resolve can't run while another one does: resolve one service at a time.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val infoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i("[Pairing] Looking for sites on the network")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            main.post { if (running) resolve(service) }
        }

        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w("[Pairing] Network search failed to start ($errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        if (running || nsd == null) return
        running = true
        try {
            nsd.discoverServices(PairingContract.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            running = false
            Log.w("[Pairing] Can't search the network: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd?.stopServiceDiscovery(discoveryListener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.forEach { runCatching { nsd?.unregisterServiceInfoCallback(it) } }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
    }

    private fun resolve(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(service)
        } else {
            resolveQueue.addLast(service)
            resolveNext()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithCallback(service: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w("[Pairing] Can't read a site's announcement ($errorCode)")
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (running) report(serviceInfo)
            }

            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks += callback
        runCatching { nsd?.registerServiceInfoCallback(service, mainExecutor, callback) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (running) report(serviceInfo)
                    resolveNext()
                }
            }
        }
        try {
            nsd?.resolveService(next, listener)
        } catch (e: Exception) {
            resolving = false
        }
    }

    private fun report(info: NsdServiceInfo) {
        val site = PairingContract.siteFromTxt(info.attributes ?: emptyMap())
        if (site == null) {
            Log.w("[Pairing] Ignored an announcement without a usable https address")
            return
        }
        if (found.add(site.pairUrl)) {
            Log.i("[Pairing] Found ${site.host}")
            onFound(site)
        }
    }
}
