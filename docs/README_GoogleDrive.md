# Google Drive upload

Google Drive upload is optional. The enhanced pipeline should normally be run with `--skip-gdrive`; downloading the source playlist and uploading output to Drive are separate operations.

## Current path behavior

The current uploader has mixed lookup paths:

- OAuth credentials: `config/gdrive_credentials.json`, then repository-root `gdrive_credentials.json` (not `data/config/`).
- OAuth token: `data/config/gdrive_token.json`, then `config/gdrive_token.json`, then root `gdrive_token.json`; a root `gdrive_token_writable.json` takes precedence when present.
- Backup config for `--backup`: `config/gdrive_config.json`, then root `gdrive_config.json` (not `data/config/`).

The container entrypoint creates root-level links for configs under `/app/data/config`, which changes what the uploader can see. Native and container behavior is therefore not identical. This is a known code/config mismatch, not a turnkey setup guarantee. Do not enable upload until the files resolve to the intended local config and the upload target has been verified.

## Setup and commands

See [GDRIVE_SETUP.md](GDRIVE_SETUP.md) for the code-supported setup steps. Once configured, the uploader supports:

```powershell
python .\upload_to_gdrive.py --setup
python .\upload_to_gdrive.py --list
python .\upload_to_gdrive.py --backup
python .\upload_to_gdrive.py .\filtered_playlist_final.m3u
```

`--backup` processes patterns from the discovered `gdrive_config.json`. Avoid adding `credentials.json`, token files, config files with secrets, or raw playlists containing account URLs to upload patterns. Never paste credentials or tokens into documentation.

Google Drive API libraries are listed in `requirements.txt`. `gdrive_setup.py --check` can inspect local package/auth/config availability, but its own path checks may not match `data/config/`; treat its output as a diagnostic, not proof the uploader will find every file.
