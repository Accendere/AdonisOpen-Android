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

package org.linhome.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.linhome.LinhomeApplication
import org.linphone.core.tools.Log
import java.util.Collections
import java.util.LinkedHashMap

object ContactsHelper {
    private const val TAG = "[ContactsHelper]"
    private const val MAX_CACHE_SIZE = 128

    // Cache of phoneNumber -> resolvedContactName (empty string represents "not found in contacts")
    private val contactsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    fun hasContactsPermission(context: Context = LinhomeApplication.instance): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryPhoneLookup(number: String, context: Context): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        val name = it.getString(nameIdx)?.trim()
                        if (!name.isNullOrEmpty()) {
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("$TAG Failed to query PhoneLookup for $number: ${e.message}")
        }
        return null
    }

    /**
     * Resolves a telephone number or clean SIP username against Android's ContactsContract.
     * Returns the contact display name if matched, or null otherwise.
     */
    fun findContactNameByNumber(
        phoneNumber: String?,
        context: Context = LinhomeApplication.instance
    ): String? {
        if (phoneNumber.isNullOrBlank()) return null
        val cleanNumber = phoneNumber.trim()

        if (!hasContactsPermission(context)) {
            return null
        }

        // Check in-memory cache first
        contactsCache[cleanNumber]?.let { cached ->
            return if (cached.isEmpty()) null else cached
        }

        var resolvedName = queryPhoneLookup(cleanNumber, context)

        // If not found and number starts with "00", try replacing with "+"
        if (resolvedName == null && cleanNumber.startsWith("00") && cleanNumber.length > 4) {
            resolvedName = queryPhoneLookup("+" + cleanNumber.substring(2), context)
        }
        // If not found and number starts with "+", try replacing with "00"
        if (resolvedName == null && cleanNumber.startsWith("+") && cleanNumber.length > 4) {
            resolvedName = queryPhoneLookup("00" + cleanNumber.substring(1), context)
        }

        // Cache the lookup result (empty string if not found to avoid redundant queries)
        contactsCache[cleanNumber] = resolvedName ?: ""
        if (resolvedName != null) {
            Log.i("$TAG Resolved $cleanNumber to contact: $resolvedName")
        }

        return resolvedName
    }

    fun clearCache() {
        contactsCache.clear()
    }
}
