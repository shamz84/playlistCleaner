# Android player handoff

Updated: 2026-11-05

## Where things are

- Repo: `Z:\training\playlistCleaner`, branch `feat/android-player` (based on `main`, not pushed). Android project in `android/`. Latest commit: `6c5a1c2`.
- Plan (source of truth for scope): `docs/plans/2026-10-29-001-feat-android-iptv-player-plan.md`. Units U1–U23 are defined; all are built except U19.
- Agent brief (environment, versions, module layout, every merged API, provider facts, open follow-ups): `android/docs/agent-build-brief.md`. Give it to any agent continuing the work.
- Provider measurements: `android/docs/provider-spike.md`. Distribution and devices: `android/docs/distribution.md`. Operator guide: `docs/ANDROID_PLAYER_SETUP.md`.

## Toolchain (user-scoped, already installed)

- JDK 21: `%LOCALAPPDATA%\DevTools\jdk21`. Android SDK: `%LOCALAPPDATA%\Android\Sdk`. adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- Build (PowerShell, from `android\`):
  `$env:JAVA_HOME="$env:LOCALAPPDATA\DevTools\jdk21"; $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"; .\gradlew.bat test assembleDebug lintDebug --console=plain --continue *> build.log`
  Check `$LASTEXITCODE` and grep the log for ` FAILED$`; with `-q` piped through `Select-String` a failure can be missed.
- Debug APK: `android\app\build\outputs\apk\debug\app-debug.apk` (56.5 MB, built 2026-11-02 with everything up to `6c5a1c2`). Install: `adb connect <fire-tv-ip>:5555` then `adb install -r <apk>`.
- `android/local.properties` (git-ignored) holds `sdk.dir` and `tmdb.apiKey`. No Drive API key is set, so Drive downloads use the public fallback URL.

## Done (committed on the branch)

- Phases 1–3 (U1–U15): playlist download from Drive or M3U URL, multiple playlists, Xtream catalog, EPG with catch-up backfill, Media3 player with failover, live/guide/catch-up/VOD screens on TV and phone, search, favourites, continue watching, settings, fail-closed parental PIN, docs.
- minSdk is 28 (Android 9, Fire OS 7/8). Fire OS 6 support was built then reverted (`a9056d3`): too sluggish on the old stick.
- Phase 4:
  - U16 + U17 (`da44887`): screen kept awake during playback; Live and Guide merged into a Live TV start screen.
  - U18 (`174bd02`): TMDB weekly Trending (catalog matches only, refreshed at most daily, hidden without a key), Continue watching, full list with Recently added / A–Z / Release year, "Series" renamed "TV Shows".
- Phase 5:
  - U21 + U22 (`8b63a6c`): TiviMate-style Live TV (details and live preview on top, dense guide, slide-in groups panel), smaller TV type scale (`TvDensity`), guide rows loaded lazily, zap shows the info overlay at once with a corner buffering indicator, last-channel shortcut (KEYCODE_LAST_CHANNEL, overlay button, phone button). Back in the guide opens the groups panel; Menu opens groups; Info or long-press OK opens programme details.
  - U20 (`79a924b`): merged titles computed once per catalog sync in the background and stored in Room (DB v6: `vod_title`, `vod_title_item`, `vod_title_state`, generation swap) with precomputed sort ranks. Browse, Trending, Continue watching, details and search read them with indexed queries and never group on the device. Catalog sync, EPG import, title index and search index run one at a time through `BackgroundWorkGate` at background priority. Browse no longer calls `get_series_info`. Movies/TV Shows show "loading" until the first index build after install or upgrade.
  - U23 (`aaa3de8`): audio and subtitle picker on every movie and episode (TV and phone, resume and next-episode paths), labels like "English · 5.1 · Dolby Digital+", "English · Forced", "Audio 2". Settings → Playback has Audio language (default device language, then English) and Subtitles. A choice in the player applies at once and becomes the preference. Live keeps its behaviour. Not covered: CEA-608 captions embedded in MP4/MKV video.
  - Subtitles default to English and on (`6c5a1c2`). An explicit Off is stored as `off`; missing or empty reads as English, so installs of the earlier U23 build also switch to English.
- About 450 app tests plus the core module tests pass; lint has 0 errors.

## Not checked on a device yet

- U16–U18: screensaver stays off; app opens on Live TV; Trending fills from the real catalog; D-pad focus on TV sort chips; grid paging.
- U21/U22: guide scroll speed; text size at 1080p and 4K; preview keeps playing after Back from fullscreen; last frame kept while zapping; whether the remote sends KEYCODE_LAST_CHANNEL.
- U20: Fire TV stays responsive during first sync and while scrolling Movies/TV Shows; how long the first index build takes.
- U23: audio and subtitle switching on a multi-language MKV/MP4 and an HLS movie (Up/Menu opens the panel on TV); the choice carries to the next episode; live plays normally after leaving a movie.

## Next steps

1. Device-test the current APK against the list above.
2. U19: measure on the Fire TV over adb (CPU, frame stats, memory, Perfetto trace of opening Movies) and write `android/docs/performance.md`. Needs adb access. If Movies/TV Shows are still heavy, the fallback is a cleaned category list.
3. U14 remainder: buffer/startup tuning and Baseline Profiles on the Fire TV; release signing; device matrix.
4. Older device checks: D-pad focus restoration, PIN pad, 4K HEVC/HDR output, tunneling and frame-rate switching, catch-up landing within ±1 min, rotation on phone.
5. Push the branch and open a PR when ready.

## Known issues

- Timing-sensitive tests that occasionally fail in full runs and pass on rerun: `SettingsViewModelTest` (DataStore file rename on Windows, e.g. `removingAPlaylistRequiresThePinAndDeletesOnlyItsData`, `groupVisibilityTogglesAskForThePinWhenLocked`), `DefaultPlaylistOnboarderTest.xtreamPlaylistReportsChannelsThenCatalogAndGuideFromTheQueue`. A timed-out settings test can make `MobileRootSmokeTest` fail in the same run.
- A leftover `app/build/test-results/testDebugUnitTest` folder from an interrupted run can fail the next run with `NoSuchFileException ... in-progress-results-generic.bin`; delete the folder and rerun.
- Search's first query of a session can wait behind a running heavy background job.
- Gradle test runs are slow (20–30 min) when several builds run at once; run one build at a time.
- Provider streams are plain HTTP with credentials in every URL; nothing the app can fix.

## Rules

- Never print, log or commit hostnames, usernames, passwords, Drive file IDs or API keys. Personalized `8k_*.m3u` files contain credentials. Tests use `example.invalid`.
- Don't stage local data (`data/config/*` tokens and backups, generated playlists).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- The user's token budget is limited: keep agents few and reports short.
