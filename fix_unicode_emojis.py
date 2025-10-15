#!/usr/bin/env python3
"""
Quick fix to replace Unicode emojis with Windows-safe text in Python scripts
"""

def fix_unicode_emojis():
    """Replace Unicode emojis with Windows-safe text equivalents"""
    
    # Emoji to text mapping
    replacements = {
        "❌": "[ERROR]",
        "✅": "[SUCCESS]", 
        "📋": "[INFO]",
        "🔍": "[CHECK]",
        "💡": "[TIP]",
        "📊": "[STATS]",
        "🎉": "[SUCCESS]",
        "🔧": "[MERGE]",
        "🚀": "[START]",
        "📁": "[FILE]",
        "📈": "[STATS]",
        "🏷️": "[OVERRIDE]",
        "🎯": "[TARGET]",
        "⏱️": "[TIME]",
        "⏭️": "[SKIP]",
        "🔄": "[PROCESS]",
        "📝": "[CMD]",
        "📤": "[OUTPUT]",
        "📥": "[ERROR]",
        "⚠️": "[WARNING]",
        "ℹ️": "[INFO]",
        "📄": "[FILE]",
        "🇬🇧": "[UK]",
        "🇺🇸": "[US]",
        "🔐": "[PROCESS]"
    }
    
    # Files to fix
    files_to_fix = [
        "merge_247_channels.py",
        "filter_m3u_with_auto_include.py",
        "download_file.py",
        "process_playlist_complete_enhanced.py",
        "replace_credentials_multi.py"
    ]
    
    total_files_changed = 0
    
    for filename in files_to_fix:
        try:
            # Read the file
            with open(filename, 'r', encoding='utf-8') as f:
                content = f.read()
        except FileNotFoundError:
            print(f"[SKIP] File not found: {filename}")
            continue
        except Exception as e:
            print(f"[ERROR] Error reading {filename}: {e}")
            continue
        
        # Apply replacements
        original_content = content
        for emoji, replacement in replacements.items():
            content = content.replace(emoji, replacement)
        
        # Check if any changes were made
        changes_made = content != original_content
        
        if changes_made:
            # Write back the file
            try:
                with open(filename, 'w', encoding='utf-8') as f:
                    f.write(content)
                print(f"[SUCCESS] Fixed Unicode emojis in {filename}")
                total_files_changed += 1
            except Exception as e:
                print(f"[ERROR] Error writing {filename}: {e}")
        else:
            print(f"[INFO] No emoji replacements needed in {filename}")
    
    print(f"\n[SUMMARY] Fixed {total_files_changed} files")
    return total_files_changed > 0

if __name__ == "__main__":
    fix_unicode_emojis()