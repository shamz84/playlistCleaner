#!/usr/bin/env python3
"""
Alternative group title override system using a separate configuration file.

This script uses group_title_overrides.json to define title mappings.
Useful when you want to keep override logic separate from the main configuration.

Usage:
1. Edit group_title_overrides.json to define your mappings
2. Run this script to apply the overrides to your playlist
"""

import json
import re
import os
from datetime import datetime

def load_overrides_config():
    """Load group title overrides from separate config file."""
    config_file = 'group_title_overrides.json'
    
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        overrides = config.get('overrides', {})
        print(f"📋 Loaded {len(overrides)} group title overrides from {config_file}")
        return overrides
        
    except FileNotFoundError:
        print(f"❌ Override config file not found: {config_file}")
        print("💡 Create group_title_overrides.json with your mappings")
        return {}
    except Exception as e:
        print(f"❌ Error loading override config: {e}")
        return {}

def apply_group_title_overrides_v2(input_file, output_file, overrides):
    """Apply group title overrides using separate config approach."""
    
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
    
    # Apply overrides with fuzzy matching options
    for original_title, new_title in overrides.items():
        # Try exact match first
        pattern = re.compile(r'group-title="' + re.escape(original_title) + r'"', re.IGNORECASE)
        matches = len(pattern.findall(content))
        
        if matches > 0:
            replacement = f'group-title="{new_title}"'
            content = pattern.sub(replacement, content)
            replacements_made[original_title] = {
                'new_title': new_title,
                'count': matches,
                'match_type': 'exact'
            }
        else:
            # Try partial match (contains)
            pattern = re.compile(r'group-title="([^"]*' + re.escape(original_title) + r'[^"]*)"', re.IGNORECASE)
            partial_matches = pattern.findall(content)
            
            if partial_matches:
                # Show partial matches for user confirmation
                print(f"\n🔍 Found partial matches for '{original_title}':")
                for i, match in enumerate(partial_matches[:3]):  # Show first 3
                    print(f"   {i+1}. '{match}'")
                if len(partial_matches) > 3:
                    print(f"   ... and {len(partial_matches) - 3} more")
                
                confirm = input(f"Replace all partial matches with '{new_title}'? (y/n): ").strip().lower()
                if confirm == 'y':
                    # Replace all partial matches
                    pattern = re.compile(r'group-title="[^"]*' + re.escape(original_title) + r'[^"]*"', re.IGNORECASE)
                    replacement = f'group-title="{new_title}"'
                    content = pattern.sub(replacement, content)
                    replacements_made[original_title] = {
                        'new_title': new_title,
                        'count': len(partial_matches),
                        'match_type': 'partial'
                    }
    
    # Save the modified content
    if replacements_made:
        # Create backup
        backup_file = f"{input_file}.backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        try:
            with open(backup_file, 'w', encoding='utf-8') as f:
                f.write(original_content)
            print(f"\n💾 Created backup: {backup_file}")
        except Exception as e:
            print(f"⚠️  Warning: Could not create backup: {e}")
        
        # Save modified content
        try:
            with open(output_file, 'w', encoding='utf-8') as f:
                f.write(content)
            
            print(f"✅ Successfully applied overrides to: {output_file}")
            
            # Show summary
            print(f"\n📊 Override Summary:")
            print("Original Title                       → New Title                          | Changes | Type")
            print("-" * 100)
            
            for original, info in replacements_made.items():
                orig_display = original[:30] + "..." if len(original) > 30 else original
                new_display = info['new_title'][:30] + "..." if len(info['new_title']) > 30 else info['new_title']
                match_type = info['match_type']
                print(f"{orig_display:<33} → {new_display:<33} | {info['count']:<7} | {match_type}")
            
            total_changes = sum(info['count'] for info in replacements_made.values())
            print(f"\nTotal group title changes: {total_changes}")
            
            return True
            
        except Exception as e:
            print(f"❌ Error writing output file: {e}")
            return False
    else:
        print("ℹ️  No matching group titles found to override")
        return True

def preview_overrides():
    """Preview what overrides would be applied without making changes."""
    overrides = load_overrides_config()
    if not overrides:
        return
    
    input_file = "data/downloaded_file.m3u"
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        return
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            content = f.read()
    except Exception as e:
        print(f"❌ Error reading input file: {e}")
        return
    
    print("🔍 Preview: Group titles that would be changed")
    print("-" * 60)
    
    found_matches = 0
    for original_title, new_title in overrides.items():
        pattern = re.compile(r'group-title="' + re.escape(original_title) + r'"', re.IGNORECASE)
        matches = len(pattern.findall(content))
        
        if matches > 0:
            print(f"'{original_title}' → '{new_title}' ({matches} channels)")
            found_matches += matches
    
    if found_matches == 0:
        print("No matching group titles found")
    else:
        print(f"\nTotal channels that would be affected: {found_matches}")

def main():
    print("🔄 Group Title Override Tool (Separate Config)")
    print("=" * 50)
    
    # Check for input file
    input_file = "data/downloaded_file.m3u"
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        print("💡 Make sure you have downloaded a playlist first")
        return
    
    # Load overrides
    overrides = load_overrides_config()
    if not overrides:
        return
    
    # Show menu
    print("\nOptions:")
    print("1. Preview changes (no modifications)")
    print("2. Apply overrides to playlist")
    print("3. Exit")
    
    choice = input("\nEnter your choice (1-3): ").strip()
    
    if choice == "1":
        preview_overrides()
    elif choice == "2":
        output_file = input_file  # Overwrite original
        success = apply_group_title_overrides_v2(input_file, output_file, overrides)
        if success:
            print("\n🎉 Group title overrides applied successfully!")
    elif choice == "3":
        print("👋 Goodbye!")
    else:
        print("❌ Invalid choice")

if __name__ == "__main__":
    main()