#!/usr/bin/env python3
"""
Complete Playlist Processing Pipeline - Enhanced Version with UK TV Overrides
This script orchestrates the complete workflow with enhanced filtering:
0. Generates API playlists from Xtream Codes servers (optional)
1. Downloads playlist from remote server
2. Filters and processes the downloaded playlist using ENHANCED AUTO-INCLUDE filtering
   (now includes group title overrides during filtering)
2.5. Applies UK TV Guide overrides using dynamic system (optional)
3. Replaces credentials for multiple users
4. Backs up files to Google Drive (optional)

Key Enhancement: Now uses filter_m3u_with_auto_include.py which automatically
includes unknown groups unless they match exclusion patterns, and applies
group title overrides during filtering for maximum efficiency.

NEW: UK TV Override system replaces UK TV Guide entries with better alternatives
(BBC iPlayer, higher quality streams, etc.) using robust channel name matching.

Usage:
    python process_playlist_complete_enhanced.py [--skip-api] [--skip-download] [--skip-filter] [--skip-uk-override] [--skip-credentials] [--skip-gdrive]
"""
import subprocess
import sys
import os
import json
import time
from pathlib import Path
from datetime import datetime

# Fix Unicode encoding issues on Windows
if sys.platform.startswith('win'):
    try:
        import codecs
        sys.stdout = codecs.getwriter('utf-8')(sys.stdout.buffer, 'replace')
        sys.stderr = codecs.getwriter('utf-8')(sys.stderr.buffer, 'replace')
    except:
        pass  # Fallback to default behavior

def print_banner(title):
    """Print a formatted banner with Windows encoding support"""
    print("\n" + "="*60)
    try:
        print(f"🚀 {title}")
    except UnicodeEncodeError:
        print(f">> {title}")
    print("="*60)

def find_config_file(filename):
    """Find config file using config-first approach (like container)"""
    config_path = f"data/config/{filename}"
    root_path = filename
    
    if os.path.exists(config_path):
        return config_path
    elif os.path.exists(root_path):
        return root_path
    else:
        return filename  # Return filename for error reporting

def run_script(script_name, args=None, description=""):
    """Run a Python script and return success status"""
    if args is None:
        args = []
    
    cmd = ["python", script_name] + args
    
    print(f"\n🔄 {description}")
    print(f"📝 Command: {' '.join(cmd)}")
    
    try:
        # Set environment for proper Unicode handling on Windows
        env = os.environ.copy()
        env['PYTHONIOENCODING'] = 'utf-8'
        
        result = subprocess.run(cmd, check=True, capture_output=True, text=True, 
                               encoding='utf-8', errors='replace', env=env)
        print(f"✅ {description} completed successfully")
        if result.stdout:
            print("📤 Output:")
            print(result.stdout)
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ {description} failed with exit code {e.returncode}")
        if e.stdout:
            print("📤 Output:")
            print(e.stdout)
        if e.stderr:
            print("❌ Error:")
            print(e.stderr)
        return False
    except Exception as e:
        print(f"❌ {description} failed with error: {e}")
        return False

def check_file_exists(filepath, description="File", silent=False):
    """Check if a file exists and report the result"""
    if os.path.exists(filepath):
        if not silent:
            size = os.path.getsize(filepath)
            print(f"✅ {description} found: {filepath} ({size:,} bytes)")
        return True
    else:
        if not silent:
            print(f"❌ {description} not found: {filepath}")
        return False

def get_file_info(filepath):
    """Get file size and line count"""
    try:
        size = os.path.getsize(filepath)
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = sum(1 for _ in f)
        return size, lines
    except:
        return 0, 0

def step_download(skip=False):
    """Step 1: Download playlist from remote server"""
    print_banner("STEP 1: DOWNLOAD PLAYLIST")
    
    if skip:
        print("⏭️  Skipping download step")
        return True
    
    config_file = "data/config/download_config.json"
    if not check_file_exists(config_file, "Download configuration"):
        print("❌ Download configuration missing!")
        print("💡 Please ensure data/config/download_config.json is configured properly")
        return False
    
    print("❌ Downloading playlist from remote server...")
    success = run_script("download_file.py", 
                        ["--config", config_file], 
                        f"Downloading playlist with config: {config_file}")
    
    if success:
        check_file_exists("data/downloaded_file.m3u", "Downloaded playlist")
    
    return success

