# Adonis Open Android

Adonis Open is an open-source Android smart intercom and video door entry client designed for seamless integration with modern home automation systems (including Home Assistant) and standard SIP door stations.

> **Note on Fork & Upstream**:  
> Adonis Open is a fork of [Linhome](https://www.linhome.org/) developed by [Belledonne Communications SARL](https://www.belledonne-communications.com/).  
> We build upon and retain the robust, interoperable Liblinphone / Linhome VoIP SDK while adding:
> - Home Assistant one-click auto-configuration (`adonis://` deep links)
> - Dual SIP address support (primary & alternative internal/external addresses)
> - Visual confirmation and vibration feedback for silent door release actions
> - Separate configurable history retention auto-clean up for intercom calls and voice calls
> - Voice call recognition with clean caller number formatting, earpiece audio routing, and optional local device contacts lookup (name & photo)
> - Android Quick Settings Tile and Home Screen Widget to easily toggle receiving calls
> - Default RFC 4733 DTMF method for door actions
> - Redesigned Adonis Open branding, UI themes, and icons

---

## License & Attribution

Copyright © 2010–2025 Belledonne Communications SARL  
Modifications Copyright © 2026 Adonis Open Contributors

Linhome is dual licensed under GNU/GPLv3 and proprietary terms. Adonis Open is licensed under the **GNU General Public License v3 (GPLv3)**. Please see the [LICENSE.txt](LICENSE.txt), [NOTICE](NOTICE), and [PRIVACY.md](PRIVACY.md) files for full details.

- **GNU General Public License v3**: https://www.gnu.org/licenses/gpl-3.0.html
- **Upstream Linhome Project**: https://www.linhome.org
- **Liblinphone Technical Documentation**: https://www.linphone.org/technical-corner/liblinphone
- **Adonis Open Source Code**:
  - Android App: https://github.com/Accendere/AdonisOpen-Android
  - Shared Themes: https://github.com/Accendere/AdonisOpen-Shared-Themes

*Linhome and Linphone are registered trademarks of Belledonne Communications SARL. Adonis Open is an independent open-source project and is not affiliated with, endorsed by, or sponsored by Belledonne Communications.*

---

## Building the App

The app depends on a single git submodule that holds shared theme resources (images, colors, vector icons, texts, and translations).

After cloning the repository, initialize and update the submodule:
```bash
git submodule update --init --recursive
```

The application automatically collects the appropriate information from the `AdonisOpen-Shared-Themes` submodule as part of the Gradle build script (`zipTheme` task).

### Command Line Build
Using Gradle:
```bash
./gradlew zipTheme assembleDebug
```
The compiled APK will be generated at:
```
./app/build/outputs/apk/debug/adonis-open-debug-1.5.0.apk
```
To install directly to a connected Android device:
```bash
./gradlew installDebug
```

### Release Signing
Release builds are signed only when signing is configured; otherwise they come out unsigned. Copy `keystore.properties.example` to `keystore.properties` and fill it in, or set the `ADONIS_KEYSTORE_FILE`, `ADONIS_KEYSTORE_PASSWORD`, `ADONIS_KEY_ALIAS` and `ADONIS_KEY_PASSWORD` environment variables. `keystore.properties` and any keystore are git-ignored: never commit them.

### Android Studio
Open the project in Android Studio, allow Gradle synchronization to complete, and run or build the `debug` or `release` variant.

---

## Features & Configuration

### Home Assistant Auto-Configuration
Adonis Open supports instant auto-configuration from Home Assistant or external setup URLs using the `adonis://` deep link scheme. A base64-encoded JSON configuration string containing SIP credentials and door station device settings can be provisioned in one step.

### Site Webhooks
A setup link can also carry the site's webhooks (`webhook_baseurl`, `webhook_user`, `webhook_token`, `use_webhook_to_poll`, `polling_interval`). The contract is `docs/features/adonis-open-webhooks.md` in the Adonis repository; the code is in `app/src/main/java/org/linhome/webhooks/`.
- **Device id**: a UUID derived once from Android's per-app device id, so a reinstall is the same phone to the site (an existing install keeps its id).
- **Which events**: the link's `webhook_events` lists what the phone sends; anything not listed is never sent. Names: `calls_paused`, `calls_resumed`, `call_ringing`, `call_preview`, `door_action`, `call_answered`, `call_declined`, `call_ended`, `call_summary`, `registration`, `poll`, `poll_foreground`. A link without the list behaves as before (`state` plus `use_webhook_to_poll`). `webhook_auth` is `bearer` (default) or `none`, for receivers people run themselves.
- **The switch** (`calls_paused` / `calls_resumed`, or the old `state`): at start, after a link is applied, and whenever it changes. Failed sends retry with backoff (10 s, 30 s, 2 min, then every 10 min) and right away when the network comes back; only the latest state is sent.
- **Calls**: each step is an event sent right away (ringing, or refused because the switch was off or the phone busy; silent preview; every action button with its DTMF code, before or during the call, whatever the door does with it; answered; declined; ended), and `call_summary` narrates the whole call when it ends. The site picks granular events, the summary, or both. `registration` says when the SIP account starts failing and when it recovers. Events wait in a queue that survives restarts (at most 200) and are sent in order.
- **Who answered**: when a call ends answered elsewhere, the app asks the site (`who_answered`) and the banner shows "answered by <name>".
- **`poll`** (or `poll_foreground`: only while the app is on screen) every `polling_interval` seconds. Every message is acknowledged in the next poll (`ack`). `config_update` brings a claim link bound to this phone: a notification opens the import screen; dismissing it tells the site.
- Only `https://` base URLs are used, redirects are never followed, and the token is kept in the app's private storage and never logged.
- A link with a SIP account replaces the webhook settings (or clears them when it has none); a devices-only link leaves them alone.
- If the site rejects the token (401), all calls stop and a banner asks the person to scan their new link.

### Claim Links (setup right after install)
A site can hand out a one-time **claim URL** (`https://<site>/.../claim/<code>`) instead of the setup data itself. The contract is the "Claim links" section of `docs/features/adonis-open-setup-link.md` in the Adonis repository; the code is in `app/src/main/java/org/linhome/claim/`.
- The app gets a claim URL from `adonis://claim?url=<https claim URL>`, or, on first launch with no account, from the clipboard (the site's "Install and set up" button copies it before opening the Play Store).
- It POSTs `{"device_id","platform"}` to the claim URL, gets back the same JSON an `adonis://configure` link carries, and shows the usual confirmation dialog naming the site. After the person applies it, it POSTs to `<claim URL>/confirm` (retried with backoff), which uses the code up.
- Only `https://` claim URLs are used, redirects are never followed, and the URL (it holds the code) is never logged; logs name the host only.

### LAN Pairing (find the site on the Wi-Fi)
With no link at all, the app can find its site on the local network. The contract is the "LAN pairing" section of `docs/features/adonis-open-setup-link.md` in the Adonis repository; the code is in `app/src/main/java/org/linhome/pairing/`.
- The site announces `_adonis-open._tcp` over mDNS with TXT `v=1`, `name` and `url` (its https pairing address). The app only ever uses that `url`, never the resolved IP, so the certificate matches; the site's public hostname must therefore resolve inside the LAN.
- The app searches once on first launch with no account (silently: it only speaks up when it found a site), and whenever the person taps **Find my site on this Wi-Fi** on the Account screen or in the assistant.
- `POST <url>/start` gives a 6-digit code, which the app shows; an administrator (or the person, in Home Assistant) approves it on the site. The app polls `poll_url` with the pairing id in the body; once approved it receives a claim URL and continues exactly as a claim link (fetch, dialog, confirm).
- The poll URL and the claim URL must be on the announced host and port, and https; anything else is refused.
- Discovery uses Android's `NsdManager` (no extra permission today). Android's upcoming local-network permission will need handling when the app targets an SDK that enforces it.

### Importing Settings
Every setup (link, claim, pairing, pushed update, scanned code) opens a full-screen import page. On top it says where the settings come from ("Settings from <site>": the site's name from LAN pairing or the setup's optional `site_name`, else its host) and why the page opened (a link from Home Assistant or another app, a scanned code, a site update, Wi-Fi pairing), and that nothing changes until Apply. Then a short summary with Apply, and "Show details and choose" listing the SIP account, each door and the webhooks (domain, authentication, polling, events), each with a checkbox. Each part is compared with what the phone has: the account says "no change", "new password" or which account it replaces; doors say new, unchanged or what changes. Only what changes is pre-selected. The code is in `app/src/main/java/org/linhome/ui/importsettings/`, `utils/ImportPlan.kt` and `utils/ImportSource.kt`.

Linhome's XML remote provisioning (`adonis://configure?url=` / `xml=`, JSON `url` / `xml`) is not supported: a link with only `url=` is not a setup.

### Scan a setup code
The scanner (`ui/assistant/scan/`, classification in `utils/SetupCode.kt`) accepts an `adonis://configure` link or its JSON (plain or base64), an `adonis://claim?url=` link, and the site's install page `https://<host>[:port]/api/public/adonis-open/open/<code>`, which it turns into the claim URL `https://<host>[:port]/api/public/adonis-open/claim/<code>` (https only). Anything else shows "This isn't an Adonis Open setup code" and keeps scanning: the account is never touched, and the scanned text is never logged.

### Navigation
A bottom bar with **Doors**, **History** and **Settings** (no side menu); the receive-calls switch stays in the toolbar.
- **Doors**: the account card on top (`ui/devices/AccountCardView.kt`): one line (`user · site`, else the SIP domain) with a status dot that opens the Account screen; "Connecting…" when registering takes more than 3 s; a red "Can't connect to <domain>: <reason>" banner with Retry once the registration failed, until it works again (`ui/account/AccountStatus.kt`, `linphonecore/RegistrationMonitor.kt`). Without an account the card offers Scan a setup code, Find my site on this Wi-Fi and Enter a SIP account manually. Below: the doors, with a "+" under the last one.
- **Settings**: cards that open and close (Calls, Appearance, History open; Advanced, Diagnostics, About closed).
- **Account screen**: the account's summary, **Edit account** (the SIP form prefilled, saved in place; an empty password keeps the current one), new settings from the site (scan, Wi-Fi) and **Forget account**.

### Quick Settings & Widgets
- **Quick Settings Tile**: Toggle "Receive Calls" on or off directly from the Android quick settings notification shade.
- **Home Screen Widget**: 1x1 toggle widget displaying live call reception state (green when active, red when paused).

### Door Release & Action Handling
- Supports RFC 4733 (default), SIP INFO, and in-band DTMF transmission for unlocking doors, gates, and turning on lights.
- In-call and pre-answer door open actions support silent operation with visual feedback banners and haptic vibration.

---

## Contributions

Contributions are welcome under the terms of the GNU General Public License v3. Please submit pull requests to [Accendere/AdonisOpen-Android](https://github.com/Accendere/AdonisOpen-Android).
