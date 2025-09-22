#!/usr/bin/env python3
"""
Manual Shared Drive Configuration
Allows manual entry of shared drive ID for testing
"""
import os
import json
from datetime import datetime

def manual_configure_shared_drive():
    """Manually configure shared drive for testing"""
    print("🔧 Manual Shared Drive Configuration")
    print("=" * 40)
    
    print("If you have created a shared drive, you can manually configure it here.")
    print("Otherwise, you can skip this and the system will auto-detect it.")
    print()
    
    # Check if we already have a configuration
    config_file = 'data/config/gdrive_shared_drive_info.json'
    if os.path.exists(config_file):
        with open(config_file, 'r') as f:
            existing_config = json.load(f)
        
        if not existing_config.get('mock_mode'):
            print("✅ Existing configuration found:")
            print(f"   Drive Name: {existing_config.get('shared_drive_name')}")
            print(f"   Drive ID: {existing_config.get('shared_drive_id')}")
            
            response = input("\nUse existing configuration? (y/n): ").lower().strip()
            if response == 'y':
                return test_existing_config(existing_config)
    
    print("\n📝 To find your shared drive ID:")
    print("1. Go to https://drive.google.com")
    print("2. Click on your shared drive")
    print("3. Look at the URL: https://drive.google.com/drive/folders/DRIVE_ID_HERE")
    print("4. Copy the DRIVE_ID_HERE part")
    print()
    
    drive_id = input("Enter your shared drive ID (or 'skip' to skip): ").strip()
    
    if drive_id.lower() == 'skip' or not drive_id:
        print("⏭️  Skipping manual configuration")
        return False
    
    drive_name = input("Enter a name for this drive (optional): ").strip()
    if not drive_name:
        drive_name = "PlaylistCleaner-Backup"
    
    # Create configuration
    config = {
        'shared_drive_id': drive_id,
        'shared_drive_name': drive_name,
        'service_account_email': 'playlistcleanergdautomation@pro-course-469119-d5.iam.gserviceaccount.com',
        'created_at': datetime.now().isoformat(),
        'manual_config': True
    }
    
    os.makedirs('data/config', exist_ok=True)
    with open(config_file, 'w') as f:
        json.dump(config, f, indent=2)
    
    print(f"✅ Configuration saved!")
    print(f"   Drive: {drive_name}")
    print(f"   ID: {drive_id}")
    
    return test_existing_config(config)

def test_existing_config(config):
    """Test the configuration"""
    print(f"\n🧪 Testing configuration...")
    
    try:
        from enhanced_service_account_uploader import ServiceAccountGDriveUploader
        
        uploader = ServiceAccountGDriveUploader()
        
        if uploader.authenticate():
            print("✅ Authentication successful")
            
            # Try to list files in the shared drive
            print("📁 Testing shared drive access...")
            files = uploader.list_files(3)
            
            if files is not None:
                print("✅ Shared drive access confirmed!")
                
                # Ask if user wants to test upload
                response = input("\nTest file upload? (y/n): ").lower().strip()
                if response == 'y':
                    return test_upload(uploader)
                else:
                    print("✅ Configuration verified (upload test skipped)")
                    return True
            else:
                print("❌ Cannot access shared drive")
                print("💡 Make sure the service account has Editor permissions")
                return False
        else:
            print("❌ Authentication failed")
            return False
            
    except Exception as e:
        print(f"❌ Test failed: {e}")
        return False

def test_upload(uploader):
    """Test file upload"""
    print("📤 Testing file upload...")
    
    # Create a small test file
    test_file = "config_test.txt"
    with open(test_file, 'w') as f:
        f.write(f"Configuration test - {datetime.now().isoformat()}")
    
    try:
        result = uploader.upload_file(test_file)
        
        if result:
            print("🎉 Upload test successful!")
            print(f"   File ID: {result['file_id']}")
            print(f"   View Link: {result.get('web_link', 'N/A')}")
        else:
            print("❌ Upload test failed")
            # Try to print last error from uploader if available
            if hasattr(uploader, 'last_error') and uploader.last_error:
                print(f"Uploader error: {uploader.last_error}")
            else:
                print("No additional error info from uploader.")
            import traceback
            traceback.print_exc()
            return False
    except Exception as e:
        print(f"❌ Exception during upload: {e}")
        import traceback
        traceback.print_exc()
        return False
    finally:
        # Clean up
        if os.path.exists(test_file):
            os.remove(test_file)
    
    return True

if __name__ == "__main__":
    success = manual_configure_shared_drive()
    print(f"\n📊 Configuration result: {'✅ SUCCESS' if success else '⚠️  MANUAL SETUP NEEDED'}")
    
    if not success:
        print("\n💡 Quick setup steps:")
        print("1. Go to https://drive.google.com")
        print("2. Click 'New' → 'Shared drive'")
        print("3. Name: 'PlaylistCleaner-Backup'")
        print("4. Add member: playlistcleanergdautomation@pro-course-469119-d5.iam.gserviceaccount.com")
        print("5. Set role: Editor")
        print("6. Run this script again")