#!/usr/bin/env python3
"""
Complete Playlist Processing Pipeline
This script orchestrates the complete workflow:
1. Downloads playlist from remote server
2. Filters and processes the downloaded playlist
3. Replaces credentials for multiple users
4. Backs up files to Google Drive (optional)

Usage:
    python process_playlist_complete.py [--skip-download] [--skip-filter] [--skip-credentials] [--skip-gdrive]
"""
import subprocess
import sys
import os
import json
import time
from pathlib import Path

def print_banner(title):
    """Print a formatted banner"""
    print("\n" + "="*60)
    print(f"🚀 {title}")
    print("="*60)

def run_script(script_name, args=None, description=""):
    """Run a Python script and return success status"""
    if args is None:
        args = []
    
    cmd = ["python", script_name] + args
    
    print(f"\n🔄 {description}")
    print(f"📝 Command: {' '.join(cmd)}")
    
    try:
        result = subprocess.run(cmd, 
                              capture_output=False, 
                              text=True, 
                              cwd=os.getcwd())
        
        if result.returncode == 0:
            print(f"✅ {script_name} completed successfully")
            return True
        else:
            print(f"❌ {script_name} failed with return code: {result.returncode}")
            return False
            
    except FileNotFoundError:
        print(f"❌ Script not found: {script_name}")
        return False
    except Exception as e:
        print(f"❌ Error running {script_name}: {e}")
        return False

def check_file_exists(filepath, description=""):
    """Check if a file exists and display info"""
    if os.path.exists(filepath):
        size = os.path.getsize(filepath)
        print(f"✅ {description}: {filepath} ({size:,} bytes)")
        return True
    else:
        print(f"❌ {description} not found: {filepath}")
        return False

def get_file_info(filepath):
    """Get file information"""
    if os.path.exists(filepath):
        size = os.path.getsize(filepath)
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = len(f.readlines())
        return size, lines
    return 0, 0

def step_download(skip=False):
    """Step 1: Download playlist from remote server"""
    print_banner("STEP 1: DOWNLOAD PLAYLIST")
    
    if skip:
        print("⏭️  Skipping download step")
        return check_file_exists("downloaded_file.m3u", "Downloaded playlist")
    
    # Check for download config - config folder first, then root
    config_file = None
    config_paths = ["config/download_config.json", "download_config.json"]
    
    for path in config_paths:
        if check_file_exists(path, "Download configuration"):
            config_file = path
            break
    
    if not config_file:
        print("💡 Using direct download mode as fallback")
        success = run_script("download_file.py", 
                            ["--direct"], 
                            "Downloading playlist with hardcoded parameters")
    else:
        print(f"📥 Downloading playlist using configuration file: {config_file}")
        success = run_script("download_file.py", 
                            ["--config", config_file], 
                            f"Downloading playlist with config: {config_file}")
    
    if success:
        check_file_exists("downloaded_file.m3u", "Downloaded playlist")
    
    return success

def step_filter(skip=False):
    """Step 2: Filter and process playlists"""
    print_banner("STEP 2: FILTER AND PROCESS PLAYLISTS")
    
    if skip:
        print("⏭️  Skipping filter step")
        return check_file_exists("filtered_playlist_final.m3u", "Filtered playlist")
      # Check required input files
    required_files = [
        ("downloaded_file.m3u", "Main playlist"),
        ("raw_playlist_AsiaUk.m3u", "Asia UK playlist"),
        ("group_titles_with_flags.json", "Group configuration")
    ]
    
    missing_files = []
    for filepath, description in required_files:
        if not check_file_exists(filepath, description):
            missing_files.append(filepath)
    
    if missing_files:
        print(f"❌ Missing required files for filtering: {', '.join(missing_files)}")
        print("💡 You may need to ensure all source files are available")
        return False
    
    print("🔍 Processing and filtering playlists...")
    success = run_script("filter_comprehensive.py", 
                        [], 
                        "Running comprehensive playlist filter")
    
    if success:
        check_file_exists("filtered_playlist_final.m3u", "Filtered playlist")
        
        # Show filtering results
        size, lines = get_file_info("filtered_playlist_final.m3u")
        if size > 0:
            print(f"📊 Filtered playlist: {lines:,} lines, {size:,} bytes")
    
    return success

