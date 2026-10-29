# Provider validation spike (U1)

Measured on 2026-10-29 at about 21:55–22:30 UTC against the real Xtream account behind the user's personalised playlist at the repo root. The calls were read-only and sequential. The stream probes read 50–300 KB each and then closed, and no two streams were open at the same time. Hostnames, credentials and full URLs are deliberately left out of this document and of every fixture.

Raw captures and the measurement scripts stay outside the repo, in the session scratchpad.

## Summary of design implications

| Topic | Finding | Decision for later units |
|---|---|---|
| VOD sync (U4) | `get_vod_streams` returns 45,265 items. That is 18.1 MB of JSON, or 2.5 MB on the wire (gzip), served in about 1.4 s. One category is about 20 KB. | Sync the full catalog eagerly with one call per type, but stream-decode it (kotlinx `decodeToSequence` over an okio source) with batched Room inserts. Never build the full string or list in heap. Keep per-category sync only as a fallback when a full call fails or times out. |
| tmdb coverage (U5) | The list field is `tmdb`, not `tmdb_id`. Its type is mixed: string for 36,937 items and int for 8,328. 87.5% of items have a non-empty, non-zero value. 6,915 tmdb values are shared by more than one item, up to 14 items each. `get_vod_info` does not fill in a missing tmdb (`info.tmdb_id` is `""`). | Key on `tmdb` normalised to a string, with title + year as the fallback for the 12.5% without it. Do not call `get_vod_info` to backfill tmdb. Guard against tmdb collisions: sports items (`SOC - Arsenal - Liverpool 04.02.2024`) carry the tmdb of an unrelated film (`Arsenal (2017)`). Treat a tmdb match as invalid when the parsed years differ, or when either item has no year and the normalised titles differ. |
| Live format (U6) | `allowed_output_formats = ["m3u8","ts","rtmp"]`. HLS segments are 8–12 s with `TARGETDURATION` 12–13 and a 6-segment window of about 60 s. | Default to `.ts`, as planned. HLS stays opt-in. At the default 3×target-duration live offset it would sit about 36 s behind live. |
| Connection limit (U6) | `max_connections = "1"` (string). A new live request succeeded 0 s, 2 s, 5 s and 10 s after closing the previous one, and `active_cons` was back to `"0"`. No connection-limit response was observed. | No fixed release delay is needed. Keep the bounded ConnectionLimit retry (for example 3 attempts over about 3 s) for real-player socket lifetimes. Always close the data source before opening the next stream. The status code the server uses for connection limit is still unknown, so classify 401/403/509 through `user_info` as planned. |
| Catch-up URL and timezone (U8) | Path style and `timeshift.php` both work. The server reads `start` in `server_info.timezone` (`Europe/Amsterdam`), not UTC (evidence in §10). | Default to `PathStrategy`. Convert the UTC programme start to `server_info.timezone` with java.time (DST-aware), and keep the user ±hours offset. |
| Catch-up seeking (U8) | Timeshift responses have a `Content-Length` (about 1.17 GB for 65 min), and `Range: bytes=N-` returns `206` with a correct `Content-Range` on both styles. | Use native Media3 seeking (byte ranges). Rebuilding the URL with a new start is only a fallback for servers without range support. `Accept-Ranges` is non-standard (`0-<len>`, not `bytes`), so do not gate seekability on that header. |
| XMLTV past coverage (U7/U8) | `xmltv.php` goes back only about 24 h (earliest start −24.2 h) and ahead about 50 h. Archive channels keep 3 days (327 channels) or 7 days (57). | `get_simple_data_table` backfill is required for archive channels on days 2–7. It returns about 4.6 days back and 5 days ahead. Fetch it lazily per archive channel (when the guide day-jump or catch-up list opens) or in a bounded batch after the XMLTV import. |
| API EPG timestamps (U7) | In `get_short_epg` and `get_simple_data_table`, `start`/`end` are server-local wall time, but `start_timestamp`/`stop_timestamp` are that wall time encoded as if it were UTC. They are 1 h late under CET (evidence in §7). | Derive instants from `start`/`end` parsed in `server_info.timezone`. Ignore the `*_timestamp` fields, and compute `now_playing` and `has_archive` on the client. Titles and descriptions are base64 (UTF-8). |
| EPG mapping (U7) | Only 12.1% of API live streams have an `epg_channel_id`. The playlist `tvg-id` differs from the API `epg_channel_id` for most entries. A case-insensitive match on either key covers 2,116 of 15,433 playlist entries, and 276 of 457 in the UK TV Guide group. | Match on the API `epg_channel_id` (via stream id) and on the playlist `tvg-id`, both case-insensitively, then fall back to the normalised name. Expect low coverage outside the UK guide groups. Show "No guide data" rows. |
| Error codes (U6) | A non-existent live or movie id returns `405` (`text/html`, `Server: cloudflare`, no redirect). A timeshift that is out of window or in the future returns `404` from the redirect target. | Treat 404/405 as a stream error (eligible for failover), not an account error. |
| Redirects (U3/U6) | Every live, VOD and timeshift request returns a `302` to a different host. Live and VOD redirect to a tokenised `/live/play/<token>/…` path. A path-style timeshift redirect keeps `/timeshift/<u>/<p>/…` in its path. | Redactor must cover redirect targets as well as the original host: the credential path patterns, opaque `/live/play/<token>` and `/hls/<token>` segments, and any host. Never log OkHttp redirect chains unredacted. |

