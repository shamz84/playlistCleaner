#!/usr/bin/env python3
"""
Integrate the new 24/7 subcategory groups into the main group configuration
"""

import json
import os

def find_config_file(filename):
    """Find config file using config-first approach"""
    config_path = f"data/config/{filename}"
    root_path = filename
    
    if os.path.exists(config_path):
        return config_path
    elif os.path.exists(root_path):
        return root_path
    else:
        return filename

def integrate_247_groups():
    """Integrate new 24/7 groups into main configuration"""
    
    # Load the main group configuration
    main_config_file = find_config_file('group_titles_with_flags.json')
    try:
        with open(main_config_file, 'r', encoding='utf-8') as f:
            main_config = json.load(f)
        print(f"✅ Loaded main config: {main_config_file}")
    except FileNotFoundError:
        print(f"❌ Main config file not found: {main_config_file}")
        return False
    
    # Load the new 24/7 groups configuration
    new_config_file = 'new_247_groups_config.json'
    try:
        with open(new_config_file, 'r', encoding='utf-8') as f:
            new_groups = json.load(f)
        print(f"✅ Loaded new groups config: {new_config_file}")
    except FileNotFoundError:
        print(f"❌ New groups config file not found: {new_config_file}")
        print("💡 Run analyze_247_channels.py first to generate the new groups")
        return False
    
    print(f"\n📋 New groups to integrate:")
    for group in new_groups:
        print(f"   - {group['group_title']}: {group['channel_count']} channels")
    
    # Find the original "24/7 Channels" group
    original_247_index = None
    original_247_group = None
    for i, group in enumerate(main_config):
        if group['group_title'] == '24/7 Channels':
            original_247_index = i
            original_247_group = group
            break
    
    if original_247_index is None:
        print("❌ Original '24/7 Channels' group not found in main config")
        return False
    
    print(f"\n🔍 Found original '24/7 Channels' group:")
    print(f"   - Index: {original_247_index}")
    print(f"   - Channels: {original_247_group['channel_count']}")
    print(f"   - Order: {original_247_group['order']}")
    print(f"   - Exclude: {original_247_group['exclude']}")
    
    # Create backup
    backup_file = main_config_file + '.backup_before_247_integration'
    try:
        import shutil
        shutil.copy2(main_config_file, backup_file)
        print(f"✅ Created backup: {backup_file}")
    except Exception as e:
        print(f"⚠️  Could not create backup: {e}")
    
    # Get the current count of remaining "Other" channels
    other_m3u_file = '247_channels_other.m3u'
    if os.path.exists(other_m3u_file):
        try:
            with open(other_m3u_file, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                # Count EXTINF lines
                other_count = sum(1 for line in lines if line.startswith('#EXTINF:'))
            print(f"\n📊 Remaining '24/7 Other' channels: {other_count}")
        except:
            other_count = 0
    else:
        other_count = 0
        print(f"\n⚠️  Could not find {other_m3u_file}")
    
    # Ask user what to do with original group
    print(f"\n🤔 What should we do with the original '24/7 Channels' group?")
    print(f"   1. Update it to contain only the remaining 'Other' channels ({other_count} channels)")
    print(f"   2. Exclude it completely (set exclude='true')")
    print(f"   3. Keep it as-is (not recommended - will cause duplicates)")
    
    choice = input("Enter choice (1, 2, or 3): ").strip()
    
    if choice == '1':
        # Update to contain only Other channels
        original_247_group['channel_count'] = other_count
        original_247_group['group_title'] = '24/7 Other'
        print(f"✅ Will update original group to '24/7 Other' with {other_count} channels")
    elif choice == '2':
        # Exclude the original group
        original_247_group['exclude'] = 'true'
        print(f"✅ Will exclude the original '24/7 Channels' group")
    elif choice == '3':
        print(f"⚠️  Keeping original group as-is (may cause duplicates)")
    else:
        print(f"❌ Invalid choice. Aborting.")
        return False
    
    # Insert new groups after the original 24/7 Channels group
    insert_position = original_247_index + 1
    
    # Adjust orders of existing groups that come after
    base_order = original_247_group['order'] + 1
    
    # Update orders of groups that come after
    for group in main_config[insert_position:]:
        if isinstance(group.get('order'), int):
            group['order'] += len(new_groups)
    
    # Set orders for new groups
    for i, new_group in enumerate(new_groups):
        new_group['order'] = base_order + i
    
    # Insert new groups
    for i, new_group in enumerate(reversed(new_groups)):
        main_config.insert(insert_position, new_group)
    
    # Save the updated configuration
    try:
        with open(main_config_file, 'w', encoding='utf-8') as f:
            json.dump(main_config, f, indent=2, ensure_ascii=False)
        print(f"\n✅ Updated main configuration: {main_config_file}")
    except Exception as e:
        print(f"❌ Failed to save updated configuration: {e}")
        return False
    
    # Summary
    print(f"\n🎉 Integration Summary:")
    print(f"   ✅ Added {len(new_groups)} new groups:")
    for group in new_groups:
        print(f"      - {group['group_title']}: {group['channel_count']} channels (order {group['order']})")
    
    if choice == '1':
        print(f"   ✅ Updated original group to '24/7 Other': {other_count} channels")
    elif choice == '2':
        print(f"   ✅ Excluded original '24/7 Channels' group")
    
    print(f"   ✅ Adjusted order numbers for subsequent groups")
    print(f"   ✅ Backup created: {backup_file}")
    
    print(f"\n💡 Next steps:")
    print(f"   1. Run: python process_playlist_complete_enhanced.py --skip-download --skip-gdrive")
    print(f"   2. The new subcategory groups should now appear in the filtered output")
    
    return True

if __name__ == "__main__":
    print("🔧 Integrating 24/7 subcategory groups into main configuration...")
    success = integrate_247_groups()
    if success:
        print("\n🎉 Integration completed successfully!")
    else:
        print("\n❌ Integration failed!")
    exit(0 if success else 1)