def step_credentials(skip=False):
    """Step 3: Replace credentials for multiple users"""
    print_banner("STEP 3: REPLACE CREDENTIALS")
    
    if skip:
        print("⏭️  Skipping credentials step")
        return True
    
    # Check required files
    if not check_file_exists("filtered_playlist_final.m3u", "Filtered playlist"):
        print("❌ Filtered playlist required for credential replacement")
        return False
    
    if not check_file_exists("credentials.json", "Credentials configuration"):
        print("❌ Credentials configuration required")
        print("💡 Please ensure credentials.json contains user configurations")
        return False
    
    print("🔐 Replacing credentials for multiple users...")
    success = run_script("replace_credentials_multi.py", 
                        [], 
                        "Replacing credentials for all configured users")
    
    if success:
        # Check for generated files
        try:
            with open("credentials.json", 'r', encoding='utf-8') as f:
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
            print(f"⚠️  Could not verify generated files: {e}")
    
    return success

def step_gdrive_backup(skip=False):
    """Step 4: Backup files to Google Drive (Optional)"""
    print_banner("STEP 4: GOOGLE DRIVE BACKUP (OPTIONAL)")
    
    if skip:
        print("⏭️  Skipping Google Drive backup")
        return True
    
    # Check if Google Drive script exists
    if not os.path.exists("upload_to_gdrive.py"):
        print("⚠️  Google Drive upload script not found")
        print("💡 Google Drive backup is optional and can be skipped")
        return True
    
    print("☁️  Backing up files to Google Drive...")
    success = run_script("upload_to_gdrive.py", 
                        ["--backup"], 
                        "Uploading files to Google Drive")
    
    if success:
        print("✅ Files backed up to Google Drive successfully")
    else:
        print("⚠️  Google Drive backup failed (this is optional)")
        print("💡 You can run 'python upload_to_gdrive.py --backup' manually later")
    
    return True  # Always return True since this is optional

def main():
    """Main orchestrator function"""
    print_banner("M3U PLAYLIST PROCESSING PIPELINE")
    print("📋 This script will:")
    print("   1. 📥 Download playlist from remote server")
    print("   2. 🔍 Filter and process playlists")
    print("   3. 🔐 Replace credentials for multiple users")
    print("   4. ☁️ Backup files to Google Drive (optional)")
    
    # Parse command line arguments
    skip_download = "--skip-download" in sys.argv
    skip_filter = "--skip-filter" in sys.argv
    skip_credentials = "--skip-credentials" in sys.argv
    skip_gdrive = "--skip-gdrive" in sys.argv
    
    if any([skip_download, skip_filter, skip_credentials, skip_gdrive]):
        print("\n⚠️  Skipping steps:")
        if skip_download: print("   - Download")
        if skip_filter: print("   - Filter")
        if skip_credentials: print("   - Credentials")
        if skip_gdrive: print("   - Google Drive Backup")
    
    start_time = time.time()
    
    # Execute steps
    steps = [
        ("Download", step_download, skip_download),
        ("Filter", step_filter, skip_filter),
        ("Credentials", step_credentials, skip_credentials),
        ("Google Drive Backup", step_gdrive_backup, skip_gdrive)
    ]
    
    completed_steps = 0
    failed_step = None
    
    for step_name, step_func, skip in steps:
        try:
            success = step_func(skip)
            if success:
                completed_steps += 1
                print(f"✅ Step {completed_steps}: {step_name} completed")
            else:
                failed_step = step_name
                print(f"❌ Step {completed_steps + 1}: {step_name} failed")
                break
        except Exception as e:
            failed_step = step_name
            print(f"💥 Step {completed_steps + 1}: {step_name} crashed: {e}")
            break
    
    end_time = time.time()
    total_time = end_time - start_time
    
    print_banner("PIPELINE RESULTS")
    print(f"⏱️  Total processing time: {total_time:.2f} seconds")
    print(f"✅ Completed steps: {completed_steps}/{len(steps)}")
    
    if failed_step:
        print(f"❌ Failed at step: {failed_step}")
        print(f"\n💡 Troubleshooting tips:")
        if failed_step == "Download":
            print("   - Check internet connection")
            print("   - Verify server is accessible")
            print("   - Check download_file.py configuration")
        elif failed_step == "Filter":
            print("   - Ensure downloaded_file.m3u exists")
            print("   - Ensure raw_playlist_AsiaUk.m3u exists")
            print("   - Check group_titles_with_flags.json")
        elif failed_step == "Credentials":
            print("   - Ensure filtered_playlist_final.m3u exists")
            print("   - Check credentials.json format")
            print("   - Verify replace_credentials_multi.py")
        elif failed_step == "Google Drive Backup":
            print("   - This step is optional. If you encounter issues,")
            print("     you can skip it and run the backup manually later")
        
        return False
    else:
        print(f"🎉 ALL STEPS COMPLETED SUCCESSFULLY!")
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
        
        return True

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
