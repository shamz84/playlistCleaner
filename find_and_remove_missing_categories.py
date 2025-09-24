#!/usr/bin/env python3
"""
find_and_remove_missing_categories.py

This script compares group titles in the configuration file (group_titles_with_flags.json by default) 
with those in the downloaded playlist file (downloaded_file.m3u).
It identifies categories that exist in the configuration but not in the current playlist.
It also provides an option to remove these missing categories from the configuration file.

Usage:
    python find_and_remove_missing_categories.py              # Uses group_titles_with_flags.json by default
    python find_and_remove_missing_categories.py --updated    # Uses group_titles_with_flags_updated.json
"""

import json
import re
import os
import sys
import datetime
import argparse

def get_config_file_path(use_updated=False):
    """Determine which configuration file to use based on user preference."""
    base_dir = 'data/config'
    updated_file = os.path.join(base_dir, 'group_titles_with_flags_updated.json')
    regular_file = os.path.join(base_dir, 'group_titles_with_flags.json')
    
    # Use updated file if specified and it exists
    if use_updated and os.path.exists(updated_file):
        return updated_file
    # Otherwise use regular file if it exists
    elif os.path.exists(regular_file):
        return regular_file
    # Fall back to updated file if regular doesn't exist
    elif os.path.exists(updated_file):
        return updated_file
    else:
        print("❌ Error: Could not find any configuration file.")
        print(f"    Expected at: {regular_file} or {updated_file}")
        sys.exit(1)

def load_json_config(file_path):
    """Load the JSON configuration file."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
        print(f"📋 Loaded {len(config)} group entries from {file_path}")
        return config
    except Exception as e:
        print(f"❌ Error loading configuration: {str(e)}")
        sys.exit(1)

def extract_group_titles_from_playlist(playlist_path):
    """Extract all unique group titles from the m3u playlist file."""
    try:
        with open(playlist_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Extract all group-title values
        group_titles = re.findall(r'group-title="([^"]+)"', content)
        unique_group_titles = set(group_titles)
        
        print(f"🔍 Found {len(unique_group_titles)} unique group titles in playlist")
        return unique_group_titles
    except Exception as e:
        print(f"❌ Error reading playlist file: {str(e)}")
        sys.exit(1)

def find_missing_categories(config_groups, playlist_groups):
    """Find categories that exist in config but not in the playlist."""
    missing_categories = []
    
    for entry in config_groups:
        group_title = entry['group_title']
        if group_title not in playlist_groups:
            missing_categories.append(entry)
    
    return missing_categories

def create_backup(file_path):
    """Create a backup of the configuration file."""
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_path = f"{file_path}.backup_{timestamp}"
    
    try:
        with open(file_path, 'r', encoding='utf-8') as src:
            with open(backup_path, 'w', encoding='utf-8') as dst:
                dst.write(src.read())
        print(f"💾 Created backup: {backup_path}")
        return True
    except Exception as e:
        print(f"⚠️ Warning: Could not create backup: {str(e)}")
        return False

def remove_categories_from_config(config_file_path, config_data, categories_to_remove):
    """Remove specified categories from the configuration file."""
    # Create a backup first
    if not create_backup(config_file_path):
        user_input = input("Failed to create backup. Continue anyway? (y/n): ").strip().lower()
        if user_input != 'y':
            print("Operation cancelled by user.")
            return False
    
    # Create new config without the missing categories
    group_titles_to_remove = [entry['group_title'] for entry in categories_to_remove]
    new_config = [entry for entry in config_data if entry['group_title'] not in group_titles_to_remove]
    
    # Save the new config
    try:
        with open(config_file_path, 'w', encoding='utf-8') as f:
            json.dump(new_config, f, indent=2, ensure_ascii=False)
        print(f"✅ Successfully removed {len(categories_to_remove)} missing categories from configuration")
        print(f"📋 New configuration has {len(new_config)} entries")
        return True
    except Exception as e:
        print(f"❌ Error saving updated configuration: {str(e)}")
        return False

def main():
    # Parse command line arguments
    parser = argparse.ArgumentParser(description='Find and remove missing categories from configuration.')
    parser.add_argument('--updated', action='store_true', help='Use group_titles_with_flags_updated.json instead of group_titles_with_flags.json')
    args = parser.parse_args()
    
    print("🔄 Finding categories in configuration but missing from playlist...")
    
    # Determine file paths based on arguments
    config_file_path = get_config_file_path(use_updated=args.updated)
    playlist_path = 'data/downloaded_file.m3u'
    
    print(f"💼 Using configuration file: {config_file_path}")
    
    if not os.path.exists(playlist_path):
        print(f"❌ Error: Playlist file not found at {playlist_path}")
        sys.exit(1)
    
    # Load configuration and playlist data
    config_data = load_json_config(config_file_path)
    playlist_group_titles = extract_group_titles_from_playlist(playlist_path)
    
    # Find missing categories
    missing_categories = find_missing_categories(config_data, playlist_group_titles)
    
    if not missing_categories:
        print("✅ Good news! All categories in configuration exist in the playlist.")
        return
    
    # Print missing categories
    print(f"\n📋 Found {len(missing_categories)} categories in configuration but not in playlist:")
    print("\nCategory Name                        | Channel Count | Exclude | Order")
    print("------------------------------------ | ------------- | ------- | -----")
    
    for i, entry in enumerate(missing_categories, 1):
        group_title = entry['group_title']
        channel_count = entry.get('channel_count', 'N/A')
        exclude = entry.get('exclude', 'N/A')
        order = entry.get('order', 'N/A')
        
        # Truncate long group titles for display
        if len(group_title) > 35:
            display_title = group_title[:32] + "..."
        else:
            display_title = group_title
            
        print(f"{display_title:<36} | {channel_count:<13} | {exclude:<7} | {order}")
    
    # Ask if user wants to remove these categories
    user_input = input("\nDo you want to remove these missing categories from the configuration? (y/n): ").strip().lower()
    
    if user_input == 'y':
        remove_categories_from_config(config_file_path, config_data, missing_categories)
    else:
        print("Operation cancelled. No changes made to configuration.")

if __name__ == "__main__":
    main()