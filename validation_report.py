#!/usr/bin/env python3
"""
Comprehensive validation report: What happens when downloaded playlist contains 
groups that are not in group_titles_with_flags.json
"""

import re
import json
from collections import Counter

def generate_validation_report():
    """Generate comprehensive validation report"""
    
    print("🔍 PLAYLIST GROUP VALIDATION REPORT")
    print("=" * 80)
    print("QUESTION: What happens when downloaded playlist contains groups not in config?")
    print("ANSWER: Unknown groups are EXCLUDED by default!")
    print("=" * 80)
    
    # Load data
    print("\n📊 CURRENT SITUATION ANALYSIS:")
    print("-" * 50)
    
    # Extract groups from downloaded playlist
    with open('data/downloaded_file.m3u', 'r', encoding='utf-8') as f:
        content = f.read()
    
    groups = re.findall(r'group-title="([^"]+)"', content)
    playlist_group_counts = Counter(groups)
    
    # Load config groups
    with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
        config = json.load(f)
    
    config_groups = {}
    for entry in config:
        config_groups[entry['group_title']] = {
            'exclude': entry.get('exclude'),
            'order': entry.get('order'),
            'config_count': entry.get('channel_count', 0)
        }
    
    # Analysis
    config_group_names = set(config_groups.keys())
    playlist_group_names = set(playlist_group_counts.keys())
    unknown_groups = playlist_group_names - config_group_names
    known_groups = playlist_group_names & config_group_names
    
    total_channels = sum(playlist_group_counts.values())
    unknown_channels = sum([playlist_group_counts[g] for g in unknown_groups])
    known_channels = sum([playlist_group_counts[g] for g in known_groups])
    
    print(f"• Total channels in downloaded playlist: {total_channels:,}")
    print(f"• Groups defined in config: {len(config_group_names)}")
    print(f"• Groups found in playlist: {len(playlist_group_names)}")
    print(f"• Known groups (in config): {len(known_groups)}")
    print(f"• Unknown groups (NOT in config): {len(unknown_groups)}")
    print(f"• Channels in unknown groups: {unknown_channels} ({unknown_channels/total_channels*100:.1f}%)")
    
    print(f"\n⚠️  CRITICAL FINDING:")
    print(f"   {unknown_channels} channels ({unknown_channels/total_channels*100:.1f}%) will be EXCLUDED")
    print(f"   because their groups are not defined in group_titles_with_flags.json!")
    
    # Show unknown groups
    if unknown_groups:
        print(f"\n📋 UNKNOWN GROUPS (will be EXCLUDED):")
        print("-" * 50)
        unknown_list = [(g, playlist_group_counts[g]) for g in unknown_groups]
        unknown_list.sort(key=lambda x: x[1], reverse=True)
        
        for group, count in unknown_list:
            print(f"   {count:3d} channels - '{group}'")
    
    # Filtering behavior explanation
    print(f"\n🔧 HOW THE FILTER WORKS:")
    print("-" * 50)
    print("1. The filter loads all groups where exclude='false' from group_titles_with_flags.json")
    print("2. For each channel in the playlist:")
    print("   • Extract the group-title value")
    print("   • Check if group-title is in the 'allowed_groups' set")
    print("   • If YES: Include the channel in filtered output")
    print("   • If NO: Exclude the channel (skip it completely)")
    print("3. Unknown groups are NOT in the allowed_groups set")
    print("4. Therefore: ALL channels from unknown groups are EXCLUDED")
    
    # Show the exact filtering logic
    print(f"\n💻 FILTERING LOGIC (from filter_m3u_playlist.py):")
    print("-" * 50)
    print("```python")
    print("if group_title in allowed_groups:")
    print("    # Include this entry")
    print("    filtered_lines.append(extinf_line)")
    print("    filtered_lines.append(url_line)")
    print("else:")
    print("    # Skip this entry (EXCLUDE)")
    print("    # Unknown groups fall into this category!")
    print("```")
    
    # Calculate what would be included vs excluded
    print(f"\n📈 FILTERING SIMULATION:")
    print("-" * 50)
    
    included_channels = 0
    excluded_known_channels = 0
    
    for group in known_groups:
        count = playlist_group_counts[group]
        if config_groups[group]['exclude'] == 'false':
            included_channels += count
        else:
            excluded_known_channels += count
    
    print(f"• Channels that would be INCLUDED: {included_channels:,} ({included_channels/total_channels*100:.1f}%)")
    print(f"• Channels excluded (known groups, exclude='true'): {excluded_known_channels:,} ({excluded_known_channels/total_channels*100:.1f}%)")
    print(f"• Channels excluded (unknown groups): {unknown_channels:,} ({unknown_channels/total_channels*100:.1f}%)")
    print(f"• Total excluded: {excluded_known_channels + unknown_channels:,} ({(excluded_known_channels + unknown_channels)/total_channels*100:.1f}%)")
    
    # Recommendations
    print(f"\n💡 RECOMMENDATIONS:")
    print("-" * 50)
    print("1. ADD UNKNOWN GROUPS TO CONFIG:")
    print("   • Add the 14 unknown groups to group_titles_with_flags.json")
    print("   • Set appropriate 'exclude' values (true/false)")
    print("   • Set 'order' values for desired positioning")
    print("   • This will give you control over 612 additional channels")
    
    print(f"\n2. IMMEDIATE ACTIONS:")
    print("   • Review the unknown groups list above")
    print("   • Decide which ones you want to include (exclude='false')")
    print("   • Decide which ones you want to exclude (exclude='true')")
    print("   • Add them to group_titles_with_flags.json")
    
    print(f"\n3. AUTOMATED SOLUTION:")
    print("   • Use the validate_playlist_groups.py script")
    print("   • It can generate a template JSON for unknown groups")
    print("   • Review and merge into your main config file")
    
    # Generate template for unknown groups
    print(f"\n📝 TEMPLATE FOR UNKNOWN GROUPS:")
    print("-" * 50)
    
    max_order = max([info['order'] for info in config_groups.values()]) if config_groups else 1000
    unknown_list = [(g, playlist_group_counts[g]) for g in unknown_groups]
    unknown_list.sort(key=lambda x: x[1], reverse=True)
    
    template_entries = []
    for i, (group, count) in enumerate(unknown_list, 1):
        template_entries.append({
            "group_title": group,
            "channel_count": count,
            "exclude": "false",  # Default to include - you can change this
            "order": max_order + i
        })
        print(f"  {{")
        print(f"    \"group_title\": \"{group}\",")
        print(f"    \"channel_count\": {count},")
        print(f"    \"exclude\": \"false\",  // Change to \"true\" if you want to exclude")
        print(f"    \"order\": {max_order + i}")
        print(f"  }},")
    
    # Save template file
    try:
        with open('unknown_groups_template.json', 'w', encoding='utf-8') as f:
            json.dump(template_entries, f, indent=2, ensure_ascii=False)
        print(f"\n💾 Template saved to: unknown_groups_template.json")
    except Exception as e:
        print(f"⚠️  Could not save template: {e}")
    
    print(f"\n✅ VALIDATION COMPLETE!")
    print(f"   Unknown groups behavior: EXCLUDED by default")
    print(f"   Impact: {unknown_channels} channels currently being excluded")
    print(f"   Solution: Add unknown groups to configuration file")

if __name__ == "__main__":
    generate_validation_report()
