# Google Drive setup

Drive upload is optional and separate from downloading the playlist source. Current config lookup checks `data/config/` first, with `config/` and repository-root fallbacks; see [README_GoogleDrive.md](README_GoogleDrive.md) for the lookup order.

## Dependencies and Google project

The Drive API packages are included in `requirements.txt`. If installing them separately:

```powershell
python -m pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib
```

In Google Cloud Console, enable the Google Drive API and create a Desktop OAuth client. Store the downloaded OAuth client JSON locally as `data/config/gdrive_credentials.json`; legacy `config/` and repository-root locations remain supported. Do not commit it. For a user-authorized OAuth flow, run:

```powershell
python .\upload_to_gdrive.py --setup
```

The uploader creates/updates its token file as part of authentication. Protect the token as a secret. The `gdrive_setup.py --install` and `--check` commands are package/status helpers; `--install` does not itself authenticate, and `--check` checks `data/config/` before legacy paths.

## Configure backups

The uploader's `--backup` searches `data/config/gdrive_config.json` first, then legacy config/root locations. A minimal safe config can specify only generated playlists, for example:

```json
{
  "default_folder": "PlaylistBackups",
  "auto_create_folders": true,
  "overwrite_existing": true,
  "backup_files": []
}
```

The generated config intentionally starts with no upload patterns. Add only files you explicitly intend to upload. Personalized `8k_*.m3u` playlists contain account credentials in their stream URLs; downloaded/source playlists may also contain private URLs. Never add credential JSON, token files, or private playlists unless you have deliberately assessed the exposure and destination access controls.

## Useful commands

```powershell
python .\gdrive_setup.py --check
python .\upload_to_gdrive.py --list
python .\upload_to_gdrive.py --backup
```

The pipeline skips Drive upload with `--skip-gdrive`, and the Docker image defaults to that skip. Container setup has additional path caveats; see [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md).
