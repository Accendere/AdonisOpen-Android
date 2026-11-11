# Privacy Policy — Adonis Open Android

**Effective Date:** November 2026

Adonis Open is a free, open-source smart intercom and SIP client licensed under the GNU General Public License v3 (GPLv3).

## 1. Local Data Storage
All configuration data—including your SIP account credentials, configured door/intercom devices, call history logs, snapshots, and optional call recordings—is stored locally on your Android device inside the application's private storage.

## 2. Permissions Used
- **Microphone & Camera (`RECORD_AUDIO`, `CAMERA`)**: Used exclusively during active SIP audio/video calls to transmit audio and video to your SIP server or intercom device.
- **Contacts (`READ_CONTACTS`)**: Optional permission used locally on your device to match incoming voice call phone numbers against your address book so caller names and photos can be displayed. Your contacts are never uploaded or transmitted off your device.
- **Phone State (`READ_PHONE_STATE`)**: Used locally to detect whether a cellular GSM call is in progress when an intercom call arrives, allowing the app to answer in video-only mode without interrupting your phone call.
- **Notifications (`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`)**: Used to alert you of incoming intercom and voice calls.

## 3. Network Communication
Adonis Open connects directly to the SIP server(s) and provisioning URLs that you configure. Adonis Open Contributors do not operate a central server and do not collect, store, or sell your personal data.

If you choose to use a `linhome.org` SIP account or push gateway operated by Belledonne Communications SARL, your use of that service is subject to Belledonne Communications' privacy policy (https://www.linhome.org/privacy-policy).

**Home automation webhooks (optional, configured by you):** if you set the app up from a home automation system (for example with an `adonis://` setup link or QR code from an Adonis or Home Assistant installation), the app sends status updates to the web address contained in that configuration. These updates contain your login name on that system, an identifier for this phone, whether this phone is set to receive calls, and the events of each door call (ringing, answered, door opened, ended, and which person answered). They let the system know who can take calls and show the call history to the other residents. The address is whatever server the configuration points to, typically the home automation system of your own building or home; it is not a server operated by the Adonis Open Contributors. Without such a configuration, no webhooks are sent. Removing the account or uninstalling the app stops them.

## 4. Analytics, Crash Reporting & Third-Party Services
- **No analytics or tracking**: Adonis Open does not include any analytics, advertising, tracking, or telemetry SDKs.
- **No crash reporting**: Crash reporting services (such as Firebase Crashlytics) have been completely removed. No crash logs or usage metrics are collected or transmitted automatically.
- **Push notifications (Firebase Cloud Messaging only)**: so that a door call can wake the phone while the app is asleep, the app includes Firebase Cloud Messaging and nothing else from Firebase (no Analytics, no Crashlytics). It registers with Google for a push token, which is sent to your SIP server in the SIP registration; the SIP server (for `sip.linhome.org` accounts, the push gateway operated by Belledonne Communications) uses it to send a wake-up push when a call comes in. The push carries no call content.
- **Debug log upload (manual only)**: If you explicitly choose to upload debug logs from the Settings screen, the log file is uploaded to the file transfer server operated by Belledonne Communications (`files.linphone.org`) and a link is generated for you to share. Logs are never uploaded automatically.

## 5. Data Deletion
All data stored by the app is deleted when you uninstall it or clear its storage. Data that the app sent to your own SIP server or home automation system is held by whoever operates that system; ask its administrator to delete it.

## 6. Contact
Questions about this policy: community@probonis.org
