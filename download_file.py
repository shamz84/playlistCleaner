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

def download_file_with_config(config_file="download_config.json"):
    """Download file using configuration from JSON file"""
    
    # Default configuration
    default_config = {
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
        
        # Validate required fields
        if 'url' not in config or 'data' not in config:
            print(f"❌ Missing required fields in {config_file}")
            print("Required: url, data")
            return False
            
    except json.JSONDecodeError as e:
        print(f"❌ Invalid JSON in {config_file}: {e}")
        return False
    except Exception as e:
        print(f"❌ Error loading config: {e}")
        return False
    
    return download_file(config)

def download_file(config):
    """Download file using the provided configuration"""
    
    url = config.get('url')
    headers = config.get('headers', {"Content-Type": "application/json"})
    data = config.get('data', {})
    output_filename = config.get('output_filename', 'downloaded_file.m3u')
    timeout = config.get('timeout', 30)
    
    print("=== File Download Tool ===")
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
          # Display first few lines if it's text content
        if 'text' in content_type or output_filename.endswith(('.m3u', '.txt', '.json')):
            try:
                text_content = content.decode('utf-8')
                lines = text_content.split('\n')[:5]
                total_lines = len(text_content.split('\n'))
                print(f"\n📄 File preview (first 5 lines):")
                for i, line in enumerate(lines, 1):
                    print(f"   {i}. {line[:80]}")
                if total_lines > 5:
                    print(f"   ... ({total_lines} total lines)")
            except UnicodeDecodeError:
                print(f"📄 File saved as binary content")
        
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
        else:
            print("Usage:")
            print("  python download_file.py --direct          # Use hardcoded parameters")
            print("  python download_file.py --config [file]   # Use config file (default: download_config.json)")
            print("  python download_file.py                   # Interactive mode")
            return False
    else:
        # Interactive mode - ask user what they want to do
        print("=== File Download Tool ===")
        print("1. Download with hardcoded parameters (original curl command)")
        print("2. Download with configuration file")
        
        choice = input("\nSelect option (1 or 2): ").strip()
        
        if choice == "1":
            success = download_direct()
        elif choice == "2":
            success = download_file_with_config()
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
