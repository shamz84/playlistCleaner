# Multiple Credential M3U Generator

## Overview
The `replace_credentials_multi.py` script processes M3U playlist files and creates personalized versions with different server credentials. It supports both single and multiple credential configurations.

## Features
✅ **Multiple Credential Support** - Process one playlist with multiple server configurations  
✅ **Automatic File Naming** - Creates files named `8k_{username}.m3u`  
✅ **Input Validation** - Validates JSON structure and required fields  
✅ **Progress Tracking** - Shows detailed progress for each credential set  
✅ **Error Handling** - Continues processing even if one credential set fails  

## Configuration

### Single Credential Format
```json
{
  "dns": "your-server.com:8080",
  "username": "your_username", 
  "password": "your_password"
}
```

### Multiple Credentials Format
```json
[
  {
    "dns": "server2.example.com:8080",
    "username": "test",
    "password": "p2"
  },
  {
    "dns": "server2.example.com:8080",
    "username": "john_doe", 
    "password": "secure123"
  },
  {
    "dns": "myserver.tv:80",
    "username": "premium_user",
    "password": "xyz789"
  }
]
```

## Usage

1. **Setup Configuration**
   ```bash
   # Edit credentials.json with your server details
   notepad credentials.json
   ```

2. **Run the Script**
   ```bash
   python replace_credentials_multi.py
   ```

3. **Output Files**
   - Input: `filtered_playlist_final.m3u`
   - Output: `8k_{username}.m3u` (one file per credential set)

## Example Output

```
=== M3U Credential Replacement Tool ===
🔧 Found 2 credential set(s) to process
📥 Input file: filtered_playlist_final.m3u

==================================================
🔄 Processing credential set 1/2
   DNS: eastlower.online:80
   Username: test
   Password: **
   Output: 8k_test.m3u
   📖 Reading filtered_playlist_final.m3u (14356 lines)
   ✅ Created 8k_test.m3u
   📊 Replaced credentials in 7178 URLs
   📏 Output file size: 1,901,337 bytes
   ✅ SUCCESS: 8k_test.m3u

==================================================
🔄 Processing credential set 2/2
   DNS: server2.example.com:8080
   Username: john_doe
   Password: *********
   Output: 8k_john_doe.m3u
   📖 Reading filtered_playlist_final.m3u (14356 lines)  
   ✅ Created 8k_john_doe.m3u
   📊 Replaced credentials in 7178 URLs
   📏 Output file size: 2,016,185 bytes
   ✅ SUCCESS: 8k_john_doe.m3u

==================================================
📊 PROCESSING SUMMARY
   Total credential sets: 2
   Successful: 2
   Failed: 0

🎉 Files created:
   ✅ 8k_test.m3u
   ✅ 8k_john_doe.m3u

🚀 All playlists are ready to use!
```

## Files Generated

- **`8k_test.m3u`** - Playlist with eastlower.online:80 credentials
- **`8k_john_doe.m3u`** - Playlist with server2.example.com:8080 credentials

## Technical Details

### Replacements Made
- `DNS` → `{your_dns_server}`
- `USERNAME` → `{your_username}`  
- `PASSWORD` → `{your_password}`

### Content Included
- All filtered groups from JSON configuration (89+ groups)
- Asia UK content (ASIA| UK groups)
- 7,000+ total channels across all categories

### File Structure
Each generated M3U file contains:
- Complete filtered playlist content
- Server-specific credentials in all URLs
- Proper M3U formatting for media players
- All group titles and metadata preserved

## Error Handling

The script handles various error scenarios:
- Missing credential fields
- Invalid JSON format
- File not found errors
- Partial processing failures

If any credential set fails, the script continues with remaining sets and provides a detailed summary.
