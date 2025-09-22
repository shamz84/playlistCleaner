#!/usr/bin/env python3
"""
File Download Script
This script downloads a file using HTTP POST request with JSON payload.
Equivalent to: curl --location 'https://repo-server.site/manual' --header 'Content-Type: application/json' --data '{"id":"19"}'
"""
import requests
import json
import sys
import os
from pathlib import Path
import time
import re
from urllib.parse import urlparse, parse_qs

def download_file_with_config(config_file="download_config.json"):
    """Download file using configuration from JSON file"""
    
    # Default configuration
    default_config = {
        "download_type": "post_request",  # "post_request" or "google_drive"
        "url": "https://repo-server.site/manual",
        "headers": {
            "Content-Type": "application/json"
        },
        "data": {
            "id": "20"
        },
        "output_filename": "downloaded_file.m3u",
        "timeout": 30,
        # Google Drive specific fields
        "google_drive_url": "https://drive.google.com/file/d/YOUR_FILE_ID_HERE/view?usp=sharing",
        "google_drive_file_id": "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
    }
    
    # Load configuration if file exists, otherwise create template
    if not os.path.exists(config_file):
        print(f"📝 Creating configuration file: {config_file}")
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(default_config, f, indent=2)
        
        print(f"⚠️  Please edit {config_file} with your actual download parameters and run again.")
        return False
    
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        # Validate required fields based on download type
        download_type = config.get('download_type', 'post_request')
        
        if download_type == 'post_request':
            if 'url' not in config or 'data' not in config:
                print(f"❌ Missing required fields in {config_file}")
                print("Required for post_request: url, data")
                return False
        elif download_type == 'google_drive':
            # Check for multi-file format
            if 'files' in config:
                files = config.get('files', [])
                if not files:
                    print(f"❌ Missing required fields in {config_file}")
                    print("Required for google_drive multi-file: files array with file objects")
                    return False
                # Validate each file has either URL or ID
                for i, file_config in enumerate(files):
                    if 'google_drive_url' not in file_config and 'google_drive_file_id' not in file_config:
                        print(f"❌ Missing required fields in {config_file}")
                        print(f"File {i+1} requires: google_drive_url OR google_drive_file_id")
                        return False
            # Check for single file format
            elif 'google_drive_url' not in config and 'google_drive_file_id' not in config:
                print(f"❌ Missing required fields in {config_file}")
                print("Required for google_drive: google_drive_url OR google_drive_file_id")
                return False
        else:
            print(f"❌ Invalid download_type: {download_type}")
            print("Valid types: post_request, google_drive")
            return False
            
    except json.JSONDecodeError as e:
        print(f"❌ Invalid JSON in {config_file}: {e}")
        return False
    except Exception as e:
        print(f"❌ Error loading config: {e}")
    
    return download_file(config)

def download_google_drive_interactive():
    """Interactive Google Drive download"""
    
    print("=== Google Drive Download ===")
    print("Enter Google Drive file URL or File ID:")
    print("Examples:")
    print("  - https://drive.google.com/file/d/1ABC123.../view?usp=sharing")
    print("  - https://drive.google.com/open?id=1ABC123...")
    print("  - 1ABC123... (just the file ID)")
    print()
    
    url_or_id = input("Google Drive URL or File ID: ").strip()
    
    if not url_or_id:
        print("❌ No URL or ID provided")
        return False
    
    # Extract file ID
    file_id = extract_google_drive_file_id(url_or_id)
    if not file_id:
        print(f"❌ Could not extract file ID from: {url_or_id}")
        return False
    
    print(f"✅ Extracted file ID: {file_id}")
    
    # Get output filename
    default_filename = "gdrive_download.file"
    output_filename = input(f"Output filename (default: {default_filename}): ").strip()
    if not output_filename:
        output_filename = default_filename
    
    # Download the file
    return download_google_drive_file(file_id, output_filename)

