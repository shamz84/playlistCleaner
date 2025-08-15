# Complete M3U Playlist Processing System

## 🎯 System Overview

This comprehensive system automates the complete workflow for M3U playlist processing, from download to credential replacement, with optional cloud backup.

## 🚀 Components

### 1. **Main Orchestrator** 
**File**: `process_playlist_complete.py`
- **Purpose**: Coordinates the entire pipeline
- **Steps**: Download → Filter → Credentials → Google Drive Backup
- **Usage**: `python process_playlist_complete.py [options]`

### 2. **Download Module**
**File**: `download_file.py`
- **Purpose**: Downloads playlists from remote servers
- **Method**: HTTP POST with JSON payload
- **Output**: `manual_download.m3u`

### 3. **Filter Module**
**File**: `filter_comprehensive.py`
- **Purpose**: Filters and combines multiple playlist sources
- **Sources**: 
  - `raw_playlist_20.m3u` (main playlist)
  - `raw_playlist_AsiaUk.m3u` (always included)
  - `manual_download.m3u` (downloaded playlist)
- **Configuration**: `group_titles_with_flags.json`
- **Output**: `filtered_playlist_final.m3u`

### 4. **Credential Replacement**
**File**: `replace_credentials_multi.py`
- **Purpose**: Generates personalized playlists for multiple users
- **Configuration**: `credentials.json` (array format)
- **Output**: `8k_[username].m3u` files

### 5. **Google Drive Backup** (Optional)
**File**: `upload_to_gdrive.py`
- **Purpose**: Backs up generated files to Google Drive
- **Authentication**: OAuth 2.0
- **Configuration**: `gdrive_config.json`

## 📋 Usage Examples

### Complete Pipeline
```bash
# Full workflow (all steps)
python process_playlist_complete.py

# Skip download (use existing files)
python process_playlist_complete.py --skip-download

# Skip Google Drive backup
python process_playlist_complete.py --skip-gdrive

# Filter and credentials only
python process_playlist_complete.py --skip-download --skip-gdrive
```

### Individual Components
```bash
# Download only
python download_file.py --direct

# Filter only
python filter_comprehensive.py

# Credentials only
python replace_credentials_multi.py

# Google Drive backup only
python upload_to_gdrive.py --backup
```

## 🔧 Configuration Files

### `credentials.json` - Multi-User Credentials
```json
[
  {
    "dns": "server1.example.com:8080",
    "username": "user1",
    "password": "password1"
  },
  {
    "dns": "server2.example.com:8080", 
    "username": "user2",
    "password": "password2"
  }
]
```

### `group_titles_with_flags.json` - Group Filtering
```json
[
  {
    "group_title": "UK | ENTERTAINMENT",
    "exclude": "false",
    "order": 1
  },
  {
    "group_title": "UK | SPORTS",
    "exclude": "false", 
    "order": 2
  }
]
```

### `gdrive_config.json` - Google Drive Settings
```json
{
  "default_folder": "PlaylistBackups",
  "auto_create_folders": true,
  "backup_files": [
    "filtered_playlist_final.m3u",
    "8k_*.m3u",
    "manual_download.m3u"
  ]
}
```

## 📊 Processing Results

### Latest Pipeline Run:
- **Source Files**: 3 playlist sources (raw_playlist_20.m3u, raw_playlist_AsiaUk.m3u, manual_download.m3u)
- **Filtered Output**: 14,356 lines, 1.9MB
- **Generated Files**: 2+ personalized playlists
- **Processing Time**: ~0.20 seconds (excluding download)

### File Sizes:
- `raw_playlist_20.m3u`: 3.5MB (26,757 lines)
- `raw_playlist_AsiaUk.m3u`: 15KB (146 lines)
- `manual_download.m3u`: 81MB (varies by download)
- `filtered_playlist_final.m3u`: 1.9MB (14,356 lines)
- `8k_[username].m3u`: 1.9-2.0MB each

## 🛠️ Setup Instructions

### 1. Basic Setup
```bash
# Install dependencies
pip install -r requirements.txt

# Ensure source files exist
# - raw_playlist_20.m3u
# - raw_playlist_AsiaUk.m3u  
# - group_titles_with_flags.json
# - credentials.json
```

### 2. Google Drive Setup (Optional)
```bash
# Check Google Drive setup status
python gdrive_setup.py --check

# Install Google Drive packages
python gdrive_setup.py --install

# Follow setup guide in GDRIVE_SETUP.md
```

### 3. First Run
```bash
# Test with existing files
python process_playlist_complete.py --skip-download --skip-gdrive

# Full pipeline
python process_playlist_complete.py
```

## 🎛️ Command Line Options

### Pipeline Options
- `--skip-download`: Skip downloading, use existing files
- `--skip-filter`: Skip filtering, use existing filtered file  
- `--skip-credentials`: Skip credential replacement
- `--skip-gdrive`: Skip Google Drive backup

### Individual Script Options
- `download_file.py --direct`: Use hardcoded download parameters
- `upload_to_gdrive.py --backup`: Backup all configured files
- `upload_to_gdrive.py --setup`: Setup Google Drive authentication

## 🔍 Troubleshooting

### Common Issues:
1. **Missing Files**: Ensure all source files exist in the directory
2. **Download Failures**: Check internet connection and server accessibility  
3. **Filter Errors**: Verify group_titles_with_flags.json format
4. **Credential Issues**: Check credentials.json array format
5. **Google Drive**: Run `python gdrive_setup.py --check` for diagnosis

### Error Recovery:
- Each step can be run independently
- Use `--skip-*` options to bypass completed steps
- Check generated files before proceeding to next step

## 📈 Performance

- **Filter Step**: ~1000 entries/second progress reporting
- **Credential Replacement**: Processes 7000+ URLs in seconds
- **Download**: Dependent on server speed and file size
- **Google Drive Upload**: Includes progress tracking and resumable uploads

## 🎉 Success Indicators

The pipeline is working correctly when you see:
```
🎉 ALL STEPS COMPLETED SUCCESSFULLY!
📁 Generated files:
   - 8k_user1.m3u (1,901,337 bytes)
   - 8k_user2.m3u (2,016,185 bytes)
   - filtered_playlist_final.m3u (1,858,269 bytes, 14,356 lines)
```

This indicates all processing steps completed and personalized playlists are ready for use!
