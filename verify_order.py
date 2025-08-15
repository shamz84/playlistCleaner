import re
import json

# Load the expected order from JSON
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    json_data = json.load(f)

expected_order = []
for entry in json_data:
    if entry.get('exclude') == 'false':
        expected_order.append((entry.get('order', 999), entry.get('group_title')))

expected_order.sort()
print(f"Expected {len(expected_order)} groups in order:")
for i, (order, group) in enumerate(expected_order[:10], 1):
    print(f"{i:2}. Order {order}: {group}")

# Check the actual order in the filtered file
print("\nActual order in filtered file:")
with open('filtered_playlist_final.m3u', 'r', encoding='utf-8') as f:
    content = f.read()

groups_found = []
seen = set()
matches = re.findall(r'group-title="([^"]*)"', content)

for match in matches:
    if match not in seen:
        seen.add(match)
        groups_found.append(match)

print(f"Found {len(groups_found)} unique groups")
for i, group in enumerate(groups_found[:10], 1):
    print(f"{i:2}. {group}")

# Verify order matches
print(f"\nOrder verification:")
print(f"Expected order matches actual: {[g for o, g in expected_order] == groups_found}")