def extract_google_drive_file_id(url):
    """Extract file ID from various Google Drive URL formats"""
    
    # Pattern 1: https://drive.google.com/file/d/FILE_ID/view?usp=sharing
    pattern1 = r'drive\.google\.com/file/d/([a-zA-Z0-9_-]+)'
    match1 = re.search(pattern1, url)
    if match1:
        return match1.group(1)
    
    # Pattern 2: https://drive.google.com/open?id=FILE_ID
    pattern2 = r'drive\.google\.com/open\?id=([a-zA-Z0-9_-]+)'
    match2 = re.search(pattern2, url)
    if match2:
        return match2.group(1)
    
    # Pattern 3: https://drive.google.com/uc?id=FILE_ID
    pattern3 = r'drive\.google\.com/uc\?.*id=([a-zA-Z0-9_-]+)'
    match3 = re.search(pattern3, url)
    if match3:
        return match3.group(1)
    
    # Pattern 4: Direct file ID (if just the ID is provided)
    if re.match(r'^[a-zA-Z0-9_-]{25,}$', url):
        return url
    
    return None

def download_google_drive_file(file_id, output_filename, timeout=30):
    """Download file from Google Drive using file ID"""
    
    print(f"🔄 Downloading from Google Drive...")
    print(f"📋 File ID: {file_id}")
    print(f"📁 Output: {output_filename}")
    
    # Try direct download first
    direct_url = f"https://drive.google.com/uc?export=download&id={file_id}"
    
    try:
        start_time = time.time()
        
        session = requests.Session()
        response = session.get(direct_url, timeout=timeout, stream=True)
        
        # Check if Google is asking for virus scan confirmation
        if 'virus scan warning' in response.text.lower() or 'download_warning' in response.text:
            print("⚠️  Large file detected, handling virus scan warning...")
            
            # Look for the confirmation token
            token_pattern = r'name="confirm" value="([^"]+)"'
            token_match = re.search(token_pattern, response.text)
            
            if token_match:
                token = token_match.group(1)
                confirm_url = f"https://drive.google.com/uc?export=download&id={file_id}&confirm={token}"
                
                print(f"🔄 Retrying with confirmation token...")
                response = session.get(confirm_url, timeout=timeout, stream=True)
            else:
                print("❌ Could not find confirmation token")
                return False
        
        response.raise_for_status()
        
        # Check content type and length
        content_type = response.headers.get('content-type', 'unknown')
        content_length = response.headers.get('content-length')
        
        if content_length:
            content_length = int(content_length)
            print(f"📏 Content Length: {content_length:,} bytes")
        else:
            print(f"📏 Content Length: Unknown")
        
        print(f"🏷️  Content Type: {content_type}")
        
        # Download the file
        total_downloaded = 0
        chunk_size = 8192
        
        with open(output_filename, 'wb') as f:
            for chunk in response.iter_content(chunk_size=chunk_size):
                if chunk:
                    f.write(chunk)
                    total_downloaded += len(chunk)
                    
                    # Show progress for large files
                    if content_length and total_downloaded % (chunk_size * 100) == 0:
                        progress = (total_downloaded / content_length) * 100
                        print(f"📥 Progress: {progress:.1f}% ({total_downloaded:,}/{content_length:,} bytes)", end='\r')
        
        end_time = time.time()
        download_time = end_time - start_time
        
        print(f"\n✅ Download successful!")
        print(f"💾 Saved to: {output_filename}")
        print(f"📊 Total size: {total_downloaded:,} bytes")
        print(f"⏱️  Download time: {download_time:.2f} seconds")
        
        # Show preview if it's a text file
        show_file_preview(output_filename, total_downloaded)
        
        return True
        
    except requests.exceptions.RequestException as e:
        print(f"❌ Download failed: {e}")
        return False
    except Exception as e:
        print(f"❌ Error during download: {e}")
        return False

