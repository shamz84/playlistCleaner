#!/usr/bin/env python3
"""
List all group titles where exclude is set to "false".
"""

import json


def main():
    json_file = r"group_titles_with_flags.json"
    
    try:
        # Read the JSON file with UTF-8 encoding
        with open(json_file, 'r', encoding='utf-8') as file:
            group_data = json.load(file)
        
        print(f"Loaded {len(group_data)} group titles from JSON file")
        print("=" * 60)
        
        # Filter groups with exclude = "false"
        exclude_false_groups = [entry for entry in group_data if entry.get('exclude') == "false"]
        
        print(f"Group titles with exclude = 'false': {len(exclude_false_groups)}")
        print("=" * 60)
        
        # Display the groups
        for i, entry in enumerate(exclude_false_groups, 1):
            print(f"{i:3d}. {entry['group_title']} ({entry['channel_count']} channels) - Order: {entry['order']}")
        
        print("=" * 60)
        print(f"Total groups with exclude='false': {len(exclude_false_groups)}")
        print(f"Total groups with exclude='true': {len(group_data) - len(exclude_false_groups)}")
        
    except FileNotFoundError:
        print(f"Error: File '{json_file}' not found.")
    except Exception as e:
        print(f"Error reading file: {e}")


if __name__ == "__main__":
    main()