## 1. `player_api.php` (no action)

- `user_info`:
  - `status` is `"Active"`, and the account expires in 2027-03.
  - `max_connections` is `"1"` and `active_cons` is `"0"`. Both are strings.
  - `allowed_output_formats` is `["m3u8","ts","rtmp"]`.
  - `is_trial` is `"0"` (string) and `auth` is `1` (int).
  - Keys: `active_cons, allowed_output_formats, auth, created_at, exp_date, is_trial, max_connections, message, password, status, username`.
- `server_info`:
  - `timezone` is `"Europe/Amsterdam"`.
  - `timestamp_now` is an int, `1793310874`, which is the true UTC epoch (it matched local UTC within 1 s).
  - `time_now` is `"2026-10-29 22:54:34"`, local wall time while UTC was 21:54:34. That is +01:00, which is CET after the 2026-10-25 DST change.
  - So the server's offset is `time_now − timestamp_now` = +1 h, and it is consistent with the IANA zone. Derive offsets from `timezone` using tz rules, not from a fixed number.
  - `server_protocol` is `"http"`. `port` is `"80"`, `https_port` is `"443"` and `rtmp_port` is `"25462"`, all strings. `process` is `true`. There is also a `url` field (host, redacted).
- Numeric fields come back as strings. Parse them leniently (string or number).

## 2. Live categories and streams

- There are 297 live categories and 22,006 live streams. The response is 8.18 MB of JSON and 0.44 MB on the wire.
- Fields on every item: `num, name, stream_type ("live"), stream_id (int), stream_icon, epg_channel_id, added (string epoch), is_adult (int), category_id (string), category_ids (int list, always length 1), custom_sid (null), tv_archive (int), direct_source (""), tv_archive_duration (string)`.
- Archive:
  - 384 streams have `tv_archive=1`. Their `tv_archive_duration` is `"3"` for 327 streams and `"7"` for 57.
  - 378 of the 384 archive streams have an `epg_channel_id`.
- `epg_channel_id` is non-empty for 2,652 streams (12.1%).
- Stream ids against the playlist:
  - The playlist has 15,433 live entries with 14,706 distinct ids. All of them are `.ts` URLs on the same account.
  - 13,314 of those ids exist in `get_live_streams`. 1,392 do not. These are mostly the merged 24/7 channels (for example `Bluey`, `American Dad`, and the `## EVERY 24/7 CHANNEL ##` header).
  - Sync must tolerate playlist entries with no API row. Treat them as playable, without archive or EPG enrichment.
- Playlist `tvg-id` against API `epg_channel_id`, for matched ids:
  - 1,464 are equal.
  - 11,311 have a playlist value where the API has none.
  - 638 differ only by case.
  - 617 differ completely, because of UK overrides. For example, the API has `BBCOneOxford.uk` where the playlist has `bbconelondon.uk`.