def step_filter(skip=False):
    """Step 2: Filter and process playlists using ENHANCED filtering"""
    print_banner("STEP 2: ENHANCED FILTER AND PROCESS PLAYLISTS")
    
    if skip:
        print("⏭️  Skipping filter step")
        return True
    
    # Check for available input files with config-first approach
    main_playlist = "data/downloaded_file.m3u"
    asia_playlist = "data/raw_playlist_AsiaUk.m3u"
    
    # Check what input files are available
    has_main = os.path.exists(main_playlist)
    has_asia = os.path.exists(asia_playlist)
    
    print(f"📋 Input files available:")
    print(f"  • Main playlist: {'✅' if has_main else '❌'} {main_playlist}")
    print(f"  • AsiaUK playlist: {'✅' if has_asia else '❌'} {asia_playlist}")
    
    if not has_main and not has_asia:
        print("❌ No input playlists found!")
        print("💡 You may need to download a playlist first or place files manually")
        return False
    
    # If we have AsiaUK but no main, copy AsiaUK to main location
    if has_asia and not has_main:
        print("📋 Using AsiaUK playlist as main input...")
        try:
            import shutil
            shutil.copy2(asia_playlist, main_playlist)
            print("✅ AsiaUK playlist copied to main input location")
        except Exception as e:
            print(f"❌ Failed to copy AsiaUK playlist: {e}")
            return False
    else:
        print("📋 Using main downloaded playlist only")
    
    # Check required files
    required_files = [
        (main_playlist, "Main playlist (merged if applicable)"),
        (find_config_file("group_titles_with_flags.json"), "Group configuration")
    ]
    
    missing_files = []
    for filepath, description in required_files:
        if not check_file_exists(filepath, description):
            missing_files.append(filepath)
    
    if missing_files:
        print(f"❌ Missing required files for filtering: {', '.join(missing_files)}")
        print("💡 You may need to ensure all source files are available")
        return False
    
    # Check if we need to merge categorized 24/7 channels
    print("🔍 Checking if 24/7 channel categorization is needed...")
    merge_success = run_script("merge_247_channels.py", 
                              [], 
                              "Applying 24/7 channel categorization if needed")
    
    if not merge_success:
        print("❌ 24/7 channel merge failed")
        return False
    
    print("🔍 Processing and filtering playlists with ENHANCED AUTO-INCLUDE...")
    print("💡 This enhanced filter automatically includes unknown groups")
    print("   unless they match patterns of excluded content types")
    print("🏷️  Group title overrides will be applied during filtering")
    
    success = run_script("filter_m3u_with_auto_include.py", 
                        [], 
                        "Running enhanced playlist filter with auto-include for unknown groups")
    
    if success:
        check_file_exists("filtered_playlist_final.m3u", "Enhanced filtered playlist")
        
        # Show filtering results
        size, lines = get_file_info("filtered_playlist_final.m3u")
        if size > 0:
            print(f"📊 Enhanced filtered playlist: {lines:,} lines, {size:,} bytes")
            print("✅ Unknown groups were automatically analyzed and included/excluded intelligently!")
    
    return success

