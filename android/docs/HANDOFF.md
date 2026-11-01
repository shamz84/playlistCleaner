# Android player handoff

Updated: 2026-11-01

## Where things are

- Repo: `Z:\training\playlistCleaner`, branch `feat/android-player` (based on `main`, not pushed). Android project in `android/`.
- Plan (source of truth for scope): `docs/plans/2026-10-29-001-feat-android-iptv-player-plan.md`. Units U1–U15 are built; Phase 4 (U16–U18) was added on 2026-10-31 after the first device test.
- Agent brief (environment, versions, module layout, every merged API, provider facts, open follow-ups): `android/docs/agent-build-brief.md`. Give it to any agent continuing the work.
- Provider measurements: `android/docs/provider-spike.md`. Distribution and devices: `android/docs/distribution.md`. Operator guide: `docs/ANDROID_PLAYER_SETUP.md`.

## Toolchain (user-scoped, already installed)

- JDK 21: `%LOCALAPPDATA%\DevTools\jdk21`. Android SDK: `%LOCALAPPDATA%\Android\Sdk`. adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- Build (PowerShell, from `android\`):
  `$env:JAVA_HOME="$env:LOCALAPPDATA\DevTools\jdk21"; $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"; .\gradlew.bat assembleDebug test --console=plain -q`
- Debug APK: `android\app\build\outputs\apk\debug\app-debug.apk`. Install: `adb connect <fire-tv-ip>:5555` then `adb install -r <apk>`.
- `android/local.properties` (git-ignored) holds `sdk.dir` and `tmdb.apiKey`. No Drive API key is set, so Drive downloads use the public fallback URL.

## Done (committed on the branch)

- Phases 1–3 of the plan (U1–U15): playlist download from Drive or M3U URL, multiple playlists, Xtream catalog, EPG with catch-up backfill, Media3 player with failover, live/guide/catch-up/VOD screens on TV and phone, search, favourites, continue watching, settings, fail-closed parental PIN, docs. About 820 unit tests pass; lint has 0 errors.
- minSdk is 28 (Android 9, Fire OS 7/8). Fire OS 6 support was built then reverted (commit `a9056d3`): too sluggish on the old stick.
- First device test (Android phone and a Fire OS 7/8 Fire TV): app works.

## In progress (NOT committed) — resume here

Both agents were stopped by the spend limit before running tests.

1. U16 + U17 are done and committed (`da44887`): 398 app tests pass apart from the known flaky settings test, which passed on rerun; lint 0 errors. Needs a Fire TV check: screensaver stays off during playback; app opens on Live TV.
2. U18 is done and committed: TMDB weekly Trending (only catalog matches, refreshed at most daily, hidden without a key), Continue watching, full list with Recently added / A–Z / Release year sort, "Series" → "TV Shows". DB is v5. Tests and lint (0 errors) pass. Needs a device check: Trending fills from the real catalog, D-pad focus on TV sort chips (experimental tv-material chips), grid paging and column count, TV Shows "Next" episode for shows without cached episode lists (may trigger network calls).

## Phase 5 progress

- U21 + U22 done and committed (no device check yet): TiviMate-style Live TV (details + live preview on top, dense guide, slide-in groups panel), smaller TV type scale and `TvDensity` tokens app-wide, guide rows loaded lazily in blocks of 24, zap shows info overlay immediately with a corner buffering indicator, last-channel shortcut (KEYCODE_LAST_CHANNEL + overlay button; phone button). Behaviour change: Back in the guide opens the groups panel; Menu opens groups. Check on device: guide scroll speed, text size at 1080p/4K, preview continues after Back from fullscreen, last frame kept while zapping.
- U19 (measure), U20 (cheap VOD path), U23 (audio/subtitles) still to do.

## Phase 5 (planned 2026-11-01)

Second device test: the U18 build made the whole Fire TV sluggish. User feedback is captured in the plan as R34–R40 and units U19–U23: measure first on the Fire TV (U19), then a cheap VOD data path (U20), TiviMate-style guide layout with smaller 4K-correct text (U21, reference image tivimatenederland.com hidegroup1), no spinner on zap plus a last-channel shortcut (U22, reference tivimate.com screen_01), audio/subtitle selection on all on-demand playback (U23). Open decision: return to a cleaned category list for Movies/TV Shows, or keep one list computed in the background — decide after U19 measurements.

## Next steps after that

1. Rebuild the APK and device-test U16–U18 on Fire TV.
2. U14 remainder: buffer/startup tuning and Baseline Profiles on the Fire TV over adb; release signing; device matrix.
3. Device checks still outstanding from earlier units: D-pad focus restoration, PIN pad, 4K HEVC/HDR output, tunneling and frame-rate switching, catch-up landing within ±1 min, rotation on phone.
4. Push the branch and open a PR when ready.

## Known issues

- Two tests are timing-sensitive and occasionally fail in full runs: `SettingsViewModelTest.removingAPlaylistRequiresThePinAndDeletesOnlyItsData`, `DefaultPlaylistOnboarderTest.xtreamPlaylistReportsChannelsThenCatalogAndGuideFromTheQueue`.
- Gradle test runs are slow (20–30 min) when several agents build at once; run one build at a time.
- Provider streams are plain HTTP with credentials in every URL; nothing the app can fix.

## Rules

- Never print, log or commit hostnames, usernames, passwords, Drive file IDs or API keys. Personalized `8k_*.m3u` files contain credentials. Tests use `example.invalid`.
- Don't stage local data (`data/config/*` tokens and backups, generated playlists).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- The user's token budget is limited: keep agents few and reports short.