- The playlist marks archive with `catchup-type="xc" catchup-days="3"` on 243 entries. The API says 301 playlist entries are on archive streams. In Xtream mode, use the API `tv_archive` as the authority.
- Separator rows exist in the API too (for example `#### BBC IPLAYER ᴿᴬᵂ ####`, `### UK NEWS HEVC/4K ###`).

## 3. VOD categories and streams

- There are 100 VOD categories and 45,265 movies. The response is 18.14 MB of JSON and 2.52 MB on the wire (gzip, no Content-Length), served in 1.3–1.4 s. A single category (`category_id=`) returned 42 items, 0.02 MB, in 0.1 s.
- Fields: `num, name, stream_type ("movie"), stream_id, stream_icon, rating (string), rating_5based (number), tmdb (string or int), trailer, added (string epoch), is_adult, category_id, category_ids (length 1), container_extension, custom_sid, direct_source`. There is no `year` field. 83.7% of names contain a year.
- `tmdb`:
  - 87.5% of items have a non-empty, non-zero value. There are 27,992 distinct values.
  - 6,915 values are shared by more than one item. Most-shared: Leo (2023) has 14 items, Arsenal has 11 (1 film, 1 doc and 9 football matches), and Avengers: Infinity War has 10.
- `container_extension`: mkv 32,490, mp4 12,438, m4v 188, avi 148, ts 1.
- Naming pattern: `<PREFIX> - <Title> (<Year>)`.
  - Language prefixes: `EN` 19,035, `IN` 4,364, `IN-EN`, `IN-TL`, `IN-TU`, `IN-MM`, `IN-KN`, `IN-PI`, `IN-GU`, `IN-BS`, `IN-TM`, `IN-KD`, `IN-BI`, `AS`.
  - Source/platform prefixes, which carry no language: `NF` 4,981, `SOC` 4,815, `TOP` 1,555, `AMZ`, `D+`, `UNV`, `PRMT`, `VP`, `MRVL`.
  - The quality prefix `4K-` combines with the others, for example `4K-EN`, `4K-NF`, `4K-IN`, `4K-D+`, `4K-TOP`.
  - In-title quality tags are rare: `4K` 2,002, `HEVC` 88, `MULTI` 7.
- Name quirks:
  - double spaces, as in `EN - Leo  (2023)` and `IN-TM  - Leo (2023)`
  - no prefix, as in `Leo (2023)`
  - no year, as in `TOP - Avengers: Infinity War`
  - dotted release names, as in `EN-TOP -63.Avengers.Infinity.War.2018`
  - dates in sports and wrestling names, as in `EN - WWE Raw  (30/06/2025)` and `SOC - Arsenal - PSV 20.09.2023`
- Category names also signal language and quality (`IN - HINDI`, `EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ`, `TOP MOVIES BLURAY (MULTI-SUBS)`). Prefer the category language when the name prefix is a platform code.

## 4. Series

- There are 67 series categories and 10,910 series. The response is 10.9 MB of JSON and 3.32 MB on the wire. The first call took 7.3 s and a repeat took 0.6 s, which suggests server-side caching, so allow a generous read timeout.
- Fields: `num, name, series_id (int), cover, plot, cast, director, genre, releaseDate, release_date, last_modified, rating (string), rating_5based (string here), backdrop_path (list), youtube_trailer, tmdb (string), episode_run_time, category_id, category_ids`. 93.9% have `tmdb`.
- Names look like `EN - Utopia (2013) (GB)`, with a trailing country code.
- `get_series_info` sample (`EN - Utopia`, 28.6 KB):
  - Top-level keys are `seasons`, `info` and `episodes`.
  - `episodes` is an object keyed by season-number strings (`"1"`, `"2"`), and each value is a list of episodes.
  - `seasons` has 3 entries, including season 0 with `episode_count: "4"` (string), but there is no `episodes["0"]`. Treat `episodes` as the source of truth and `seasons` as metadata only.
  - Each episode has `id` (string), `episode_num` (int), `season` (int), `title`, `container_extension` (`mkv`), `added`, `custom_sid`, `direct_source`, and `info` (`duration_secs`, `duration`, `movie_image`, `video`, `audio`, `bitrate`, `rating`, `air_date`, `crew`, `id`).

## 5. `get_vod_info`

