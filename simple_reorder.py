import json

# Read the JSON file
with open("group_titles_with_flags.json", 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"Total entries: {len(data)}")

# Separate by exclude value
exclude_false = [entry for entry in data if entry.get("exclude") == "false"]
exclude_true = [entry for entry in data if entry.get("exclude") == "true"]

print(f"Exclude false: {len(exclude_false)}")
print(f"Exclude true: {len(exclude_true)}")

# Reorder: false first, then true
reordered = exclude_false + exclude_true

print(f"Reordered length: {len(reordered)}")
print(f"First entry exclude value: {reordered[0].get('exclude')}")
print(f"Last entry exclude value: {reordered[-1].get('exclude')}")

# Save the reordered data
with open("group_titles_with_flags.json", 'w', encoding='utf-8') as f:
    json.dump(reordered, f, indent=2, ensure_ascii=False)

print("Done!")
