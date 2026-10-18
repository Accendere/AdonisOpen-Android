/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
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

data class ContactInfo(
    val name: String,
    val photoUri: String? = null
)

object ContactsHelper {
    private const val TAG = "[ContactsHelper]"
    private const val MAX_CACHE_SIZE = 128

    // Cache of phoneNumber -> ContactInfo (empty name indicates "not found")
    private val contactsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ContactInfo>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContactInfo>?): Boolean {
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

    private fun queryPhoneLookup(number: String, context: Context): ContactInfo? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
            )
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val photoIdx = it.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
                    if (nameIdx != -1) {
                        val name = it.getString(nameIdx)?.trim()
                        val photoUri = if (photoIdx != -1) it.getString(photoIdx)?.trim() else null
                        if (!name.isNullOrEmpty()) {
                            return ContactInfo(name, photoUri)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("$TAG Failed to query PhoneLookup for $number: ${e.message}")
        }
        return null
    }

    fun findContactByNumber(
        phoneNumber: String?,
        context: Context = LinhomeApplication.instance
    ): ContactInfo? {
        if (phoneNumber.isNullOrBlank()) return null
        val cleanNumber = phoneNumber.trim()

        if (!hasContactsPermission(context)) {
            return null
        }

        contactsCache[cleanNumber]?.let { cached ->
            return if (cached.name.isEmpty()) null else cached
        }

        var resolved = queryPhoneLookup(cleanNumber, context)

        if (resolved == null && cleanNumber.startsWith("00") && cleanNumber.length > 4) {
            resolved = queryPhoneLookup("+" + cleanNumber.substring(2), context)
        }
        if (resolved == null && cleanNumber.startsWith("+") && cleanNumber.length > 4) {
            resolved = queryPhoneLookup("00" + cleanNumber.substring(1), context)
        }

        contactsCache[cleanNumber] = resolved ?: ContactInfo("")
        if (resolved != null) {
            Log.i("$TAG Resolved $cleanNumber to contact: ${resolved.name}")
        }

        return resolved
    }

    fun findContactNameByNumber(
        phoneNumber: String?,
        context: Context = LinhomeApplication.instance
    ): String? {
        return findContactByNumber(phoneNumber, context)?.name
    }

    fun findContactPhotoUriByNumber(
        phoneNumber: String?,
        context: Context = LinhomeApplication.instance
    ): String? {
        return findContactByNumber(phoneNumber, context)?.photoUri
    }

    fun clearCache() {
        contactsCache.clear()
    }
}
