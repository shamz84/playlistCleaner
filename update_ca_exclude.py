#!/usr/bin/env python3
"""
Update the exclude property for CA| prefixed group titles.
Changes exclude from "false" to "true" for all group titles starting with "CA|".
"""

import json


def update_ca_exclude_flags(json_file_path):
    """Update exclude flags for CA| prefixed group titles."""
    try:
        # Read the current JSON file
        with open(json_file_path, 'r', encoding='utf-8') as file:
            group_data = json.load(file)
        
        print(f"Loaded {len(group_data)} group titles from JSON file")
        
        # Track changes
        updated_count = 0
        
        # Update CA| prefixed group titles
        for entry in group_data:
            if entry['group_title'].startswith('CA|'):
                if entry['exclude'] == "false":
                    entry['exclude'] = "true"
                    updated_count += 1
                    print(f"Updated: {entry['group_title']}")
        
        # Write back to JSON file
        with open(json_file_path, 'w', encoding='utf-8') as file:
            json.dump(group_data, file, indent=2, ensure_ascii=False)
        
        print(f"\nSuccessfully updated {updated_count} CA| group titles")
        print(f"Updated JSON file: {json_file_path}")
        
        # Show summary of CA| groups
        ca_groups = [entry for entry in group_data if entry['group_title'].startswith('CA|')]
        print(f"\nTotal CA| groups: {len(ca_groups)}")
        print("CA| groups with exclude=true:")
        for entry in ca_groups:
            if entry['exclude'] == "true":
                print(f"  - {entry['group_title']} ({entry['channel_count']} channels)")
        
        return updated_count
        
    except FileNotFoundError:
        print(f"Error: File '{json_file_path}' not found.")
        return 0
    except Exception as e:
        print(f"Error updating file: {e}")
        return 0


def main():
    json_file = r"c:\dev\training\PlaylistCleaner\group_titles_with_flags.json"
    
    print("Updating CA| group titles to exclude=true...")
    updated_count = update_ca_exclude_flags(json_file)
    
    if updated_count > 0:
        print(f"\n✅ Successfully updated {updated_count} CA| group titles!")
    else:
        print("\n⚠️  No CA| group titles were updated (they may already be set to exclude=true)")


if __name__ == "__main__":
    main()
