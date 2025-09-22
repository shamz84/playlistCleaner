#!/usr/bin/env python3
"""
Google Drive Shared Drive Manager
Helps create and manage shared drives for service accounts
"""
import os
import json
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from datetime import datetime

class SharedDriveManager:
    """Manage Google Drive shared drives for service accounts"""
    
    def __init__(self):
        self.service_account_file = 'data/config/gdrive_service_account.json'
        self.service = None
        self.service_account_email = None
    
    def authenticate(self):
        """Authenticate with service account"""
        if not os.path.exists(self.service_account_file):
            print(f"❌ Service account file not found: {self.service_account_file}")
            return False
        
        try:
            # Load service account email
            with open(self.service_account_file, 'r') as f:
                sa_data = json.load(f)
            self.service_account_email = sa_data.get('client_email')
            
            credentials = service_account.Credentials.from_service_account_file(
                self.service_account_file,
                scopes=['https://www.googleapis.com/auth/drive']
            )
            
            self.service = build('drive', 'v3', credentials=credentials)
            print(f"✅ Authenticated as: {self.service_account_email}")
            return True
            
        except Exception as e:
            print(f"❌ Authentication failed: {e}")
            return False
    
    def list_shared_drives(self):
        """List all accessible shared drives"""
        if not self.service and not self.authenticate():
            return []
        
        try:
            print("📁 Scanning for shared drives...")
            drives_result = self.service.drives().list().execute()
            shared_drives = drives_result.get('drives', [])
            
            if shared_drives:
                print(f"✅ Found {len(shared_drives)} shared drive(s):")
                for i, drive in enumerate(shared_drives, 1):
                    print(f"   {i}. {drive['name']}")
                    print(f"      ID: {drive['id']}")
                    
                    # Check if we can write to it
                    try:
                        # Try to list files to test permissions
                        self.service.files().list(
                            q=f"'{drive['id']}' in parents",
                            pageSize=1,
                            supportsAllDrives=True,
                            includeItemsFromAllDrives=True
                        ).execute()
                        print(f"      Access: ✅ Read/Write")
                    except HttpError as e:
                        if e.resp.status == 403:
                            print(f"      Access: ❌ No permission")
                        else:
                            print(f"      Access: ⚠️  Unknown ({e.resp.status})")
                    print()
                
                return shared_drives
            else:
                print("📝 No shared drives found")
                print("\n💡 To create a shared drive:")
                print("1. Go to https://drive.google.com")
                print("2. Click 'New' → 'Shared drive'")
                print("3. Name it: 'PlaylistCleaner-Backup'")
                print("4. Add this service account as Editor:")
                print(f"   {self.service_account_email}")
                return []
                
        except Exception as e:
            print(f"❌ Error listing shared drives: {e}")
            return []
    
    def create_shared_drive_config(self, drive_id=None, drive_name=None):
        """Create shared drive configuration file"""
        if not drive_id:
            drives = self.list_shared_drives()
            if not drives:
                return False
            
            # Use the first available drive
            drive_id = drives[0]['id']
            drive_name = drives[0]['name']
        
        config = {
            'shared_drive_id': drive_id,
            'shared_drive_name': drive_name,
            'service_account_email': self.service_account_email,
            'created_at': datetime.now().isoformat(),
            'last_tested': None
        }
        
        os.makedirs('data/config', exist_ok=True)
        config_file = 'data/config/gdrive_shared_drive_info.json'
        
        with open(config_file, 'w') as f:
            json.dump(config, f, indent=2)
        
        print(f"✅ Created shared drive config: {config_file}")
        print(f"   Drive: {drive_name}")
        print(f"   ID: {drive_id}")
        
        return True
    
    def test_upload_to_shared_drive(self, drive_id=None):
        """Test upload to a specific shared drive"""
        if not self.service and not self.authenticate():
            return False
        
        if not drive_id:
            # Try to load from config
            config_file = 'data/config/gdrive_shared_drive_info.json'
            if os.path.exists(config_file):
                with open(config_file, 'r') as f:
                    config = json.load(f)
                drive_id = config.get('shared_drive_id')
                drive_name = config.get('shared_drive_name', 'Unknown')
            else:
                print("❌ No shared drive configured")
                return False
        
        try:
            # Create a test file
            test_filename = f"test_upload_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
            test_content = f"""Service Account Upload Test
Timestamp: {datetime.now().isoformat()}
Service Account: {self.service_account_email}
Test successful!
"""
            
            with open(test_filename, 'w') as f:
                f.write(test_content)
            
            print(f"📄 Created test file: {test_filename}")
            
            # Upload to shared drive
            from googleapiclient.http import MediaFileUpload
            
            file_metadata = {
                'name': test_filename,
                'parents': [drive_id],
                'description': 'Test upload from PlaylistCleaner service account'
            }
            
            media = MediaFileUpload(test_filename, resumable=True)
            
            file = self.service.files().create(
                body=file_metadata,
                media_body=media,
                fields='id,name,webViewLink',
                supportsAllDrives=True
            ).execute()
            
            print(f"🎉 Upload successful!")
            print(f"   File ID: {file.get('id')}")
            print(f"   File Name: {file.get('name')}")
            print(f"   View Link: {file.get('webViewLink')}")
            
            # Update config with successful test
            config_file = 'data/config/gdrive_shared_drive_info.json'
            if os.path.exists(config_file):
                with open(config_file, 'r') as f:
                    config = json.load(f)
                config['last_tested'] = datetime.now().isoformat()
                config['last_test_file_id'] = file.get('id')
                
                with open(config_file, 'w') as f:
                    json.dump(config, f, indent=2)
            
            # Clean up local file
            try:
                os.remove(test_filename)
                print(f"🧹 Cleaned up: {test_filename}")
            except:
                pass
            
            return True
            
        except HttpError as e:
            print(f"❌ Upload failed: {e}")
            if e.resp.status == 403:
                print("💡 The service account may not have permission to upload to this shared drive")
                print(f"   Make sure {self.service_account_email} is added as Editor")
            return False
        except Exception as e:
            print(f"❌ Upload failed: {e}")
            return False

