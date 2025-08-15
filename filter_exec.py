import json
import re

# Get allowed group titles from JSON
allowed_groups = set()
try:
    with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    for entry in data:
        if entry.get('exclude') == 'false':
            allowed_groups.add(entry.get('group_title'))
    
    print(f"Loaded {len(allowed_groups)} allowed groups")
except Exception as e:
    print(f"Error loading JSON: {e}")
    exit(1)

# Process M3U file
filtered_lines = ['#EXTM3U\n']
total_entries = 0
included_entries = 0

try:
    with open('raw_playlist_20.m3u', 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    print(f"Processing {len(lines)} lines from M3U file")
    
    i = 1  # Skip first line (#EXTM3U)
    while i < len(lines):
        line = lines[i].strip()
        
        if line.startswith('#EXTINF:'):
            total_entries += 1
            
            # Extract group-title using regex
            match = re.search(r'group-title="([^"]*)"', line)
            
            if match:
                group_title = match.group(1)
                
                if group_title in allowed_groups:
                    # Include this entry
                    filtered_lines.append(lines[i])      # EXTINF line
                    if i + 1 < len(lines):
                        filtered_lines.append(lines[i + 1])  # URL line
                        included_entries += 1
            
            i += 2  # Skip both EXTINF and URL lines
        else:
            i += 1
    
    # Write filtered file
    with open('filtered_playlist.m3u', 'w', encoding='utf-8') as f:
        f.writelines(filtered_lines)
    
    print(f"Success! Created filtered_playlist.m3u")
    print(f"Total entries processed: {total_entries}")
    print(f"Entries included: {included_entries}")
    print(f"Entries excluded: {total_entries - included_entries}")
    print(f"Output file has {len(filtered_lines)} lines")

except Exception as e:
    print(f"Error processing M3U file: {e}")
