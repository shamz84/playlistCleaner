#!/usr/bin/env python3
"""
Comprehensive M3U playlist filter script.
This will filter the raw M3U playlist to include only groups marked as exclude=false.
Now supports multiple input sources including downloaded playlists.
"""
import json
import re
import sys
import os

def main():
    print("=== M3U Playlist Filter ===")
    
    # Step 1: Load allowed groups with order information
    print("1. Loading allowed groups from JSON...")
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            json_data = json.load(f)
        
        allowed_groups = set()
        group_order = {}  # Maps group_title to its order number
        ordered_groups = []  # List of groups in order for output
        
        for entry in json_data:
            if entry.get('exclude') == 'false':
                group_title = entry.get('group_title')
                order = entry.get('order', 999)  # Default order if missing
                
                allowed_groups.add(group_title)
                group_order[group_title] = order
                ordered_groups.append((order, group_title))
        
        # Sort groups by their order
        ordered_groups.sort(key=lambda x: x[0])
        
        print(f"   ✓ Found {len(allowed_groups)} allowed groups")
        
        # Show first few groups for verification
        print("   First 5 allowed groups (in order):")
        for i, (order, group) in enumerate(ordered_groups[:5], 1):
            print(f"     {i}. Order {order}: {group}")
    
    except Exception as e:
        print(f"   ✗ Error loading JSON: {e}")
        return False
    
    # Step 2: Read M3U files
    print("\\n2. Reading raw M3U playlists...")
    
    # Check which files are available
    available_files = []
    
    # Main playlist file
    if os.path.exists('raw_playlist_20.m3u'):
        try:
            with open('raw_playlist_20.m3u', 'r', encoding='utf-8') as f:
                lines = f.readlines()
            print(f"   ✓ Loaded {len(lines)} lines from raw_playlist_20.m3u")
            available_files.append(('raw_playlist_20.m3u', lines, False))  # False = apply filtering
        except Exception as e:
            print(f"   ✗ Error reading raw_playlist_20.m3u: {e}")
            return False
    else:
        print(f"   ⚠️  raw_playlist_20.m3u not found")
    
    # Asia UK playlist file
    if os.path.exists('raw_playlist_AsiaUk.m3u'):
        try:
            with open('raw_playlist_AsiaUk.m3u', 'r', encoding='utf-8') as f:
                asia_lines = f.readlines()
            print(f"   ✓ Loaded {len(asia_lines)} lines from raw_playlist_AsiaUk.m3u")
            available_files.append(('raw_playlist_AsiaUk.m3u', asia_lines, True))  # True = include all
        except Exception as e:
            print(f"   ✗ Error reading raw_playlist_AsiaUk.m3u: {e}")
    else:
        print(f"   ⚠️  raw_playlist_AsiaUk.m3u not found")
    
    # Downloaded playlist file (from download script)
    if os.path.exists('manual_download.m3u'):
        try:
            with open('manual_download.m3u', 'r', encoding='utf-8') as f:
                downloaded_lines = f.readlines()
            print(f"   ✓ Loaded {len(downloaded_lines)} lines from manual_download.m3u")
            available_files.append(('manual_download.m3u', downloaded_lines, False))  # False = apply filtering
        except Exception as e:
            print(f"   ✗ Error reading manual_download.m3u: {e}")
    else:
        print(f"   ⚠️  manual_download.m3u not found (run download script first)")
    
    if not available_files:
        print(f"   ✗ No playlist files found! Please ensure at least one source file exists.")
        return False
    
    print(f"   ✓ Found {len(available_files)} available playlist sources")
    
    # Step 3: Filter entries and group them by group-title
    print("\\n3. Filtering playlist entries...")
    
    # Dictionary to store entries by group title
    grouped_entries = {}
    total_entries = 0
    included_entries = 0
    
    # Process each available playlist file
    for filename, file_lines, include_all in available_files:
        print(f"   Processing {filename}...")
        
        i = 1 if len(file_lines) > 0 and file_lines[0].strip().startswith('#EXTM3U') else 0
        file_entries = 0
        
        while i < len(file_lines):
            line = file_lines[i].strip()
            
            if line.startswith('#EXTINF:'):
                total_entries += 1
                current_extinf = file_lines[i]
                current_url = file_lines[i + 1] if i + 1 < len(file_lines) else None
                
                # Extract group-title using regex
                match = re.search(r'group-title="([^"]*)"', line)
                
                if match:
                    group_title = match.group(1)
                    
                    # Decide whether to include this entry
                    should_include = include_all or (group_title in allowed_groups)
                    
                    if should_include:
                        # Group entries by their group title
                        if group_title not in grouped_entries:
                            grouped_entries[group_title] = []
                        
                        grouped_entries[group_title].append((current_extinf, current_url))
                        included_entries += 1
                        file_entries += 1
                        
                        # Print progress every 1000 included entries
                        if included_entries % 1000 == 0:
                            print(f"   Progress: {included_entries} entries included...")
                
                i += 2  # Skip both EXTINF and URL lines
            else:
                i += 1
        
        print(f"   ✓ {filename}: {file_entries} entries added")
    
    print(f"   ✓ Processed {total_entries} total entries")
    print(f"   ✓ Included {included_entries} entries from {len(grouped_entries)} groups")
    print(f"   ✓ Excluded {total_entries - included_entries} entries")
    
    # Step 4: Build filtered playlist in order
    print("\\n4. Building ordered playlist...")
    filtered_lines = ['#EXTM3U\\n']
    
    groups_added = 0
    
    # First, add groups from JSON configuration in order
    for order, group_title in ordered_groups:
        if group_title in grouped_entries:
            entries = grouped_entries[group_title]
            groups_added += 1
            
            print(f"   Adding group {groups_added}/{len(grouped_entries)}: '{group_title}' ({len(entries)} entries)")
            
            for extinf_line, url_line in entries:
                filtered_lines.append(extinf_line)
                if url_line:
                    filtered_lines.append(url_line)
    
    # Then, add any remaining groups (like Asia UK or downloaded content) that weren't in the JSON
    for group_title, entries in grouped_entries.items():
        if group_title not in [group for _, group in ordered_groups]:
            groups_added += 1
            
            print(f"   Adding additional group {groups_added}/{len(grouped_entries)}: '{group_title}' ({len(entries)} entries)")
            
            for extinf_line, url_line in entries:
                filtered_lines.append(extinf_line)
                if url_line:
                    filtered_lines.append(url_line)
    
    # Step 5: Save filtered playlist
    print("\\n5. Saving filtered playlist...")
    output_file = 'filtered_playlist_final.m3u'
    
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            f.writelines(filtered_lines)
        
        print(f"   ✓ Saved {output_file}")
        print(f"   ✓ Total lines written: {len(filtered_lines)}")
        
        # Calculate file size
        file_size = sum(len(line.encode('utf-8')) for line in filtered_lines)
        print(f"   ✓ File size: {file_size:,} bytes")
        
        return True
        
    except Exception as e:
        print(f"   ✗ Error saving file: {e}")
        return False

if __name__ == "__main__":
    success = main()
    if success:
        print("\\n🎉 Filtering completed successfully!")
    else:
        print("\\n💥 Filtering failed!")
    
    sys.exit(0 if success else 1)
