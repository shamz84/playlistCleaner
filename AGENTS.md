# Repository Agent Context

## Purpose and layout

This repository downloads and converts IPTV M3U sources, filters and orders channel groups, applies UK TV overrides, creates per-user playlists, and optionally uploads outputs to Google Drive.

Run Python commands from the repository root (`Z:\training\playlistCleaner`); scripts mostly use relative paths. Current runtime configuration is under `data/config/`, inputs and intermediate files under `data/`, and generated filtered/personalized playlists at the repository root. Do not assume old root-level config paths documented in older READMEs are current.

## Canonical pipeline

Use `process_playlist_complete_enhanced.py` for the current pipeline. Its stages are Xtream API conversion, remote download, 24/7 channel merge and enhanced filtering, optional UK TV overrides, multi-user credential replacement, and optional Google Drive upload. Supporting scripts include `api_to_m3u_converter.py`, `download_file.py`, `merge_247_channels.py`, `analyze_247_channels.py`, `filter_m3u_with_auto_include.py`, `uk_tv_override_dynamic.py`, and `replace_credentials_multi.py`.

Install Python dependencies once with `python -m pip install -r requirements.txt`.

From PowerShell at the repository root:

- Full host run, skipping only Google Drive upload: `python .\process_playlist_complete_enhanced.py --skip-gdrive`
- Reprocess the existing `data/downloaded_file.m3u` without API generation, download, or Drive upload: `python .\process_playlist_complete_enhanced.py --skip-api --skip-download --skip-gdrive`
- Check a JSON config parses: `python -m json.tool .\data\config\group_titles_with_flags.json`

The full run makes network requests for API conversion and download. Pipeline stages can overwrite downloaded/intermediate/output playlists; the 24/7 merge may modify `data/downloaded_file.m3u` and creates a backup. Confirm before initiating a live run when the user only asks for analysis or editing.

## Configuration and data safety

- Main group configuration: `data/config/group_titles_with_flags.json`. `exclude` values are strings (`"false"` means include, `"true"` means exclude); `order` controls group output ordering. Keep included entries before excluded entries when sorting, preserve intended group ordering, and renumber only when requested.
- Download and Xtream API settings: `data/config/download_config.json` and `data/config/xtream_api_config.json`.
- Credential, Google Drive, and UK override configuration is also under `data/config/`.
- Treat credentials and token files as secrets. Never print, stage, or commit tokens/passwords. Do not add generated M3U files, backups, or local auth/config data to commits unless explicitly requested.
- `filter_m3u_with_auto_include.py` may identify unknown groups and write a proposed updated group config. Review changes before replacing the canonical config.

## Docker and validation

Docker is the deployment target. Build from the repository root with `docker build -t playlist-processor:latest .`. The current host Python command is the reliable run path; Docker direct-run guidance and known limitations are in `docs/DOCKER_DEPLOYMENT.md`. Checked-in Compose examples still contain mounts that do not match this checkout's `data/config/` layout, the entrypoint does not expose `--skip-api`, and its skipped-download preflight checks the wrong location. Review mounts and preflight behavior before any container run. QNAP-specific deployment is not maintained.

The Drive uploader and setup checker search `data/config/` first for OAuth credentials and backup config, with legacy `config/` and repository-root fallbacks; token lookup also checks the current config directory. The container entrypoint creates compatibility links for mounted configs. Keep Drive upload disabled unless the intended auth/config files and upload patterns have been verified.

There is no established automated test suite. Validate config edits as JSON and use focused script checks or a user-approved pipeline run. `verify_results.py` is a historical diagnostic with hard-coded expectations; do not treat it as a general test gate without checking that those expectations match current configuration.

## Documentation and conventions

Project documentation is under `docs/`; start with `docs/README.md` and the current host runbook at `docs/README_Complete_System.md`. `AGENTS.md` stays at the repository root so agents discover these instructions automatically. Historical migrations and test snapshots are under `docs/archive/` and are not current run instructions. Prefer the enhanced orchestrator and inspect current code/config when docs conflict. Keep changes focused, preserve UTF-8 group names/emojis, and avoid editing generated playlists unless requested.
