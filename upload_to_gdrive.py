#!/usr/bin/env python3
"""
Google Drive Upload Script
This script uploads files to Google Drive using the Google Drive API.
Supports authentication, folder creation, and file upload with progress tracking.
"""
import os
import json
import sys
from pathlib import Path
import mimetypes
import time

try:
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from google_auth_oauthlib.flow import InstalledAppFlow
    from googleapiclient.discovery import build
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload
except ImportError:
    print("❌ Google Drive API libraries not installed!")
    print("📦 Please install required packages:")
    print("   pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib")
    sys.exit(1)

# If modifying these scopes, delete the file token.json.
SCOPES = ['https://www.googleapis.com/auth/drive.file']

class GoogleDriveUploader:
    def __init__(self, credentials_file='gdrive_credentials.json', token_file='gdrive_token.json'):
        """Initialize the Google Drive uploader"""
        self.credentials_file = credentials_file
        self.token_file = token_file
        self.service = None
        
    def authenticate(self):
        """Authenticate with Google Drive API"""
        creds = None
        
        # The file token.json stores the user's access and refresh tokens.
        if os.path.exists(self.token_file):
            creds = Credentials.from_authorized_user_file(self.token_file, SCOPES)
        
        # If there are no (valid) credentials available, let the user log in.
        if not creds or not creds.valid:
            if creds and creds.expired and creds.refresh_token:
                try:
                    print("🔄 Refreshing expired credentials...")
                    creds.refresh(Request())
                except Exception as e:
                    print(f"❌ Failed to refresh credentials: {e}")
                    print("💡 You may need to re-authenticate")
                    creds = None
            
            if not creds:
                if not os.path.exists(self.credentials_file):
                    print(f"❌ Credentials file not found: {self.credentials_file}")
                    print("📝 Please follow these steps:")
                    print("   1. Go to https://console.cloud.google.com/")
                    print("   2. Create a new project or select existing one")
                    print("   3. Enable Google Drive API")
                    print("   4. Create credentials (OAuth 2.0 Client ID)")
                    print("   5. Download the JSON file and save as 'gdrive_credentials.json'")
                    return False
                
                try:
                    print("🔐 Starting OAuth authentication flow...")
                    flow = InstalledAppFlow.from_client_secrets_file(
                        self.credentials_file, SCOPES)
                    creds = flow.run_local_server(port=0)
                except Exception as e:
                    print(f"❌ Authentication failed: {e}")
                    return False
            
            # Save the credentials for the next run
            with open(self.token_file, 'w') as token:
                token.write(creds.to_json())
            print(f"✅ Credentials saved to {self.token_file}")
        
        try:
            self.service = build('drive', 'v3', credentials=creds)
            print("✅ Successfully authenticated with Google Drive")
            return True
        except Exception as e:
            print(f"❌ Failed to build Google Drive service: {e}")
            return False
    
    def create_folder(self, folder_name, parent_folder_id=None):
        """Create a folder in Google Drive"""
        try:
            file_metadata = {
                'name': folder_name,
                'mimeType': 'application/vnd.google-apps.folder'
            }
            
            if parent_folder_id:
                file_metadata['parents'] = [parent_folder_id]
            
            folder = self.service.files().create(body=file_metadata, fields='id').execute()
            folder_id = folder.get('id')
            
            print(f"✅ Created folder '{folder_name}' with ID: {folder_id}")
            return folder_id
            
        except HttpError as error:
            print(f"❌ Failed to create folder: {error}")
            return None
    
    def find_folder(self, folder_name, parent_folder_id=None):
        """Find a folder by name"""
        try:
            query = f"name='{folder_name}' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            if parent_folder_id:
                query += f" and '{parent_folder_id}' in parents"
            
            results = self.service.files().list(q=query, fields="files(id, name)").execute()
            folders = results.get('files', [])
            
            if folders:
                folder_id = folders[0]['id']
                print(f"✅ Found folder '{folder_name}' with ID: {folder_id}")
                return folder_id
            else:
                print(f"📁 Folder '{folder_name}' not found")
                return None
                
        except HttpError as error:
            print(f"❌ Error searching for folder: {error}")
            return None
    
    def get_or_create_folder(self, folder_name, parent_folder_id=None):
        """Get existing folder or create new one"""
        folder_id = self.find_folder(folder_name, parent_folder_id)
        if not folder_id:
            folder_id = self.create_folder(folder_name, parent_folder_id)
        return folder_id
    
    def upload_file(self, file_path, folder_id=None, new_name=None):
        """Upload a file to Google Drive"""
        if not os.path.exists(file_path):
            print(f"❌ File not found: {file_path}")
            return None
        
        try:
            file_name = new_name or os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            
            print(f"📤 Uploading {file_name} ({file_size:,} bytes)...")
            
            # Determine MIME type
            mime_type, _ = mimetypes.guess_type(file_path)
            if not mime_type:
                mime_type = 'application/octet-stream'
            
            file_metadata = {'name': file_name}
            if folder_id:
                file_metadata['parents'] = [folder_id]
            
            # Create media upload object
            media = MediaFileUpload(file_path, mimetype=mime_type, resumable=True)
            
            # Upload the file
            start_time = time.time()
            request = self.service.files().create(body=file_metadata, media_body=media, fields='id,webViewLink')
            
            response = None
            while response is None:
                status, response = request.next_chunk()
                if status:
                    progress = int(status.progress() * 100)
                    print(f"   📊 Upload progress: {progress}%")
            
            end_time = time.time()
            upload_time = end_time - start_time
            
            file_id = response.get('id')
            web_link = response.get('webViewLink')
            
            print(f"✅ Upload completed!")
            print(f"   📁 File ID: {file_id}")
            print(f"   🔗 Web Link: {web_link}")
            print(f"   ⏱️  Upload time: {upload_time:.2f} seconds")
            
            return {
                'id': file_id,
                'name': file_name,
                'link': web_link,
                'size': file_size
            }
            
        except HttpError as error:
            print(f"❌ Upload failed: {error}")
            return None
        except Exception as e:
            print(f"❌ Unexpected error during upload: {e}")
            return None
    
    def list_files(self, folder_id=None, limit=10):
        """List files in Google Drive"""
        try:
            query = "trashed=false"
            if folder_id:
                query += f" and '{folder_id}' in parents"
            
            results = self.service.files().list(
                q=query,
                pageSize=limit,
                fields="files(id, name, size, modifiedTime, mimeType)"
            ).execute()
            
            files = results.get('files', [])
            
            if not files:
                print("📂 No files found")
                return []
            
            print(f"📂 Found {len(files)} files:")
            for file in files:
                size = int(file.get('size', 0)) if file.get('size') else 0
                modified = file.get('modifiedTime', 'Unknown')
                print(f"   📄 {file['name']} ({size:,} bytes) - {modified}")
            
            return files
            
        except HttpError as error:
            print(f"❌ Error listing files: {error}")
            return []

