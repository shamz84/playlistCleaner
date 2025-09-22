#!/usr/bin/env python3
"""
Enhanced Service Account Google Drive Uploader with Shared Drive Support
"""
import os
import json
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from googleapiclient.errors import HttpError

class ServiceAccountGDriveUploader:
    """Google Drive uploader using service account with shared drive support"""
    
    def __init__(self, service_account_file=None, shared_drive_config=None):
        """Initialize with service account and optional shared drive config"""
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
        self.shared_drive_id = None
        self.shared_drive_name = None
        
        # Load shared drive configuration
        self._load_shared_drive_config(shared_drive_config)
    
    def _load_shared_drive_config(self, config_file=None):
        """Load shared drive configuration"""
        if config_file is None:
            config_file = 'data/config/gdrive_shared_drive_info.json'
        
        if os.path.exists(config_file):
            try:
                with open(config_file, 'r') as f:
                    config = json.load(f)
                self.shared_drive_id = config.get('shared_drive_id')
                self.shared_drive_name = config.get('shared_drive_name')
                print(f"✅ Loaded shared drive config: {self.shared_drive_name}")
            except Exception as e:
                print(f"⚠️  Could not load shared drive config: {e}")
    
    def authenticate(self):
        """Authenticate using service account"""
        try:
            credentials = service_account.Credentials.from_service_account_file(
                self.service_account_file,
                scopes=['https://www.googleapis.com/auth/drive']
            )
            
            self.service = build('drive', 'v3', credentials=credentials)
            
            # Auto-detect shared drive if not configured
            if not self.shared_drive_id:
                self._auto_detect_shared_drive()
            
            return True
            
        except Exception as e:
            print(f"❌ Service account authentication failed: {e}")
            return False
    
    def _auto_detect_shared_drive(self):
        """Auto-detect available shared drives"""
        try:
            drives_result = self.service.drives().list().execute()
            shared_drives = drives_result.get('drives', [])
            
            if shared_drives:
                # Use the first available shared drive
                self.shared_drive_id = shared_drives[0]['id']
                self.shared_drive_name = shared_drives[0]['name']
                print(f"🔍 Auto-detected shared drive: {self.shared_drive_name}")
                
                # Save configuration for future use
                config = {
                    'shared_drive_id': self.shared_drive_id,
                    'shared_drive_name': self.shared_drive_name,
                    'auto_detected': True
                }
                
                os.makedirs('data/config', exist_ok=True)
                with open('data/config/gdrive_shared_drive_info.json', 'w') as f:
                    json.dump(config, f, indent=2)
                
                return True
            else:
                print("⚠️  No shared drives found. Upload will fail.")
                return False
                
        except Exception as e:
            print(f"⚠️  Could not detect shared drives: {e}")
            return False
    
    def upload_file(self, file_path, remote_name=None, folder_id=None):
        """Upload file to Google Drive shared drive"""
        if not self.service:
            if not self.authenticate():
                return False
        
        if not self.shared_drive_id:
            print("❌ No shared drive configured. Cannot upload.")
            print("💡 Please run: python setup_shared_drive.py")
            return False
        
        try:
            file_name = remote_name or os.path.basename(file_path)
            
            file_metadata = {
                'name': file_name,
                'parents': [folder_id or self.shared_drive_id]
            }
            
            media = MediaFileUpload(file_path, resumable=True)
            
            file = self.service.files().create(
                body=file_metadata,
                media_body=media,
                fields='id,name,webViewLink',
                supportsAllDrives=True  # Required for shared drives
            ).execute()
            
            print(f"✅ Uploaded {file_path} to shared drive")
            print(f"   Name: {file.get('name')}")
            print(f"   ID: {file.get('id')}")
            print(f"   Shared Drive: {self.shared_drive_name}")
            
            return {
                'file_id': file.get('id'),
                'file_name': file.get('name'),
                'web_link': file.get('webViewLink'),
                'shared_drive': self.shared_drive_name
            }
            
        except HttpError as e:
            if e.resp.status == 403 and 'storageQuotaExceeded' in str(e):
                print("❌ Upload failed: Service account needs shared drive access")
                print("💡 Please follow GDRIVE_SHARED_DRIVE_SETUP.md instructions")
            else:
                print(f"❌ Upload failed: {e}")
            return False
        except Exception as e:
            print(f"❌ Upload failed: {e}")
            return False
    
    def list_files(self, max_results=10):
        """List files in the shared drive"""
        if not self.service:
            if not self.authenticate():
                return []
        
        if not self.shared_drive_id:
            print("❌ No shared drive configured")
            return []
        
        try:
            results = self.service.files().list(
                q=f"'{self.shared_drive_id}' in parents",
                pageSize=max_results,
                fields="nextPageToken, files(id, name, modifiedTime, size)",
                supportsAllDrives=True,
                includeItemsFromAllDrives=True
            ).execute()
            
            files = results.get('files', [])
            
            print(f"📁 Files in {self.shared_drive_name}:")
            for file in files:
                size = file.get('size', 'Unknown')
                if size.isdigit():
                    size = f"{int(size):,} bytes"
                print(f"   - {file['name']} ({size})")
            
            return files
            
        except Exception as e:
            print(f"❌ Error listing files: {e}")
            return []

def test_enhanced_uploader():
    """Test the enhanced uploader"""
    print("🧪 Testing Enhanced Service Account Uploader")
    print("=" * 50)
    
    try:
        uploader = ServiceAccountGDriveUploader()
        
        if uploader.authenticate():
            print("✅ Authentication successful")
            
            # List existing files
            uploader.list_files(5)
            
            # Create a test file
            test_file = "enhanced_uploader_test.txt"
            with open(test_file, 'w') as f:
                f.write(f"Enhanced uploader test - {os.urandom(8).hex()}")
            
            # Upload test file
            result = uploader.upload_file(test_file)
            
            # Clean up
            if os.path.exists(test_file):
                os.remove(test_file)
            
            return bool(result)
        else:
            print("❌ Authentication failed")
            return False
            
    except Exception as e:
        print(f"❌ Test failed: {e}")
        return False

if __name__ == "__main__":
    success = test_enhanced_uploader()
    print(f"\n📊 Enhanced uploader test: {'✅ PASS' if success else '❌ FAIL'}")