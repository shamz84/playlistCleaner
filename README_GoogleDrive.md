# Google Drive Upload Setup Guide

## Prerequisites

1. **Install Required Packages**
   ```bash
   pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib
   ```

2. **Google Cloud Console Setup**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Create a new project or select an existing one
   - Enable the Google Drive API:
     - Navigate to "APIs & Services" > "Library"
     - Search for "Google Drive API"
     - Click "Enable"

3. **Create OAuth 2.0 Credentials**
   - Go to "APIs & Services" > "Credentials"
   - Click "Create Credentials" > "OAuth 2.0 Client ID"
   - Choose "Desktop application"
   - Give it a name (e.g., "Playlist Uploader")
   - Download the JSON file
   - Rename it to `gdrive_credentials.json` and place it in your project folder

## Usage

### Setup Authentication
```bash
python upload_to_gdrive.py --setup
```
This will open a browser window for Google OAuth authentication.

### Upload Single File
```bash
python upload_to_gdrive.py "filtered_playlist_final.m3u"
python upload_to_gdrive.py "8k_test.m3u" "MyPlaylists"
```

### Backup All Playlist Files
```bash
python upload_to_gdrive.py --backup
```
This will upload all files specified in `gdrive_config.json`.

### List Files in Google Drive
```bash
python upload_to_gdrive.py --list
```

## Configuration

Edit `config/gdrive_config.json` to customize (or `gdrive_config.json` for backward compatibility):

```json
{
  "default_folder": "PlaylistBackups",
  "auto_create_folders": true,
  "overwrite_existing": false,
  "backup_files": [
    "filtered_playlist_final.m3u",
    "8k_*.m3u",
    "manual_download.m3u",
    "group_titles_with_flags.json",
    "credentials.json"
  ]
}
```

## Integration with Pipeline

You can add Google Drive backup to your processing pipeline by calling:

```python
# Add to process_playlist_complete.py
import subprocess

def backup_to_gdrive():
    """Backup generated files to Google Drive"""
    try:
        result = subprocess.run(["python", "upload_to_gdrive.py", "--backup"], 
                              capture_output=True, text=True)
        return result.returncode == 0
    except Exception as e:
        print(f"❌ Backup failed: {e}")
        return False
```

## Troubleshooting

### Authentication Issues
- Delete `gdrive_token.json` and run setup again
- Ensure `gdrive_credentials.json` is valid
- Check Google Cloud Console project settings

### Upload Failures
- Check internet connection
- Verify file exists and isn't locked
- Check Google Drive storage quota

### Permission Errors
- Ensure OAuth scope includes drive.file
- Re-authenticate if needed
