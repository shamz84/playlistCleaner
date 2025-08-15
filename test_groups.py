import json

# Test JSON loading
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"Total groups: {len(data)}")

# Get first 10 exclude=false groups
allowed = []
for entry in data:
    if entry.get('exclude') == 'false':
        allowed.append(entry['group_title'])

print(f"Total allowed groups: {len(allowed)}")
print("First 10 allowed groups:")
for i, group in enumerate(allowed[:10], 1):
    print(f"{i:2}. '{group}'")
