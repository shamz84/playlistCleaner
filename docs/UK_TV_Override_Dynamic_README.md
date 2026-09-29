# UK TV guide overrides

`uk_tv_override_dynamic.py` applies replacement rules to entries in the `🇬🇧 TV Guide (UK)` group and can insert configured additions. Matching identifies source entries by group title plus channel name; it does not depend on `tvg-id`.

The pipeline reads `data/config/uk_tv_overrides_dynamic.conf` first, then `config/uk_tv_overrides_dynamic.conf`, then a repository-root file. The pipeline writes an intermediate file at `data/filtered_playlist_with_uk_overrides.m3u` and copies the successful result over root `filtered_playlist_final.m3u`.

## Inspect and run

From the repository root:

```powershell
python .\uk_tv_override_dynamic.py --list .\filtered_playlist_final.m3u
python .\uk_tv_override_dynamic.py --find .\filtered_playlist_final.m3u "BBC One"
python .\uk_tv_override_dynamic.py .\filtered_playlist_final.m3u .\override-preview.m3u --config .\data\config\uk_tv_overrides_dynamic.conf
```

Use a separate output path when testing; do not overwrite the source until the result is reviewed. The pipeline calls the same script using its configured input/output paths.

## Config syntax

Each non-comment line can map a source channel name to a target channel name, exact group/channel identifier, direct stream URL, or custom M3U entry:

```text
# Look up this target channel name anywhere in the input playlist
BBC One = BBC One London

# Exact target identifier: group-title||channel-name
BBC Two = UK| BBC IPLAYER ᴿᴬᵂ||BBC Two England

# Replace only the source URL with a direct stream URL
Channel 4 = STREAM:https://example.invalid/stream.m3u8

# Custom EXTINF line and URL separated by |
Channel 5 = #EXTINF:-1 tvg-name="Channel 5" group-title="🇬🇧 TV Guide (UK)",Channel 5 HD|https://example.invalid/channel5.m3u8
```

A simple channel-name target selects the first matching playlist entry; prefer `group-title||channel-name` when duplicates exist. Replacements use replacement metadata/URL but preserve the source entry's group title. A `STREAM:` target keeps the existing EXTINF line and swaps only the URL.

Additions use `[ADD:position] channel-name = EXTINF-line|URL-line`. Supported positions are `TOP`, `BOTTOM`, `AFTER:<UK source channel name>`, `BEFORE:<UK source channel name>`, and `INDEX:<zero-based index>`. Test additions against a copy; validate the resulting M3U before publishing it.
