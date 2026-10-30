# Distribution and sideloading

The app is distributed as a signed APK for sideloading. There is no store listing in v1.

## Supported devices

- Fire OS 7 and 8: Fire TV Stick 4K (2nd gen), Fire TV Stick 4K Max (1st and 2nd gen), Fire TV Cube.
- Android phones on Android 9 (API 28) or newer.

Not supported: devices running Vega OS (for example Fire TV Stick 4K Select and the newer 2026 sticks). Vega OS cannot install APKs, and v1 has no Vega build. Check Settings, My Fire TV, About on the device if unsure.

Device verification on real hardware is still pending; see the plan's U14 device matrix.

## Build

Prerequisites: JDK 21 and the Android SDK (platform 36, build-tools 36.1.0). In PowerShell:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\DevTools\jdk21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd Z:\training\playlistCleaner\android
.\gradlew.bat assembleDebug
```

`android/local.properties` holds `sdk.dir` and is untracked.

Debug APK: `app\build\outputs\apk\debug\app-debug.apk`.

Release APK: provide signing details in `android/local.properties` (untracked):

```
release.storeFile=<path to keystore, relative to android/ or absolute>
release.storePassword=<store password>
release.keyAlias=<alias>
release.keyPassword=<key password>
```

or as environment variables `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Then:

```powershell
.\gradlew.bat assembleRelease
```

Output: `app\build\outputs\apk\release\`. If any of the four values is missing the release build is unsigned and will not install. Create a keystore once with `keytool -genkeypair -v -keystore <file> -alias <alias> -keyalg RSA -keysize 4096 -validity 10000`, keep it outside the repo, and back it up. Updates must be signed with the same key.

Signing notes: the build enables v1 and v2 signing. With minSdk 28 the Android Gradle Plugin emits only v2, which every API 24+ device (including Fire OS 7/8) verifies. Never commit keystores, passwords or the Drive API key.

To embed a Drive API key, see the last section of [../../docs/ANDROID_PLAYER_SETUP.md](../../docs/ANDROID_PLAYER_SETUP.md). The key restriction uses the SHA-1 of the key that signed the APK, so register the release key's SHA-1 before shipping.

## Sideload on Fire TV

1. On the device: Settings, My Fire TV, Developer options. Turn on Install unknown apps for the installer you will use (Downloader), or turn on ADB debugging. If Developer options is hidden, open About and press the device name seven times.
2. Option A, Downloader: install the Downloader app from the Amazon Appstore, then enter the URL where you host the APK (a private link you control) and install.
3. Option B, ADB: find the device IP under Settings, My Fire TV, About, Network. Then:

   ```powershell
   adb connect <device-ip>:5555
   adb install -r app-release.apk
   ```

   Accept the debugging prompt on the TV the first time.
4. Open the app from Your Apps and Channels.

Do not host the APK at a public, searchable URL, and do not name it after content or services.

## Sideload on a phone

1. Copy the APK to the phone (USB, private link, or `adb install -r <apk>`).
2. Allow installs from the app you opened it with (Settings, Apps, Special access, Install unknown apps).
3. Open the APK and install.

## Platform risks

- Amazon install-time blocklist: Amazon may block apps it classifies as IPTV or piracy tools. The app mitigates this with neutral branding, no bundled or suggested content, and its own package name. If it is blocked on a Fire TV, use the ADB route or an Android phone.
- Google developer verification: Google has announced that, on certified Android devices, apps from unverified developers will need verification to be installed, with a staged rollout that began in some regions in 2026 and is planned to widen in 2027, and an advanced path for experienced users. Details and dates may change, so check Google's current developer-verification documentation before relying on phone sideloading. Fire OS is not an Android certified-device platform and is not affected by this.

## First run

1. Open the app. On Fire TV the side rail is used; on phones the bottom navigation.
2. Add a playlist: choose a name, then enter the Drive file ID (or a pasted share link), or an M3U URL. Your operator provides the ID; see the setup guide.
3. Wait for the first import to finish (playlist, then catalog and guide). It runs in the background.
4. Open Live and pick a channel. Optionally set a parental PIN in Settings.

## Debug playback harness

Debug builds include `DebugPlaybackActivity`, a bare player for testing a stream:

```powershell
adb shell am start -n app.playlistplayer/.DebugPlaybackActivity --es url "<stream url>"
```

Optional extras: `alts`, `live`, `tunneling`, `match_frame_rate`. Stream URLs contain account credentials. Do not paste real URLs into shared logs, tickets or chat, and clear your shell history and `adb logcat` captures afterwards. The harness is not in release builds.

## More

- [Provider spike notes](provider-spike.md)
- [Operator setup guide](../../docs/ANDROID_PLAYER_SETUP.md)
- [Android README](../README.md)
