#!/usr/bin/env python3
"""
Google Drive Token Priority Checker
Shows which token files exist and which one is actually being used
"""
import os
import json
from datetime import datetime

def check_gdrive_token_priority():
    """Check Google Drive token priority and usage"""
    print("🔍 Google Drive Token Priority Check")
    print("=" * 50)
    
    # The exact priority order from upload_to_gdrive.py
    token_priority = [
        ("gdrive_token_writable.json", "Container writable token (HIGHEST PRIORITY)"),
        ("data/config/gdrive_token.json", "Config folder token"),
        ("gdrive_token.json", "Root folder token (LOWEST PRIORITY)")
    ]
    
    print("📋 **Priority Order (highest to lowest):**")
    for i, (file_path, description) in enumerate(token_priority, 1):
        exists = "✅ EXISTS" if os.path.exists(file_path) else "❌ Missing"
        print(f"{i}. {description}")
        print(f"   File: {file_path}")
        print(f"   Status: {exists}")
        
        if os.path.exists(file_path):
            # Show file details
            try:
                stat = os.stat(file_path)
                size = stat.st_size
                modified = datetime.fromtimestamp(stat.st_mtime)
                print(f"   Size: {size:,} bytes")
                print(f"   Modified: {modified}")
                
                # Check token content
                with open(file_path, 'r') as f:
                    token_data = json.load(f)
                
                if 'expiry' in token_data and token_data['expiry']:
                    expiry_time = datetime.fromisoformat(token_data['expiry'].replace('Z', '+00:00'))
                    now = datetime.now(expiry_time.tzinfo)
                    time_left = expiry_time - now
                    
                    if time_left.total_seconds() > 0:
                        print(f"   ⏰ Expires in: {time_left}")
                    else:
                        print(f"   ❌ EXPIRED {abs(time_left)} ago")
                else:
                    print(f"   ⚠️  No expiry information")
                
                has_refresh = "✅ YES" if token_data.get('refresh_token') else "❌ NO"
                print(f"   🔄 Has refresh token: {has_refresh}")
                
            except Exception as e:
                print(f"   ❌ Error reading file: {e}")
        
        print()
    
    # Determine which file is actually being used
    print("🎯 **ACTUAL FILE BEING USED:**")
    
    # Simulate the logic from GoogleDriveUploader.__init__
    if os.path.exists('gdrive_token_writable.json'):
        active_file = 'gdrive_token_writable.json'
        reason = "Container writable token takes precedence"
    elif os.path.exists('data/config/gdrive_token.json'):
        active_file = 'data/config/gdrive_token.json'
        reason = "Config folder token found"
    elif os.path.exists('gdrive_token.json'):
        active_file = 'gdrive_token.json'
        reason = "Root folder token found"
    else:
        active_file = None
        reason = "No token files found"
    
    if active_file:
        print(f"✅ **USING: {active_file}**")
        print(f"📋 Reason: {reason}")
    else:
        print("❌ **NO TOKEN FILE BEING USED**")
        print(f"📋 Reason: {reason}")
    
    return active_file

def show_credentials_priority():
    """Show credentials file priority"""
    print("\n" + "=" * 50)
    print("🔐 Google Drive Credentials Priority Check")
    print("=" * 50)
    
    creds_priority = [
        ("data/config/gdrive_credentials.json", "Config folder credentials (HIGHER PRIORITY)"),
        ("gdrive_credentials.json", "Root folder credentials (LOWER PRIORITY)")
    ]
    
    print("📋 **Credentials Priority Order:**")
    for i, (file_path, description) in enumerate(creds_priority, 1):
        exists = "✅ EXISTS" if os.path.exists(file_path) else "❌ Missing"
        print(f"{i}. {description}")
        print(f"   File: {file_path}")
        print(f"   Status: {exists}")
        
        if os.path.exists(file_path):
            try:
                stat = os.stat(file_path)
                size = stat.st_size
                modified = datetime.fromtimestamp(stat.st_mtime)
                print(f"   Size: {size:,} bytes")
                print(f"   Modified: {modified}")
            except Exception as e:
                print(f"   ❌ Error reading file: {e}")
        print()
    
    # Show which credentials file is being used
    if os.path.exists('data/config/gdrive_credentials.json'):
        active_creds = 'data/config/gdrive_credentials.json'
        reason = "Config folder credentials found"
    elif os.path.exists('gdrive_credentials.json'):
        active_creds = 'gdrive_credentials.json'
        reason = "Root folder credentials found"
    else:
        active_creds = None
        reason = "No credentials files found"
    
    if active_creds:
        print(f"✅ **USING CREDENTIALS: {active_creds}**")
        print(f"📋 Reason: {reason}")
    else:
        print("❌ **NO CREDENTIALS FILE BEING USED**")
        print(f"📋 Reason: {reason}")

def provide_recommendations():
    """Provide recommendations based on current setup"""
    print("\n" + "=" * 50)
    print("💡 Recommendations")
    print("=" * 50)
    
    # Check what we have
    has_writable = os.path.exists('gdrive_token_writable.json')
    has_config = os.path.exists('data/config/gdrive_token.json')
    has_root = os.path.exists('gdrive_token.json')
    
    if has_writable:
        print("🐳 **Container Environment Detected**")
        print("   - You're using the container writable token")
        print("   - This is good for container environments")
        print("   - Token updates will be saved to this file")
        
    elif has_config and has_root:
        print("⚠️  **Multiple Token Files Found**")
        print(f"   - Currently using: data/config/gdrive_token.json")
        print(f"   - Also found: gdrive_token.json (ignored)")
        print("   - Consider removing the duplicate in root folder")
        print("   - Command: Remove-Item gdrive_token.json")
        
    elif has_config:
        print("✅ **Clean Setup**")
        print("   - Using config folder token (recommended)")
        print("   - This keeps configuration organized")
        
    elif has_root:
        print("📁 **Root Folder Token**")
        print("   - Using root folder token")
        print("   - Consider moving to data/config/ for better organization")
        print("   - Command: Move-Item gdrive_token.json data/config/")
        
    else:
        print("❌ **No Authentication**")
        print("   - No Google Drive tokens found")
        print("   - Run: python gdrive_setup.py")

if __name__ == "__main__":
    active_token = check_gdrive_token_priority()
    show_credentials_priority()
    provide_recommendations()
    
    if active_token:
        print(f"\n🎉 **SUMMARY: System is using {active_token}**")
    else:
        print(f"\n❌ **SUMMARY: No Google Drive authentication available**")
