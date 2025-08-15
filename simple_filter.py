import json
import re

# Load exclude=false groups
print("Loading groups...")
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

allowed_groups = set()
for entry in data:
    if entry.get('exclude') == 'false':
        allowed_groups.add(entry.get('group_title'))

print(f"Found {len(allowed_groups)} allowed groups")

# Read M3U file
print("Reading M3U file...")
with open('raw_playlist_20.m3u', 'r', encoding='utf-8') as f:
    lines = f.readlines()

print(f"M3U file has {len(lines)} lines")

# Filter entries
filtered_lines = ['#EXTM3U\n']
total_entries = 0
included_entries = 0

i = 1  # Skip first line (#EXTM3U)
while i < len(lines):
    line = lines[i].strip()
    
    if line.startswith('#EXTINF:'):
        total_entries += 1
        
        # Extract group-title
        group_match = re.search(r'group-title="([^"]*)"', line)
        
        if group_match:
            group_title = group_match.group(1)
            
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
print("Writing filtered file...")
with open('filtered_playlist.m3u', 'w', encoding='utf-8') as f:
    f.writelines(filtered_lines)

print(f"Done! Processed {total_entries} entries, included {included_entries}")
print(f"Created filtered_playlist.m3u with {len(filtered_lines)} lines")
