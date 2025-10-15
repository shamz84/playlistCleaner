#!/usr/bin/env python3
"""
Check for duplicate group titles in group_titles_with_flags.json
"""

import json
from collections import Counter

def check_duplicates():
    print("🔍 Checking for duplicate group titles...")
    
    # Load the configuration file
    config_file = 'data/config/group_titles_with_flags.json'
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
    except Exception as e:
        print(f"❌ Error loading config file: {e}")
        return
    
    print(f"📊 Total entries: {len(config)}")
    
    # Extract all group titles
    group_titles = [entry['group_title'] for entry in config]
    
    # Count occurrences
    title_counts = Counter(group_titles)
    
    # Find duplicates
    duplicates = {title: count for title, count in title_counts.items() if count > 1}
    
    if not duplicates:
        print("✅ No duplicate entries found!")
        return
    
    print(f"\n❌ Found {len(duplicates)} duplicate group titles:")
    print("\nGroup Title                          | Count | Details")
    print("------------------------------------ | ----- | -------")
    
    for title, count in duplicates.items():
        # Truncate long titles for display
        display_title = title[:35] + "..." if len(title) > 35 else title
        print(f"{display_title:<36} | {count:<5} |")
        
        # Show details for each duplicate
        duplicate_entries = [entry for entry in config if entry['group_title'] == title]
        for i, entry in enumerate(duplicate_entries, 1):
            channel_count = entry.get('channel_count', 'N/A')
            exclude = entry.get('exclude', 'N/A')
            order = entry.get('order', 'N/A')
            print(f"{'':36} |       | [{i}] channels:{channel_count}, exclude:{exclude}, order:{order}")
        print()
    
    print(f"📋 Summary:")
    print(f"   Total unique group titles: {len(title_counts)}")
    print(f"   Total entries: {len(config)}")
    print(f"   Duplicate titles: {len(duplicates)}")
    print(f"   Extra entries due to duplicates: {sum(duplicates.values()) - len(duplicates)}")
    
    # Offer to save list of duplicates
    save_list = input("\nDo you want to save the duplicate list to a file? (y/n): ").strip().lower()
    if save_list == 'y':
        with open('duplicate_groups_report.txt', 'w', encoding='utf-8') as f:
            f.write("Duplicate Group Titles Report\n")
            f.write("=" * 50 + "\n\n")
            
            for title, count in duplicates.items():
                f.write(f"Group: {title}\n")
                f.write(f"Occurrences: {count}\n")
                
                duplicate_entries = [entry for entry in config if entry['group_title'] == title]
                for i, entry in enumerate(duplicate_entries, 1):
                    f.write(f"  [{i}] channels:{entry.get('channel_count')}, exclude:{entry.get('exclude')}, order:{entry.get('order')}\n")
                f.write("\n")
        
        print("💾 Duplicate report saved to: duplicate_groups_report.txt")

if __name__ == "__main__":
    check_duplicates()