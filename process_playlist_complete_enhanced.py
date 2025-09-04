#!/usr/bin/env python3
"""
Complete Playlist Processing Pipeline - Enhanced Version
This script orchestrates the complete workflow with enhanced filtering:
1. Downloads playlist from remote server
2. Filters and processes the downloaded playlist using ENHANCED AUTO-INCLUDE filtering
3. Replaces credentials for multiple users
4. Backs up files to Google Drive (optional)

Key Enhancement: Now uses filter_m3u_with_auto_include.py which automatically
includes unknown groups unless they match exclusion patterns.

Usage:
    python process_playlist_complete_enhanced.py [--skip-download] [--skip-filter] [--skip-credentials] [--skip-gdrive]
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

def check_file_exists(filepath, description="File"):
    """Check if a file exists and show file info"""
    if os.path.exists(filepath):
        size = os.path.getsize(filepath)
        print(f"✅ {description}: {filepath} ({size:,} bytes)")
        return True
    else:
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
    
    config_file = "download_config.json"
    if not check_file_exists(config_file, "Download configuration"):
        print("❌ Download configuration missing!")
        print("💡 Please ensure download_config.json is configured properly")
        return False
    
    print("📥 Downloading playlist from remote server...")
    success = run_script("download_file.py", 
                        [], 
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
    
    # Check required input files
    required_files = [
        ("data/downloaded_file.m3u", "Main playlist"),
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
    
    print("🔍 Processing and filtering playlists with ENHANCED AUTO-INCLUDE...")
    print("💡 This enhanced filter automatically includes unknown groups")
    print("   unless they match patterns of excluded content types")
    
    success = run_script("filter_m3u_with_auto_include.py", 
                        [], 
                        "Running enhanced playlist filter with auto-include for unknown groups")
    
    if success:
        check_file_exists("filtered_playlist_final.m3u", "Enhanced filtered playlist")
        
        # Show filtering results
        size, lines = get_file_info("filtered_playlist_final.m3u")
        if size > 0:
            print(f"📊 Enhanced filtered playlist: {lines:,} lines, {size:,} bytes")
            print("🎉 Unknown groups were automatically analyzed and included/excluded intelligently!")
    
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
            print(f"⚠️  Could not verify generated playlists: {e}")
    
    return success

def step_gdrive_backup(skip=False):
    """Step 4: Backup files to Google Drive (optional)"""
    print_banner("STEP 4: GOOGLE DRIVE BACKUP (OPTIONAL)")
    
    if skip:
        print("⏭️  Skipping Google Drive backup step")
        return True
    
    # Check if Google Drive is configured
    if not check_file_exists("gdrive_credentials.json", "Google Drive credentials"):
        print("⚠️  Google Drive not configured - skipping backup")
        print("💡 To enable backups, run: python gdrive_setup.py")
        return True
    
    print("☁️  Backing up files to Google Drive...")
    success = run_script("upload_to_gdrive.py", 
                        ["--upload-all"], 
                        "Uploading all generated playlists to Google Drive")
    
    if not success:
        print("⚠️  Google Drive backup failed, but this is optional")
        print("💡 You can run the backup manually later with: python upload_to_gdrive.py --upload-all")
        return True  # Don't fail the pipeline for optional step
    
    return success

def main():
    """Main pipeline orchestrator"""
    print_banner("ENHANCED PLAYLIST PROCESSING PIPELINE")
    print("🎯 Enhanced with intelligent auto-include filtering!")
    print("📋 Processing stages:")
    print("   1. Download playlist from remote server")
    print("   2. Enhanced filtering with auto-include for unknown groups")
    print("   3. Replace credentials for multiple users")
    print("   4. Backup to Google Drive (optional)")
    
    # Parse command line arguments
    args = sys.argv[1:]
    skip_download = "--skip-download" in args
    skip_filter = "--skip-filter" in args
    skip_credentials = "--skip-credentials" in args
    skip_gdrive = "--skip-gdrive" in args
    
    if skip_download:
        print("⚠️  Download will be skipped")
    if skip_filter:
        print("⚠️  Enhanced filtering will be skipped")
    if skip_credentials:
        print("⚠️  Credential replacement will be skipped")
    if skip_gdrive:
        print("⚠️  Google Drive backup will be skipped")
    
    # Pipeline execution
    start_time = time.time()
    steps = [
        ("Download", lambda: step_download(skip_download)),
        ("Enhanced Filter", lambda: step_filter(skip_filter)),
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
        if failed_step == "Download":
            print("   - Check internet connection")
            print("   - Verify server is accessible")
            print("   - Check download_file.py configuration")
        elif failed_step == "Enhanced Filter":
            print("   - Ensure data/downloaded_file.m3u exists")
            print("   - Check group_titles_with_flags.json configuration")
            print("   - Verify filter_m3u_with_auto_include.py script")
            print("   - Enhanced filter auto-includes unknown groups intelligently")
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
            print(f"     📈 Enhanced with auto-included unknown groups!")
        
        return True

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
