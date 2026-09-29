# Docker deployment

The Dockerfile builds the Python pipeline image. The container entrypoint runs `process_playlist_complete_enhanced.py`, after checking runtime config and input files. The host Python workflow in [README_Complete_System.md](README_Complete_System.md) is the reliable path; container/Compose setup has known mismatches described below.

## Build

From the repository root:

```powershell
docker build -t playlist-processor:latest .
```

## Direct run with the current data/config directory

The runtime config and data are under `data/`. A direct run can mount that directory and skip Google Drive upload:

```powershell
docker run --rm `
  --mount "type=bind,source=$($PWD.Path)\data,target=/app/data" `
  -e SKIP_GDRIVE=--skip-gdrive `
  playlist-processor:latest
```

To skip Xtream API generation while still downloading the configured playlist, add `-e SKIP_API=--skip-api`. To skip both network input stages and process an existing data file, set both `SKIP_API=--skip-api` and `SKIP_DOWNLOAD=--skip-download`; the file must be available under the mounted `/app/data/` directory.

The full run may call the configured Xtream API and remote download service, update `data/downloaded_file.m3u` during the 24/7 merge, and produce playlists in the mounted data directory after the entrypoint copies outputs. It requires valid `data/config/` settings, including credentials if credential replacement is enabled. Do not run it as a harmless container smoke test.

## Current limitations — read before operating

The checked-in Compose files and some older deployment guides are not validated against this checkout:

- `docker-compose.yml` mounts `./config` rather than the present `./data/config`, mounts a root Google Drive token, and references a root Asia playlist. Those paths may not exist.
- When download is skipped and filtering runs, preflight now checks `/app/data/downloaded_file.m3u` or the optional `/app/data/raw_playlist_AsiaUk.m3u`, matching the enhanced pipeline inputs.
- `SKIP_API=--skip-api` is now passed through the entrypoint and declared in the Compose services, so Xtream API generation can be skipped without also skipping the download stage.
- Google Drive uploader config/credential lookup differs between native and container execution. The default is to skip upload. Do not enable Drive upload in a container until auth/config paths have been verified for the specific setup.
- `restart: unless-stopped` in Compose is service-style behavior and can rerun this one-shot processing job; do not use that policy for an unattended run without changing and reviewing it.

The direct command above documents intended mounts and flags; it has not been certified as a production deployment. Validate with a controlled copy of the data/config and inspect outputs before using production playlists. The container receives credentials through the mounted `data/` tree; protect that path and do not publish it.

## Container skip variables

The entrypoint passes `SKIP_API`, `SKIP_DOWNLOAD`, `SKIP_FILTER`, `SKIP_UK_OVERRIDE`, `SKIP_CREDENTIALS`, and `SKIP_GDRIVE` to the orchestrator. Set each variable to its exact corresponding flag, e.g. `SKIP_API=--skip-api` or `SKIP_GDRIVE=--skip-gdrive`; leave it empty to run that stage.

## Scope

This project uses a general Docker deployment. QNAP-specific instructions were removed; use the general Docker guide and validate it against your Docker host rather than assuming NAS-specific behavior.
