# Playlist processing system

This repository processes IPTV M3U playlists: it can generate Xtream API playlists, download a source playlist, merge categorized 24/7 groups, filter/order groups, apply UK TV overrides, create personalized playlists, and optionally upload results to Google Drive.

## Current entry point

Run from the repository root. The current orchestrator is [process_playlist_complete_enhanced.py](../process_playlist_complete_enhanced.py). Older documents may mention `process_playlist_complete.py`, `filter_comprehensive.py`, root-level configs, or `manual_download.m3u`; those describe older paths and are not the current workflow.

Install requirements once:

```powershell
python -m pip install -r requirements.txt
```

Full host pipeline, skipping only Google Drive upload:

```powershell
python .\process_playlist_complete_enhanced.py --skip-gdrive
```

Reprocess the existing downloaded playlist without API generation or downloading:

```powershell
python .\process_playlist_complete_enhanced.py --skip-api --skip-download --skip-gdrive
```

The second command still runs merge/filter, UK override, and credential generation unless those steps are separately skipped. Review the side effects below before running it.

## Pipeline stages and paths

| Stage | Main input/config | Result |
|---|---|---|
| Xtream API conversion | `data/config/xtream_api_config.json`, root `tv_logos.json` | `data/xstream_api_*.m3u` |
| Download | `data/config/download_config.json` | Configured output; current pipeline expects `data/downloaded_file.m3u` |
| 24/7 merge and filter | `data/downloaded_file.m3u`, root `247_channels_*.m3u`, `data/config/group_titles_with_flags.json` | May update the downloaded playlist; writes root `filtered_playlist_final.m3u` |
| UK TV override | `data/config/uk_tv_overrides_dynamic.conf` | Intermediate `data/filtered_playlist_with_uk_overrides.m3u`; successful result is copied over the root filtered playlist |
| Credential replacement | `data/config/credentials.json` | Root `8k_<username>.m3u` files |
| Optional Drive upload | Google Drive config/auth files | Uploads configured matching files |

`data/config/` is the current runtime config location. The orchestrator searches there first for group config, credentials, and UK override config, then falls back to the repository root. Keep secrets in local config files; do not paste their contents into chat or commit them.

## Skip options

The host orchestrator supports `--skip-api`, `--skip-download`, `--skip-filter`, `--skip-uk-override`, `--skip-credentials`, and `--skip-gdrive`. Use `--help` only after checking source; this script currently parses known flags by checking `sys.argv` and may not provide standard argparse help.

Skipping download avoids the configured network download, but API generation is a separate network stage. To avoid both, use `--skip-api --skip-download` and ensure `data/downloaded_file.m3u` already exists.

## Data safety

- A full run can make API and download network requests.
- `merge_247_channels.py` can rewrite `data/downloaded_file.m3u`; it creates a backup before merging. Do not run the pipeline just to inspect configuration.
- Filtering writes/overwrites `filtered_playlist_final.m3u` and may write a proposed `data/config/group_titles_with_flags_updated.json`. Review proposed group changes before promoting them.
- UK overrides update the filtered playlist used by credential replacement.
- Credential replacement writes personalized playlists in the repository root. These outputs and source playlists may contain private URLs or credentials.
- Keep credentials, tokens, playlists, and backups out of commits unless explicitly requested. `.gitignore` covers common local secret/output files, but verify `git status` before staging.

## Validation

Validate a JSON edit without running the pipeline:

```powershell
python -m json.tool .\data\config\group_titles_with_flags.json
```

There is no maintained automated test suite. `verify_results.py` contains historical hard-coded expectations and is not a general test gate; inspect it before use. For Docker/container details, see [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md). For individual operations, use the component guides linked from [the documentation index](README.md).