def create_config_template():
    """Create a configuration template"""
    config = {
        "default_folder": "PlaylistBackups",
        "auto_create_folders": True,
        "overwrite_existing": False,
        "backup_files": [
            "filtered_playlist_final.m3u",
            "8k_*.m3u",
            "manual_download.m3u"
        ]
    }
    
    config_file = "gdrive_config.json"
    with open(config_file, 'w', encoding='utf-8') as f:
        json.dump(config, f, indent=2)
    
    print(f"📝 Created configuration template: {config_file}")
    return config

def main():
    """Main function"""
    print("=== Google Drive Upload Tool ===")
    
    # Check command line arguments
    if len(sys.argv) < 2:
        print("Usage:")
        print("  python upload_to_gdrive.py <file_path> [folder_name]")
        print("  python upload_to_gdrive.py --setup     # Setup authentication")
        print("  python upload_to_gdrive.py --backup    # Backup playlist files")
        print("  python upload_to_gdrive.py --list      # List files in Drive")
        return False
    
    # Initialize uploader
    uploader = GoogleDriveUploader()
    
    if sys.argv[1] == "--setup":
        print("🔧 Setting up Google Drive authentication...")
        if uploader.authenticate():
            print("✅ Setup completed successfully!")
            print("💡 You can now upload files to Google Drive")
        else:
            print("❌ Setup failed!")
        return True
    
    # Authenticate
    if not uploader.authenticate():
        print("❌ Authentication failed!")
        print("💡 Run with --setup to configure authentication")
        return False
    
    if sys.argv[1] == "--list":
        print("📂 Listing files in Google Drive...")
        uploader.list_files(limit=20)
        return True
    
    if sys.argv[1] == "--backup":
        print("💾 Starting playlist backup...")
        
        # Load or create config
        config_file = "gdrive_config.json"
        if os.path.exists(config_file):
            with open(config_file, 'r', encoding='utf-8') as f:
                config = json.load(f)
        else:
            print("⚠️  Configuration not found, creating template...")
            config = create_config_template()
        
        # Get or create backup folder
        folder_name = config.get('default_folder', 'PlaylistBackups')
        folder_id = uploader.get_or_create_folder(folder_name)
        
        if not folder_id:
            print("❌ Failed to create/find backup folder")
            return False
        
        # Backup specified files
        backup_patterns = config.get('backup_files', [])
        uploaded_files = []
        
        for pattern in backup_patterns:
            if '*' in pattern:
                # Handle wildcard patterns
                files = list(Path('.').glob(pattern))
            else:
                # Handle specific files
                files = [Path(pattern)] if os.path.exists(pattern) else []
            
            for file_path in files:
                if file_path.exists():
                    print(f"\n📤 Backing up {file_path.name}...")
                    result = uploader.upload_file(str(file_path), folder_id)
                    if result:
                        uploaded_files.append(result)
        
        print(f"\n🎉 Backup completed!")
        print(f"📊 Uploaded {len(uploaded_files)} files")
        total_size = sum(f['size'] for f in uploaded_files)
        print(f"📏 Total size: {total_size:,} bytes")
        
        return True
    
    # Regular file upload
    file_path = sys.argv[1]
    folder_name = sys.argv[2] if len(sys.argv) > 2 else None
    
    if not os.path.exists(file_path):
        print(f"❌ File not found: {file_path}")
        return False
    
    folder_id = None
    if folder_name:
        folder_id = uploader.get_or_create_folder(folder_name)
        if not folder_id:
            print(f"❌ Failed to create/find folder: {folder_name}")
            return False
    
    # Upload the file
    result = uploader.upload_file(file_path, folder_id)
    
    if result:
        print(f"\n🎉 Upload successful!")
        print(f"📱 You can access your file at: {result['link']}")
        return True
    else:
        print(f"\n💥 Upload failed!")
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
