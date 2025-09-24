#!/usr/bin/env python3
"""
Find group titles that exist in the configuration file but are not present in the downloaded playlist.
This helps identify outdated or no longer used categories in your configuration.
"""

import json
import re
from collections import Counter
import os.path

def main():
    print("🔍 Comparing group titles in config with those in the playlist...")
    
    # Check if files exist
    config_path = 'data/config/group_titles_with_flags.json'
    playlist_path = 'data/downloaded_file.m3u'
    
    if not os.path.exists(config_path):
        print(f"❌ Error: Configuration file not found at {config_path}")
        return
        
    if not os.path.exists(playlist_path):
        print(f"❌ Error: Playlist file not found at {playlist_path}")
        return
    
    # Load group titles from configuration
    with open(config_path, 'r', encoding='utf-8') as f:
        config_data = json.load(f)
    
    config_group_titles = {entry['group_title'] for entry in config_data}
    print(f"📊 Found {len(config_group_titles)} unique group titles in configuration")
    
    # Load playlist and extract group titles
    with open(playlist_path, 'r', encoding='utf-8') as f:
        playlist_content = f.read()
    
    playlist_groups = re.findall(r'group-title="([^"]+)"', playlist_content)
    playlist_unique_groups = set(playlist_groups)
    playlist_group_counts = Counter(playlist_groups)
    
    print(f"📊 Found {len(playlist_unique_groups)} unique group titles in playlist")
    
    # Find group titles in config but not in playlist
    missing_groups = config_group_titles - playlist_unique_groups
    
    # Sort missing groups by their presence in the config (preserve config order)
    sorted_missing = [group for group in [entry['group_title'] for entry in config_data] if group in missing_groups]
    
    if not sorted_missing:
        print("✅ All group titles in configuration exist in the playlist!")
        return
        
    print(f"\n🚫 Found {len(sorted_missing)} group titles in configuration that are NOT in the playlist:")
    
    # Print missing groups with their exclude status and order from config
    for i, group_title in enumerate(sorted_missing):
        # Find the config entry for this group
        for entry in config_data:
            if entry['group_title'] == group_title:
                exclude_status = "❌ EXCLUDE" if entry['exclude'] == "true" else "✅ INCLUDE"
                order = entry.get('order', 'N/A')
                print(f"  {i+1}. {group_title}")
                print(f"     Status: {exclude_status}, Order: {order}")
                break
    
    # Option to save missing groups to a file
    save_option = input("\nDo you want to save these missing groups to a file? (y/n): ")
    if save_option.lower() == 'y':
        output_file = 'missing_groups.json'
        
        # Extract full entries for missing groups
        missing_entries = [entry for entry in config_data if entry['group_title'] in missing_groups]
        
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(missing_entries, f, indent=2, ensure_ascii=False)
        
        print(f"💾 Saved {len(missing_entries)} missing group entries to {output_file}")

if __name__ == "__main__":
    main()