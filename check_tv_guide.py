#!/usr/bin/env python3
"""
Check TV Guide groups in configuration
"""

import json

def check_tv_guide_groups():
    """Check all TV Guide groups and their exclude status."""
    
    try:
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        print("📺 TV Guide Groups in Configuration:")
        tv_guide_groups = [item for item in data if 'tv guide' in item['group_title'].lower()]
        
        for item in tv_guide_groups:
            group_title = item['group_title']
            exclude_status = item['exclude']
            channel_count = item.get('channel_count', 0)
            print(f"   • {group_title}: exclude={exclude_status} ({channel_count} channels)")
        
        if tv_guide_groups:
            included_count = sum(1 for item in tv_guide_groups if item['exclude'] == 'false')
            excluded_count = len(tv_guide_groups) - included_count
            print(f"\n📊 Summary:")
            print(f"   • TV Guide groups included: {included_count}")
            print(f"   • TV Guide groups excluded: {excluded_count}")
            
            if included_count > 0:
                print(f"\n💡 Note: Some TV Guide groups are currently set to include.")
                print(f"    If you want to exclude all TV Guide content, you should:")
                print(f"    1. Update the configuration to set exclude='true' for all TV Guide groups")
                print(f"    2. Or add better pattern matching in the filter logic")
        
    except Exception as e:
        print(f"Error checking TV Guide groups: {e}")

if __name__ == "__main__":
    check_tv_guide_groups()
