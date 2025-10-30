# UK TV Guide Override System - Dynamic Version

## Overview

This is the enhanced dynamic version of the UK TV Guide override system that uses **group-title + channel name** as identifiers instead of fragile tvg-id values. This makes the system much more robust when tvg-id values change in playlists.

## Key Features

- **Dynamic Identification**: Uses `group-title="🇬🇧 TV Guide (UK)"` + channel name as the identifier
- **Robust Matching**: Won't break if tvg-id values change in future playlist updates  
- **Complete Entry Replacement**: Replaces entire EXTINF line + URL with replacement entries
- **Flexible Replacement Search**: Can find replacements by channel name across all groups
- **Multiple Group Support**: Find replacements in BBC iPlayer, General HD, or any other group

## Files

- `uk_tv_override_dynamic.py` - Main processing script
- `uk_tv_overrides_dynamic.conf` - Configuration file  
- `uk_tv_override_dynamic.bat` - Windows batch helper
- `test_uk_override.py` - Test script

## Configuration Format

The configuration file uses a simple format:

```
# Comments start with #
source_channel_name = replacement_channel_name_or_identifier

# Simple channel name matching (searches all groups)
BBC One = BBC One London
BBC Two = BBC Two England
ITV 1 = ITV 1 London

# Exact group+channel matching (more precise)  
BBC One = UK| BBC IPLAYER ᴿᴬᵂ||BBC One London
BBC Two = UK| BBC IPLAYER ᴿᴬᵂ||BBC Two England
```

## Usage

### 1. List UK TV Guide Entries
```bash
python uk_tv_override_dynamic.py --list playlist.m3u
```

### 2. Find Replacement Channels
```bash
python uk_tv_override_dynamic.py --find playlist.m3u "BBC One"
python uk_tv_override_dynamic.py --find playlist.m3u "Channel 4"
```

### 3. Process Playlist with Overrides
```bash
python uk_tv_override_dynamic.py input.m3u output.m3u
```

## How It Works

1. **Source Identification**: The script identifies UK TV Guide entries by:
   - `group-title="🇬🇧 TV Guide (UK)"`
   - Channel name (text after the last comma in EXTINF)

2. **Configuration Loading**: Reads the configuration file and creates mappings:
   - `BBC One` → `BBC One London`
   - Creates identifier: `🇬🇧 TV Guide (UK)||BBC One`

3. **Playlist Indexing**: Builds an index of ALL playlist entries by group+channel combination

4. **Replacement Search**: For each configured replacement:
   - If just channel name given: searches all groups for matching channel
   - If `group||channel` format: looks for exact match

5. **Complete Replacement**: Replaces entire EXTINF line + URL with the replacement entry

## Examples

### Example 1: Basic BBC iPlayer Replacement
Configuration:
```
BBC One = BBC One London
BBC Two = BBC Two England
```

This replaces:
- `🇬🇧 TV Guide (UK)||BBC One` → first "BBC One London" found in any group
- `🇬🇧 TV Guide (UK)||BBC Two` → first "BBC Two England" found in any group

### Example 2: Precise Group Targeting
Configuration:
```
BBC One = UK| BBC IPLAYER ᴿᴬᵂ||BBC One London
BBC Two = UK| BBC IPLAYER ᴿᴬᵂ||BBC Two England
```

This ensures replacements come specifically from the BBC iPlayer group.

### Example 3: Mixed Sources
Configuration:
```
BBC One = BBC One London                           # BBC iPlayer version
BBC Two = BBC Two England                          # BBC iPlayer version  
ITV 1 = UK| GENERAL ᴴᴰ/ᴿᴬᵂ||ITV 1 London          # General HD version
Channel 4 = UK| GENERAL ᴴᴰ/ᴿᴬᵂ||Channel 4 London   # General HD version
```

## Benefits Over Original System

1. **Robust**: Won't break if tvg-id values change (e.g., `bbconeyorks.uk` → `bbcone.uk`)
2. **Intuitive**: Uses human-readable channel names instead of cryptic IDs
3. **Flexible**: Can target specific groups or search across all groups
4. **Maintainable**: Easy to understand and modify configurations
5. **Future-proof**: Works regardless of tvg-id changes in playlist updates

## Testing

Use the test script to verify functionality:
```bash
python test_uk_override.py
```

This runs a small test replacing BBC One and BBC Two to verify the system works correctly.

## Troubleshooting

### No replacements found
- Use `--find` command to verify replacement channels exist
- Check exact spelling of channel names in configuration
- Verify the replacement group exists in the playlist

### Replacement not working
- Use `--list` command to see exact channel names in UK TV Guide
- Check configuration file syntax (source_channel = replacement_channel)
- Ensure UK TV Guide entries exist with group-title="🇬🇧 TV Guide (UK)"

### Finding the right replacement
- Use `--find playlist.m3u "partial_name"` to search for candidates
- Look for channels in "UK| BBC IPLAYER ᴿᴬᵂ" or "UK| GENERAL ᴴᴰ/ᴿᴬᵂ" groups
- Use the full "group||channel" format for precise targeting