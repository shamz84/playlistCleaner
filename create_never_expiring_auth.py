#!/usr/bin/env python3
"""
Create a Service Account for Google Drive (Never Expires)
This is the best solution for containers and long-running applications
"""
import json

def create_service_account_instructions():
    """Provide detailed instructions for service account setup"""
    print("🔐 Google Service Account Setup (NEVER EXPIRES)")
    print("=" * 60)
    
    print("\n📋 **Step-by-Step Instructions:**")
    print("\n1. **Go to Google Cloud Console:**")
    print("   https://console.cloud.google.com/")
    
    print("\n2. **Create or Select Project:**")
    print("   - Click 'Select a project' → 'New Project'")
    print("   - Name: 'Playlist Cleaner' (or any name)")
    print("   - Click 'Create'")
    
    print("\n3. **Enable Google Drive API:**")
    print("   - Go to 'APIs & Services' → 'Library'")
    print("   - Search 'Google Drive API'")
    print("   - Click 'Enable'")
    
    print("\n4. **Create Service Account:**")
    print("   - Go to 'APIs & Services' → 'Credentials'")
    print("   - Click 'Create Credentials' → 'Service Account'")
    print("   - Name: 'playlist-cleaner-service'")
    print("   - Click 'Create and Continue'")
    print("   - Skip roles (click 'Continue')")
    print("   - Skip user access (click 'Done')")
    
    print("\n5. **Download Service Account Key:**")
    print("   - Click on the created service account")
    print("   - Go to 'Keys' tab")
    print("   - Click 'Add Key' → 'Create new key'")
    print("   - Select 'JSON' format")
    print("   - Click 'Create' (downloads JSON file)")
    
    print("\n6. **Save the JSON File:**")
    print("   - Rename to: gdrive_service_account.json")
    print("   - Place in: data/config/ folder")
    
    print("\n7. **Share Google Drive Folder:**")
    print("   - Open your Google Drive backup folder")
    print("   - Click 'Share'")
    print("   - Add the service account email (from JSON file)")
    print("   - Give 'Editor' permissions")
    
    print("\n🎉 **Benefits of Service Account:**")
    print("   ✅ NEVER EXPIRES")
    print("   ✅ No browser authentication needed")
    print("   ✅ Perfect for containers")
    print("   ✅ Secure for production")

def create_service_account_config():
    """Create configuration for service account usage"""
    config = {
        "auth_type": "service_account",
        "service_account_file": "data/config/gdrive_service_account.json",
        "never_expires": True,
        "container_friendly": True,
        "production_ready": True
    }
    
    with open('service_account_config.json', 'w') as f:
        json.dump(config, f, indent=2)
    
    print(f"\n✅ Created service_account_config.json")

if __name__ == "__main__":
    create_service_account_instructions()
    create_service_account_config()
