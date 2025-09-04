#!/usr/bin/env python3
"""
Simple script to extract and count group titles from playlist
"""

import re
from collections import Counter

def extract_groups_from_playlist(playlist_file):
    """Extract group titles from playlist file"""
    try:
        with open(playlist_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Find all group-title values
        group_matches = re.findall(r'group-title="([^"]*)"', content)
        group_counts = Counter(group_matches)
        
        print(f"📁 File: {playlist_file}")
        print(f"📊 Found {len(group_counts)} unique groups with {sum(group_counts.values())} total entries")
        
        # Show top 20 groups by count
        print(f"\n🔝 Top 20 groups by channel count:")
        for group, count in group_counts.most_common(20):
            print(f"   {count:4d} - '{group}'")
        
        return group_counts
    
    except Exception as e:
        print(f"❌ Error reading {playlist_file}: {e}")
        return Counter()

def load_config_groups():
    """Load groups from config file"""
    try:
        import json
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        config_groups = set()
        for entry in data:
            config_groups.add(entry.get('group_title'))
        
        print(f"📋 Config has {len(config_groups)} groups defined")
        return config_groups
    
    except Exception as e:
        print(f"❌ Error reading config: {e}")
        return set()

if __name__ == "__main__":
    print("🔍 PLAYLIST GROUP EXTRACTOR")
    print("=" * 40)
    
    # Extract groups from downloaded file
    playlist_groups = extract_groups_from_playlist('data/downloaded_file.m3u')
    
    # Load config groups
    config_groups = load_config_groups()
    
    if playlist_groups and config_groups:
        # Find unknown groups
        playlist_group_names = set(playlist_groups.keys())
        unknown_groups = playlist_group_names - config_groups
        
        print(f"\n⚠️  UNKNOWN GROUPS (not in config): {len(unknown_groups)}")
        if unknown_groups:
            unknown_counts = [(group, playlist_groups[group]) for group in unknown_groups]
            unknown_counts.sort(key=lambda x: x[1], reverse=True)
            
            for group, count in unknown_counts:
                print(f"   {count:4d} - '{group}'")
            
            total_unknown_channels = sum([count for _, count in unknown_counts])
            print(f"\n📊 Total channels in unknown groups: {total_unknown_channels}")
        else:
            print("   ✅ All groups are known!")
    
    print(f"\n✅ Analysis complete!")
