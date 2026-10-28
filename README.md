<p align="center">
  <img src="docs/banner.svg" alt="FireFrontend: A lightweight, customizable launcher for Fire TV." width="720">
</p>

# FireFrontend
An open-source, lightweight alternative home screen for Amazon Fire TV devices.

No ads, no sponsored rows, just your apps, a clock and the weather, in a clean "liquid glass" design. FireFrontend doesn't modify Fire OS and needs no root; uninstalling it puts everything back.

- **Your apps, nothing else** - every installed app, with the ability to hide the ones you don't use.
- **Ambient home** - large clock, date and live weather (rain, storms, snow, fog, sun and moon) over a sky that follows the time of day.
- **App showcase** - moving through apps tints the screen with each app's colors and shows when you last opened it.
- **Home button** - pressing Home brings you back to FireFrontend.
- **Tiny** - under 100 KB, built for 1 GB Fire TV Sticks.


## Install

You need a computer (Windows, Mac or Linux) on the same Wi-Fi network as your Fire TV. It takes about 5 minutes and you only do it once.

### 1. Turn on Developer Options and ADB debugging on your Fire TV

1. Go to **Settings > My Fire TV > About**.
2. Highlight your device name (the first line, e.g. "Fire TV Stick") and press **OK 7 times**, until it says *"You are now a developer"*.
3. Go back to **Settings > My Fire TV > Developer Options**.
4. Turn **ADB debugging** **ON**.

### 2. Find your Fire TV's IP address

Go to **Settings > My Fire TV > About > Network** and note the **IP address** (for example `192.168.1.50`).

### 3. Run the installer on your computer

**Windows** — open **PowerShell** (Start menu → type "PowerShell") and run:

```powershell
irm https://raw.githubusercontent.com/jackSeigerman/FireFrontend/main/install.ps1 | iex
```

**Mac or Linux** — open **Terminal** and run:

```bash
curl -fsSL https://raw.githubusercontent.com/jackSeigerman/FireFrontend/main/install.sh | bash
```

The installer will:

1. Download Google's official `adb` tool if you don't already have it (nothing else is installed on your computer).
2. Ask for your Fire TV's IP address.
3. Connect to your Fire TV. **Look at your TV:** when it asks *"Allow USB debugging?"*, tick **Always allow from this computer** and press **OK**.
4. Install the latest FireFrontend and turn on the Home button redirect.

### 4. Finish on the TV

- Open **Settings** in FireFrontend (top right) and enter your city or ZIP code for weather.
- Hold **OK** (or press **Menu**) on any app to **Move** it (use the arrows, then **OK** to place), **Move to front**, or hide it. **Manage apps** brings hidden apps back.

That's it. You can turn ADB debugging back off if you like — FireFrontend keeps working. To update later, run the same installer command again.

If the installer says Fire OS hasn't started the Home button service yet, restart your Fire TV (**Settings > My Fire TV > Restart**). Home still works in the meantime, just more slowly.

### About the Home button

Fire OS normally sends the Home button straight to Amazon's home screen. The installer turns on two ways for FireFrontend to take it back:

- **Accessibility service** (like Button Mapper and Projectivy). It catches the Home button before Fire OS acts on it and opens FireFrontend, so Amazon's screen shows for a second or less, if at all. If Amazon's home ever does open, it covers it with a loading skeleton of FireFrontend's home until FireFrontend is back.
- **Usage watcher**: a fallback for when the accessibility service is off. It notices Amazon's home and brings FireFrontend back, but on Fire OS 7 Amazon's screen shows for about 5 seconds first.

The accessibility service only looks at the Home button (every other button passes straight through) and at whether Amazon's home has opened. It doesn't read anything on screen. Fire OS doesn't list it in its Accessibility settings, which is why it's turned on over ADB. Some people report that while *any* accessibility service is on, Prime Video doesn't auto-hide its playback controls. To install without it, set `FIREFRONTEND_NO_ACCESSIBILITY=1` before running the installer.

### Doing it by hand

Run these from the `platform-tools` folder the installer downloaded (`%LOCALAPPDATA%\FireFrontend\platform-tools` on Windows, `~/.firefrontend/platform-tools` on Mac and Linux), or anywhere `adb` works. On Windows PowerShell, write `.\adb` instead of `adb`. Connect first, replacing the IP with your Fire TV's:

```bash
adb connect 192.168.1.50:5555
```

Turn on the accessibility Home redirect. This replaces any other accessibility services you have on; to keep them, run `adb shell settings get secure enabled_accessibility_services` first and add FireFrontend's to that list, separated by `:`.

```bash
adb shell settings put secure enabled_accessibility_services dev.firefrontend/dev.firefrontend.HomeRedirectService
```

