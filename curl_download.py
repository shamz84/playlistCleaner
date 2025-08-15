#!/usr/bin/env python3
"""
Simple cURL Equivalent Script
This script replicates the exact curl command you provided.
"""
import subprocess
import sys
import os
import json

def download_with_curl():
    """Use system curl to download the file"""
    
    print("=== cURL Download Tool ===")
    print("🔄 Executing curl command...")
    
    # Your exact curl command
    curl_command = [
        "curl",
        "--location",
        "https://repo-server.site/manual",
        "--header", "Content-Type: application/json",
        "--data", '{"id":"19"}',
        "--output", "manual_download.m3u",
        "--silent",
        "--show-error"
    ]
    
    try:
        print(f"🌐 URL: https://repo-server.site/manual")
        print(f"📤 Data: {{'id':'19'}}")
        print(f"📁 Output: manual_download.m3u")
        
        # Execute curl command
        result = subprocess.run(curl_command, capture_output=True, text=True)
        
        if result.returncode == 0:
            print(f"✅ Download completed successfully!")
            
            # Check if file was created
            if os.path.exists("manual_download.m3u"):
                file_size = os.path.getsize("manual_download.m3u")
                print(f"📏 File size: {file_size:,} bytes")
                
                # Show preview if it's a text file
                try:
                    with open("manual_download.m3u", 'r', encoding='utf-8') as f:
                        lines = f.readlines()[:5]
                    
                    print(f"📄 File preview (first 5 lines):")
                    for i, line in enumerate(lines, 1):
                        print(f"   {i}. {line.strip()}")
                    
                    if len(lines) == 5:
                        print(f"   ... (file continues)")
                        
                except Exception as e:
                    print(f"📄 File saved (unable to preview: {e})")
            else:
                print(f"⚠️  Command executed but no output file found")
                
            return True
        else:
            print(f"❌ cURL failed with return code: {result.returncode}")
            if result.stderr:
                print(f"❌ Error: {result.stderr}")
            return False
            
    except FileNotFoundError:
        print(f"❌ cURL not found. Please install cURL or use the Python version.")
        print(f"💡 Alternative: python download_file.py --direct")
        return False
    except Exception as e:
        print(f"❌ Error executing cURL: {e}")
        return False

def download_with_powershell():
    """Use PowerShell Invoke-WebRequest as alternative"""
    
    print("=== PowerShell Download Tool ===")
    print("🔄 Using Invoke-WebRequest...")
    
    # PowerShell equivalent command
    ps_command = [
        "powershell", "-Command",
        "$body = '{\"id\":\"19\"}'; "
        "$headers = @{'Content-Type'='application/json'}; "
        "Invoke-WebRequest -Uri 'https://repo-server.site/manual' "
        "-Method POST -Body $body -Headers $headers "
        "-OutFile 'manual_download_ps.m3u'"
    ]
    
    try:
        print(f"🌐 URL: https://repo-server.site/manual")
        print(f"📤 Data: {{'id':'19'}}")
        print(f"📁 Output: manual_download_ps.m3u")
        
        result = subprocess.run(ps_command, capture_output=True, text=True)
        
        if result.returncode == 0:
            print(f"✅ Download completed successfully!")
            
            if os.path.exists("manual_download_ps.m3u"):
                file_size = os.path.getsize("manual_download_ps.m3u")
                print(f"📏 File size: {file_size:,} bytes")
            
            return True
        else:
            print(f"❌ PowerShell command failed: {result.stderr}")
            return False
            
    except Exception as e:
        print(f"❌ Error executing PowerShell: {e}")
        return False

def main():
    """Main function"""
    
    print("=== Download Tool Options ===")
    print("1. Use cURL (system command)")
    print("2. Use PowerShell (Windows)")
    print("3. Use Python requests (recommended)")
    
    if len(sys.argv) > 1:
        choice = sys.argv[1]
    else:
        choice = input("\nSelect option (1, 2, or 3): ").strip()
    
    if choice == "1" or choice == "--curl":
        success = download_with_curl()
    elif choice == "2" or choice == "--powershell":
        success = download_with_powershell()
    elif choice == "3" or choice == "--python":
        print(f"💡 Run: python download_file.py --direct")
        return True
    else:
        print("❌ Invalid choice. Using cURL by default...")
        success = download_with_curl()
    
    return success

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
