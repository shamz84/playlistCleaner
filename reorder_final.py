import json
import sys

def reorder_json():
    try:
        # Read the JSON file
        print("Reading group_titles_with_flags.json...")
        with open("group_titles_with_flags.json", 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        print(f"Loaded {len(data)} entries")
        
        # Count current distribution
        false_entries = []
        true_entries = []
        
        for entry in data:
            exclude_value = entry.get("exclude")
            if exclude_value == "false":
                false_entries.append(entry)
            elif exclude_value == "true":
                true_entries.append(entry)
            else:
                print(f"Warning: Unexpected exclude value '{exclude_value}' for {entry.get('group_title')}")
        
        print(f"Found {len(false_entries)} entries with exclude='false'")
        print(f"Found {len(true_entries)} entries with exclude='true'")
        
        # Create reordered list: false entries first, then true entries
        reordered_data = false_entries + true_entries
        
        print(f"Reordered list has {len(reordered_data)} entries")
        
        # Verify the reordering
        if len(reordered_data) > 0:
            print(f"First entry after reordering: {reordered_data[0].get('group_title')} (exclude: {reordered_data[0].get('exclude')})")
            print(f"Last entry after reordering: {reordered_data[-1].get('group_title')} (exclude: {reordered_data[-1].get('exclude')})")
        
        # Write back to file
        print("Writing reordered data back to file...")
        with open("group_titles_with_flags.json", 'w', encoding='utf-8') as f:
            json.dump(reordered_data, f, indent=2, ensure_ascii=False)
        
        print("✅ Successfully reordered JSON file!")
        print(f"   • {len(false_entries)} entries with exclude='false' are now at the top")
        print(f"   • {len(true_entries)} entries with exclude='true' are now at the bottom")
        
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    reorder_json()
