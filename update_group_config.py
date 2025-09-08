#!/usr/bin/env python3
"""
Update group_titles_with_flags.json with all categories found in the playlist
"""

import json
import re
from collections import Counter

def categorize_group(group_name):
    """Automatically categorize a group and suggest exclude flag based on patterns."""
    group_lower = group_name.lower()
    
    # Patterns that should typically be excluded
    exclude_patterns = [
        # Adult content
        ('adult', 'xxx', 'for adults'),
        # Local networks/affiliates
        ('network', 'affiliates', 'local'),
        # TV guides
        ('guide', 'tv guide'),
        # Low quality
        (' sd', 'sd '),
        # HEVC (codec issues)
        ('hevc', 'h265'),
        # Specific sports leagues that are usually PPV/premium
        ('league pass', 'center ice', 'extra innings', 'sunday ticket'),
        # Game replays
        ('replays', 'replay'),
        # Some PPV patterns
        ('ppv game', 'ppv event'),
    ]
    
    # Check for exclusion patterns
    for patterns in exclude_patterns:
        if any(pattern in group_lower for pattern in patterns):
            return "true"
    
    # Patterns that should typically be included
    include_patterns = [
        # General content
        ('entertainment', 'general', 'news', 'kids', 'music'),
        # Sports (non-PPV)
        ('sport', 'espn+', 'peacock', 'paramount+'),
        # Premium services
        ('hbo max', 'disney+', 'amazon prime', 'netflix', 'hulu'),
        # High quality
        ('4k', 'uhd', 'hd', 'raw'),
        # International good content
        ('uk|', 'ca|', 'au|', 'nz|'),
    ]
    
    # Check for inclusion patterns
    for patterns in include_patterns:
        if any(pattern in group_lower for pattern in patterns):
            return "false"
    
    # Default to exclude for unknown patterns (conservative approach)
    return "false"  # Changed to false to be more inclusive by default

def main():
    print("🔄 Updating group_titles_with_flags.json with all categories...")
    
    # Load current config
    with open('data/config/group_titles_with_flags.json', 'r', encoding='utf-8') as f:
        current_config = json.load(f)
    
    # Get the highest order number
    max_order = max(entry['order'] for entry in current_config)
    print(f"📊 Current config has {len(current_config)} groups, max order: {max_order}")
    
    # Load playlist to find all groups
    with open('data/downloaded_file.m3u', 'r', encoding='utf-8') as f:
        content = f.read()
    
    groups = re.findall(r'group-title="([^"]+)"', content)
    group_counts = Counter(groups)
    
    print(f"📊 Found {len(group_counts)} unique groups in playlist")
    
    # Create sets of existing groups
    existing_groups = {entry['group_title'] for entry in current_config}
    
    # Find new groups (not in current config)
    new_groups = []
    for group, count in group_counts.items():
        if group not in existing_groups:
            new_groups.append((group, count))
    
    print(f"📊 Found {len(new_groups)} new groups to add")
    
    if not new_groups:
        print("✅ No new groups to add - configuration is up to date!")
        return
    
    # Sort new groups by channel count (descending) for better organization
    new_groups.sort(key=lambda x: x[1], reverse=True)
    
    # Add new groups to config
    updated_config = current_config.copy()
    current_order = max_order
    
    print("\n🔍 Auto-categorizing new groups...")
    added_count = 0
    
    for group, count in new_groups:
        current_order += 1
        exclude_flag = categorize_group(group)
        
        new_entry = {
            "group_title": group,
            "channel_count": count,
            "exclude": exclude_flag,
            "order": current_order
        }
        
        updated_config.append(new_entry)
        added_count += 1
        
        status = "❌ EXCLUDE" if exclude_flag == "true" else "✅ INCLUDE"
        print(f"  {status}: '{group}' ({count} channels)")
    
    # Sort by order to maintain consistency
    updated_config.sort(key=lambda x: x['order'])
    
    # Create backup of original
    backup_file = 'data/config/group_titles_with_flags_backup.json'
    with open(backup_file, 'w', encoding='utf-8') as f:
        json.dump(current_config, f, indent=2, ensure_ascii=False)
    print(f"\n💾 Created backup: {backup_file}")
    
    # Write updated config
    output_file = 'data/config/group_titles_with_flags_updated.json'
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(updated_config, f, indent=2, ensure_ascii=False)
    
    print(f"\n✅ Updated configuration saved to: {output_file}")
    print(f"📊 Total groups: {len(updated_config)} ({added_count} new)")
    
    # Summary statistics
    include_count = sum(1 for entry in updated_config if entry['exclude'] == 'false')
    exclude_count = sum(1 for entry in updated_config if entry['exclude'] == 'true')
    
    print(f"\n📈 Configuration Summary:")
    print(f"  • Groups to include (exclude=false): {include_count}")
    print(f"  • Groups to exclude (exclude=true): {exclude_count}")
    print(f"  • Total groups: {len(updated_config)}")
    
    print(f"\n💡 Next steps:")
    print(f"  1. Review the updated configuration: {output_file}")
    print(f"  2. Adjust any exclude flags as needed")
    print(f"  3. Replace the original file when satisfied:")
    print(f"     Copy-Item '{output_file}' 'data/config/group_titles_with_flags.json'")

if __name__ == "__main__":
    main()
