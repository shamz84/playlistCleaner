# Download script

`download_file.py` supports the configured download used by the current pipeline and standalone download modes. Run commands from the repository root.

## Pipeline download

The enhanced orchestrator calls:

```powershell
python .\download_file.py --config data/config/download_config.json
```

The checked-in runtime config controls download type, URL/file ID, request payload, timeout, and output filename. The current pipeline expects the main source at `data/downloaded_file.m3u`; check `data/config/download_config.json` locally to ensure its output filename is correct. Do not include config values in documentation or chat because endpoints or IDs may be private.

## Standalone modes

```powershell
# Show the interactive menu
python .\download_file.py

# Use an explicit JSON config
python .\download_file.py --config data/config/download_config.json

# Run the built-in direct POST mode
python .\download_file.py --direct

# Download a publicly shared Google Drive file by URL or ID
python .\download_file.py --gdrive
python .\download_file.py --gdrive "<shared-file-url-or-id>"
```

Standalone config files can use `download_type: "post_request"` or `download_type: "google_drive"`. Google Drive file download uses a shareable file ID/URL; it is separate from the OAuth-based Google Drive uploader described in [README_GoogleDrive.md](README_GoogleDrive.md).

The configured download writes/overwrites its output file. Confirm the target path before running. The script requires `requests` from `requirements.txt` and network access.
