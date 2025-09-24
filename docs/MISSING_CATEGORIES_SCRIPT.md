# Missing Categories Finder and Remover

## Overview

This script (`find_and_remove_missing_categories.py`) helps you maintain your playlist configuration by:

1. Finding categories that exist in your configuration file but are not present in the current playlist
2. Optionally removing these missing categories to keep your configuration clean

## Usage

Run the script with:

```
# Use the regular group_titles_with_flags.json (default)
python find_and_remove_missing_categories.py

# Use the updated version (group_titles_with_flags_updated.json)
python find_and_remove_missing_categories.py --updated
```

### What the Script Does

1. Loads your configuration file (`group_titles_with_flags.json` by default, or `group_titles_with_flags_updated.json` if the `--updated` flag is used)
2. Scans the downloaded playlist file (`data/downloaded_file.m3u`) for all unique group titles
3. Compares the two to find categories in your config that don't exist in the current playlist
4. Displays a table of all missing categories with their details
5. Prompts you to choose whether to remove these missing categories
6. If you choose to remove them, creates a backup of your config file first (with timestamp)
7. Saves an updated configuration file without the missing categories

### Example Output

```
🔄 Finding categories in configuration but missing from playlist...
� Using configuration file: data/config/group_titles_with_flags.json
�📋 Loaded 294 group entries from data/config/group_titles_with_flags.json
🔍 Found 217 unique group titles in playlist

📋 Found 77 categories in configuration but not in playlist:

Category Name                        | Channel Count | Exclude | Order
------------------------------------ | ------------- | ------- | -----
UK| SPORT ᴿᴬᵂ ⱽᴵᴾ                    | 50            | false   | 4
UK| ITV X VIP                        | 16            | false   | 6
...

Do you want to remove these missing categories from the configuration? (y/n): y
💾 Created backup: data/config/group_titles_with_flags_updated.json.backup_20250923_220452
✅ Successfully removed 77 missing categories from configuration
📋 New configuration has 217 entries
```

## When to Use This Script

- After downloading a new playlist that might have different channel groups
- When you notice configuration issues or want to clean up your config file
- As part of regular maintenance to ensure your configuration stays in sync with the available content

## Important Notes

1. The script always creates a backup before making changes (named with timestamp)
2. It will work with either the regular or updated version of your configuration file
3. Categories are considered "missing" if their exact group title isn't found in the playlist
4. The script doesn't modify any playlist files, only the configuration
5. If you accidentally remove categories you want to keep, you can restore from the backup