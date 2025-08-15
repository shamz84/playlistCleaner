#!/usr/bin/env python3
"""
Script to reorder group_titles_with_flags.json to place all entries with "exclude": "false" at the top.
"""

import json
import os

def reorder_json_by_exclude():
    # File paths
    input_file = "group_titles_with_flags.json"
    
    # Check if input file exists
    if not os.path.exists(input_file):
        print(f"Error: {input_file} not found!")
        return
    
    # Read the JSON file
    print(f"Reading {input_file}...")
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    print(f"Total entries: {len(data)}")
    
    # Separate entries by exclude value
    exclude_false = []
    exclude_true = []
    
    for entry in data:
        if entry.get("exclude") == "false":
            exclude_false.append(entry)
        else:
            exclude_true.append(entry)
    
    print(f"Entries with exclude='false': {len(exclude_false)}")
    print(f"Entries with exclude='true': {len(exclude_true)}")
    
    # Combine lists with exclude=false first
    reordered_data = exclude_false + exclude_true
    
    # Write the reordered JSON back to the file
    print(f"Writing reordered data to {input_file}...")
    with open(input_file, 'w', encoding='utf-8') as f:
        json.dump(reordered_data, f, indent=2, ensure_ascii=False)
    
    print("✅ JSON file successfully reordered!")
    print(f"   - {len(exclude_false)} entries with exclude='false' at the top")
    print(f"   - {len(exclude_true)} entries with exclude='true' at the bottom")

if __name__ == "__main__":
    reorder_json_by_exclude()
