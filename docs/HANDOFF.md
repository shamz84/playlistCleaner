# Work handoff

Updated: 2026-09-29

## Repository state

- Repository: `Z:\training\playlistCleaner`
- Branch: `main`, up to date with `origin/main` at handoff creation.
- Latest pushed commit at start of this follow-up: `34ac6ed` — `Refresh repository documentation and archive historical guides`.
- Google Drive path-precedence fix is committed locally as `b195e8d`; Docker entrypoint changes are currently uncommitted.
- Local untracked files remain and must not be staged accidentally: `data/config/gdrive_token_writable.json`, two group-config backups, and `data/downloaded_file.m3u.backup_before_247_merge`. Treat the token as secret and backups as local data.

## Current project context

- Canonical host pipeline: `process_playlist_complete_enhanced.py`.
- Current runtime config is under `data/config/`; the primary playlist input is `data/downloaded_file.m3u`.
- Main host run, excluding Google Drive upload: `python .\process_playlist_complete_enhanced.py --skip-gdrive`.
- Reprocess existing downloaded data without API generation/download: `python .\process_playlist_complete_enhanced.py --skip-api --skip-download --skip-gdrive`. This can still merge and overwrite the downloaded playlist, filter, apply UK overrides, and write personalized playlists; check inputs/backups first.
- Docker is the deployment target. The current host run path is better verified than the container setup. No QNAP-specific deployment is maintained.

## Work completed

- Added root `AGENTS.md` with repository purpose, run commands, data-safety rules, and known pipeline/container caveats.
- Refreshed current guides in `docs/` and added `docs/README.md` as the documentation index.
- Moved old migration reports, test snapshots, and legacy container/Drive-auth documents to `docs/archive/`, with historical-only notices.
- Removed the QNAP-specific deployment guide at the user's direction.
- Validated Markdown relative links and key script references. No pipeline run or production Docker run was performed during the docs update.

## Recommended next work

1. **Completed and committed locally:** `upload_to_gdrive.py` searches `data/config/` first for OAuth credentials, token files, writable tokens, and backup config; legacy `config/` and repository-root paths remain fallbacks. New backup config templates are created under `data/config/` with an empty upload-pattern list, avoiding accidental upload of credential-bearing personalized playlists. `gdrive_setup.py` uses the same config precedence. Commit: `b195e8d`. Focused temporary-file tests passed with no Google authentication or network calls.
2. **Implemented and statically validated; runtime test pending:** `SKIP_API` is wired through the Dockerfile, entrypoint, and Compose services. When download is skipped but filtering runs, entrypoint preflight now checks the enhanced pipeline's `data/downloaded_file.m3u` or optional `data/raw_playlist_AsiaUk.m3u`. Bash syntax after build-time CRLF normalization, flag forwarding, Compose declarations, and preflight paths passed isolated checks. No image/pipeline run was performed: the local Podman machine socket was unavailable and Docker is not installed. If runtime verification is needed, start an available container engine and use disposable data/config fixtures only.
3. Decide whether to consolidate or delete redundant current Docker/Drive guides after container limitations are resolved. The archived docs should remain clearly non-authoritative.
4. If any project code/config is changed, validate JSON and run focused tests first. Do not invoke the full pipeline unless its network and file-overwrite effects are intended.

## Validation guidance

- JSON syntax: `python -m json.tool .\data\config\group_titles_with_flags.json`
- No maintained automated test suite was identified. `verify_results.py` has historical hard-coded assumptions; inspect it before treating it as a test gate.
- Inspect `git status --short` before staging. Keep tokens, credentials, generated playlists, and backups out of commits unless explicitly requested.

Start with [the documentation index](README.md) and [the current system guide](README_Complete_System.md); `docs/archive/` contains historical material only.
