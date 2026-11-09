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
package org.linhome.utils

/**
 * Where a setup came from, for the import screen's header ("You opened a setup link from Home Assistant…"):
 * people who didn't expect the screen should see why it opened. Pure: unit tested.
 */
enum class ImportSource(val textKey: String) {
    HOME_ASSISTANT("import_source_home_assistant"),
    OTHER_APP("import_source_other_app"),
    SCANNED("import_source_scanned"),
    SITE_UPDATE("import_source_site_update"),
    LAN_PAIRING("import_source_pairing"),
    CLAIM_LINK("import_source_claim_link");

    companion object {
        /** The Home Assistant companion app and its variants (minimal, beta, debug builds). */
        private const val HOME_ASSISTANT_PACKAGE = "io.homeassistant.companion.android"

        /**
         * The source of a link another app opened, from `Activity.getReferrer()` (`android-app://<package>`,
         * or a web page's URL for a browser that reports one). Null for our own intents (a site-update
         * notification: the caller knows better).
         */
        fun fromReferrer(referrer: String?, ownPackage: String): ImportSource? {
            val pkg = referrer?.takeIf { it.startsWith("android-app://", ignoreCase = true) }
                ?.removePrefix("android-app://")?.substringBefore('/')?.trim()
            if (pkg == ownPackage) return null
            if (pkg != null && (pkg == HOME_ASSISTANT_PACKAGE || pkg.startsWith("$HOME_ASSISTANT_PACKAGE."))) return HOME_ASSISTANT
            return OTHER_APP
        }

        /**
         * The name shown in "Settings from <site>": the site's own name when known (LAN pairing, or a setup
         * that carries `site_name`), else the host it came from, else the SIP domain.
         */
        fun siteLabel(siteName: String?, claimHost: String?, webhookHost: String?, sipDomain: String?): String? =
            listOf(siteName, claimHost, webhookHost, sipDomain).firstOrNull { !it.isNullOrBlank() && it != "?" }?.trim()
    }
}
