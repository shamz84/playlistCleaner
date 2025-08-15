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

def filter_m3u_playlist(input_file, output_file, allowed_groups):
    """Filter M3U playlist to include only entries from allowed groups."""
    
    if not os.path.exists(input_file):
        print(f"Error: Input file {input_file} not found!")
        return False
    
    print(f"Loading playlist from {input_file}...")
    
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
                if group_title in allowed_groups:
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
    input_file = "raw_playlist_20.m3u"
    output_file = "filtered_playlist.m3u"
    
    print("🔄 Starting M3U playlist filtering...")
    
    # Load allowed groups (exclude=false)
    print("📋 Loading allowed group titles...")
    allowed_groups = load_exclude_false_groups()
    
    if not allowed_groups:
        print("❌ No allowed groups found or error loading JSON file!")
        return
    
    print(f"✅ Found {len(allowed_groups)} allowed groups:")
    for i, group in enumerate(sorted(allowed_groups), 1):
        print(f"   {i:2}. {group}")
    
    # Filter the playlist
    print(f"\n🔍 Filtering playlist...")
    success = filter_m3u_playlist(input_file, output_file, allowed_groups)
    
    if success:
        print(f"\n🎉 Filtering complete!")
        print(f"📄 Filtered playlist saved as: {output_file}")
    else:
        print(f"\n❌ Filtering failed!")

if __name__ == "__main__":
    main()
