import json

# Load and inspect the current file
with open("group_titles_with_flags.json", 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"Total entries: {len(data)}")

# Count exclude values
exclude_counts = {}
for entry in data:
    exclude_val = entry.get('exclude', 'unknown')
    exclude_counts[exclude_val] = exclude_counts.get(exclude_val, 0) + 1

print("Exclude value counts:")
for key, count in exclude_counts.items():
    print(f"  {key}: {count}")

# Show first 10 entries with their exclude values
print("\nFirst 10 entries:")
for i, entry in enumerate(data[:10]):
    print(f"{i+1:2}. {entry.get('group_title')[:40]:40} | exclude: {entry.get('exclude')}")

# Find first few entries with exclude=false
print("\nFirst 5 entries with exclude='false':")
false_entries = [entry for entry in data if entry.get('exclude') == 'false']
for i, entry in enumerate(false_entries[:5]):
    print(f"{i+1}. {entry.get('group_title')}")

print(f"\nTotal exclude='false' entries: {len(false_entries)}")
