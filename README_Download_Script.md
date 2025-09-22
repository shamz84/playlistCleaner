# Download File Script - Enhanced with Google Drive Support

This script can download files using two methods:
1. **POST Request** - Original functionality for API endpoints
2. **Google Drive** - New functionality to download files from Google Drive

## Usage Options

### 1. Interactive Mode (Recommended)
```bash
python download_file.py
```
Choose from:
- Option 1: POST request download (original)
- Option 2: Configuration file download
- Option 3: Google Drive download (interactive)

### 2. Command Line Options

#### Direct POST Request
```bash
python download_file.py --direct
```

#### Configuration File
```bash
python download_file.py --config [config_file]
```

#### Google Drive Download
```bash
# Interactive Google Drive download
python download_file.py --gdrive

# Direct Google Drive download with URL
python download_file.py --gdrive "https://drive.google.com/file/d/FILE_ID/view"

# Direct Google Drive download with file ID
python download_file.py --gdrive "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
```

## Configuration Files

### POST Request Configuration (`download_config.json`)
```json
{
  "download_type": "post_request",
  "url": "https://repo-server.site/manual",
  "headers": {
    "Content-Type": "application/json"
  },
  "data": {
    "id": "20"
  },
  "output_filename": "downloaded_file.m3u",
  "timeout": 30
}
```

### Google Drive Configuration (`data/config/gdrive_download_config.json`)

This configuration supports both single and multiple file downloads:

**Single File:**
```json
{
  "download_type": "google_drive",
  "files": [
    {
      "google_drive_file_id": "YOUR_FILE_ID_HERE",
      "output_filename": "downloaded_file.m3u",
      "description": "Main playlist file"
    }
  ],
  "timeout": 60,
  "description": "Google Drive download configuration"
}
```

**Multiple Files (just add more objects to the files array):**
```json
{
  "download_type": "google_drive",
  "files": [
    {
      "google_drive_file_id": "FILE_ID_1",
      "output_filename": "playlist_1.m3u",
      "description": "Main playlist file"
    },
    {
      "google_drive_file_id": "FILE_ID_2",
      "output_filename": "playlist_backup.m3u",
      "description": "Backup playlist file"
    }
  ],
  "timeout": 60,
  "description": "Google Drive download configuration"
}
```

## Google Drive URL Formats Supported

The script automatically extracts file IDs from these Google Drive URL formats:

1. **Share Link Format:**
   ```
   https://drive.google.com/file/d/FILE_ID/view?usp=sharing
   ```

2. **Open Format:**
   ```
   https://drive.google.com/open?id=FILE_ID
   ```

3. **Direct Download Format:**
   ```
   https://drive.google.com/uc?export=download&id=FILE_ID
   ```

4. **File ID Only:**
   ```
   FILE_ID
   ```

## Features

### Google Drive Downloads
- ✅ Automatic file ID extraction from various URL formats
- ✅ Handles large file virus scan warnings automatically
- ✅ Progress indication for large downloads
- ✅ Streaming download for memory efficiency
- ✅ Automatic file type detection and preview

### POST Request Downloads
- ✅ JSON payload support
- ✅ Custom headers
- ✅ Redirect following
- ✅ Timeout configuration
- ✅ File preview for text files

### General Features
- ✅ Interactive and command-line modes
- ✅ Configuration file support
- ✅ Detailed download progress
- ✅ File size and type reporting
- ✅ Error handling and reporting

## Examples

### Download a Google Drive File Interactively
```bash
python download_file.py --gdrive
# Enter URL: https://drive.google.com/file/d/1ABC123.../view?usp=sharing
# Enter filename: my_playlist.m3u
```

### Download with Configuration File
```bash
# Edit data/config/gdrive_download_config.json with your Google Drive file(s)
python download_file.py --config data/config/gdrive_download_config.json
```

### Quick Google Drive Download
```bash
python download_file.py --gdrive "https://drive.google.com/file/d/1ABC123.../view"
```

## Requirements

- Python 3.6+
- `requests` library
- Internet connection

## File Permissions

For Google Drive downloads, the file must be:
- Shared with "Anyone with the link can view" 
- OR shared with your account if using OAuth (not implemented)

## Troubleshooting

### Google Drive Issues
- **"Access denied"**: File is not shared publicly
- **"File not found"**: Invalid file ID or URL
- **"Virus scan warning"**: Script handles this automatically for large files

### POST Request Issues  
- **Connection timeout**: Increase timeout in configuration
- **Invalid JSON**: Check data format in configuration
- **404 errors**: Verify URL and endpoint availability

## Output

The script provides detailed information during download:
- File size and content type
- Download progress (for large files)
- Download speed and time
- File preview (for text files)
- Success/error status

Files are saved to the current directory unless specified otherwise in the configuration.