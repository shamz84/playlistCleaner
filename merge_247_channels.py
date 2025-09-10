#!/usr/bin/env python3
"""
Replace the original 24/7 Channels in downloaded_file.m3u with the new categorized subcategories
"""

import re
import os

def merge_categorized_247_channels():
    """Replace original 24/7 Channels with categorized subcategories - ONLY if they exist"""
    
    # Input files
    main_file = "data/downloaded_file.m3u"
    categorized_files = [
        "247_channels_kids.m3u",
        "247_channels_movies.m3u", 
        "247_channels_tv_shows.m3u",
        "247_channels_other.m3u"
    ]
    
    # Check if main file exists
    if not os.path.exists(main_file):
        print(f"❌ Main playlist file not found: {main_file}")
        return False
    
    print("📋 Reading main playlist file...")
    try:
        with open(main_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"❌ Error reading main file: {e}")
        return False
    
    print(f"✅ Read {len(lines)} lines from main file")
    
    # First, check if the downloaded file actually contains "24/7 Channels" group
    print("🔍 Checking if downloaded file contains '24/7 Channels' group...")
    has_247_channels = False
    for line in lines:
        if 'group-title="24/7 Channels"' in line:
            has_247_channels = True
            break
    
    if not has_247_channels:
        print("ℹ️  No '24/7 Channels' group found in downloaded file")
        print("✅ No merge needed - file already properly categorized or doesn't contain 24/7 content")
        return True  # This is not an error, just nothing to do
    
    print("✅ Found '24/7 Channels' group in downloaded file - proceeding with categorization")
    
    # Check if categorized files exist
    missing_files = []
    for file in categorized_files:
        if not os.path.exists(file):
            missing_files.append(file)
    
    if missing_files:
        print(f"⚠️  Missing categorized files: {', '.join(missing_files)}")
        print("� Automatically generating categorized files...")
        
        # Import and run the analyze script
        try:
            import subprocess
            import sys
            
            # Set environment for proper Unicode handling
            env = os.environ.copy()
            env['PYTHONIOENCODING'] = 'utf-8'
            
            result = subprocess.run([sys.executable, "analyze_247_channels.py"], 
                                  capture_output=True, 
                                  text=True, 
                                  cwd=os.getcwd(),
                                  env=env,
                                  encoding='utf-8')
            
            if result.returncode == 0:
                print("✅ Successfully generated categorized files")
                
                # Check again if files were created
                still_missing = []
                for file in categorized_files:
                    if not os.path.exists(file):
                        still_missing.append(file)
                
                if still_missing:
                    print(f"❌ Some files still missing after generation: {', '.join(still_missing)}")
                    return False
                    
            else:
                print(f"❌ Failed to generate categorized files:")
                if result.stderr:
                    print(f"   Error: {result.stderr}")
                if result.stdout:
                    print(f"   Output: {result.stdout}")
                return False
                
        except Exception as e:
            print(f"❌ Error running analyze_247_channels.py: {e}")
            return False
    
    # Find all 24/7 Channels entries
    print("🔍 Finding original 24/7 Channels entries...")
    new_lines = []
    skip_next = False
    removed_count = 0
    
    for i, line in enumerate(lines):
        if skip_next:
            # This is the URL line after an EXTINF, skip it
            skip_next = False
            removed_count += 1
            continue
            
        if line.startswith('#EXTINF:') and 'group-title="24/7 Channels"' in line:
            # This is a 24/7 Channels entry, skip it and the next line (URL)
            skip_next = True
            removed_count += 1
            continue
        
        # Keep all other lines
        new_lines.append(line)
    
    print(f"✅ Removed {removed_count} original 24/7 Channels entries")
    
    # Read and append categorized channels
    print("📥 Reading categorized channels...")
    total_added = 0
    
    for cat_file in categorized_files:
        print(f"   Reading {cat_file}...")
        try:
            with open(cat_file, 'r', encoding='utf-8') as f:
                cat_lines = f.readlines()
            
            # Skip the #EXTM3U header if present
            if cat_lines and cat_lines[0].strip() == '#EXTM3U':
                cat_lines = cat_lines[1:]
            
            # Count actual entries (EXTINF lines)
            cat_entries = sum(1 for line in cat_lines if line.startswith('#EXTINF:'))
            
            # Append to main content
            new_lines.extend(cat_lines)
            total_added += cat_entries
            print(f"      ✅ Added {cat_entries} channels")
            
        except Exception as e:
            print(f"      ❌ Error reading {cat_file}: {e}")
            return False
    
    print(f"✅ Total categorized channels added: {total_added}")
    
    # Create backup of original file
    backup_file = main_file + '.backup_before_247_merge'
    try:
        import shutil
        shutil.copy2(main_file, backup_file)
        print(f"✅ Created backup: {backup_file}")
    except Exception as e:
        print(f"⚠️  Could not create backup: {e}")
    
    # Write the merged content back
    print("💾 Writing merged content...")
    try:
        with open(main_file, 'w', encoding='utf-8') as f:
            f.writelines(new_lines)
        print(f"✅ Successfully updated {main_file}")
    except Exception as e:
        print(f"❌ Error writing merged file: {e}")
        return False
    
    # Summary
    print(f"\n🎉 Merge Summary:")
    print(f"   ✅ Removed {removed_count} original '24/7 Channels' entries")
    print(f"   ✅ Added {total_added} categorized entries:")
    
    # Show breakdown by category
    for cat_file in categorized_files:
        try:
            with open(cat_file, 'r', encoding='utf-8') as f:
                cat_lines = f.readlines()
            cat_entries = sum(1 for line in cat_lines if line.startswith('#EXTINF:'))
            category = cat_file.replace('247_channels_', '').replace('.m3u', '').replace('_', ' ').title()
            print(f"      - {category}: {cat_entries} channels")
        except:
            pass
    
    print(f"   ✅ Backup created: {backup_file}")
    print(f"   ✅ New total lines in main file: {len(new_lines)}")
    
    print(f"\n💡 Next steps:")
    print(f"   1. The main file now contains categorized 24/7 channels")
    print(f"   2. Run: python process_playlist_complete_enhanced.py --skip-download --skip-gdrive")
    print(f"   3. The new subcategory groups should now appear in the filtered output")
    
    return True

if __name__ == "__main__":
    print("🔧 Merging categorized 24/7 channels back into main playlist...")
    success = merge_categorized_247_channels()
    if success:
        print("\n🎉 Merge completed successfully!")
    else:
        print("\n❌ Merge failed!")
    exit(0 if success else 1)
