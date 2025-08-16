#!/usr/bin/env python3
"""
Google Drive Setup Helper
This script helps set up Google Drive integration for the playlist system.
"""
import os
import sys
import subprocess
import json

def check_google_packages():
    """Check if Google Drive API packages are installed"""
    try:
        import googleapiclient
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from google_auth_oauthlib.flow import InstalledAppFlow
        from googleapiclient.discovery import build
        from googleapiclient.errors import HttpError
        from googleapiclient.http import MediaFileUpload
        
        print("✅ All Google Drive API packages are installed")
        return True
    except ImportError as e:
        print(f"❌ Google Drive API packages not installed: {e}")
        return False

def install_google_packages():
    """Install Google Drive API packages"""
    packages = [
        "google-api-python-client",
        "google-auth-httplib2", 
        "google-auth-oauthlib"
    ]
    
    print("📦 Installing Google Drive API packages...")
    try:
        for package in packages:
            print(f"   Installing {package}...")
            result = subprocess.run([sys.executable, "-m", "pip", "install", package], 
                                  capture_output=True, text=True)
            if result.returncode != 0:
                print(f"❌ Failed to install {package}: {result.stderr}")
                return False
        
        print("✅ All packages installed successfully")
        return True
    except Exception as e:
        print(f"❌ Installation failed: {e}")
        return False

def create_setup_guide():
    """Create a setup guide for Google Drive authentication"""
    guide = """
# Google Drive Setup Instructions

## 1. Install Required Packages
Run the following command:
```
python gdrive_setup.py --install
```

## 2. Set Up Google Cloud Project
1. Go to https://console.cloud.google.com/
2. Create a new project or select existing one
3. Enable Google Drive API:
   - Navigate to "APIs & Services" > "Library"
   - Search for "Google Drive API" and click "Enable"

## 3. Create OAuth Credentials
1. Go to "APIs & Services" > "Credentials"
2. Click "Create Credentials" > "OAuth 2.0 Client ID"
3. Choose "Desktop application"
4. Name it "Playlist Uploader"
5. Download the JSON file
6. Rename to "gdrive_credentials.json" and place in project folder

## 4. First-Time Authentication
Run:
```
python upload_to_gdrive.py --setup
```

This will open a browser for Google OAuth authentication.

## 5. Test Upload
```
python upload_to_gdrive.py "filtered_playlist_final.m3u"
```

## 6. Backup All Files
```
python upload_to_gdrive.py --backup
```

## Files Created:
- gdrive_credentials.json (your OAuth credentials - place in config folder)
- gdrive_token.json (authentication token - auto-generated)
- config/gdrive_config.json (backup configuration)
"""
    
    with open("GDRIVE_SETUP.md", "w", encoding="utf-8") as f:
        f.write(guide)
    
    print("📄 Created GDRIVE_SETUP.md with detailed instructions")

def check_credentials():
    """Check if Google Drive credentials are set up"""
    # Check config folder first, then root
    config_creds = "config/gdrive_credentials.json"
    root_creds = "gdrive_credentials.json"
    
    if os.path.exists(config_creds):
        print(f"✅ gdrive_credentials.json found in config folder: {config_creds}")
        return True
    elif os.path.exists(root_creds):
        print(f"✅ gdrive_credentials.json found in root folder: {root_creds}")
        print("💡 Consider moving to config/gdrive_credentials.json for better organization")
        return True
    else:
        print("❌ gdrive_credentials.json not found")
        print("💡 You need to download OAuth credentials from Google Cloud Console")
        print("📁 Place the file in either:")
        print("   - config/gdrive_credentials.json (recommended)")
        print("   - gdrive_credentials.json (root folder)")
        return False

def check_config():
    """Check if Google Drive configuration exists"""
    # Check config folder first, then root
    config_paths = ["config/gdrive_config.json", "gdrive_config.json"]
    
    for config_file in config_paths:
        if os.path.exists(config_file):
            try:
                with open(config_file, "r", encoding="utf-8") as f:
                    config = json.load(f)
                print(f"✅ gdrive_config.json found: {config_file}")
                print(f"   Default folder: {config.get('default_folder', 'Not set')}")
                print(f"   Backup files: {len(config.get('backup_files', []))} configured")
                if config_file == "gdrive_config.json":
                    print("💡 Consider moving to config/gdrive_config.json for better organization")
                return True
            except Exception as e:
                print(f"❌ gdrive_config.json invalid at {config_file}: {e}")
                continue
    
    print("❌ gdrive_config.json not found")
    print("💡 Place the file in either:")
    print("   - config/gdrive_config.json (recommended)")
    print("   - gdrive_config.json (root folder)")
    return False

def main():
    """Main function"""
    print("=== Google Drive Setup Helper ===")
    
    if len(sys.argv) > 1 and sys.argv[1] == "--install":
        print("🚀 Installing Google Drive API packages...")
        if install_google_packages():
            print("\n✅ Installation completed!")
            print("💡 Next step: Set up Google Cloud credentials")
            create_setup_guide()
        else:
            print("\n❌ Installation failed!")
        return
    
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        print("🔍 Checking Google Drive setup...")
        
        checks = [
            ("Google API Packages", check_google_packages),
            ("Credentials File", check_credentials),
            ("Configuration File", check_config)
        ]
        
        all_good = True
        for name, check_func in checks:
            print(f"\n📋 {name}:")
            if not check_func():
                all_good = False
        
        if all_good:
            print("\n🎉 Google Drive setup is complete!")
            print("💡 You can now use: python upload_to_gdrive.py --backup")
        else:
            print("\n⚠️  Setup incomplete. See GDRIVE_SETUP.md for instructions")
        return
    
    # Default: Show status and options
    print("📋 Google Drive Integration Status:")
    print("=" * 50)
    
    print("\n1. 📦 Package Installation:")
    packages_ok = check_google_packages()
    
    print("\n2. 🔐 Authentication Setup:")
    creds_ok = check_credentials()
    
    print("\n3. ⚙️  Configuration:")
    config_ok = check_config()
    
    print("\n" + "=" * 50)
    
    if packages_ok and creds_ok and config_ok:
        print("🎉 Google Drive integration is ready!")
        print("\n💡 Available commands:")
        print("   python upload_to_gdrive.py --backup")
        print("   python upload_to_gdrive.py 'filename.m3u'")
        print("   python upload_to_gdrive.py --list")
    else:
        print("⚠️  Setup required. Available options:")
        print("\n🔧 Setup commands:")
        if not packages_ok:
            print("   python gdrive_setup.py --install    # Install packages")
        print("   python gdrive_setup.py --check      # Check setup status")
        print("\n📖 For detailed instructions, see GDRIVE_SETUP.md")
        create_setup_guide()

if __name__ == "__main__":
    main()
