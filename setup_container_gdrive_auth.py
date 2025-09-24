#!/usr/bin/env python3
"""
Container-Ready Google Drive Authentication
No browser required - uses service accounts or pre-authenticated tokens
"""
import os
import json
import base64

def setup_container_auth():
    """Setup Google Drive authentication for containers (no browser needed)"""
    print("🐳 Container Google Drive Authentication Setup")
    print("=" * 50)
    print("🚫 No browser authentication in containers!")
    print("✅ Using container-friendly methods only")
    print()
    
    # Method 1: Check for service account
    service_account_setup = check_service_account()
    
    # Method 2: Check for environment token
    env_token_setup = check_environment_token()
    
    # Method 3: Check for mounted token
    mounted_token_setup = check_mounted_token()
    
    if service_account_setup:
        print("🎉 Service Account authentication is ready!")
        return True
    elif env_token_setup:
        print("🎉 Environment token authentication is ready!")
        return True
    elif mounted_token_setup:
        print("🎉 Mounted token authentication is ready!")
        return True
    else:
        print("❌ No container-friendly authentication found!")
        print_setup_instructions()
        return False

def check_service_account():
    """Check if service account authentication is available"""
    print("🔍 Checking for Service Account authentication...")
    
    service_account_paths = [
        os.getenv('GOOGLE_APPLICATION_CREDENTIALS'),
        'data/config/gdrive_service_account.json',
        '/app/data/config/gdrive_service_account.json'
    ]
    
    for sa_path in service_account_paths:
        if sa_path and os.path.exists(sa_path):
            try:
                with open(sa_path, 'r') as f:
                    sa_data = json.load(f)
                
                required_fields = ['type', 'client_email', 'private_key']
                if all(field in sa_data for field in required_fields):
                    print(f"✅ Valid service account found: {sa_path}")
                    print(f"📧 Service account email: {sa_data['client_email']}")
                    
                    # Set environment variable if not set
                    if not os.getenv('GOOGLE_APPLICATION_CREDENTIALS'):
                        os.environ['GOOGLE_APPLICATION_CREDENTIALS'] = sa_path
                        print(f"✅ Set GOOGLE_APPLICATION_CREDENTIALS={sa_path}")
                    
                    return True
                else:
                    print(f"⚠️  Invalid service account format: {sa_path}")
            except Exception as e:
                print(f"⚠️  Could not read service account {sa_path}: {e}")
    
    print("❌ No valid service account found")
    return False

def check_environment_token():
    """Check if environment-based token is available"""
    print("🔍 Checking for Environment token...")
    
    # Check for base64 encoded token
    encoded_token = os.getenv('GDRIVE_TOKEN_B64')
    if encoded_token:
        try:
            token_json = base64.b64decode(encoded_token).decode()
            token_data = json.loads(token_json)
            
            # Write token to file
            with open('gdrive_token.json', 'w') as f:
                json.dump(token_data, f)
            
            print("✅ Environment token decoded and saved")
            return True
        except Exception as e:
            print(f"⚠️  Failed to decode environment token: {e}")
    
    # Check for direct JSON token
    token_json = os.getenv('GDRIVE_TOKEN_JSON')
    if token_json:
        try:
            token_data = json.loads(token_json)
            
            with open('gdrive_token.json', 'w') as f:
                json.dump(token_data, f)
            
            print("✅ Environment JSON token saved")
            return True
        except Exception as e:
            print(f"⚠️  Failed to parse environment JSON token: {e}")
    
    print("❌ No environment token found")
    return False

def check_mounted_token():
    """Check if pre-authenticated token is mounted"""
    print("🔍 Checking for mounted token files...")
    
    mounted_token_paths = [
        '/app/gdrive_auth/gdrive_token.json',
        '/app/data/config/gdrive_token.json'
    ]
    
    for mounted_path in mounted_token_paths:
        if os.path.exists(mounted_path):
            try:
                # Copy token file to working directory
                import shutil
                shutil.copy2(mounted_path, 'gdrive_token.json')
                print(f"✅ Copied token from: {mounted_path}")
                return True
            except Exception as e:
                print(f"⚠️  Failed to copy {mounted_path}: {e}")
    
    print("❌ No mounted token files found")
    return False

def print_setup_instructions():
    """Print instructions for setting up container authentication"""
    print("\n🛠️  Container Authentication Setup Required")
    print("=" * 50)
    print("Since browsers don't work in containers, use one of these methods:")
    print()
    print("📋 Method 1: Service Account (Recommended for Production)")
    print("   1. Create service account at: https://console.cloud.google.com/")
    print("   2. Download JSON key file")
    print("   3. Mount it in container:")
    print("      -v /path/to/service-account.json:/app/data/config/gdrive_service_account.json:ro")
    print("   4. Or set environment variable:")
    print("      -e GOOGLE_APPLICATION_CREDENTIALS=/app/data/config/gdrive_service_account.json")
    print()
    print("📋 Method 2: Pre-Authenticated Token (Easiest)")
    print("   1. Authenticate on local machine: python gdrive_setup.py")
    print("   2. Mount token in container:")
    print("      -v /path/to/gdrive_token.json:/app/gdrive_auth/gdrive_token.json:ro")
    print()
    print("📋 Method 3: Environment Variables")
    print("   1. On local machine: python setup_env_gdrive.py")
    print("   2. Set environment variable:")
    print("      -e GDRIVE_TOKEN_B64='your_base64_token'")
    print()
    print("💡 The pipeline will automatically skip Google Drive backup if authentication fails")

def test_authentication():
    """Test if the current authentication setup works"""
    print("\n🔬 Testing Google Drive Authentication...")
    
    try:
        # Try to import and use the uploader
        from upload_to_gdrive import GoogleDriveUploader
        
        uploader = GoogleDriveUploader()
        if uploader.authenticate():
            print("✅ Google Drive authentication test successful!")
            return True
        else:
            print("❌ Google Drive authentication test failed")
            return False
            
    except Exception as e:
        print(f"❌ Authentication test error: {e}")
        return False

if __name__ == "__main__":
    success = setup_container_auth()
    
    if success:
        # Test the authentication
        test_authentication()
    
    print("\n" + "=" * 50)
    print("🐳 Container authentication setup complete!")
    
    if not success:
        print("❌ No authentication configured - Google Drive backup will be skipped")
        print("💡 This is normal and won't break the pipeline")
    else:
        print("✅ Google Drive backup should work in container!")