- Two items without `tmdb` in the list (`EN - Blue Grace (2018)` and `EN - Catherine Cookson - The Mallen Girls`) returned `info.tmdb_id: ""` and an empty `releasedate`. One item with `tmdb: "26594"` returned `info.tmdb_id: "26594"` and `releasedate: "2006-03-26"`.
- So `get_vod_info` does not add tmdb ids that the list lacks.
- `info` also carries `duration_secs`, `duration`, `plot`, `cast`, `genre`, `cover_big` and `backdrop_path`. `video` was null in all three samples, so it cannot be used for quality detection before playback.
- `movie_data` carries `stream_id`, `container_extension`, `category_id(s)` and `added`.

## 6. `xmltv.php`

- The response is `200 application/xml; charset=utf-8` with `Content-Encoding: gzip`, chunked and without a Content-Length. TTFB was 0.16 s and the whole download took 3.6 s. It is 16.59 MB on the wire and 86.13 MB uncompressed.
- The gzip is transfer encoding, so OkHttp's transparent gzip handles it. Keep the planned magic-byte sniff for override sources.
- There are 8,350 `<channel>` elements but only 3,614 have programmes. There are 256,030 `<programme>` elements.
- Every programme uses `YYYYMMDDHHMMSS +0100`: server-local wall time with an explicit offset. Parse the offset per value. Expect `+0200` under CEST, but do not assume it.
- Programmes have only `start`, `stop`, `channel`, `<title>` and `<desc>`. There are no categories or episode numbers.
- The `<tv>` element's `generator-info-url` contains a hostname, which is scrubbed in the fixture.
- Time span:
  - The earliest start is now − 24.2 h, and almost every channel starts 23–25 h back. The latest stop is now + 50 h.
  - For archive channels, 308 of 384 have XMLTV data by exact id, 12 more by case-insensitive id, and 64 have none. Their median earliest start is −23.9 h.
  - So XMLTV covers only day 1 of a 3- or 7-day archive.
- Coverage of playlist entries:
  - Playlist `tvg-id`: 1,068 exact matches and 1,708 case-insensitive.
  - API `epg_channel_id`: 1,553 exact and 1,586 case-insensitive.
  - Either key, case-insensitive: 2,116 of 15,433. The UK TV Guide group gets 276 of 457 this way.
- Parse in one streaming pass with a time-window and wanted-channel filter. 86 MB of XML must never be held in memory.

## 7. `get_short_epg` and `get_simple_data_table`

Tested on archive channel `UK: BBC ONE LONDON 4K` (3-day archive) and an `IN:` channel with a 7-day archive.

- **`get_short_epg`**:
  - Always returned 4 entries; `limit=10` made no difference.
  - Fields: `id, epg_id, title (base64), lang (""), start, end, description (base64), channel_id, start_timestamp, stop_timestamp, stream_id`. All values are strings.
  - There is no `has_archive` or `now_playing`.
  - The first entry is the programme airing now. The selection is correct, but its timestamps are skewed (see the timestamp finding below).
- **`get_simple_data_table`**:
  - For the 3-day channel it returned 246 entries (103 KB). For the 7-day channel it returned 663 entries (317 KB).
  - Entries span about −110 h to +122 h by their own timestamps.
  - It adds `now_playing` (int) and `has_archive` (int) and has no `stream_id`. Titles and descriptions are base64.
  - Entries with `has_archive=1` run from −72.5 h to −1.5 h on the 3-day channel and from −110 h to −1 h on the 7-day channel. So EPG for archive days 5–7 is absent even here.
- **`get_simple_date_table`** (misspelled) is not a supported action. It returned the plain `user_info`/`server_info` object.
- **Timestamp finding.** XMLTV lists BBC One `EastEnders` as `20261029203000 +0100`, which is 19:30 UTC and matches the normal UK broadcast slot. `get_simple_data_table` lists the same programme as `start: "2026-10-29 20:30:00"` with `start_timestamp` = 20:30 UTC.
  - So the strings are Europe/Amsterdam wall time, and the `*_timestamp` fields treat that wall time as UTC, which makes them 1 h late.
  - `now_playing=1` was set on the entry whose skewed timestamps contained the current UTC time. Per XMLTV, that was the wrong programme.
  - `get_short_epg` started at the programme that was actually airing (`Not Going Out`, local 22:30–23:00, which is 21:30–22:00 UTC).
  - Rule: `instant = LocalDateTime.parse(start).atZone(server_info.timezone)`.

