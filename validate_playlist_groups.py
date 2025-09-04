#!/usr/bin/env python3
"""
Test and validate what happens when downloaded playlist contains groups 
that are not present in group_titles_with_flags.json file
"""

import json
import re
import os
from collections import Counter, defaultdict

def load_group_titles_config():
    """Load group titles configuration"""
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        groups_config = {}
        for entry in data:
            groups_config[entry.get('group_title')] = {
                'exclude': entry.get('exclude'),
                'order': entry.get('order'),
                'channel_count': entry.get('channel_count', 0)
            }
        
        return groups_config
    except Exception as e:
        print(f"Error loading group titles config: {e}")
        return {}

def analyze_playlist_groups(playlist_file):
    """Analyze groups found in the playlist file"""
    if not os.path.exists(playlist_file):
        print(f"❌ Playlist file not found: {playlist_file}")
        return {}
    
    print(f"📁 Analyzing groups in: {playlist_file}")
    
    try:
        with open(playlist_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Error reading playlist file: {e}")
        return {}
    
    group_counts = Counter()
    total_entries = 0
    entries_with_groups = 0
    entries_without_groups = 0
    
    for line in lines:
        if line.strip().startswith('#EXTINF:'):
            total_entries += 1
            
            # Extract group-title using regex
            group_match = re.search(r'group-title="([^"]*)"', line)
            
            if group_match:
                group_title = group_match.group(1)
                group_counts[group_title] += 1
                entries_with_groups += 1
            else:
                entries_without_groups += 1
    
    print(f"📊 Playlist Analysis Results:")
    print(f"   • Total entries: {total_entries}")
    print(f"   • Entries with groups: {entries_with_groups}")
    print(f"   • Entries without groups: {entries_without_groups}")
    print(f"   • Unique groups found: {len(group_counts)}")
    
    return dict(group_counts)

def validate_groups_against_config(playlist_groups, config_groups):
    """Validate playlist groups against configuration"""
    print(f"\n🔍 Validating groups against configuration...")
    
    # Categorize groups
    known_groups = {}
    unknown_groups = {}
    
    for group, count in playlist_groups.items():
        if group in config_groups:
            known_groups[group] = {
                'count': count,
                'exclude': config_groups[group]['exclude'],
                'order': config_groups[group]['order'],
                'config_count': config_groups[group]['channel_count']
            }
        else:
            unknown_groups[group] = count
    
    return known_groups, unknown_groups

def simulate_filtering(known_groups, unknown_groups):
    """Simulate what would happen during filtering"""
    print(f"\n🧪 Simulating filtering behavior...")
    
    # Count what would be included/excluded
    included_channels = 0
    excluded_known_channels = 0
    excluded_unknown_channels = 0
    
    # Known groups
    for group, info in known_groups.items():
        if info['exclude'] == 'false':
            included_channels += info['count']
            print(f"✅ INCLUDE: '{group}' ({info['count']} channels) - exclude={info['exclude']}")
        else:
            excluded_known_channels += info['count']
            print(f"❌ EXCLUDE: '{group}' ({info['count']} channels) - exclude={info['exclude']}")
    
    # Unknown groups (these will be EXCLUDED by default)
    for group, count in unknown_groups.items():
        excluded_unknown_channels += count
        print(f"⚠️  EXCLUDE: '{group}' ({count} channels) - UNKNOWN GROUP (not in config)")
    
    total_channels = included_channels + excluded_known_channels + excluded_unknown_channels
    
    print(f"\n📈 Filtering Summary:")
    print(f"   • Total channels: {total_channels}")
    print(f"   • Would be INCLUDED: {included_channels} ({included_channels/total_channels*100:.1f}%)")
    print(f"   • Would be EXCLUDED (known groups): {excluded_known_channels} ({excluded_known_channels/total_channels*100:.1f}%)")
    print(f"   • Would be EXCLUDED (unknown groups): {excluded_unknown_channels} ({excluded_unknown_channels/total_channels*100:.1f}%)")
    
    return {
        'total': total_channels,
        'included': included_channels,
        'excluded_known': excluded_known_channels,
        'excluded_unknown': excluded_unknown_channels
    }

def generate_report(known_groups, unknown_groups, simulation_results):
    """Generate detailed validation report"""
    
    print(f"\n📋 DETAILED VALIDATION REPORT")
    print(f"=" * 80)
    
    # Unknown groups section
    if unknown_groups:
        print(f"\n⚠️  UNKNOWN GROUPS (NOT IN CONFIG) - These will be EXCLUDED:")
        print(f"-" * 60)
        total_unknown = sum(unknown_groups.values())
        for group, count in sorted(unknown_groups.items(), key=lambda x: x[1], reverse=True):
            print(f"   '{group}': {count} channels")
        print(f"\n   Total unknown groups: {len(unknown_groups)}")
        print(f"   Total channels in unknown groups: {total_unknown}")
        
        # Suggest action
        print(f"\n💡 RECOMMENDATION:")
        print(f"   • Add these {len(unknown_groups)} unknown groups to group_titles_with_flags.json")
        print(f"   • Set appropriate 'exclude' and 'order' values for each")
        print(f"   • This will give you control over {total_unknown} additional channels")
    else:
        print(f"\n✅ ALL GROUPS ARE KNOWN - No unknown groups found!")
    
    # Count mismatches
    print(f"\n📊 CHANNEL COUNT VALIDATION:")
    print(f"-" * 60)
    count_mismatches = []
    for group, info in known_groups.items():
        config_count = info['config_count']
        actual_count = info['count']
        if config_count != actual_count:
            count_mismatches.append((group, config_count, actual_count, actual_count - config_count))
    
    if count_mismatches:
        print(f"⚠️  Found {len(count_mismatches)} groups with count mismatches:")
        for group, config_count, actual_count, diff in count_mismatches:
            status = "📈" if diff > 0 else "📉"
            print(f"   {status} '{group}': config={config_count}, actual={actual_count} (diff: {diff:+d})")
        
        print(f"\n💡 This suggests the group_titles_with_flags.json may need updating")
    else:
        print(f"✅ All group channel counts match configuration!")
    
    # High impact unknown groups
    if unknown_groups:
        high_impact = [(g, c) for g, c in unknown_groups.items() if c > 10]
        if high_impact:
            print(f"\n🔥 HIGH IMPACT UNKNOWN GROUPS (>10 channels):")
            print(f"-" * 60)
            for group, count in sorted(high_impact, key=lambda x: x[1], reverse=True):
                print(f"   '{group}': {count} channels")

def main():
    print("🔍 PLAYLIST GROUP VALIDATION TOOL")
    print("=" * 50)
    
    # Check for playlist files
    playlist_files = [
        'downloaded_file.m3u',
        'raw_playlist_20.m3u', 
        'data/downloaded_file.m3u'
    ]
    
    playlist_file = None
    for file in playlist_files:
        if os.path.exists(file):
            playlist_file = file
            break
    
    if not playlist_file:
        print("❌ No playlist file found!")
        print(f"   Checked: {', '.join(playlist_files)}")
        return
    
    # Load configuration
    print(f"📋 Loading group configuration...")
    config_groups = load_group_titles_config()
    if not config_groups:
        print("❌ Failed to load group configuration!")
        return
    
    print(f"✅ Loaded configuration for {len(config_groups)} groups")
    
    # Analyze playlist
    playlist_groups = analyze_playlist_groups(playlist_file)
    if not playlist_groups:
        print("❌ Failed to analyze playlist!")
        return
    
    # Validate groups
    known_groups, unknown_groups = validate_groups_against_config(playlist_groups, config_groups)
    
    print(f"\n📊 Group Validation Results:")
    print(f"   • Known groups: {len(known_groups)}")
    print(f"   • Unknown groups: {len(unknown_groups)}")
    
    # Simulate filtering
    simulation_results = simulate_filtering(known_groups, unknown_groups)
    
    # Generate detailed report
    generate_report(known_groups, unknown_groups, simulation_results)
    
    # Save unknown groups for easy addition to config
    if unknown_groups:
        unknown_groups_file = 'unknown_groups_found.json'
        try:
            # Create entries in the same format as group_titles_with_flags.json
            unknown_entries = []
            max_order = max([info['order'] for info in config_groups.values()]) if config_groups else 1000
            
            for i, (group, count) in enumerate(sorted(unknown_groups.items()), 1):
                unknown_entries.append({
                    "group_title": group,
                    "channel_count": count,
                    "exclude": "false",  # Default to include - user can change
                    "order": max_order + i
                })
            
            with open(unknown_groups_file, 'w', encoding='utf-8') as f:
                json.dump(unknown_entries, f, indent=2, ensure_ascii=False)
            
            print(f"\n💾 Saved unknown groups template to: {unknown_groups_file}")
            print(f"   You can review and merge these into group_titles_with_flags.json")
            
        except Exception as e:
            print(f"⚠️  Failed to save unknown groups template: {e}")

if __name__ == "__main__":
    main()
