
# Google Drive Setup Instructions

## 1. Install Required Packages
Run the following command:
```
python gdrive_setup.py --install
```

## 2. Set Up Google Cloud Project
1. Go to https://console.cloud.google.com/
2. Create a new project or select existing one
3. Enable Google Drive API:
   - Navigate to "APIs & Services" > "Library"
   - Search for "Google Drive API" and click "Enable"

## 3. Create OAuth Credentials
1. Go to "APIs & Services" > "Credentials"
2. Click "Create Credentials" > "OAuth 2.0 Client ID"
3. Choose "Desktop application"
4. Name it "Playlist Uploader"
5. Download the JSON file
6. Rename to "gdrive_credentials.json" and place in project folder

## 4. First-Time Authentication
Run:
```
python upload_to_gdrive.py --setup
```

This will open a browser for Google OAuth authentication.

## 5. Test Upload
```
python upload_to_gdrive.py "filtered_playlist_final.m3u"
```

## 6. Backup All Files
```
python upload_to_gdrive.py --backup
```

## Files Created:
- gdrive_credentials.json (your OAuth credentials - place in config folder)
- gdrive_token.json (authentication token - auto-generated)
- config/gdrive_config.json (backup configuration)