def show_file_preview(filename, file_size):
    """Show preview of downloaded file"""
    
    # Only show preview for reasonably sized text files
    if file_size > 1024 * 1024:  # Skip preview for files > 1MB
        print(f"📄 File too large for preview")
        return
    
    try:
        # Try to detect if it's a text file
        with open(filename, 'rb') as f:
            sample = f.read(1024)
        
        # Simple text detection
        try:
            sample.decode('utf-8')
            is_text = True
        except UnicodeDecodeError:
            is_text = False
        
        if is_text or filename.endswith(('.m3u', '.txt', '.json', '.xml', '.html', '.csv')):
            with open(filename, 'r', encoding='utf-8') as f:
                lines = f.readlines()[:5]
                total_lines = len(open(filename, 'r', encoding='utf-8').readlines())
                
                print(f"\n📄 File preview (first 5 lines):")
                for i, line in enumerate(lines, 1):
                    print(f"   {i}. {line.rstrip()[:80]}")
                if total_lines > 5:
                    print(f"   ... ({total_lines} total lines)")
        else:
            print(f"📄 Binary file - no preview available")
            
    except Exception as e:
        print(f"📄 Could not preview file: {e}")

def download_file(config):
    """Download file using the provided configuration"""
    
    download_type = config.get('download_type', 'post_request')
    
    if download_type == 'google_drive':
        return download_google_drive_wrapper(config)
    else:
        return download_post_request(config)

def download_google_drive_wrapper(config):
    """Wrapper for Google Drive downloads"""
    
    # Check if this is a multi-file configuration
    if 'files' in config:
        return download_multiple_google_drive_files(config)
    
    # Single file download
    file_id = config.get('google_drive_file_id')
    google_drive_url = config.get('google_drive_url', '')
    output_filename = config.get('output_filename', 'gdrive_download.file')
    timeout = config.get('timeout', 30)
    
    # Extract file ID if URL is provided
    if not file_id and google_drive_url:
        file_id = extract_google_drive_file_id(google_drive_url)
        if not file_id:
            print(f"❌ Could not extract file ID from URL: {google_drive_url}")
            print("💡 Make sure the URL is a valid Google Drive share link")
            return False
    
    if not file_id:
        print(f"❌ No file ID provided")
        return False
    
    print("=== Google Drive Download ===")
    print(f"📋 File ID: {file_id}")
    print(f"📁 Output: {output_filename}")
    print(f"⏱️  Timeout: {timeout}s")
    
    return download_google_drive_file(file_id, output_filename, timeout)

def download_multiple_google_drive_files(config):
    """Download multiple Google Drive files"""
    
    files = config.get('files', [])
    timeout = config.get('timeout', 30)
    
    if not files:
        print("❌ No files specified in configuration")
        return False
    
    print("=== Multiple Google Drive Downloads ===")
    print(f"📋 Files to download: {len(files)}")
    print(f"⏱️  Timeout: {timeout}s")
    
    success_count = 0
    total_files = len(files)
    
    for i, file_config in enumerate(files, 1):
        file_id = file_config.get('google_drive_file_id')
        google_drive_url = file_config.get('google_drive_url', '')
        output_filename = file_config.get('output_filename', f'gdrive_download_{i}.file')
        description = file_config.get('description', f'File {i}')
        
        print(f"\n📂 [{i}/{total_files}] {description}")
        
        # Extract file ID if URL is provided
        if not file_id and google_drive_url:
            file_id = extract_google_drive_file_id(google_drive_url)
            if not file_id:
                print(f"❌ Could not extract file ID from URL: {google_drive_url}")
                continue
        
        if not file_id:
            print(f"❌ No file ID provided for file {i}")
            continue
        
        # Download the file
        if download_google_drive_file(file_id, output_filename, timeout):
            success_count += 1
            print(f"✅ [{i}/{total_files}] Successfully downloaded: {output_filename}")
        else:
            print(f"❌ [{i}/{total_files}] Failed to download: {output_filename}")
    
    print(f"\n📊 Download Summary:")
    print(f"   ✅ Successful: {success_count}")
    print(f"   ❌ Failed: {total_files - success_count}")
    print(f"   📁 Total: {total_files}")
    
    return success_count > 0

def download_post_request(config):
    """Download file using POST request (original functionality)"""
