#!/usr/bin/env python3
"""
Sort group_titles_with_flags.json by exclude flag (false first) then by order
"""

import json

def main():
    print("🔄 Sorting group_titles_with_flags.json by exclude flag and order...")
    
    # Load current config
    input_file = 'data/config/group_titles_with_flags.json'
    with open(input_file, 'r', encoding='utf-8') as f:
        config = json.load(f)
    
    print(f"📊 Loaded {len(config)} groups")
    
    # Sort by exclude flag (false first), then by order
    # exclude=false will come before exclude=true when sorted as strings
    sorted_config = sorted(config, key=lambda x: (x['exclude'], x['order']))
    
    # Count groups by exclude flag
    include_count = sum(1 for entry in sorted_config if entry['exclude'] == 'false')
    exclude_count = sum(1 for entry in sorted_config if entry['exclude'] == 'true')
    
    print(f"📈 Groups breakdown:")
    print(f"  • Include (exclude=false): {include_count}")
    print(f"  • Exclude (exclude=true): {exclude_count}")
    print(f"  • Total: {len(sorted_config)}")
    
    # Create backup
    backup_file = 'data/config/group_titles_with_flags_backup_before_sort.json'
    with open(backup_file, 'w', encoding='utf-8') as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
    print(f"💾 Created backup: {backup_file}")
    
    # Write sorted config
    with open(input_file, 'w', encoding='utf-8') as f:
        json.dump(sorted_config, f, indent=2, ensure_ascii=False)
    
    print(f"✅ Sorted configuration saved to: {input_file}")
    print(f"\n📋 File structure:")
    print(f"  • Groups with exclude=false (include): {include_count} groups (orders 1-{include_count if include_count > 0 else 0})")
    print(f"  • Groups with exclude=true (exclude): {exclude_count} groups (orders {include_count+1 if exclude_count > 0 else 0}-{len(sorted_config)})")
    
    # Show first few of each type
    print(f"\n🔍 Preview - First 5 included groups:")
    included_groups = [g for g in sorted_config if g['exclude'] == 'false']
    for i, group in enumerate(included_groups[:5]):
        print(f"  {i+1}. {group['group_title']} (order: {group['order']})")
    
    print(f"\n🔍 Preview - First 5 excluded groups:")
    excluded_groups = [g for g in sorted_config if g['exclude'] == 'true']
    for i, group in enumerate(excluded_groups[:5]):
        print(f"  {i+1}. {group['group_title']} (order: {group['order']})")

if __name__ == "__main__":
    main()
