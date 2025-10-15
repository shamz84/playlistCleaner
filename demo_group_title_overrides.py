#!/usr/bin/env python3
"""
Demo: How to Add Group Title Overrides to Your Configuration

This script demonstrates how to add group title overrides to your existing
group_titles_with_flags.json configuration file.

The override system allows you to standardize group titles by mapping original
titles to new, consistent names.
"""

import json
import os
from datetime import datetime

def create_example_config():
    """Create an example configuration with override examples"""
    
    example_config = [
        {
            "group_title": "UK Entertainment", 
            "override_title": "🇬🇧 UK Entertainment",
            "channel_count": 45,
            "exclude": "false",
            "order": 1
        },
        {
            "group_title": "Movies HD",
            "override_title": "🎬 Movies HD", 
            "channel_count": 120,
            "exclude": "false",
            "order": 2
        },
        {
            "group_title": "24/7 Comedy",
            "override_title": "😂 24/7 Comedy",
            "channel_count": 25,
            "exclude": "false", 
            "order": 3
        },
        {
            "group_title": "News USA",
            "override_title": "📰 US News",
            "channel_count": 30,
            "exclude": "false",
            "order": 4
        },
        {
            "group_title": "Kids Cartoons",
            "override_title": "👶 Kids Content",
            "channel_count": 85,
            "exclude": "false",
            "order": 5
        },
        {
            "group_title": "Adult Content",
            "channel_count": 150,
            "exclude": "true",
            "order": 999
        }
    ]
    
    return example_config

def show_override_examples():
    """Show examples of how overrides work"""
    print("🎯 Group Title Override Examples")
    print("=" * 50)
    print()
    
    examples = [
        ("UK Entertainment", "🇬🇧 UK Entertainment", "Add country flag emoji"),
        ("Movies HD", "🎬 Movies HD", "Add category emoji"),
        ("24/7 Comedy", "😂 24/7 Comedy", "Add content type emoji"),
        ("News USA", "📰 US News", "Standardize format"),
        ("Kids Cartoons", "👶 Kids Content", "Unify kids categories"),
        ("Adult Content", None, "No override - will be excluded")
    ]
    
    print("Original Title         → New Title                    | Purpose")
    print("-" * 80)
    
    for original, new, purpose in examples:
        if new:
            print(f"{original:<22} → {new:<29} | {purpose}")
        else:
            print(f"{original:<22} → (excluded)                   | {purpose}")
    
    print()

def show_json_structure():
    """Show the JSON structure for overrides"""
    print("📝 JSON Configuration Structure")
    print("=" * 40)
    print()
    
    print("Add 'override_title' field to any entry you want to rename:")
    print()
    
    example = {
        "group_title": "Original Title",
        "override_title": "New Standardized Title",
        "channel_count": 50,
        "exclude": "false", 
        "order": 10
    }
    
    print(json.dumps(example, indent=2))
    print()
    
    print("💡 Key Points:")
    print("   • 'override_title' is optional - only add it when you want to rename")
    print("   • If 'override_title' matches 'group_title', no change will be made")
    print("   • Leave out 'override_title' to keep the original name")
    print("   • Excluded groups don't need overrides (they won't appear anyway)")

def show_workflow():
    """Show the complete workflow"""
    print("🔄 Complete Workflow")
    print("=" * 25)
    print()
    
    steps = [
        "1. Edit group_titles_with_flags.json",
        "   Add 'override_title' fields where needed",
        "",
        "2. Run the enhanced pipeline:",
        "   python process_playlist_complete_enhanced.py",
        "   OR",
        "   python process_playlist_complete_enhanced.py --skip-overrides",
        "",
        "3. Or apply overrides manually:",
        "   python apply_group_title_overrides.py",
        "",
        "4. Check the results in your filtered playlist"
    ]
    
    for step in steps:
        print(step)
    
    print()

def backup_current_config():
    """Create a backup of the current configuration"""
    config_file = "data/config/group_titles_with_flags.json"
    
    if os.path.exists(config_file):
        backup_file = f"{config_file}.backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        try:
            import shutil
            shutil.copy2(config_file, backup_file)
            print(f"📁 Created backup: {backup_file}")
            return True
        except Exception as e:
            print(f"❌ Could not create backup: {e}")
            return False
    else:
        print(f"❌ Configuration file not found: {config_file}")
        return False

def add_sample_overrides():
    """Add sample overrides to the existing configuration"""
    config_file = "data/config/group_titles_with_flags.json"
    
    if not os.path.exists(config_file):
        print(f"❌ Configuration file not found: {config_file}")
        return False
    
    # Create backup first
    if not backup_current_config():
        return False
    
    try:
        # Load existing config
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        # Add sample overrides to first few entries
        sample_overrides = {
            "UK": "🇬🇧 United Kingdom",
            "USA": "🇺🇸 United States", 
            "Movies": "🎬 Movies",
            "News": "📰 News",
            "Kids": "👶 Kids",
            "Sports": "⚽ Sports"
        }
        
        modified_count = 0
        
        for entry in config[:10]:  # Only modify first 10 entries as examples
            group_title = entry.get('group_title', '')
            
            # Check if any sample override key is in the group title
            for key, override in sample_overrides.items():
                if key.lower() in group_title.lower() and 'override_title' not in entry:
                    # Create a sensible override
                    if key == "UK":
                        entry['override_title'] = group_title.replace("UK", "🇬🇧 UK")
                    elif key == "USA":
                        entry['override_title'] = group_title.replace("USA", "🇺🇸 USA")
                    else:
                        entry['override_title'] = f"{override.split()[0]} {group_title}"
                    
                    modified_count += 1
                    break
        
        # Save modified config
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2, ensure_ascii=False)
        
        print(f"✅ Added {modified_count} sample overrides to configuration")
        print("💡 Review the changes and adjust as needed")
        
        return True
        
    except Exception as e:
        print(f"❌ Error modifying configuration: {e}")
        return False

def main():
    print("🎯 Group Title Override Demo & Setup")
    print("=" * 45)
    print()
    
    show_override_examples()
    print()
    show_json_structure() 
    print()
    show_workflow()
    print()
    
    print("🛠️  Setup Options:")
    print("1. View examples only (no changes)")
    print("2. Add sample overrides to your configuration")
    print("3. Create example configuration file")
    print("4. Exit")
    
    choice = input("\nEnter your choice (1-4): ").strip()
    
    if choice == "1":
        print("👀 Examples shown above - no changes made")
        
    elif choice == "2":
        print("\n🔧 Adding sample overrides to your configuration...")
        success = add_sample_overrides()
        if success:
            print("\n🎉 Sample overrides added!")
            print("💡 Edit data/config/group_titles_with_flags.json to customize")
        
    elif choice == "3":
        print("\n📝 Creating example configuration...")
        example_config = create_example_config()
        
        example_file = "group_titles_overrides_example.json"
        try:
            with open(example_file, 'w', encoding='utf-8') as f:
                json.dump(example_config, f, indent=2, ensure_ascii=False)
            print(f"✅ Example configuration created: {example_file}")
            print("💡 Use this as a reference for your own overrides")
        except Exception as e:
            print(f"❌ Error creating example: {e}")
            
    elif choice == "4":
        print("👋 Goodbye!")
        
    else:
        print("❌ Invalid choice")

if __name__ == "__main__":
    main()