## 8. Live stream requests

- **`.ts`**, tested once on `UK: BBC 1 HD` and then repeated in the release test:
  - `302` to a different host (not the API host), with a tokenised `/live/play/<token>/…` path.
  - The target returns `200`, `Content-Type: video/mp2t`, `Connection: close`, `Server: Apache2`, and no Content-Length.
  - The stream starts on a TS sync byte at offset 0. The SDT names the service `FFmpeg/Service01`, so the stream is remuxed by ffmpeg.
  - Time to first byte, including the redirect, was 2.57 s on the first request and 0.42–1.13 s on later ones.
- **`.m3u8`**:
  - `302` to a different host, then `200 application/x-mpegURL` with Content-Length 536 and `Cache-Control: no-cache, no-store, must-revalidate`. TTFB was 0.24 s.
  - The playlist is `#EXT-X-VERSION:3` and `TARGETDURATION` 12–13, with 6 segments of 8–12 s each.
  - Segment URIs are relative, `/hls/<token>/<id>_<seq>.ts`.
- The API host is fronted by Cloudflare. The stream servers report `Apache2`.

## 9. Connection release

- After a `.ts` stream was closed, new requests to the same channel succeeded at 0 s, 2 s, 5 s and 10 s, all with `200` and media bytes. `active_cons` read `"0"` afterwards.
- No connection-limit error occurred, so the server's status code and body for that case are still unknown.
- Caveat: these probes closed the socket cleanly. The server also sends `Connection: close` for live. A player that keeps a socket half-open could behave differently, so this needs re-checking on device.

## 10. Catch-up

Channel `UK: BBC 1 HD` (3-day archive). Programme from XMLTV: `The Celebrity Traitors`, 19:00–20:05 UTC on 2026-10-29, which had ended 1.9 h before the test. Duration is 65 min. The start is `2026-10-29:20-00` in Europe/Amsterdam and `2026-10-29:19-00` in UTC.

| Variant | Status | Content-Length | Range `bytes=1000000-` |
|---|---|---|---|
| `/timeshift/u/p/65/2026-10-29:20-00/{id}.ts` (server tz) | 302 to other host, then 200 `video/mp2t` | 1,169,567,744 | 206, `Content-Range: bytes 1000000-1169567743/1169567744` |
| `/timeshift/…/2026-10-29:19-00/…` (UTC) | 302, then 200 | 1,173,188,608 (different content) | not repeated |
| `timeshift.php?…&start=2026-10-29:20-00&duration=65` (server tz) | 302 to `/play/timeshift.php` on other host, then 200 | 1,169,567,744 | 206 |
| `timeshift.php` (UTC) | 302, then 200 | 1,173,188,608 | not repeated |

- For the same start, both URL styles returned the same Content-Length and the same leading bytes, so they appear to be equivalent.
- **Timezone discrimination.** Both of the start interpretations above are inside the archive window, so they cannot tell the timezones apart. Two more requests did:
  - Start `= wall(UTC now + 40 min)` with a 10-min duration. Read in Europe/Amsterdam, that start is 20 min in the past; read in UTC, it is 40 min in the future. Both styles returned 200 with 186,597,376 bytes, a full 10 minutes at the channel's bitrate.
  - Start `= wall(Amsterdam now + 30 min)`, which is in the future under both readings, returned 404.
  - **So the server interprets `start` in `server_info.timezone`.** Content-Length scales with `duration`: about 2.4–2.5 Mbps for both 65 min and 10 min.
- **Archive window.** A start 4 days ago on the 3-day channel returned 404. A start 2.5 days ago returned 200, which shows that the archive outlives the 24 h XMLTV window.
- **Range and seeking:**
  - `bytes=1000000-`, `bytes=500000000-` and `bytes=0-99999` all returned 206 with a correct `Content-Range` on both styles. Every Range request re-runs the 302 redirect.
  - Plain 200 responses also carry `Content-Range`, and `Accept-Ranges` has the non-standard form `0-<len>`.
  - The body does not start on a TS packet boundary: the first sync byte is at offset 172. Media3 `TsExtractor` resyncs, but a strict sync-at-0 sniff would misdetect it.
