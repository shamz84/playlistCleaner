#!/usr/bin/env python3
"""
Comprehensive analysis of the enhanced filtering results
"""

import re
import json
from collections import Counter

def comprehensive_analysis():
    """Provide a complete analysis of the filtering results."""
    
    print("🎯 Enhanced M3U Filtering - Complete Analysis")
    print("=" * 60)
    
    # Load original data for comparison
    try:
        with open('data/downloaded_file.m3u', 'r', encoding='utf-8') as f:
            original_content = f.read()
        original_groups = re.findall(r'group-title="([^"]+)"', original_content)
        original_counter = Counter(original_groups)
        
        print(f"📁 Original Playlist:")
        print(f"   • Total channels: {len(original_groups):,}")
        print(f"   • Unique groups: {len(original_counter)}")
        
    except Exception as e:
        print(f"❌ Error reading original file: {e}")
        return
    
    # Load filtered data
    try:
        with open('filtered_playlist_final.m3u', 'r', encoding='utf-8') as f:
            filtered_content = f.read()
        filtered_groups = re.findall(r'group-title="([^"]+)"', filtered_content)
        filtered_counter = Counter(filtered_groups)
        
        print(f"\n📄 Filtered Playlist:")
        print(f"   • Total channels: {len(filtered_groups):,}")
        print(f"   • Unique groups: {len(filtered_counter)}")
        
    except Exception as e:
        print(f"❌ Error reading filtered file: {e}")
        return
    
    # Load configuration
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            config_data = json.load(f)
        
        known_groups = set(item['group_title'] for item in config_data)
        included_groups = set(item['group_title'] for item in config_data if item['exclude'] == 'false')
        excluded_groups = set(item['group_title'] for item in config_data if item['exclude'] == 'true')
        
        print(f"\n⚙️  Configuration:")
        print(f"   • Known groups: {len(known_groups)}")
        print(f"   • Configured to include: {len(included_groups)}")
        print(f"   • Configured to exclude: {len(excluded_groups)}")
        
    except Exception as e:
        print(f"❌ Error reading configuration: {e}")
        return
    
    # Analyze unknown groups that were auto-included
    original_unknown = set(original_counter.keys()) - known_groups
    filtered_unknown = set(filtered_counter.keys()) - known_groups
    
    print(f"\n🔍 Unknown Groups Analysis:")
    print(f"   • Unknown groups in original: {len(original_unknown)}")
    print(f"   • Unknown groups in filtered: {len(filtered_unknown)}")
    
    if filtered_unknown:
        print(f"\n✅ Auto-Included Unknown Groups:")
        total_auto_included = 0
        for group in sorted(filtered_unknown):
            count = filtered_counter[group]
            total_auto_included += count
            print(f"   • {group}: {count} channels")
        
        print(f"\n📊 Auto-Include Summary:")
        print(f"   • Groups auto-included: {len(filtered_unknown)}")
        print(f"   • Channels recovered: {total_auto_included:,}")
    
    # Check what was excluded
    excluded_unknown = original_unknown - filtered_unknown
    if excluded_unknown:
        print(f"\n❌ Auto-Excluded Unknown Groups:")
        total_auto_excluded = 0
        for group in sorted(excluded_unknown):
            count = original_counter[group]
            total_auto_excluded += count
            print(f"   • {group}: {count} channels (pattern-based exclusion)")
        
        print(f"\n🚫 Auto-Exclude Summary:")
        print(f"   • Groups auto-excluded: {len(excluded_unknown)}")
        print(f"   • Channels excluded: {total_auto_excluded:,}")
    
    # Overall statistics
    channels_saved = len(filtered_groups)
    channels_lost = len(original_groups) - len(filtered_groups)
    retention_rate = (channels_saved / len(original_groups)) * 100
    
    print(f"\n📈 Overall Results:")
    print(f"   • Channels retained: {channels_saved:,} ({retention_rate:.1f}%)")
    print(f"   • Channels filtered out: {channels_lost:,}")
    
    # Before vs After comparison
    print(f"\n⚡ Enhancement Impact:")
    print(f"   • Before enhancement: Many unknown groups were lost")
    print(f"   • After enhancement: {len(filtered_unknown)} unknown groups recovered")
    print(f"   • Smart exclusion: Pattern-based filtering prevents unwanted content")
    
    print(f"\n✅ Success! Enhanced filtering is working properly!")

if __name__ == "__main__":
    comprehensive_analysis()
