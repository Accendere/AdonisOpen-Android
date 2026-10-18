# Privacy Policy — Adonis Open Android

**Effective Date:** October 2026

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
