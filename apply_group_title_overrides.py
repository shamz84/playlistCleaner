#!/usr/bin/env python3
"""
Apply group title overrides to M3U playlist based on group_titles_with_flags.json configuration.

This script allows you to override/replace group titles in the playlist using mappings
defined in the group configuration file.

Usage:
1. Add "override_title" field to entries in group_titles_with_flags.json
2. Run this script to apply the overrides to your playlist

Example configuration entry:
{
  "group_title": "Original Title",
  "override_title": "New Standardized Title", 
  "channel_count": 50,
  "exclude": "false",
  "order": 10
}
"""

import json
import re
import os
from datetime import datetime

def load_group_overrides():
    """Load group title overrides from configuration file."""
    config_file = 'data/config/group_titles_with_flags.json'
    
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        overrides = {}
        override_count = 0
        
        for entry in config:
            original_title = entry.get('group_title')
            override_title = entry.get('override_title')
            
            if override_title and override_title != original_title:
                overrides[original_title] = override_title
                override_count += 1
        
        print(f"📋 Loaded {override_count} group title overrides from configuration")
        return overrides
        
    except Exception as e:
        print(f"❌ Error loading configuration: {e}")
        return {}

def apply_group_title_overrides(input_file, output_file, overrides):
    """Apply group title overrides to the M3U playlist."""
    
    if not overrides:
        print("⚠️  No overrides found - no changes will be made")
        return False
    
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        return False
    
    print(f"📖 Reading playlist: {input_file}")
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            content = f.read()
    except Exception as e:
        print(f"❌ Error reading input file: {e}")
        return False
    
    # Track replacements
    replacements_made = {}
    original_content = content
    
    # Apply overrides
    for original_title, new_title in overrides.items():
        # Create regex pattern to match group-title="Original Title"
        pattern = re.compile(r'group-title="' + re.escape(original_title) + r'"', re.IGNORECASE)
        replacement = f'group-title="{new_title}"'
        
        # Count matches before replacement
        matches = len(pattern.findall(content))
        if matches > 0:
            content = pattern.sub(replacement, content)
            replacements_made[original_title] = {
                'new_title': new_title,
                'count': matches
            }
    
    # Save the modified content
    if replacements_made:
        # Create backup
        backup_file = f"{input_file}.backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        try:
            with open(backup_file, 'w', encoding='utf-8') as f:
                f.write(original_content)
            print(f"💾 Created backup: {backup_file}")
        except Exception as e:
            print(f"⚠️  Warning: Could not create backup: {e}")
        
        # Save modified content
        try:
            with open(output_file, 'w', encoding='utf-8') as f:
                f.write(content)
            
            print(f"✅ Successfully applied overrides to: {output_file}")
            
            # Show summary
            print(f"\n📊 Override Summary:")
            print("Original Title                       → New Title                          | Changes")
            print("-" * 90)
            
            for original, info in replacements_made.items():
                orig_display = original[:35] + "..." if len(original) > 35 else original
                new_display = info['new_title'][:35] + "..." if len(info['new_title']) > 35 else info['new_title']
                print(f"{orig_display:<36} → {new_display:<36} | {info['count']}")
            
            total_changes = sum(info['count'] for info in replacements_made.values())
            print(f"\nTotal group title changes: {total_changes}")
            
            return True
            
        except Exception as e:
            print(f"❌ Error writing output file: {e}")
            return False
    else:
        print("ℹ️  No matching group titles found to override")
        return True

def main():
    print("🔄 Group Title Override Tool")
    print("=" * 40)
    
    # Default file paths
    input_file = "data/downloaded_file.m3u"
    output_file = "data/downloaded_file.m3u"  # Overwrite the original
    
    # Check if input file exists
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        print("💡 Make sure you have downloaded a playlist first")
        return
    
    # Load overrides from configuration
    overrides = load_group_overrides()
    
    if not overrides:
        print("ℹ️  No group title overrides configured")
        print("\n💡 To add overrides, edit your group_titles_with_flags.json file:")
        print('   Add "override_title" field to entries you want to rename')
        print('   Example: "override_title": "Standardized Title Name"')
        return
    
    print(f"\n🔍 Configured Overrides:")
    for original, new in list(overrides.items())[:5]:  # Show first 5
        print(f"   '{original}' → '{new}'")
    if len(overrides) > 5:
        print(f"   ... and {len(overrides) - 5} more")
    
    # Confirm with user
    confirm = input(f"\nApply {len(overrides)} overrides to {input_file}? (y/n): ").strip().lower()
    if confirm != 'y':
        print("Operation cancelled by user")
        return
    
    # Apply overrides
    success = apply_group_title_overrides(input_file, output_file, overrides)
    
    if success:
        print("\n🎉 Group title overrides applied successfully!")
        print("💡 Your playlist now uses the standardized group titles")
    else:
        print("\n❌ Failed to apply overrides")

if __name__ == "__main__":
    main()