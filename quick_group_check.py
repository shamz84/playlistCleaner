import re
import json

# Extract groups from downloaded playlist
with open('data/downloaded_file.m3u', 'r', encoding='utf-8') as f:
    content = f.read()

groups = re.findall(r'group-title="([^"]+)"', content)
from collections import Counter
group_counts = Counter(groups)

print(f"Found {len(group_counts)} unique groups in downloaded playlist")
print(f"Total entries: {sum(group_counts.values())}")

# Load config groups
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    config = json.load(f)

config_groups = set([entry['group_title'] for entry in config])

# Find unknown groups
playlist_groups = set(group_counts.keys())
unknown_groups = playlist_groups - config_groups

print(f"\nConfig has {len(config_groups)} groups")
print(f"Playlist has {len(playlist_groups)} groups")
print(f"Unknown groups (not in config): {len(unknown_groups)}")

if unknown_groups:
    print("\nUnknown groups:")
    for group in sorted(unknown_groups):
        count = group_counts[group]
        print(f"  {count:4d} channels - '{group}'")
    
    total_unknown = sum([group_counts[g] for g in unknown_groups])
    print(f"\nTotal channels in unknown groups: {total_unknown}")
    print(f"These channels would be EXCLUDED by the current filter!")
else:
    print("\nAll groups are known!")
