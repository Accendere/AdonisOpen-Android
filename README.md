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

### Android Studio
Open the project in Android Studio, allow Gradle synchronization to complete, and run or build the `debug` or `release` variant.

---

## Features & Configuration

### Home Assistant Auto-Configuration
Adonis Open supports instant auto-configuration from Home Assistant or external setup URLs using the `adonis://` deep link scheme. A base64-encoded JSON configuration string containing SIP credentials and door station device settings can be provisioned in one step.

### Quick Settings & Widgets
- **Quick Settings Tile**: Toggle "Receive Calls" on or off directly from the Android quick settings notification shade.
- **Home Screen Widget**: 1x1 toggle widget displaying live call reception state (green when active, red when paused).

### Door Release & Action Handling
- Supports RFC 4733 (default), SIP INFO, and in-band DTMF transmission for unlocking doors, gates, and turning on lights.
- In-call and pre-answer door open actions support silent operation with visual feedback banners and haptic vibration.

---

## Contributions

Contributions are welcome under the terms of the GNU General Public License v3. Please submit pull requests to [Accendere/AdonisOpen-Android](https://github.com/Accendere/AdonisOpen-Android).