def download_post_request(config):
    """Download file using POST request (original functionality)"""
    
    url = config.get('url')
    headers = config.get('headers', {"Content-Type": "application/json"})
    data = config.get('data', {})
    output_filename = config.get('output_filename', 'downloaded_file.m3u')
    timeout = config.get('timeout', 30)
    
    print("=== POST Request Download ===")
    print(f"🌐 URL: {url}")
    print(f"📋 Headers: {headers}")
    print(f"📤 Data: {data}")
    print(f"📁 Output: {output_filename}")
    print(f"⏱️  Timeout: {timeout}s")
    
    try:
        print(f"\n🔄 Starting download...")
        start_time = time.time()
        
        # Make the POST request
        response = requests.post(
            url=url,
            headers=headers,
            json=data,  # This automatically converts dict to JSON and sets appropriate headers
            timeout=timeout,
            allow_redirects=True  # Follow redirects (equivalent to curl --location)
        )
        
        # Check if request was successful
        response.raise_for_status()
        
        # Get content
        content = response.content
        content_length = len(content)
        
        # Determine content type
        content_type = response.headers.get('content-type', 'unknown')
        
        print(f"✅ Download successful!")
        print(f"📊 Status Code: {response.status_code}")
        print(f"📏 Content Length: {content_length:,} bytes")
        print(f"🏷️  Content Type: {content_type}")
        
        # Save to file
        with open(output_filename, 'wb') as f:
            f.write(content)
        
        end_time = time.time()
        download_time = end_time - start_time
        
        print(f"💾 Saved to: {output_filename}")
        print(f"⏱️  Download time: {download_time:.2f} seconds")
        
        # Show preview
        show_file_preview(output_filename, content_length)
        
        return True
        
    except requests.exceptions.RequestException as e:
        print(f"❌ Download failed: {e}")
        return False
    except Exception as e:
        print(f"❌ Error during download: {e}")
        return False

def download_direct():
    """Download file using hardcoded parameters (original curl command)"""
    
    config = {
        "url": "https://repo-server.site/manual",
        "headers": {
            "Content-Type": "application/json"
        },
        "data": {
            "id": "20"
        },
        "output_filename": "downloaded_file.m3u",
        "timeout": 30
    }
    
    return download_file(config)

def main():
    """Main function"""
    
    # Check command line arguments
    if len(sys.argv) > 1:
        if sys.argv[1] == "--direct":
            print("🚀 Using direct download mode (hardcoded parameters)")
            success = download_direct()
        elif sys.argv[1] == "--config":
            config_file = sys.argv[2] if len(sys.argv) > 2 else "download_config.json"
            print(f"🚀 Using configuration file: {config_file}")
            success = download_file_with_config(config_file)
        elif sys.argv[1] == "--gdrive":
            if len(sys.argv) > 2:
                url_or_id = sys.argv[2]
                file_id = extract_google_drive_file_id(url_or_id)
                if file_id:
                    print(f"🚀 Downloading from Google Drive: {file_id}")
                    success = download_google_drive_file(file_id, "gdrive_download.file")
                else:
                    print(f"❌ Invalid Google Drive URL or ID: {url_or_id}")
                    return False
            else:
                print("🚀 Interactive Google Drive download")
                success = download_google_drive_interactive()
        else:
            print("Usage:")
            print("  python download_file.py --direct          # Use hardcoded parameters")
            print("  python download_file.py --config [file]   # Use config file (default: download_config.json)")
            print("  python download_file.py --gdrive [url/id] # Download from Google Drive")
            print("  python download_file.py                   # Interactive mode")
            return False
    else:
        # Interactive mode - ask user what they want to do
        print("=== File Download Tool ===")
        print("1. Download with hardcoded parameters (original curl command)")
        print("2. Download with configuration file")
        print("3. Download from Google Drive (interactive)")
        
        choice = input("\nSelect option (1, 2, or 3): ").strip()
        
        if choice == "1":
            success = download_direct()
        elif choice == "2":
            success = download_file_with_config()
        elif choice == "3":
            success = download_google_drive_interactive()
        else:
            print("❌ Invalid choice")
            return False
    
    if success:
        print(f"\n🎉 Download completed successfully!")
    else:
        print(f"\n💥 Download failed!")
    
    return success

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
