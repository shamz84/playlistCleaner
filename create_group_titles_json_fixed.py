#!/usr/bin/env python3
"""
Create JSON file from unique group titles with flag property.
Reads the unique_group_titles_raw20.txt file and creates a JSON file
where each group title has a "flag" property set to "exclude" and an "order" property
based on the sequence they appear in the raw_playlist_20.m3u file.
"""

import json
import re


def get_group_title_order_from_m3u(m3u_file_path):
    """Parse the M3U file to get the order of group titles as they first appear."""
    group_order = {}
    order_counter = 1
    
    try:
        with open(m3u_file_path, 'r', encoding='utf-8') as file:
            for line in file:
                if line.startswith('#EXTINF:'):
                    # Extract group-title using regex
                    match = re.search(r'group-title="([^"]*)"', line)
                    if match:
                        group_title = match.group(1).strip()
                        # Only assign order if we haven't seen this group title before
                        if group_title and group_title not in group_order:
                            group_order[group_title] = order_counter
                            order_counter += 1
        
        print(f"Found {len(group_order)} unique group titles in M3U file")
        return group_order
        
    except FileNotFoundError:
        print(f"Error: M3U file '{m3u_file_path}' not found.")
        return {}
    except Exception as e:
        print(f"Error reading M3U file: {e}")
        return {}


def parse_group_titles_file(file_path, group_order):
    """Parse the group titles text file and extract group names and channel counts."""
    group_data = []
    
    try:
        with open(file_path, 'r', encoding='utf-8') as file:
            content = file.read()
              # Pattern to match lines like: "  1. 24/7 Channels (2019 channels)"
        pattern = r'^\s*\d+\.\s+(.+?)\s+\((\d+)\s+channels?\)$'
        
        for line in content.split('\n'):
            match = re.match(pattern, line)
            if match:
                group_title = match.group(1).strip()
                channel_count = int(match.group(2))
                
                # Get order from M3U file, default to 999999 if not found
                order = group_order.get(group_title, 999999)
                
                group_data.append({
                    "group_title": group_title,
                    "channel_count": channel_count,
                    "exclude": "false",
                    "order": order
                })
        
        # Sort by order
        group_data.sort(key=lambda x: x['order'])
        return group_data
        
    except FileNotFoundError:
        print(f"Error: File '{file_path}' not found.")
        return []
    except Exception as e:
        print(f"Error reading file: {e}")
        return []


def main():
    input_file = r"c:\dev\training\PlaylistCleaner\unique_group_titles_raw20.txt"
    m3u_file = r"c:\dev\training\PlaylistCleaner\raw_playlist_20.m3u"
    output_file = r"c:\dev\training\PlaylistCleaner\group_titles_with_flags.json"
    
    print(f"Reading M3U file to get group title order: {m3u_file}")
    group_order = get_group_title_order_from_m3u(m3u_file)
    
    print(f"Reading group titles from: {input_file}")
    print(f"Output file will be: {output_file}")
    
    # Check if input file exists
    import os
    if not os.path.exists(input_file):
        print(f"ERROR: Input file does not exist: {input_file}")
        return
    
    group_data = parse_group_titles_file(input_file, group_order)
    print(f"Parsed {len(group_data)} entries from input file")
    
    if not group_data:
        print("No group titles found or error reading file.")
        return
    
    print(f"Found {len(group_data)} group titles")
    
    # Write to JSON file
    try:
        with open(output_file, 'w', encoding='utf-8') as file:
            json.dump(group_data, file, indent=2, ensure_ascii=False)
        
        print(f"Successfully created: {output_file}")
        print(f"Total groups: {len(group_data)}")        # Show first few entries as preview
        print("\nFirst 5 entries (ordered by appearance in M3U):")
        for i, entry in enumerate(group_data[:5]):
            print(f"  {i+1}. {entry['group_title']} ({entry['channel_count']} channels) - Exclude: {entry['exclude']} - Order: {entry['order']}")
            
    except Exception as e:
        print(f"Error writing JSON file: {e}")


if __name__ == "__main__":
    main()