- **VOD** (`/movie/…/{id}.mkv`): `302` to the tokenised `/live/play/<token>` path, then `200 video/x-matroska` with a Content-Length of 3.1 GB. A Range request returned 206.
- **Not verified:** whether the programme shown on screen is correct (picture content) could not be checked from a script. U8's device check (three past programmes landing within ±1 minute) is still pending.

## 11. Error codes

- A non-existent stream id (`/live/…/999999999.ts` and `/movie/…/999999999.mkv`) returned `405 text/html` from Cloudflare, with no redirect.
- A timeshift outside the archive window or in the future returned `404 text/html` from the redirect target.
- No authentication or lockout errors were triggered on purpose.

## Plan "Deferred to Implementation" answers

- **Does `get_vod_streams` carry `tmdb`/`tmdb_id`?** Yes, as `tmdb` (string or int). 87.5% of items have a value. `get_vod_info` exposes it as `info.tmdb_id` but adds nothing new.
- **`allowed_output_formats`:** `m3u8`, `ts`, `rtmp`.
- **`server_info.timezone` and whether timeshift honours it:** `Europe/Amsterdam`, and yes, timeshift honours it. The API EPG `*_timestamp` fields do not, so derive them from the strings.
- **Sizes:**
  - `get_vod_streams`: 18.1 MB JSON, 2.5 MB gzip.
  - `get_series`: 10.9 MB JSON, 3.3 MB gzip.
  - `get_live_streams`: 8.2 MB JSON, 0.44 MB gzip.
  - `xmltv.php`: 86 MB XML, 16.6 MB gzip.
- **Range seeking on archive URLs:** supported, with 206 and a correct `Content-Range`.
- **Eager or lazy VOD sync:** eager, streamed, as covered in the summary.
- **`DefaultLoadControl` numbers, the Fire TV 1st-gen OS level, and the GCP project:** not measurable here. These still need a device or the owner.

## Fixtures written

Under `core/network/src/test/resources/fixtures/`. Hosts are replaced with `example.invalid`, the username with `user`, the password with `pass`, and IPs with `192.0.2.1`. `exp_date` and `created_at` are synthetic.

- `user_info.json`: full `player_api.php` response.
- `get_live_streams.json`: 28 items. Includes BBC/ITV archive channels (3-day), 7-day `IN:` channels, separators, Sky Sports Main Event variants, superscript names, items with and without an EPG id, and `+1` channels.
- `get_vod_streams.json`: 47 items. Includes the Leo (2023) and Avengers: Infinity War families across language, platform and 4K prefixes, the Arsenal tmdb collision with `SOC` matches, items without tmdb, int tmdb values, and the mp4/avi/m4v/ts extensions.
- `get_series.json`: 16 items. `get_series_info.json` covers Utopia: season 0 metadata plus seasons 1–2.
- `get_vod_info.json` has tmdb. `get_vod_info_no_tmdb.json` has `tmdb_id: ""`.
- `get_short_epg.json`: 4 entries, base64 titles, skewed timestamps.
- `get_simple_data_table.json`: 23 entries, trimmed. Covers past `has_archive=1`, `now_playing`, future entries, and entries older than the archive window. The skewed timestamps are kept on purpose for the U7 tests.
- `xmltv_sample.xml`: 10 channels, including one with no programmes, and 135 programmes, 81 of them in the past (back to about −24 h). It uses the provider's exact formatting (`channel="…" >`, `+0100`, `&apos;`).

Under `core/consolidation/src/test/resources/names/`:

- `live_names.txt`: 300 real playlist display names, chosen as tvg-id families. Examples: Sky Sports Main Event in `ᵁᴴᴰ ᴴᴰᴿ`, `ᴴᴰ`, `UHD 50fps`, `1080p50` and `1080p25`; the BBC One regions; `+1` channels; `ᴿᴬᵂ` and `³⁸⁴⁰ᴾ` labels; `(Backup)`; and separators.
- `vod_names.txt`: 200 VOD titles, covering duplicate families, missing-tmdb items, and quality-tagged items.
