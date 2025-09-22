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
        # Always set credentials_file first
        config_creds = os.path.join('config', credentials_file)
        if os.path.exists(config_creds):
            self.credentials_file = config_creds
            print(f"📁 Using credentials from config folder: {config_creds}")
        elif os.path.exists(credentials_file):
            self.credentials_file = credentials_file
            print(f"📁 Using credentials from root folder: {credentials_file}")
        else:
            self.credentials_file = credentials_file  # Will fail later with proper error message
        
        # Check for token file (contains both credentials and tokens)
        data_config_token = os.path.join('data', 'config', token_file)
        config_token = os.path.join('config', token_file)
        if os.path.exists(data_config_token):
            self.token_file = data_config_token
            print(f"📁 Using token from data/config folder: {data_config_token}")
        elif os.path.exists(config_token):
            self.token_file = config_token
            print(f"📁 Using token from config folder: {config_token}")
        elif os.path.exists(token_file):
            self.token_file = token_file
            print(f"📁 Using token from root folder: {token_file}")
        else:
            self.token_file = token_file
        
        # Check for writable token file (container environment)
        writable_token = 'gdrive_token_writable.json'
        if os.path.exists(writable_token):
            self.token_file = writable_token
            print(f"📁 Using writable token file: {writable_token}")
        # Don't override if we already found a valid token file
        
        self.service = None
        self.auth_method = None  # Track which auth method is being used
        
    def authenticate(self):
        """Authenticate with Google Drive API - Container friendly with fallback"""
        # Try Service Account authentication first (container-friendly)
        if self.try_service_account_auth():
            # Test if service account actually works by doing a simple API call
            if self.test_service_account_permissions():
                return True
            else:
                print("⚠️  Service account has no storage quota, falling back to OAuth...")
                self.service = None  # Reset service for OAuth attempt
        
        # Fall back to OAuth token authentication
        return self.try_oauth_auth()
    
    def test_service_account_permissions(self):
        """Test if service account has proper permissions"""
        try:
            # Skip API test that may hang - just return True and let upload handle errors
            print("ℹ️  Skipping service account API test to avoid hanging")
            print("ℹ️  Will attempt upload and fallback to OAuth if storage quota exceeded")
            return True
        except Exception as e:
            if "storage quota" in str(e).lower() or "storageQuotaExceeded" in str(e):
                print(f"⚠️  Service account storage quota issue: {e}")
                return False
            # Other errors might be temporary, so we'll still try to use it
            print(f"⚠️  Service account test warning: {e}")
            return True
    
    def try_oauth_auth(self):
        """Try OAuth authentication"""
        print("🔄 Attempting OAuth authentication...")
        creds = None
        
        # The file token.json stores the user's access and refresh tokens.
        if os.path.exists(self.token_file):
            try:
                creds = Credentials.from_authorized_user_file(self.token_file, SCOPES)
            except Exception as e:
                print(f"⚠️  Could not load token file: {e}")
                creds = None
        
        # If there are no (valid) credentials available, let the user log in.
        if not creds or not creds.valid:
            if creds and creds.expired and creds.refresh_token:
                try:
                    print("🔄 Refreshing expired credentials...")
                    creds.refresh(Request())
                    # Save the refreshed credentials
                    with open(self.token_file, 'w') as token:
                        token.write(creds.to_json())
                    print(f"✅ Credentials refreshed and saved to {self.token_file}")
                except Exception as e:
                    print(f"❌ Failed to refresh credentials: {e}")
                    print("💡 You may need to re-authenticate")
                    print("💡 For containers, use service account authentication")
                    return False
            else:
                print("❌ No valid credentials available")
                print("❌ Interactive authentication not possible in containers")
                print("💡 For containers, use one of these methods:")
                print("   1. Service Account: Set GOOGLE_APPLICATION_CREDENTIALS")
                print("   2. Pre-authenticated token: Mount gdrive_token.json")
                print("   3. Environment auth: Set GDRIVE_TOKEN_B64")
                return False
        
        # Store credentials for API usage
        self.service = build('drive', 'v3', credentials=creds)
        print("✅ Successfully authenticated with Google Drive")
        self.auth_method = "oauth"
        return True
    
    def try_service_account_auth(self):
        """Try to authenticate using service account (container-friendly)"""
        service_account_paths = [
            os.getenv('GOOGLE_APPLICATION_CREDENTIALS'),
            'data/config/gdrive_service_account.json',
            '/app/data/config/gdrive_service_account.json'
        ]
        
        for sa_path in service_account_paths:
            if sa_path and os.path.exists(sa_path):
                try:
                    print(f"🔐 Testing service account: {sa_path}")
                    from google.oauth2 import service_account
                    credentials = service_account.Credentials.from_service_account_file(
                        sa_path, scopes=SCOPES
                    )
                    
                    print("🔨 Building Google Drive service...")
                    self.service = build('drive', 'v3', credentials=credentials)
                    
                    # Test the connection with a simple API call with timeout
                    print("🧪 Testing service account permissions...")
                    try:
                        import signal
                        
                        def timeout_handler(signum, frame):
                            raise TimeoutError("Service account test timed out")
                        
                        # Set timeout for API test (Windows doesn't support signal.alarm)
                        try:
                            about = self.service.about().get(fields="user").execute()
                            email = about.get('user', {}).get('emailAddress', 'Unknown')
                            print(f"✅ Service account working for: {email}")
                        except Exception as test_e:
                            print(f"⚠️  Service account API test failed: {test_e}")
                            # This is expected for service accounts on personal drives
                            # The service account can authenticate but may not have storage quota
                            print("ℹ️  This is expected - service accounts have no storage quota on personal drives")
                            
                    except Exception as test_e:
                        print(f"⚠️  Service account test failed: {test_e}")
                    
                    print(f"✅ Service account authentication successful: {sa_path}")
                    self.auth_method = "service_account"
                    return True
                except Exception as e:
                    print(f"⚠️  Service account auth failed for {sa_path}: {e}")
        
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
        """Get existing folder or create new one with fallback handling"""
        try:
            return self._get_or_create_folder_internal(folder_name, parent_folder_id)
        except Exception as e:
            # If we're using service account and folder access fails, the folder 
            # might be in the user's personal drive, not accessible to service account
            if self.auth_method == "service_account" and ("not found" in str(e).lower() or "access" in str(e).lower()):
                print(f"⚠️  Service account cannot access folder, this is expected for personal drives")
                # Don't fall back here - let the upload method handle the fallback
                return None
            else:
                print(f"❌ Folder operation failed: {e}")
                return None
    
    def _get_or_create_folder_internal(self, folder_name, parent_folder_id=None):
        """Internal folder operation"""
        folder_id = self.find_folder(folder_name, parent_folder_id)
        if not folder_id:
            folder_id = self.create_folder(folder_name, parent_folder_id)
        return folder_id

    def find_file_by_name(self, file_name, folder_id=None):
        """Find a file by name in the specified folder"""
        try:
            query = f"name='{file_name}' and trashed=false"
            if folder_id:
                query += f" and '{folder_id}' in parents"
            
            results = self.service.files().list(q=query, fields="files(id, name)").execute()
            files = results.get('files', [])
            
            if files:
                return files[0]['id']  # Return the first match
            return None
                
        except HttpError as error:
            print(f"❌ Error searching for file: {error}")
            return None
    
    def delete_file(self, file_id):
        """Delete a file from Google Drive"""
        try:
            self.service.files().delete(fileId=file_id).execute()
            print(f"🗑️  Deleted existing file (ID: {file_id})")
            return True
        except HttpError as error:
            print(f"❌ Error deleting file: {error}")
            return False

    def update_file(self, file_id, file_path):
        """Update an existing file's content on Google Drive with service account fallback"""
        try:
            return self._update_file_internal(file_id, file_path)
        except HttpError as e:
            # Check if it's a service account storage quota error
            if "storage quota" in str(e).lower() or "storageQuotaExceeded" in str(e):
                print(f"⚠️  Service account update failed: {e}")
                print("🔄 Falling back to OAuth authentication...")
                
                # Try to switch to OAuth authentication
                if self.try_oauth_auth():
                    print("✅ OAuth fallback successful, retrying update...")
                    return self._update_file_internal(file_id, file_path)
                else:
                    print("❌ OAuth fallback failed")
                    return None
            else:
                # Re-raise other HttpErrors
                print(f"❌ Update failed: {e}")
                return None
        except Exception as e:
            # Handle other exceptions
            print(f"❌ Unexpected error during update: {e}")
            return None
    
    def _update_file_internal(self, file_id, file_path):
        """Internal update method"""
        try:
            file_name = os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            
            print(f"🔄 Updating existing file '{file_name}' (ID: {file_id})...")
            print(f"📤 Uploading new content ({file_size:,} bytes)...")
            
            # Determine MIME type
            mime_type, _ = mimetypes.guess_type(file_path)
            if not mime_type:
                mime_type = 'application/octet-stream'
            
            # Create media upload object
            media = MediaFileUpload(file_path, mimetype=mime_type, resumable=True)
            
            # Update the file content
            start_time = time.time()
            request = self.service.files().update(
                fileId=file_id,
                media_body=media,
                fields='id,webViewLink,modifiedTime'
            )
            
            response = None
            while response is None:
                status, response = request.next_chunk()
                if status:
                    progress = int(status.progress() * 100)
                    print(f"   📊 Update progress: {progress}%")
            
            end_time = time.time()
            update_time = end_time - start_time
            
            file_id = response.get('id')
            web_link = response.get('webViewLink')
            modified_time = response.get('modifiedTime')
            
            print(f"✅ Update completed!")
            print(f"   📁 File ID: {file_id} (preserved)")
            print(f"   🔗 Web Link: {web_link}")
            print(f"   🕒 Modified: {modified_time}")
            print(f"   ⏱️  Update time: {update_time:.2f} seconds")
            
            return {
                'id': file_id,
                'name': file_name,
                'link': web_link,
                'size': file_size,
                'updated': True
            }
            
        except HttpError as error:
            # Re-raise HttpError so the fallback logic can catch it
            raise error
        except Exception as e:
            print(f"❌ Unexpected error during update: {e}")
            return None

    def upload_file(self, file_path, folder_id=None, new_name=None, overwrite=False):
        """Upload a file to Google Drive with service account fallback"""
        if not os.path.exists(file_path):
            print(f"❌ File not found: {file_path}")
            return None
        
        try:
            return self._upload_file_internal(file_path, folder_id, new_name, overwrite)
        except Exception as e:
            # Check if it's a service account storage quota error or folder access issue
            error_str = str(e).lower()
            if (self.auth_method == "service_account" and 
                ("storage quota" in error_str or 
                 "storagequotaexceeded" in error_str or
                 "service accounts do not have storage quota" in error_str or
                 "folder not found" in error_str or
                 "insufficient permissions" in error_str or
                 "access denied" in error_str)):
                
                print(f"⚠️  Service account issue: {e}")
                print("🔄 Falling back to OAuth authentication...")
                
                # Try to switch to OAuth authentication
                if self.try_oauth_auth():
                    print("✅ OAuth fallback successful, retrying upload...")
                    # Note: With OAuth, we may need to recreate/find the folder
                    # since folders created by service account may not be accessible
                    return self._upload_file_internal(file_path, folder_id, new_name, overwrite)
                else:
                    print("❌ OAuth fallback failed")
                    return None
            else:
                # Re-raise other exceptions
                print(f"❌ Upload failed: {e}")
                return None
    
    def _upload_file_internal(self, file_path, folder_id=None, new_name=None, overwrite=False):
        """Internal upload method"""
        if not os.path.exists(file_path):
            print(f"❌ File not found: {file_path}")
            return None
        
        try:
            file_name = new_name or os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            
            # Check for existing file if overwrite is enabled
            if overwrite:
                existing_file_id = self.find_file_by_name(file_name, folder_id)
                if existing_file_id:
                    print(f"🔄 File '{file_name}' already exists, updating content...")
                    return self.update_file(existing_file_id, file_path)
            
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
            # Re-raise HttpError so the fallback logic can catch it
            raise error
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
        "overwrite_existing": True,
        "backup_files": [
            "filtered_playlist_final.m3u",
            "8k_*.m3u",
            "manual_download.m3u"
        ]
    }
    
    # Create config folder if it doesn't exist
    os.makedirs("config", exist_ok=True)
    config_file = "config/gdrive_config.json"
    
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
        print("  python upload_to_gdrive.py <file_path> [folder_name] [--overwrite]")
        print("  python upload_to_gdrive.py --setup     # Setup authentication")
        print("  python upload_to_gdrive.py --backup    # Backup playlist files (uses config overwrite setting)")
        print("  python upload_to_gdrive.py --list      # List files in Drive")
        print("")
        print("Options:")
        print("  --overwrite  : Replace existing files with the same name")
        print("")
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
        
        # Load or create config - check config folder first, then root
        config_file = None
        config_paths = ["config/gdrive_config.json", "gdrive_config.json"]
        
        for path in config_paths:
            if os.path.exists(path):
                config_file = path
                break
        
        if config_file:
            print(f"📁 Using config from: {config_file}")
            with open(config_file, 'r', encoding='utf-8') as f:
                config = json.load(f)
        else:
            print("⚠️  Configuration not found, creating template...")
            config = create_config_template()
            config_file = "config/gdrive_config.json"
        
        # Get or create backup folder
        folder_name = config.get('default_folder', 'PlaylistBackups')
        folder_id = uploader.get_or_create_folder(folder_name)
        
        if not folder_id:
            print("❌ Failed to create/find backup folder")
            return False
          # Backup specified files
        backup_patterns = config.get('backup_files', [])
        overwrite_existing = config.get('overwrite_existing', True)
        uploaded_files = []
        
        print(f"🔧 Overwrite mode: {'✅ Enabled' if overwrite_existing else '❌ Disabled'}")
        
        for pattern in backup_patterns:
            if '*' in pattern:
                # Handle wildcard patterns - check data directory first, then current directory as fallback
                files = list(Path('data').glob(pattern))
                if not files:  # Only check current directory if no files found in data directory
                    files = list(Path('.').glob(pattern))
            else:
                # Handle specific files - check data directory first, then current directory as fallback
                files = []
                if os.path.exists(f"data/{pattern}"):
                    files.append(Path(f"data/{pattern}"))
                elif os.path.exists(pattern):
                    files.append(Path(pattern))
            
            for file_path in files:
                if file_path.exists():
                    print(f"\n📤 Backing up {file_path.name}...")
                    result = uploader.upload_file(str(file_path), folder_id, overwrite=overwrite_existing)
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
    overwrite = len(sys.argv) > 3 and sys.argv[3].lower() == "--overwrite"
    
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
    result = uploader.upload_file(file_path, folder_id, overwrite=overwrite)
    
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
