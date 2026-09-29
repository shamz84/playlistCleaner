# Google Drive upload

Google Drive upload is optional. The enhanced pipeline should normally be run with `--skip-gdrive`; downloading the source playlist and uploading output to Drive are separate operations.

## Config and credential paths

The uploader now checks the current config location first, then legacy locations:

- OAuth client credentials: `data/config/gdrive_credentials.json`, then `config/gdrive_credentials.json`, then repository-root `gdrive_credentials.json`.
- OAuth token: `data/config/gdrive_token.json`, then `config/gdrive_token.json`, then root `gdrive_token.json`.
- Writable token override: `data/config/gdrive_token_writable.json`, then root `gdrive_token_writable.json`; when present, this overrides the selected OAuth token.
- Backup config for `--backup`: `data/config/gdrive_config.json`, then `config/gdrive_config.json`, then root `gdrive_config.json`.

New backup templates are created at `data/config/gdrive_config.json`. The container entrypoint also creates compatibility links for mounted config files. These lookup changes resolve the native `data/config/` discovery gap; Docker still has separate runtime/preflight limitations described in [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md). Verify the selected local files and upload target before enabling Drive backup.

## Setup and commands

See [GDRIVE_SETUP.md](GDRIVE_SETUP.md) for the code-supported setup steps. Once configured, the uploader supports:

```powershell
python .\upload_to_gdrive.py --setup
python .\upload_to_gdrive.py --list
python .\upload_to_gdrive.py --backup
python .\upload_to_gdrive.py .\filtered_playlist_final.m3u
```

`--backup` processes patterns from the discovered `gdrive_config.json`; newly generated configs have an empty pattern list. Add only files explicitly approved for upload. Personalized `8k_*.m3u` playlists embed credentials in stream URLs, and source playlists may contain private URLs. Never add credential JSON, token files, or private playlists without deliberately assessing the exposure and destination access controls. Never paste credentials or tokens into documentation.

Google Drive API libraries are listed in `requirements.txt`. `gdrive_setup.py --check` checks `data/config/` first and retains `config/` and root fallbacks. It is a local package/file check, not proof that Google permissions or an upload will succeed.
