#!/usr/bin/env python3
"""
Google D    service_account_paths = [
        'data/config/gdrive_service_account.json'
    ] Service Account Setup
This provides non-interactive authentication suitable for containers and servers.
"""
import os
import json
from pathlib import Path

try:
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    GOOGLE_AVAILABLE = True
except ImportError:
    GOOGLE_AVAILABLE = False

def setup_service_account():
    """Setup Google Drive with service account authentication"""
    print("🔐 Google Drive Service Account Setup")
    print("=" * 40)
    
    if not GOOGLE_AVAILABLE:
        print("❌ Google API libraries not installed")
        print("💡 Install with: pip install google-auth google-auth-oauthlib google-api-python-client")
        return False
    
    print("📝 To use service account authentication:")
    print("1. Go to https://console.cloud.google.com/")
    print("2. Select your project or create a new one")
    print("3. Enable Google Drive API")
    print("4. Go to 'Credentials' → 'Create Credentials' → 'Service Account'")
    print("5. Create a service account and download the JSON key file")
    print("6. Save the JSON file as 'gdrive_service_account.json' in data/config/")
    print("7. Share your Google Drive folder with the service account email")
    print()
    
    # Check if service account file exists
    service_account_files = [
        'data/config/gdrive_service_account.json'
    ]
    
    service_account_file = None
    for file_path in service_account_files:
        if os.path.exists(file_path):
            service_account_file = file_path
            break
    
    if not service_account_file:
        print("❌ Service account file not found!")
        print("💡 Please save your service account JSON as:")
        print("   - data/config/gdrive_service_account.json (recommended)")
        print("   Please place your service account file in data/config/")
        return False
    
    # Test service account authentication
    try:
        print(f"🔍 Testing service account: {service_account_file}")
        
        # Load service account credentials
        credentials = service_account.Credentials.from_service_account_file(
            service_account_file,
            scopes=['https://www.googleapis.com/auth/drive']
        )
        
        # Test API access
        service = build('drive', 'v3', credentials=credentials)
        about = service.about().get(fields='user').execute()
        
        print(f"✅ Service account authenticated successfully!")
        print(f"📧 Service account email: {about['user']['emailAddress']}")
        
        # Create service account config
        create_service_account_config(service_account_file)
        
        return True
        
    except Exception as e:
        print(f"❌ Service account authentication failed: {e}")
        print("💡 Make sure:")
        print("   - The JSON file is valid")
        print("   - Google Drive API is enabled")
        print("   - The service account has necessary permissions")
        return False

def create_service_account_config(service_account_file):
    """Create configuration for service account usage"""
    config = {
        "auth_type": "service_account",
        "service_account_file": service_account_file,
        "backup_enabled": True
    }
    
    with open('gdrive_service_config.json', 'w') as f:
        json.dump(config, f, indent=2)
    
    print("✅ Created gdrive_service_config.json")

def create_service_account_uploader():
    """Create modified uploader for service account"""
    uploader_content = '''import os
import json
from google.oauth2 import service_account
from googleapiclient.discovery import build

class ServiceAccountGDriveUploader:
    """Google Drive uploader using service account authentication"""
    
    def __init__(self, service_account_file=None):
        """Initialize with service account"""
        if service_account_file is None:
            # Try to find service account file
            candidates = [
                'data/config/gdrive_service_account.json',
                '/app/data/config/gdrive_service_account.json'  # Container path
            ]
            
            for candidate in candidates:
                if os.path.exists(candidate):
                    service_account_file = candidate
                    break
        
        if not service_account_file or not os.path.exists(service_account_file):
            raise FileNotFoundError("Service account file not found")
        
        self.service_account_file = service_account_file
        self.service = None
    
    def authenticate(self):
        """Authenticate using service account"""
        try:
            credentials = service_account.Credentials.from_service_account_file(
                self.service_account_file,
                scopes=['https://www.googleapis.com/auth/drive']
            )
            
            self.service = build('drive', 'v3', credentials=credentials)
            return True
            
        except Exception as e:
            print(f"❌ Service account authentication failed: {e}")
            return False
    
    def upload_file(self, file_path, folder_id=None):
        """Upload file to Google Drive"""
        if not self.service:
            if not self.authenticate():
                return False
        
        try:
            file_metadata = {'name': os.path.basename(file_path)}
            if folder_id:
                file_metadata['parents'] = [folder_id]
            
            from googleapiclient.http import MediaFileUpload
            media = MediaFileUpload(file_path)
            
            file = self.service.files().create(
                body=file_metadata,
                media_body=media,
                fields='id'
            ).execute()
            
            print(f"✅ Uploaded {file_path} (ID: {file.get('id')})")
            return True
            
        except Exception as e:
            print(f"❌ Upload failed: {e}")
            return False
'''
    
    with open('service_account_uploader.py', 'w') as f:
        f.write(uploader_content)
    
    print("✅ Created service_account_uploader.py")

if __name__ == "__main__":
    setup_service_account()
