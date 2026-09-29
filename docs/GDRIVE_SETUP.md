# Google Drive setup

Drive upload is optional and separate from downloading the playlist source. The current uploader's config lookup is inconsistent across the repository; see [README_GoogleDrive.md](README_GoogleDrive.md) before enabling upload.

## Dependencies and Google project

The Drive API packages are included in `requirements.txt`. If installing them separately:

```powershell
python -m pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib
```

In Google Cloud Console, enable the Google Drive API and create a Desktop OAuth client. Store the downloaded OAuth client JSON locally as `gdrive_credentials.json` either in `config/` or at the repository root; do not commit it. For a user-authorized OAuth flow, run:

```powershell
python .\upload_to_gdrive.py --setup
```

The uploader creates/updates its token file as part of authentication. Protect the token as a secret. The `gdrive_setup.py --install` and `--check` commands are package/status helpers; `--install` does not itself authenticate, and `--check` looks for some files under `config/` or at root.

## Configure backups

The uploader's `--backup` currently searches for `config/gdrive_config.json` and then root `gdrive_config.json`. A `data/config/gdrive_config.json` file alone is not found by native `upload_to_gdrive.py --backup`. A minimal safe config can specify only generated playlists, for example:

```json
{
  "default_folder": "PlaylistBackups",
  "auto_create_folders": true,
  "overwrite_existing": true,
  "backup_files": [
    "filtered_playlist_final.m3u",
    "8k_*.m3u"
  ]
}
```

Do not include source credential JSON, token files, or raw playlists containing private stream URLs. Verify config lookup and selected files before uploading.

## Useful commands

```powershell
python .\gdrive_setup.py --check
python .\upload_to_gdrive.py --list
python .\upload_to_gdrive.py --backup
```

The pipeline skips Drive upload with `--skip-gdrive`, and the Docker image defaults to that skip. Container setup has additional path caveats; see [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md).
