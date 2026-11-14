# Playlist Player (Android)

A native Android app for Fire TV (Fire OS 6/7/8) and phones that plays the playlists produced by this repository's pipeline. It loads a playlist from a shared Google Drive file ID or an M3U URL, and offers live TV with a guide and catch-up, movies and series, search, favourites, continue watching, and a parental PIN. It ships no content and suggests none.

Package: `app.playlistplayer`. minSdk 25 (Android 7.1), targetSdk 36. The optimized release still requires device testing on Fire OS 6.

## Modules

| Module | Kind | Purpose |
|---|---|---|
| `:core:model` | Kotlin JVM | Domain types |
| `:core:parsing` | Kotlin JVM | M3U, XMLTV, account extraction |
| `:core:consolidation` | Kotlin JVM | Title tokenizer, alternative versions, VOD title grouping |
| `:core:network` | Kotlin JVM | Drive, M3U URL source, Xtream client, log redaction |
| `:core:database` | Android library | Room with bundled SQLite (FTS5 search) |
| `:core:player` | Android library | Media3 playback, failover, catch-up |
| `:core:sync` | Android library | WorkManager workers, encrypted secret store |
| `:app` | Android application | Features, TV and phone UI, manual DI (`AppContainer`) |

## Build and test

Requires JDK 21 and the Android SDK. In PowerShell:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\DevTools\jdk21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd Z:\training\playlistCleaner\android
.\gradlew.bat assembleDebug test --console=plain
```

Run one module, for example `.\gradlew.bat :core:parsing:test --console=plain`. Always use the wrapper (`gradlew.bat`). Unit tests (JVM and Robolectric) are the test gate; there is no connected-device test run yet.

Optional untracked settings, read from `android/local.properties` or the environment:

| Purpose | local.properties | Environment |
|---|---|---|
| Drive API key | `drive.apiKey` | `DRIVE_API_KEY` |
| Release keystore | `release.storeFile` | `RELEASE_STORE_FILE` |
| Keystore password | `release.storePassword` | `RELEASE_STORE_PASSWORD` |
| Key alias | `release.keyAlias` | `RELEASE_KEY_ALIAS` |
| Key password | `release.keyPassword` | `RELEASE_KEY_PASSWORD` |

Never commit these values, provider hostnames, credentials, Drive file IDs or full stream URLs.

## Documentation

- [Operator setup guide](../docs/ANDROID_PLAYER_SETUP.md): sharing Drive files, keeping IDs stable, rotation.
- [Distribution and sideloading](docs/distribution.md): devices, building, installing.
- [Provider spike](docs/provider-spike.md): redacted provider behaviour the design depends on.
- [Plan](../docs/plans/2026-10-29-001-feat-android-iptv-player-plan.md): requirements and implementation units.
