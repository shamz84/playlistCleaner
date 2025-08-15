#!/usr/bin/env python3
"""
Debug version of reorder script to see what's happening.
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
    
    print(f"File exists: {os.path.exists(input_file)}")
    print(f"Current working directory: {os.getcwd()}")
    
    # Read the JSON file
    print(f"Reading {input_file}...")
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        print(f"Successfully loaded JSON with {len(data)} entries")
    except Exception as e:
        print(f"Error reading JSON: {e}")
        return
    
    # Check first few entries before reordering
    print("\nFirst 3 entries before reordering:")
    for i, entry in enumerate(data[:3]):
        print(f"  {i+1}. {entry.get('group_title')} - exclude: {entry.get('exclude')}")
    
    # Separate entries by exclude value
    exclude_false = []
    exclude_true = []
    
    for entry in data:
        if entry.get("exclude") == "false":
            exclude_false.append(entry)
        else:
            exclude_true.append(entry)
    
    print(f"\nEntries with exclude='false': {len(exclude_false)}")
    print(f"Entries with exclude='true': {len(exclude_true)}")
    
    # Show first few exclude=false entries
    print("\nFirst 3 exclude=false entries:")
    for i, entry in enumerate(exclude_false[:3]):
        print(f"  {i+1}. {entry.get('group_title')}")
    
    # Combine lists with exclude=false first
    reordered_data = exclude_false + exclude_true
    
    print(f"\nReordered data length: {len(reordered_data)}")
    
    # Check first few entries after reordering
    print("\nFirst 3 entries after reordering:")
    for i, entry in enumerate(reordered_data[:3]):
        print(f"  {i+1}. {entry.get('group_title')} - exclude: {entry.get('exclude')}")
    
    # Write the reordered JSON back to the file
    print(f"\nWriting reordered data to {input_file}...")
    try:
        with open(input_file, 'w', encoding='utf-8') as f:
            json.dump(reordered_data, f, indent=2, ensure_ascii=False)
        print("✅ JSON file successfully written!")
    except Exception as e:
        print(f"Error writing JSON: {e}")
        return
    
    print(f"✅ JSON file successfully reordered!")
    print(f"   - {len(exclude_false)} entries with exclude='false' at the top")
    print(f"   - {len(exclude_true)} entries with exclude='true' at the bottom")

if __name__ == "__main__":
    reorder_json_by_exclude()
