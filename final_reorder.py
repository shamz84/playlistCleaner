#!/usr/bin/env python3

import json

# Read the JSON file
print("Loading JSON file...")
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Separate entries by exclude value
exclude_false = []
exclude_true = []

for entry in data:
    if entry.get('exclude') == 'false':
        exclude_false.append(entry)
    else:
        exclude_true.append(entry)

# Combine with exclude=false first
reordered = exclude_false + exclude_true

# Write the reordered data
print("Writing reordered JSON...")
with open('group_titles_with_flags.json', 'w', encoding='utf-8') as f:
    json.dump(reordered, f, indent=2, ensure_ascii=False)

print(f"Done! Moved {len(exclude_false)} exclude=false entries to the top.")
