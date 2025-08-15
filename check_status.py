import json

with open("group_titles_with_flags.json", 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"Total entries: {len(data)}")
print(f"First entry exclude: {data[0].get('exclude')}")
print(f"Last entry exclude: {data[-1].get('exclude')}")

false_count = sum(1 for x in data if x.get('exclude') == 'false')
true_count = sum(1 for x in data if x.get('exclude') == 'true')

print(f"Exclude false count: {false_count}")
print(f"Exclude true count: {true_count}")

# Show first 5 entries
print("\nFirst 5 entries:")
for i, entry in enumerate(data[:5]):
    print(f"{i+1}. {entry.get('group_title')} - exclude: {entry.get('exclude')}")
