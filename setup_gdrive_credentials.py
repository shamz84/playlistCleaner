#!/usr/bin/env python3
"""
Google Drive Credentials Setup Script
This script guides you through setting up Google Drive OAuth credentials.
"""

import os
import json
import webbrowser
from urllib.parse import quote

def create_instructions():
    """Create detailed setup instructions"""
    instructions = """
🔧 GOOGLE DRIVE CREDENTIALS SETUP
=====================================

You need to create OAuth 2.0 credentials from Google Cloud Console to use Google Drive integration.

📋 STEP-BY-STEP INSTRUCTIONS:

1️⃣ GO TO GOOGLE CLOUD CONSOLE
   Open: https://console.cloud.google.com/

2️⃣ CREATE OR SELECT PROJECT
   - Click on project dropdown (top left)
   - Create new project OR select existing one
   - Name: "PlaylistCleaner" (or your choice)

3️⃣ ENABLE GOOGLE DRIVE API
   - Go to "APIs & Services" > "Library"
   - Search for "Google Drive API"
   - Click on it and press "ENABLE"

4️⃣ CREATE OAUTH CREDENTIALS
   - Go to "APIs & Services" > "Credentials"
   - Click "CREATE CREDENTIALS" > "OAuth 2.0 Client ID"
   - If prompted, configure OAuth consent screen:
     * User Type: External (for personal use)
     * App name: "PlaylistCleaner"
     * User support email: your email
     * Developer contact: your email
     * Save and continue through all steps
   
5️⃣ CONFIGURE OAUTH CLIENT ID
   - Application type: "Desktop application"
   - Name: "PlaylistCleaner Desktop"
   - Click "CREATE"

6️⃣ DOWNLOAD CREDENTIALS
   - Click the download icon (⬇️) next to your new credential
   - This downloads a JSON file named like "client_secret_xxx.json"

7️⃣ RENAME AND PLACE FILE
   - Rename the downloaded file to: gdrive_credentials.json
   - Place it in your PlaylistCleaner directory
   - The file should be next to this script

8️⃣ TEST THE SETUP
   Run: python gdrive_setup.py --check

⚠️  SECURITY NOTE:
   - Never share your gdrive_credentials.json file
   - It's already added to .gitignore to prevent accidental commits
   - Keep this file secure and private

🔗 QUICK LINKS:
   - Google Cloud Console: https://console.cloud.google.com/
   - APIs & Services: https://console.cloud.google.com/apis/
   - Drive API: https://console.cloud.google.com/apis/library/drive.googleapis.com

💡 TROUBLESHOOTING:
   - If you get "app not verified" warning, click "Advanced" > "Go to app"
   - Make sure you're logged into the correct Google account
   - The credentials file must be named exactly: gdrive_credentials.json
"""
    return instructions

def open_google_console():
    """Open Google Cloud Console in browser"""
    console_url = "https://console.cloud.google.com/"
    try:
        webbrowser.open(console_url)
        print(f"🌐 Opening Google Cloud Console: {console_url}")
        return True
    except Exception as e:
        print(f"❌ Could not open browser: {e}")
        print(f"📋 Please manually open: {console_url}")
        return False

def open_apis_library():
    """Open Google APIs Library"""
    apis_url = "https://console.cloud.google.com/apis/library"
    try:
        webbrowser.open(apis_url)
        print(f"🌐 Opening APIs Library: {apis_url}")
        return True
    except Exception as e:
        print(f"❌ Could not open browser: {e}")
        print(f"📋 Please manually open: {apis_url}")
        return False

def open_drive_api():
    """Open Drive API page"""
    drive_api_url = "https://console.cloud.google.com/apis/library/drive.googleapis.com"
    try:
        webbrowser.open(drive_api_url)
        print(f"🌐 Opening Drive API page: {drive_api_url}")
        return True
    except Exception as e:
        print(f"❌ Could not open browser: {e}")
        print(f"📋 Please manually open: {drive_api_url}")
        return False

def check_credentials_file():
    """Check if credentials file exists and is valid"""
    creds_file = "gdrive_credentials.json"
    
    if not os.path.exists(creds_file):
        print(f"❌ {creds_file} not found")
        return False
    
    try:
        with open(creds_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # Check if it has the required structure
        if "installed" in data:
            client_id = data["installed"].get("client_id", "")
            client_secret = data["installed"].get("client_secret", "")
            
            if client_id and client_secret:
                if "your-client-id" not in client_id and "your-client-secret" not in client_secret:
                    print(f"✅ {creds_file} found and appears valid")
                    print(f"   Client ID: {client_id[:20]}...")
                    return True
                else:
                    print(f"❌ {creds_file} contains template values - need real credentials")
                    return False
            else:
                print(f"❌ {creds_file} missing required fields")
                return False
        else:
            print(f"❌ {creds_file} has wrong structure")
            return False
            
    except json.JSONDecodeError:
        print(f"❌ {creds_file} is not valid JSON")
        return False
    except Exception as e:
        print(f"❌ Error reading {creds_file}: {e}")
        return False

def main():
    """Main function"""
    import sys
    
    print("🔐 GOOGLE DRIVE CREDENTIALS SETUP")
    print("=" * 50)
    
    if len(sys.argv) > 1:
        cmd = sys.argv[1].lower()
        
        if cmd == "--instructions":
            print(create_instructions())
            return
        
        elif cmd == "--console":
            print("🌐 Opening Google Cloud Console...")
            open_google_console()
            return
        
        elif cmd == "--apis":
            print("🌐 Opening APIs Library...")
            open_apis_library()
            return
            
        elif cmd == "--drive-api":
            print("🌐 Opening Drive API page...")
            open_drive_api()
            return
        
        elif cmd == "--check":
            print("🔍 Checking credentials file...")
            if check_credentials_file():
                print("\n🎉 Credentials setup is complete!")
                print("💡 Next step: Run 'python gdrive_setup.py --check' for full validation")
            else:
                print("\n⚠️  Please follow the setup instructions to get your credentials")
                print("💡 Run: python setup_gdrive_credentials.py --instructions")
            return
    
    # Default: Interactive setup
    print("This script will help you set up Google Drive OAuth credentials.\n")
    
    # Check current status
    print("📋 Current Status:")
    if check_credentials_file():
        print("✅ Credentials are already set up!")
        print("\n💡 Available commands:")
        print("   python gdrive_setup.py --check     # Full validation")
        print("   python upload_to_gdrive.py --setup # Test authentication")
        return
    
    print("❌ No valid credentials found\n")
    
    # Interactive setup
    print("🚀 Let's set up your Google Drive credentials!\n")
    
    choice = input("Would you like to:\n"
                  "1. Open Google Cloud Console to create credentials\n"
                  "2. View detailed instructions\n"
                  "3. Exit\n"
                  "Enter choice (1-3): ").strip()
    
    if choice == "1":
        print("\n🌐 Opening Google Cloud Console...")
        open_google_console()
        print("\n📋 Follow these steps in the browser:")
        print("1. Create/select a project")
        print("2. Enable Google Drive API")
        print("3. Create OAuth 2.0 credentials")
        print("4. Download and rename to 'gdrive_credentials.json'")
        print("\n💡 Run this script again with --check when done")
        
    elif choice == "2":
        print(create_instructions())
        
    else:
        print("👋 Setup cancelled. Run this script again when ready.")

if __name__ == "__main__":
    main()
