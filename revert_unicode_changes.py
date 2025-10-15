#!/usr/bin/env python3
"""
Revert Unicode emoji changes - restore original emojis that were working before
"""

def revert_unicode_changes():
    """Revert text replacements back to original emojis"""
    
    # Text to emoji mapping (reverse of what we did)
    reversions = {
        "[ERROR]": "❌",
        "[SUCCESS]": "✅", 
        "[INFO]": "📋",
        "[CHECK]": "🔍",
        "[TIP]": "💡",
        "[STATS]": "📊",
        "[MERGE]": "🔧",
        "[START]": "🚀",
        "[FILE]": "📁",
        "[OVERRIDE]": "🏷️",
        "[TARGET]": "🎯",
        "[TIME]": "⏱️",
        "[SKIP]": "⏭️",
        "[PROCESS]": "🔄",
        "[CMD]": "📝",
        "[OUTPUT]": "📤",
        "[WARNING]": "⚠️",
        "[UK]": "🇬🇧",
        "[US]": "🇺🇸"
    }
    
    # Files to revert
    files_to_revert = [
        "merge_247_channels.py",
        "filter_m3u_with_auto_include.py",
        "download_file.py",
        "process_playlist_complete_enhanced.py",
        "replace_credentials_multi.py"
    ]
    
    total_files_changed = 0
    
    for filename in files_to_revert:
        try:
            # Read the file
            with open(filename, 'r', encoding='utf-8') as f:
                content = f.read()
        except FileNotFoundError:
            print(f"⏭️ File not found: {filename}")
            continue
        except Exception as e:
            print(f"❌ Error reading {filename}: {e}")
            continue
        
        # Apply reversions
        original_content = content
        for text, emoji in reversions.items():
            content = content.replace(text, emoji)
        
        # Check if any changes were made
        changes_made = content != original_content
        
        if changes_made:
            # Write back the file
            try:
                with open(filename, 'w', encoding='utf-8') as f:
                    f.write(content)
                print(f"✅ Reverted Unicode changes in {filename}")
                total_files_changed += 1
            except Exception as e:
                print(f"❌ Error writing {filename}: {e}")
        else:
            print(f"ℹ️ No reversions needed in {filename}")
    
    print(f"\n🎉 Reverted {total_files_changed} files back to original emoji format")
    return total_files_changed > 0

if __name__ == "__main__":
    print("🔄 Reverting Unicode emoji changes...")
    print("📋 Restoring original emoji format that was working before")
    print("=" * 60)
    revert_unicode_changes()