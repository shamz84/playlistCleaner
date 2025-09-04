#!/usr/bin/env python3
"""
Filter M3U playlist to include only entries from groups marked as exclude=false
in the group_titles_with_flags.json file.
"""

import json
import re
import os

def load_exclude_false_groups():
    """Load group titles where exclude is set to false."""
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        exclude_false_groups = set()
        for entry in data:
            if entry.get('exclude') == 'false':
                exclude_false_groups.add(entry.get('group_title'))
        
        return exclude_false_groups
    except Exception as e:
        print(f"Error loading group titles: {e}")
        return set()

def load_all_groups():
    """Load all group titles with their exclude status."""
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        include_groups = set()
        exclude_groups = set()
        
        for entry in data:
            group_title = entry.get('group_title')
            if entry.get('exclude') == 'false':
                include_groups.add(group_title)
            else:
                exclude_groups.add(group_title)
        
        return include_groups, exclude_groups
    except Exception as e:
        print(f"Error loading group titles: {e}")
        return set(), set()

def get_unknown_groups(input_file, known_groups):
    """Find unknown groups in the playlist."""
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        groups = re.findall(r'group-title="([^"]+)"', content)
        from collections import Counter
        group_counts = Counter(groups)
        
        unknown_groups = set(group_counts.keys()) - known_groups
        return {group: group_counts[group] for group in unknown_groups}
    except Exception as e:
        print(f"Error analyzing unknown groups: {e}")
        return {}

def should_auto_exclude_unknown_group(unknown_group, exclude_groups):
    """Check if unknown group should be excluded based on patterns."""
    group_lower = unknown_group.lower()
    
    # Common exclusion patterns
    exclude_patterns = [
        'tv guide', 'network', 'affiliates', 'adult', 'xxx', ' sd', 'hevc'
    ]
    
    for pattern in exclude_patterns:
        if pattern in group_lower:
            # Check if similar pattern exists in exclude_groups
            similar_excluded = [g for g in exclude_groups if pattern in g.lower()]
            if similar_excluded:
                return True, f"Matches excluded pattern: {pattern}"
    
    return False, "No matching exclusion patterns"

def filter_m3u_playlist(input_file, output_file, allowed_groups, auto_include_unknown=True):
    """Filter M3U playlist to include only entries from allowed groups, with auto-include for unknown groups."""
    
    if not os.path.exists(input_file):
        print(f"Error: Input file {input_file} not found!")
        return False
    
    print(f"Loading playlist from {input_file}...")
    
    # If auto-include is enabled, analyze unknown groups
    final_allowed_groups = allowed_groups.copy()
    
    if auto_include_unknown:
        include_groups, exclude_groups = load_all_groups()
        all_known_groups = include_groups | exclude_groups
        unknown_groups = get_unknown_groups(input_file, all_known_groups)
        
        if unknown_groups:
            print(f"\n🔍 Found {len(unknown_groups)} unknown groups:")
            auto_included = []
            auto_excluded = []
            
            for group, count in unknown_groups.items():
                should_exclude, reason = should_auto_exclude_unknown_group(group, exclude_groups)
                if should_exclude:
                    auto_excluded.append((group, count, reason))
                    print(f"  ❌ EXCLUDE: '{group}' ({count} channels) - {reason}")
                else:
                    auto_included.append((group, count))
                    final_allowed_groups.add(group)
                    print(f"  ✅ INCLUDE: '{group}' ({count} channels) - Auto-included")
            
            print(f"\n📊 Auto-classification:")
            print(f"  • Auto-included: {len(auto_included)} groups")
            print(f"  • Auto-excluded: {len(auto_excluded)} groups")
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Error reading input file: {e}")
        return False
    
    # Process the M3U file
    filtered_lines = []
    total_entries = 0
    included_entries = 0
    
    # Add M3U header
    if lines and lines[0].strip() == '#EXTM3U':
        filtered_lines.append(lines[0])
        i = 1
    else:
        filtered_lines.append('#EXTM3U\n')
        i = 0
    
    # Process entries
    while i < len(lines):
        line = lines[i].strip()
        
        # Look for EXTINF lines
        if line.startswith('#EXTINF:'):
            total_entries += 1
            
            # Extract group-title using regex
            group_match = re.search(r'group-title="([^"]*)"', line)
            
            if group_match:
                group_title = group_match.group(1)
                  # Check if this group should be included
                if group_title in final_allowed_groups:
                    # Include this entry (EXTINF line + URL line)
                    filtered_lines.append(lines[i])  # EXTINF line
                    
                    # Add the URL line (next line)
                    if i + 1 < len(lines):
                        filtered_lines.append(lines[i + 1])  # URL line
                        included_entries += 1
                    
                    i += 2  # Skip both lines
                else:
                    # Skip this entry
                    i += 2  # Skip EXTINF and URL lines
            else:
                # No group-title found, skip
                i += 2
        else:
            i += 1
    
    # Write filtered playlist
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            f.writelines(filtered_lines)
        
        print(f"✅ Successfully created filtered playlist: {output_file}")
        print(f"   • Total entries processed: {total_entries}")
        print(f"   • Entries included: {included_entries}")
        print(f"   • Entries excluded: {total_entries - included_entries}")
        
        return True
        
    except Exception as e:
        print(f"Error writing output file: {e}")
        return False

def main():
    input_file = "data/downloaded_file.m3u"
    output_file = "filtered_playlist_final.m3u"
    
    print("🔄 Starting Enhanced M3U playlist filtering...")
    print("   Strategy: Auto-include unknown groups unless they match excluded patterns")
    
    # Load allowed groups (exclude=false)
    print("📋 Loading allowed group titles...")
    allowed_groups = load_exclude_false_groups()
    
    if not allowed_groups:
        print("❌ No allowed groups found or error loading JSON file!")
        return
    
    print(f"✅ Found {len(allowed_groups)} allowed groups")
    
    # Filter the playlist with auto-include enabled
    print(f"\n🔍 Filtering playlist with auto-include for unknown groups...")
    success = filter_m3u_playlist(input_file, output_file, allowed_groups, auto_include_unknown=True)
    
    if success:
        print(f"\n🎉 Enhanced filtering complete!")
        print(f"📄 Filtered playlist saved as: {output_file}")
    else:
        print(f"\n❌ Filtering failed!")

if __name__ == "__main__":
    main()