def step_uk_tv_override(skip=False, filter_skipped=False):
    """Step 2.5: Apply UK TV Guide overrides using dynamic system"""
    print_banner("STEP 2.5: UK TV GUIDE OVERRIDES")
    
    if skip:
        print("⏭️  Skipping UK TV Guide override step")
        return True
    
    # Determine input file based on whether filter was skipped
    if filter_skipped:
        input_file = "data/downloaded_file.m3u"
        print("💡 Using downloaded file for UK TV overrides (filter was skipped)")
    else:
        input_file = "filtered_playlist_final.m3u"
    
    # Check if input file exists
    if not check_file_exists(input_file, "Input playlist for UK TV overrides"):
        print(f"❌ Input playlist required for UK TV overrides: {input_file}")
        return False
    
    # Check if UK TV override configuration exists (look in config directory first)
    config_file_paths = [
        "data/config/uk_tv_overrides_dynamic.conf",  # Container/mounted config
        "config/uk_tv_overrides_dynamic.conf",      # Local config directory
        "uk_tv_overrides_dynamic.conf"              # Root directory (fallback)
    ]
    
    config_file = None
    for path in config_file_paths:
        if check_file_exists(path, "UK TV override configuration", silent=True):
            config_file = path
            break
    
    if not config_file:
        # Default to config directory location
        config_file = "data/config/uk_tv_overrides_dynamic.conf"
        print("⚠️  UK TV override configuration not found")
        print("💡 Creating example configuration file...")
        
        # Ensure config directory exists
        os.makedirs("data/config", exist_ok=True)
        
        # Create example configuration
        example_config = """# UK TV Guide Override Configuration - Dynamic Version
# 
# Replace UK TV Guide entries with other entries from the playlist
# Format: source_channel_name = replacement_channel_name
#
# Examples (uncomment to use):
# BBC One = BBC One London
# BBC Two = BBC Two England
# ITV 1 = ITV 1 London
# Channel 4 = Channel 4 London
# Channel 5 = Channel 5 London

# No active overrides - add your configurations above
"""
        try:
            with open(config_file, 'w', encoding='utf-8') as f:
                f.write(example_config)
            print(f"✅ Created example configuration: {config_file}")
            print("💡 Edit data/config/uk_tv_overrides_dynamic.conf to configure your UK TV Guide overrides")
            print("⏭️  Skipping UK TV overrides (no active configuration)")
            return True
        except Exception as e:
            print(f"❌ Failed to create example configuration: {e}")
            return False
    
    # Check if the configuration has any active overrides
    try:
        active_overrides = 0
        with open(config_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    active_overrides += 1
        
        if active_overrides == 0:
            print("⚠️  No active overrides found in configuration")
            print("💡 Edit uk_tv_overrides_dynamic.conf to add UK TV Guide overrides")
            print("⏭️  Skipping UK TV overrides (no active configuration)")
            return True
        
        print(f"📋 Found {active_overrides} active override(s) in configuration")
        
    except Exception as e:
        print(f"❌ Failed to read configuration file: {e}")
        return False
    
    # Apply UK TV overrides
    output_file = "data/filtered_playlist_with_uk_overrides.m3u" if not filter_skipped else "data/downloaded_file_with_uk_overrides.m3u"
    
    print("🇬🇧 Applying UK TV Guide overrides using dynamic system...")
    print("💡 This replaces complete UK TV Guide entries with better alternatives")
    
    success = run_script("uk_tv_override_dynamic.py", 
                        [input_file, output_file, "--config", config_file], 
                        f"Applying UK TV Guide overrides: {input_file} -> {output_file}")
    
    if success:
        # Update the filtered file for subsequent steps
        if filter_skipped:
            # Copy back to downloaded file location
            try:
                import shutil
                shutil.copy2(output_file, "data/downloaded_file.m3u")
                print("✅ Updated downloaded file with UK TV overrides")
            except Exception as e:
                print(f"⚠️  Failed to update downloaded file: {e}")
        else:
            # Copy back to filtered file location
            try:
                import shutil
                shutil.copy2(output_file, "filtered_playlist_final.m3u")
                print("✅ Updated filtered playlist with UK TV overrides")
            except Exception as e:
                print(f"⚠️  Failed to update filtered playlist: {e}")
        
        # Show override results
        size, lines = get_file_info(output_file)
        if size > 0:
            print(f"📊 Playlist with UK TV overrides: {lines:,} lines, {size:,} bytes")
            print("✅ UK TV Guide entries replaced with better alternatives!")
    
    return success

def step_credentials(skip=False, filter_skipped=False):
    """Step 3: Replace credentials for multiple users"""
    print_banner("STEP 3: REPLACE CREDENTIALS")
    
    if skip:
        print("⏭️  Skipping credentials step")
        return True
    
    # Determine input file based on whether filter was skipped
    if filter_skipped:
        input_file = "data/downloaded_file.m3u"
        print("💡 Using downloaded file for credential replacement (filter was skipped)")
    else:
        input_file = "filtered_playlist_final.m3u"
    
    # Check required files
    if not check_file_exists(input_file, "Input playlist"):
        print(f"❌ Input playlist required for credential replacement: {input_file}")
        return False
    
    if not check_file_exists(find_config_file("credentials.json"), "Credentials configuration"):
        print("❌ Credentials configuration required")
        print("💡 Please ensure credentials.json contains user configurations")
        return False
    
    print("🔄 Replacing credentials for multiple users...")
    success = run_script("replace_credentials_multi.py", 
                        [], 
                        "Replacing credentials for all configured users")
    
    if success:
        # Check for generated files
        try:
            credentials_file = find_config_file("credentials.json")
            with open(credentials_file, 'r', encoding='utf-8') as f:
                creds = json.load(f)
                
            if isinstance(creds, list):
                for cred in creds:
                    if 'username' in cred:
                        output_file = f"8k_{cred['username']}.m3u"
                        check_file_exists(output_file, f"Personalized playlist for {cred['username']}")
            elif isinstance(creds, dict) and 'username' in creds:
                output_file = f"8k_{creds['username']}.m3u"
                check_file_exists(output_file, f"Personalized playlist for {creds['username']}")
                
        except Exception as e:
            print(f"⚠️  Could not verify generated playlists: {e}")
    
    return success

def check_file_exists(filepath, description="File", silent=False):
    """Check if a file exists and show file info"""
    if os.path.exists(filepath):
        if not silent:
            size = os.path.getsize(filepath)
            print(f"✅ {description}: {filepath} ({size:,} bytes)")
        return True
    else:
        if not silent:
            print(f"❌ {description} not found: {filepath}")
        return False

def get_file_info(filepath):
    """Get file size and line count"""
    try:
        size = os.path.getsize(filepath)
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = sum(1 for _ in f)
        return size, lines
    except:
        return 0, 0

def step_download(skip=False):
    """Step 1: Download playlist from remote server"""
    print_banner("STEP 1: DOWNLOAD PLAYLIST")
    
    if skip:
        print("⏭️  Skipping download step")
        return True
    
    config_file = "data/config/download_config.json"
    if not check_file_exists(config_file, "Download configuration"):
        print("❌ Download configuration missing!")
        print("💡 Please ensure data/config/download_config.json is configured properly")
        return False
    
    print("❌ Downloading playlist from remote server...")
    success = run_script("download_file.py", 
                        ["--config", config_file], 
                        f"Downloading playlist with config: {config_file}")
    
    if success:
        check_file_exists("data/downloaded_file.m3u", "Downloaded playlist")
    
    return success

def step_filter(skip=False):
    """Step 2: Filter and process playlists using ENHANCED filtering"""
    print_banner("STEP 2: ENHANCED FILTER AND PROCESS PLAYLISTS")
    
    if skip:
        print("⏭️  Skipping filter step")
        return True
    
    # Check for available input files with config-first approach
    main_playlist = "data/downloaded_file.m3u"
    asia_playlist = "data/raw_playlist_AsiaUk.m3u"
    
    # Check what input files are available
    has_main = os.path.exists(main_playlist)
    has_asia = os.path.exists(asia_playlist)
    
    # Find API-generated files in data directory
    api_files = []
    data_dir = "data"
    if os.path.exists(data_dir):
        for file in os.listdir(data_dir):
            if file.startswith("xstream_api_") and file.endswith(".m3u"):
                api_files.append(os.path.join(data_dir, file))
    
    print(f"📋 Input files available:")
    print(f"  • Main playlist: {'✅' if has_main else '❌'} {main_playlist}")
    print(f"  • AsiaUK playlist: {'✅' if has_asia else '❌'} {asia_playlist}")
    if api_files:
        print(f"  • API-generated files: ✅ {len(api_files)} files found")
        for api_file in api_files[:5]:  # Show first 5
            size = os.path.getsize(api_file) if os.path.exists(api_file) else 0
            filename = os.path.basename(api_file)
            print(f"    - {filename} ({size:,} bytes)")
        if len(api_files) > 5:
            print(f"    ... and {len(api_files) - 5} more")
    else:
        print(f"  • API-generated files: ❌ None found")
    
    if not has_main and not has_asia and not api_files:
        print("❌ No input playlist files found!")
        print("💡 Need either:")
        print("   - data/downloaded_file.m3u (from download step)")
        print("   - data/raw_playlist_AsiaUk.m3u (manual)")
        print("   - API-generated files (from API converter step)")
        return False
    
    # Merge playlists if multiple sources are available
    playlists_to_merge = []
    
    if has_main:
        playlists_to_merge.append((main_playlist, "Main downloaded"))
    
    if has_asia:
        playlists_to_merge.append((asia_playlist, "AsiaUK"))
    
    # Add API-generated files
    for api_file in api_files:
        filename = os.path.basename(api_file)
        playlists_to_merge.append((api_file, f"API: {filename}"))
    
    if len(playlists_to_merge) > 1:
        print(f"📋 Multiple playlists available ({len(playlists_to_merge)} sources)")
        print("🔄 Merging all playlists into main input...")
        
        try:
            all_content = []
            total_lines = 0
            
            # Add M3U header
            all_content.append("#EXTM3U\n")
            
            for playlist_path, description in playlists_to_merge:
                print(f"  📁 Reading {description}: {playlist_path}")
                
                with open(playlist_path, 'r', encoding='utf-8') as f:
                    content = f.readlines()
                
                # Remove header if present
                if content and content[0].strip() == '#EXTM3U':
                    content = content[1:]
                
                all_content.extend(content)
                total_lines += len(content)
                
                size = os.path.getsize(playlist_path)
                print(f"    ✅ Added {len(content)} lines ({size:,} bytes)")
            
            # Write merged content to main playlist
            with open(main_playlist, 'w', encoding='utf-8') as f:
                f.writelines(all_content)
            
            print(f"✅ Merged {len(playlists_to_merge)} playlists: {total_lines} total lines")
            
        except Exception as e:
            print(f"❌ Failed to merge playlists: {e}")
            return False
    
    elif has_asia and not has_main:
        print("📋 Using AsiaUK playlist as main input")
        print(f"🔄 Copying {asia_playlist} to {main_playlist}")
        try:
            import shutil
            shutil.copy2(asia_playlist, main_playlist)
            print("✅ AsiaUK playlist copied to main input location")
        except Exception as e:
            print(f"❌ Failed to copy AsiaUK playlist: {e}")
            return False
    
    elif api_files and not has_main and not has_asia:
        print("📋 Using API-generated files as main input")
        print(f"🔄 Merging {len(api_files)} API files to {main_playlist}")
        try:
            all_content = ["#EXTM3U\n"]
            
            for api_file in api_files:
                with open(api_file, 'r', encoding='utf-8') as f:
                    content = f.readlines()
                
                # Remove header if present
                if content and content[0].strip() == '#EXTM3U':
                    content = content[1:]
                
                all_content.extend(content)
            
            # Ensure data directory exists
            os.makedirs("data", exist_ok=True)
            
            # Write merged API content to main playlist
            with open(main_playlist, 'w', encoding='utf-8') as f:
                f.writelines(all_content)
            
            print(f"✅ API files merged to main input: {len(all_content)} lines")
            
        except Exception as e:
            print(f"❌ Failed to merge API files: {e}")
            return False
    
    else:
        print("📋 Using existing main downloaded playlist")
    
    # Check required files
    required_files = [
        (main_playlist, "Main playlist (merged if applicable)"),
        (find_config_file("group_titles_with_flags.json"), "Group configuration")
    ]
    
    missing_files = []
    for filepath, description in required_files:
        if not check_file_exists(filepath, description):
            missing_files.append(filepath)
    
    if missing_files:
        print(f"❌ Missing required files for filtering: {', '.join(missing_files)}")
        print("💡 You may need to ensure all source files are available")
        return False
    
    # Check if we need to merge categorized 24/7 channels
    print("🔍 Checking if 24/7 channel categorization is needed...")
    merge_success = run_script("merge_247_channels.py", 
                              [], 
                              "Applying 24/7 channel categorization if needed")
    
    if not merge_success:
        print("❌ 24/7 channel merge failed")
        return False
    
    print("🔍 Processing and filtering playlists with ENHANCED AUTO-INCLUDE...")
    print("💡 This enhanced filter automatically includes unknown groups")
    print("   unless they match patterns of excluded content types")
    print("🏷️  Group title overrides will be applied during filtering")
    
    success = run_script("filter_m3u_with_auto_include.py", 
                        [], 
                        "Running enhanced playlist filter with auto-include and group title overrides")
    
    if success:
        check_file_exists("filtered_playlist_final.m3u", "Enhanced filtered playlist")
        
        # Show filtering results
        size, lines = get_file_info("filtered_playlist_final.m3u")
        if size > 0:
            print(f"📊 Enhanced filtered playlist: {lines:,} lines, {size:,} bytes")
            print("✅ Unknown groups were automatically analyzed and included/excluded intelligently!")
    
    return success

def step_credentials(skip=False, filter_skipped=False):
    """Step 3: Replace credentials for multiple users"""
    print_banner("STEP 3: REPLACE CREDENTIALS")
    
    if skip:
        print("⏭️  Skipping credentials step")
        return True
    
    # Determine input file based on whether filter was skipped
    if filter_skipped:
        input_file = "data/downloaded_file.m3u"
        print("💡 Using downloaded file for credential replacement (filter was skipped)")
    else:
        input_file = "filtered_playlist_final.m3u"
    
    # Check required files
    if not check_file_exists(input_file, "Input playlist"):
        print(f"❌ Input playlist required for credential replacement: {input_file}")
        return False
    
    if not check_file_exists(find_config_file("credentials.json"), "Credentials configuration"):
        print("❌ Credentials configuration required")
        print("💡 Please ensure credentials.json contains user configurations")
        return False
    
    print("🔄 Replacing credentials for multiple users...")
    success = run_script("replace_credentials_multi.py", 
                        [], 
                        "Replacing credentials for all configured users")
    
    if success:
        # Check for generated files
        try:
            credentials_file = find_config_file("credentials.json")
            with open(credentials_file, 'r', encoding='utf-8') as f:
                creds = json.load(f)
                
            if isinstance(creds, list):
                for cred in creds:
                    if 'username' in cred:
                        output_file = f"8k_{cred['username']}.m3u"
                        check_file_exists(output_file, f"Personalized playlist for {cred['username']}")
            elif isinstance(creds, dict) and 'username' in creds:
                output_file = f"8k_{creds['username']}.m3u"
                check_file_exists(output_file, f"Personalized playlist for {creds['username']}")
                
        except Exception as e:
            print(f"⚠️  Could not verify generated playlists: {e}")
    
    return success

def step_gdrive_backup(skip=False):
    """Step 4: Backup files to Google Drive (optional)"""
    print_banner("STEP 4: GOOGLE DRIVE BACKUP (OPTIONAL)")
    
    if skip:
        print("⏭️  Skipping Google Drive backup step")
        return True
    
    # Container-friendly authentication setup
    container_auth_success = setup_container_gdrive_auth()
    
    # Check and refresh token if needed
    if not container_auth_success:
        print("🔄 Checking token status and refreshing if needed...")
        token_refreshed = check_and_refresh_token()
    
    # Check if Google Drive is configured and has valid token
    gdrive_creds = find_config_file("gdrive_credentials.json")
    gdrive_token = find_config_file("gdrive_token.json")
    
    if not check_file_exists(gdrive_creds, "Google Drive credentials"):
        print("⚠️  Google Drive credentials not configured - skipping backup")
        print("💡 For containers, see container authentication options:")
        print("   - Service Account: python setup_service_account_gdrive.py")
        print("   - Pre-auth mount: python setup_gdrive_for_container.py")
        print("   - Environment vars: python setup_env_gdrive.py")
        return True
    
    # Check if token exists and is not expired
    if not gdrive_token or not os.path.exists(gdrive_token):
        print("⚠️  Google Drive token not found - authentication required")
        if container_auth_success:
            print("✅ Container authentication was applied - retrying...")
        else:
            print("💡 For manual setup: python gdrive_setup.py")
            print("💡 For containers: python setup_gdrive_for_container.py")
            print("🔄 Skipping Google Drive backup to avoid blocking pipeline")
            return True
    
    # Check if token is valid by trying to read it
    try:
        with open(gdrive_token, 'r') as f:
            token_data = json.load(f)
        if not token_data.get('refresh_token'):
            print("⚠️  Google Drive token missing refresh token - re-authentication needed")
            print("💡 For manual setup: python gdrive_setup.py")
            print("💡 For containers: use service account or pre-authenticated token")
            print("🔄 Skipping Google Drive backup to avoid blocking pipeline")
            return True
    except Exception as e:
        print(f"⚠️  Cannot read Google Drive token: {e}")
        print("💡 For manual setup: python gdrive_setup.py")
        print("💡 For containers: python setup_gdrive_for_container.py")
        print("🔄 Skipping Google Drive backup to avoid blocking pipeline")
        return True
    
    print("☁️  Backing up files to Google Drive...")
    success = run_script("upload_to_gdrive.py", 
                        ["--backup"], 
                        "Uploading all generated playlists to Google Drive")
    
    if not success:
        print("⚠️  Google Drive backup failed, but this is optional")
        print("💡 You can run the backup manually later with: python upload_to_gdrive.py --backup")
        print("💡 If authentication is needed:")
        print("   - Manual: python gdrive_setup.py")
        print("   - Container: python setup_gdrive_for_container.py")
        return True  # Always return True for optional step to not block pipeline
    
    return True  # Always return True for optional step

def check_and_refresh_token():
    """Check token expiry and refresh if needed"""
    # Use the same logic as the main function to find token
    gdrive_token = find_config_file("gdrive_token.json")
    
    if not gdrive_token or not os.path.exists(gdrive_token):
        return False
        
    try:
        with open(gdrive_token, 'r') as f:
            token_data = json.load(f)
        
        if 'expiry' in token_data and token_data['expiry']:
            expiry_time = datetime.fromisoformat(token_data['expiry'].replace('Z', '+00:00'))
            now = datetime.now(expiry_time.tzinfo)
            time_left = expiry_time - now
            
            # If expires in less than 10 minutes, try to refresh
            if time_left.total_seconds() < 600:
                print(f"⚠️  Token expires soon: {time_left}")
                
                if token_data.get('refresh_token'):
                    print("🔄 Attempting automatic token refresh...")
                    try:
                        # Import here to avoid dependency issues
                        from google.auth.transport.requests import Request
                        from google.oauth2.credentials import Credentials
                        
                        creds = Credentials.from_authorized_user_file(gdrive_token)
                        if creds.expired and creds.refresh_token:
                            creds.refresh(Request())
                            
                            # Save refreshed token
                            with open(gdrive_token, 'w') as f:
                                f.write(creds.to_json())
                            
                            print(f"✅ Token refreshed successfully!")
                            return True
                            
                    except Exception as e:
                        print(f"❌ Auto-refresh failed: {e}")
                        print("💡 Consider using service account for containers")
                        return False
                else:
                    print("❌ No refresh token available")
                    print("💡 Manual re-authentication required")
                    return False
            else:
                print(f"✅ Token valid for: {time_left}")
                return True
                        
    except Exception as e:
        print(f"❌ Error checking token {gdrive_token}: {e}")
    
    return False

def setup_container_gdrive_auth():
    """Setup Google Drive authentication for container environments (no browser)"""
    container_indicators = [
        os.path.exists('/.dockerenv'),  # Docker container
        os.getenv('KUBERNETES_SERVICE_HOST'),  # Kubernetes
        os.getenv('CONTAINER') == 'true',  # Generic container indicator
        os.path.exists('/proc/1/cgroup')  # Linux container check
    ]
    
    # Check if we're in a container
    is_container = any(container_indicators)
    
    if is_container:
        print("🐳 Container environment detected")
        print("🚫 Browser authentication not available in containers")
        print("🔧 Using container-friendly authentication methods...")
        
        try:
            # Run the container authentication setup
            result = run_script("setup_container_gdrive_auth.py", 
                               [], 
                               "Setting up container-friendly Google Drive authentication")
            return result
        except Exception as e:
            print(f"⚠️  Container auth setup failed: {e}")
            return False
    else:
        print("💻 Local environment detected - standard authentication available")
        return False

def main():
    """Main pipeline orchestrator"""
    print_banner("ENHANCED PLAYLIST PROCESSING PIPELINE")
    print("🎯 Enhanced with intelligent auto-include filtering!")
    print("📋 Processing stages:")
    print("   0. Generate API playlists from Xtream Codes servers")
    print("   1. Download playlist from remote server")
    print("   2. Enhanced filtering with auto-include and group title overrides")
    print("   2.5. UK TV Guide overrides (dynamic system)")
    print("   3. Replace credentials for multiple users")
    print("   4. Backup to Google Drive (optional)")
    
    # Parse command line arguments
    args = sys.argv[1:]
    skip_api = "--skip-api" in args
    skip_download = "--skip-download" in args
    skip_filter = "--skip-filter" in args
    skip_uk_override = "--skip-uk-override" in args
    skip_credentials = "--skip-credentials" in args
    skip_gdrive = "--skip-gdrive" in args
    
    if skip_api:
        print("⚠️  API converter will be skipped")
    if skip_download:
        print("⚠️  Download will be skipped")
    if skip_filter:
        print("⚠️  Enhanced filtering (including overrides) will be skipped")
    if skip_uk_override:
        print("⚠️  UK TV Guide overrides will be skipped")
    if skip_credentials:
        print("⚠️  Credential replacement will be skipped")
    if skip_gdrive:
        print("⚠️  Google Drive backup will be skipped")
    
    # Pipeline execution
    start_time = time.time()
    steps = [
        ("API Converter", lambda: step_api_converter(skip_api)),
        ("Download", lambda: step_download(skip_download)),
        ("Enhanced Filter", lambda: step_filter(skip_filter)),
        ("UK TV Override", lambda: step_uk_tv_override(skip_uk_override, skip_filter)),
        ("Credentials", lambda: step_credentials(skip_credentials, skip_filter)),
        ("Google Drive Backup", lambda: step_gdrive_backup(skip_gdrive))
    ]
    
    completed_steps = 0
    failed_step = None
    
    for step_name, step_func in steps:
        print(f"\n{'='*60}")
        print(f"🎯 Executing: {step_name}")
        print(f"{'='*60}")
        
        try:
            if step_func():
                completed_steps += 1
                print(f"✅ {step_name} completed successfully")
            else:
                failed_step = step_name
                print(f"❌ {step_name} failed")
                break
        except Exception as e:
            failed_step = step_name
            print(f"❌ {step_name} failed with exception: {e}")
            break
    
    # Results summary
    total_time = time.time() - start_time
    print_banner("ENHANCED PIPELINE RESULTS")
    print(f"⏱️  Total processing time: {total_time:.2f} seconds")
    print(f"✅ Completed steps: {completed_steps}/{len(steps)}")
    
    if failed_step:
        print(f"❌ Failed at step: {failed_step}")
        print(f"\n💡 Troubleshooting tips:")
        if failed_step == "API Converter":
            print("   - Check xtream_api_config.json configuration")
            print("   - Verify server connection and credentials")
            print("   - Run api_to_m3u_converter.py manually to test")
            print("   - Files older than 24 hours will trigger regeneration")
        elif failed_step == "Download":
            print("   - Check internet connection")
            print("   - Verify server is accessible")
            print("   - Check download_file.py configuration")
        elif failed_step == "Enhanced Filter":
            print("   - Ensure data/downloaded_file.m3u exists")
            print("   - Check group_titles_with_flags.json configuration")
            print("   - Verify filter_m3u_with_auto_include.py script")
            print("   - Enhanced filter auto-includes unknown groups intelligently")
        elif failed_step == "UK TV Override":
            print("   - Ensure filtered_playlist_final.m3u exists")
            print("   - Check data/config/uk_tv_overrides_dynamic.conf configuration")
            print("   - Verify uk_tv_override_dynamic.py script exists")
            print("   - Run with --list to see available UK TV Guide entries")
            print("   - Run with --find to search for replacement channels")
            print("   - This step is skippable if you don't need UK TV overrides")
        elif failed_step == "Credentials":
            print("   - Ensure filtered_playlist_final.m3u exists")
            print("   - Check credentials.json format")
            print("   - Verify replace_credentials_multi.py")
        elif failed_step == "Google Drive Backup":
            print("   - This step is optional. If you encounter issues,")
            print("     you can skip it and run the backup manually later")
        
        return False
    else:
        print(f"✅ ALL STEPS COMPLETED SUCCESSFULLY!")
        print(f"🌟 Enhanced filtering included unknown groups intelligently!")
        print(f"\n📁 Generated files:")
        
        # List generated files
        output_files = []
        for file in Path('.').glob('8k_*.m3u'):
            size = file.stat().st_size
            output_files.append(f"   - {file.name} ({size:,} bytes)")
        
        if output_files:
            print("\n".join(output_files))
        else:
            print("   - No personalized playlists found")
        
        if os.path.exists("filtered_playlist_final.m3u"):
            size, lines = get_file_info("filtered_playlist_final.m3u")
            print(f"   - filtered_playlist_final.m3u ({size:,} bytes, {lines:,} lines)")
            print(f"     📊 Enhanced with auto-included unknown groups!")
        
        return True

def step_api_converter(skip=False):
    """Step 0: Generate API playlists from Xtream Codes servers"""
    print_banner("STEP 0: API TO M3U CONVERTER")
    
    if skip:
        print("⏭️  Skipping API converter step")
        return True
    
    config_file = "data/config/xtream_api_config.json"
    if not check_file_exists(config_file, "API configuration"):
        print("❌ API configuration missing!")
        print("💡 Please ensure data/config/xtream_api_config.json is configured properly")
        print("💡 Run api_to_m3u_converter.py manually first to set up configuration")
        return True  # Return True to continue pipeline even if API config is missing
    
    print("🌐 Converting API data to M3U playlists...")
    success = run_script("api_to_m3u_converter.py", 
                        [], 
                        "Converting Xtream API data to M3U playlists")
    
    if success:
        # Check for generated files in data directory
        api_files = []
        data_dir = "data"
        if os.path.exists(data_dir):
            for file in os.listdir(data_dir):
                if file.startswith("xstream_api_") and file.endswith(".m3u"):
                    api_files.append(os.path.join(data_dir, file))
        
        if api_files:
            print(f"✅ Generated {len(api_files)} API playlists:")
            for file in api_files:
                size, lines = get_file_info(file)
                filename = os.path.basename(file)
                print(f"  📁 {filename} ({size:,} bytes, {lines:,} lines)")
        else:
            print("⚠️  No API playlists were generated (may be due to 24-hour age check)")
    
    return success

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
