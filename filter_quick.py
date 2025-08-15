import json
import re

# Load groups
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

allowed = {x['group_title'] for x in data if x.get('exclude') == 'false'}

# Process M3U
with open('raw_playlist_20.m3u', 'r', encoding='utf-8') as f:
    lines = f.readlines()

filtered = ['#EXTM3U\n']
total = 0
included = 0

i = 1
while i < len(lines):
    if lines[i].startswith('#EXTINF:'):
        total += 1
        match = re.search(r'group-title="([^"]*)"', lines[i])
        if match and match.group(1) in allowed:
            filtered.extend([lines[i], lines[i+1]])
            included += 1
        i += 2
    else:
        i += 1

with open('filtered_playlist.m3u', 'w', encoding='utf-8') as f:
    f.writelines(filtered)

print(f'{included}/{total} entries included')