```bash
adb shell settings put secure accessibility_enabled 1
```

Turn on the usage-watcher fallback:

```bash
adb shell appops set dev.firefrontend GET_USAGE_STATS allow
```

To turn the Home redirect off again, set the accessibility list to `null` (or to your other services) and deny usage access:

```bash
adb shell settings put secure enabled_accessibility_services null
```

```bash
adb shell appops set dev.firefrontend GET_USAGE_STATS deny
```

### Uninstall

On the TV: **Settings > Applications > Manage Installed Applications > FireFrontend > Uninstall.** Your Fire TV goes straight back to normal.

Or from your computer (connected as above), turn off the Home redirect, then remove the app:

```bash
adb shell settings put secure enabled_accessibility_services null
```

```bash
adb uninstall dev.firefrontend
```


## Device Compatibility

FireFrontend supports Android-based Fire TV devices running Fire OS 5–8. Compatibility with replacing the default home screen may vary by firmware version.

| Fire TV Stick Model | Year | Model ID | OS | Launcher Supported |
|---|---|---|---|---|
| Fire TV Stick (1st Gen) | 2014 | `AFTM` | Fire OS 5 | Yes |
| Fire TV Stick (2nd Gen) | 2016 | `AFTT` | Fire OS 5 | Yes |
| Fire TV Stick Basic Edition | 2017 | `AFTT` | Fire OS 5 | Yes |
| Fire TV Stick 4K (1st Gen) | 2018 | `AFTMM` | Fire OS 6 | Yes |
| Fire TV Stick Lite | 2020 | `AFTSS` | Fire OS 7 | Yes |
| Fire TV Stick (3rd Gen) | 2020 | `AFTSSS` | Fire OS 7 | Yes  (tested) |
| Fire TV Stick 4K Max (1st Gen) | 2021 | `AFTKA` | Fire OS 7 | Yes  (tested) |
| Fire TV Stick 4K (2nd Gen) | 2023 | `AFTKM` | Fire OS 8 | Yes |
| Fire TV Stick 4K Max (2nd Gen) | 2023 | `AFTKRT` | Fire OS 8 | Yes |
| Fire TV Stick HD | 2024 | `AFTSS` | Fire OS 7 | Yes |
| Fire TV Stick 4K Plus | 2025 | `AFTMA08C15` | Fire OS 8 | Yes |
| Fire TV Stick 4K Select | 2025 | `AFTCA002` | Vega OS 1.1 | No |
| Fire TV Stick HD | 2026 | `AFTCL001` | Vega OS 1.1 | No |
| Fire TV Stick 4K (3rd Gen) | 2026 | `AFTCR001` | Vega OS 2.0 | No |

**Note:** "Yes" indicates expected compatibility with the Android launcher APK; "tested" models have been confirmed on real hardware (Fire OS 7.7.1.x). Vega OS devices cannot run the Android APK directly.

### Find Your Device Model

Navigate to **Settings > My Fire TV > About** to identify your Fire TV model and software version.

[Official Amazon Fire TV Device Specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html)


## Privacy

FireFrontend has no accounts, ads or analytics. The only network requests are to [Open-Meteo](https://open-meteo.com) for weather (your city's coordinates, about every 30 minutes) — and only once you've set a city. When you save a location, it's looked up once with Open-Meteo, [postcodes.io](https://postcodes.io) (UK postcodes) or [OpenStreetMap](https://nominatim.openstreetmap.org) (other postcodes). App usage history used for "Last opened" stays on the device.


## Development

Requires JDK 17 and the Android SDK (`local.properties` → `sdk.dir`).

```bash
./gradlew assembleDebug
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set dev.firefrontend GET_USAGE_STATS allow
adb shell am start -n dev.firefrontend/.MainActivity
```

Preview any weather condition (WMO code) without waiting for real weather:

```bash
adb shell am start -n dev.firefrontend/.MainActivity --ei debug_weather_code 95 --ez debug_weather_day false --ei debug_weather_temp 30
```

### Releases

Release builds are signed with a key configured in `~/.gradle/gradle.properties` (never committed):

```properties
FIREFRONTEND_STORE_FILE=/path/to/firefrontend-release.jks
FIREFRONTEND_STORE_PASSWORD=...
FIREFRONTEND_KEY_ALIAS=firefrontend
FIREFRONTEND_KEY_PASSWORD=...
```

```bash
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk FireFrontend.apk
gh release create v0.1.0 FireFrontend.apk --title "FireFrontend 0.1.0" --notes "..."
```

The installers always download `releases/latest/download/FireFrontend.apk`, so the asset must be named exactly `FireFrontend.apk`. To test an installer against a local build, set `FIREFRONTEND_APK` to the APK path (and optionally `FIREFRONTEND_IP`).