def main():
    """Main interactive function"""
    print("🔧 Google Drive Shared Drive Manager")
    print("=" * 50)
    
    manager = SharedDriveManager()
    
    if not manager.authenticate():
        return
    
    # List available shared drives
    drives = manager.list_shared_drives()
    
    if drives:
        print(f"\n🎯 Found {len(drives)} shared drive(s)")
        
        # Auto-configure with first drive
        if manager.create_shared_drive_config():
            print("\n🧪 Testing upload...")
            if manager.test_upload_to_shared_drive():
                print("\n✅ Service account upload is working correctly!")
                print("💾 Configuration saved for future use")
                
                # Test the enhanced uploader
                print("\n🔧 Testing enhanced uploader...")
                try:
                    from enhanced_service_account_uploader import ServiceAccountGDriveUploader
                    uploader = ServiceAccountGDriveUploader()
                    if uploader.authenticate():
                        print("✅ Enhanced uploader is ready!")
                        return True
                except Exception as e:
                    print(f"⚠️  Enhanced uploader test: {e}")
            else:
                print("\n❌ Upload test failed")
        else:
            print("\n❌ Could not configure shared drive")
    else:
        print(f"\n📋 Setup Instructions:")
        print(f"1. Go to https://drive.google.com")
        print(f"2. Click 'New' → 'Shared drive'")
        print(f"3. Name it: 'PlaylistCleaner-Backup'")
        print(f"4. Click 'Add members' and add:")
        print(f"   {manager.service_account_email}")
        print(f"5. Set role to 'Editor' or 'Content manager'")
        print(f"6. Run this script again")
    
    return False

if __name__ == "__main__":
    success = main()
    print(f"\n📊 Setup result: {'✅ SUCCESS' if success else '❌ NEEDS SETUP'}")