import json

# Read original file
with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
    original_data = json.load(f)

print(f"Loaded {len(original_data)} entries from original file")

# Split by exclude value
false_entries = [item for item in original_data if item.get('exclude') == 'false']
true_entries = [item for item in original_data if item.get('exclude') == 'true']

print(f"Found {len(false_entries)} entries with exclude=false")
print(f"Found {len(true_entries)} entries with exclude=true")

# Create reordered list
reordered_data = false_entries + true_entries

print(f"Reordered list contains {len(reordered_data)} entries")

# Write to new file first
with open('group_titles_reordered.json', 'w', encoding='utf-8') as f:
    json.dump(reordered_data, f, indent=2, ensure_ascii=False)

print("Created group_titles_reordered.json with reordered data")

# Verify the new file
with open('group_titles_reordered.json', 'r', encoding='utf-8') as f:
    verify_data = json.load(f)

print(f"Verification: New file has {len(verify_data)} entries")
if len(verify_data) > 0:
    print(f"First entry exclude value: {verify_data[0].get('exclude')}")
    print(f"Last entry exclude value: {verify_data[-1].get('exclude')}")
    
    # Count false entries at the beginning
    consecutive_false = 0
    for entry in verify_data:
        if entry.get('exclude') == 'false':
            consecutive_false += 1
        else:
            break
    
    print(f"First {consecutive_false} entries have exclude=false")
    
print("Reordering complete!")
