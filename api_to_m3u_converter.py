#!/usr/bin/env python3
"""
Xtream Codes API to M3U Converter

This script fetches channel data from Xtream Codes player_api.php endpoint
and converts the JSON response to M3U playlist format.

The script checks if existing M3U files are older than 24 hours before running.
Use --force to bypass this check.

Usage:
    python api_to_m3u_converter.py         # Normal run with age check
    python api_to_m3u_converter.py --force # Force run regardless of file age
"""

import json
import requests
import os
import glob
import sys
from urllib.parse import urljoin
from datetime import datetime, timedelta

def load_config():
    """Load server configuration from file or prompt user"""
    config_file = "data/config/xtream_api_config.json"
    
    # Try to load existing config
    if os.path.exists(config_file):
        try:
            with open(config_file, 'r', encoding='utf-8') as f:
                config = json.load(f)
                
                # Ensure new format fields exist
                if 'categories' not in config:
                    config['categories'] = []
                if 'selected_categories' not in config:
                    config['selected_categories'] = []
                
                print(f"📋 Loaded configuration from {config_file}")
                return config
        except Exception as e:
            print(f"❌ Error loading config: {e}")
    
    # Create new config
    print("🔧 Creating new API configuration...")
    config = {
        "server": input("Enter server URL (e.g., http://server.com:8080): ").strip(),
        "username": input("Enter username: ").strip(),
        "password": input("Enter password: ").strip(),
        "endpoint": "/player_api.php",
        "stream_format": "ts",
        "categories": [],
        "selected_categories": []
    }
    
    # Save config
    try:
        os.makedirs("data/config", exist_ok=True)
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2)
        print(f"✅ Configuration saved to {config_file}")
    except Exception as e:
        print(f"⚠️  Could not save config: {e}")
    
    return config

def check_existing_files_age(config):
    """Check if existing M3U files are older than 24 hours"""
    if not config.get('categories'):
        return True  # No categories configured, proceed
    
    # Get unique category names
    unique_categories = set(cat['category_name'] for cat in config['categories'])
    
    # Check each expected output file
    all_files_recent = True
    files_status = []
    
    for cat_name in unique_categories:
        # Create expected filename pattern
        safe_category = "".join(c for c in cat_name if c.isalnum() or c in (' ', '-', '_')).rstrip()
        safe_category = safe_category.replace(' ', '_')
        filename = f"{safe_category}.m3u"
        
        # Check if file exists
        if not os.path.exists(filename):
            files_status.append(f"❌ {cat_name}: No existing file found ({filename})")
            all_files_recent = False
            continue
        
        # Check file age
        file_age = datetime.now() - datetime.fromtimestamp(os.path.getmtime(filename))
        
        if file_age > timedelta(hours=24):
            files_status.append(f"⏰ {cat_name}: {filename} is {file_age.days}d {file_age.seconds//3600}h old (needs update)")
            all_files_recent = False
        else:
            hours_old = file_age.seconds // 3600
            minutes_old = (file_age.seconds % 3600) // 60
            files_status.append(f"✅ {cat_name}: {filename} is {hours_old}h {minutes_old}m old (recent)")
    
    # Display status
    print("\n📅 Existing file age check:")
    for status in files_status:
        print(f"  {status}")
    
    if all_files_recent:
        print("\n🕐 All files are less than 24 hours old. Skipping export.")
        print("💡 Use --force to override this check.")
        return False
    else:
        print(f"\n⚡ Some files are older than 24 hours. Proceeding with export...")
        return True

def save_config(config):
    """Save configuration back to file"""
    config_file = "data/config/xtream_api_config.json"
    try:
        os.makedirs("data/config", exist_ok=True)
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2)
        print(f"✅ Configuration updated and saved to {config_file}")
        return True
    except Exception as e:
        print(f"⚠️  Could not save updated config: {e}")
        return False

def fetch_channels(config, action="get_live_streams", category_id=None):
    """Fetch channels from Xtream API"""
    
    # Build API URL
    base_url = config['server'].rstrip('/')
    endpoint = config['endpoint']
    
    params = {
        'username': config['username'],
        'password': config['password'],
        'action': action
    }
    
    if category_id:
        params['category_id'] = category_id
    
    url = f"{base_url}{endpoint}"
    
    # Build full URL with parameters for display
    param_string = "&".join([f"{k}={v}" for k, v in params.items()])
    full_url = f"{url}?{param_string}"
    
    print(f"📡 Fetching channels from: {action}")
    if category_id:
        print(f"📂 Category ID: {category_id}")
    print(f"🔗 URL: {full_url}")
    
    # Add headers that many Xtream servers require
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'en-US,en;q=0.9',
        'Accept-Encoding': 'gzip, deflate',
        'Connection': 'keep-alive',
        'Cache-Control': 'no-cache'
    }
    
    try:
        response = requests.get(url, params=params, headers=headers, timeout=30)
        response.raise_for_status()
        
        channels = response.json()
        print(f"✅ Successfully fetched {len(channels)} channels")
        return channels
        
    except requests.exceptions.RequestException as e:
        print(f"❌ Network error: {e}")
        return None
    except json.JSONDecodeError as e:
        print(f"❌ JSON decode error: {e}")
        print(f"Response content: {response.text[:500]}")
        return None
    except Exception as e:
        print(f"❌ Unexpected error: {e}")
        return None

def fetch_categories(config, action="get_live_categories"):
    """Fetch available categories"""
    base_url = config['server'].rstrip('/')
    endpoint = config['endpoint']
    
    params = {
        'username': config['username'],
        'password': config['password'],
        'action': action
    }
    
    url = f"{base_url}{endpoint}"
    
    # Add headers that many Xtream servers require
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'en-US,en;q=0.9',
        'Accept-Encoding': 'gzip, deflate',
        'Connection': 'keep-alive',
        'Cache-Control': 'no-cache'
    }
    
    try:
        response = requests.get(url, params=params, headers=headers, timeout=30)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"❌ Error fetching categories: {e}")
        return []

def build_stream_url(config, stream_id, stream_type="live", extension="ts"):
    """Build the streaming URL for a channel with placeholder credentials"""
    base_url = config['server'].rstrip('/')
    # Use placeholders instead of real credentials for security
    username = "USERNAME"
    password = "PASSWORD"
    
    if stream_type == "live":
        return f"{base_url}/live/{username}/{password}/{stream_id}.{extension}"
    elif stream_type == "movie":
        return f"{base_url}/movie/{username}/{password}/{stream_id}.{extension}"
    elif stream_type == "series":
        return f"{base_url}/series/{username}/{password}/{stream_id}.{extension}"
    else:
        return f"{base_url}/live/{username}/{password}/{stream_id}.{extension}"

def convert_to_m3u(channels, config, category_name="API Channels"):
    """Convert JSON channel data to M3U format"""
    
    if not channels:
        print("❌ No channels to convert")
        return None
    
    # M3U header
    m3u_content = ["#EXTM3U\n"]
    
    print(f"🔄 Converting {len(channels)} channels to M3U format...")
    
    for channel in channels:
        try:
            # Extract channel info
            stream_id = channel.get('stream_id', '')
            name = channel.get('name', 'Unknown Channel')
            icon = channel.get('stream_icon', '')
            epg_id = channel.get('epg_channel_id', '')
            stream_type = channel.get('stream_type', 'live')
            category_id = channel.get('category_id', '')
            
            # Use individual category name if available, otherwise use the provided category_name
            channel_category = channel.get('_category_name', category_name)
            
            # Build streaming URL
            stream_url = build_stream_url(config, stream_id, stream_type)
            
            # Create EXTINF line
            extinf_parts = [
                f'#EXTINF:-1',
                f'CUID="{stream_id}"' if stream_id else '',
                f'tvg-name="{name}"' if name else '',
                f'tvg-id="{epg_id}"' if epg_id else '',
                f'tvg-logo="{icon}"' if icon else '',
                f'group-title="{channel_category}"'
            ]
            
            # Filter out empty parts and join
            extinf_line = ' '.join(filter(None, extinf_parts)) + f',{name}\n'
            
            # Add to M3U content
            m3u_content.append(extinf_line)
            m3u_content.append(f"{stream_url}\n")
            
        except Exception as e:
            print(f"⚠️  Error processing channel {channel.get('name', 'Unknown')}: {e}")
            continue
    
    return ''.join(m3u_content)

def save_m3u_file(m3u_content, filename):
    """Save M3U content to file"""
    try:
        with open(filename, 'w', encoding='utf-8') as f:
            f.write(m3u_content)
        
        # Get file size
        file_size = os.path.getsize(filename)
        line_count = len(m3u_content.split('\n'))
        
        print(f"✅ M3U playlist saved: {filename}")
        print(f"📊 File size: {file_size:,} bytes")
        print(f"📏 Lines: {line_count:,}")
        
        return True
    except Exception as e:
        print(f"❌ Error saving M3U file: {e}")
        return False

def use_configured_categories(config):
    """Use pre-configured categories from config file"""
    if not config.get('categories') or not config.get('selected_categories'):
        print("⚠️  No pre-configured categories found in config file")
        return None
    
    print("\n📂 Using pre-configured categories:")
    
    # Display configured categories
    category_map = {cat['category_id']: cat['category_name'] 
                   for cat in config['categories']}
    
    selected_names = []
    for cat_id in config['selected_categories']:
        cat_name = category_map.get(cat_id, f"Unknown ({cat_id})")
        selected_names.append(cat_name)
        print(f"  ✓ {cat_name} (ID: {cat_id})")
    
    # Fetch channels from all selected categories
    all_channels = []
    for cat_id in config['selected_categories']:
        cat_name = category_map.get(cat_id, f"Category {cat_id}")
        print(f"\n🔍 Fetching channels from {cat_name}...")
        
        channels = fetch_channels(config, "get_live_streams", cat_id)
        if channels:
            print(f"  📺 Found {len(channels)} channels")
            all_channels.extend(channels)
        else:
            print(f"  ⚠️  No channels found in {cat_name}")
    
    if all_channels:
        category_name = f"Combined ({', '.join(selected_names)})"
        return all_channels, category_name
    
    return None

def main():
    """Main function"""
    print("🚀 Xtream Codes API to M3U Converter")
    print("=" * 50)
    
    # Check for force flag
    force_run = "--force" in sys.argv
    if force_run:
        print("🔧 Force mode enabled - skipping age check")
    
    # Load configuration
    config = load_config()
    if not config:
        print("❌ Failed to load configuration")
        return
    
    # Check if we need to run based on file ages (unless forced)
    if not force_run:
        if not check_existing_files_age(config):
            return  # Exit if files are recent
    
    # Show available options
    print("\n📋 Available actions:")
    print("1. Use pre-configured categories")
    print("2. Live TV channels (get_live_streams) [DEFAULT]")
    print("3. Movies (get_vod_streams)")
    print("4. TV Series (get_series)")
    print("5. Browse categories first")
    
    choice = input("\nSelect option (1-5) [press Enter for option 2]: ").strip()
    
    # Default to option 2 if no input
    if not choice:
        choice = "2"
        print("Using default option 2: Live TV channels")
    
    if choice == "1":
        # Use pre-configured categories
        result = use_configured_categories(config)
        if result:
            channels, category_name = result
        else:
            print("❌ Failed to use pre-configured categories")
            return
    elif choice == "5":
        # Show categories first
        print("\n🔍 Fetching categories...")
        categories = fetch_categories(config)
        
        if categories:
            print("\n📂 Available categories:")
            for i, cat in enumerate(categories[:10]):  # Show first 10
                cat_id = cat.get('category_id', 'Unknown')
                cat_name = cat.get('category_name', 'Unknown')
                print(f"  {i+1:2d}. {cat_name} (ID: {cat_id})")
            
            if len(categories) > 10:
                print(f"     ... and {len(categories) - 10} more")
            
            # Offer to save categories to config
            save_choice = input("\nSave these categories to config file? (y/N): ").strip().lower()
            if save_choice == 'y':
                config['categories'] = categories
                if save_config(config):
                    print("💾 Categories saved! You can now use option 1 for quick access.")
            
            # Ask for category selection
            try:
                cat_choice = int(input(f"\nSelect category (1-{min(len(categories), 10)}): "))
                if 1 <= cat_choice <= len(categories):
                    selected_cat = categories[cat_choice - 1]
                    category_id = selected_cat.get('category_id')
                    category_name = selected_cat.get('category_name', 'Selected Category')
                    
                    # Fetch channels from selected category
                    channels = fetch_channels(config, "get_live_streams", category_id)
                else:
                    print("❌ Invalid category selection")
                    return
            except ValueError:
                print("❌ Invalid input")
                return
        else:
            print("❌ No categories found")
            return
    else:
        # Direct channel fetch
        action_map = {
            "2": ("get_live_streams", "Live TV"),
            "3": ("get_vod_streams", "Movies"),
            "4": ("get_series", "TV Series")
        }
        
        if choice not in action_map:
            print("❌ Invalid choice")
            return
        
        action, category_name = action_map[choice]
        
        # For Live TV (option 2), use configured categories if available
        if choice == "2" and config.get('categories'):
            print(f"\n📂 Using configured categories for {category_name}:")
            
            # Group categories by name and combine channels
            category_groups = {}
            
            # First pass: collect all channels by category name
            for cat in config['categories']:
                cat_id = cat['category_id']
                cat_name = cat['category_name']
                print(f"\n🔍 Fetching from {cat_name} (ID: {cat_id})...")
                
                channels = fetch_channels(config, action, cat_id)
                if channels:
                    print(f"  📺 Found {len(channels)} channels")
                    
                    # Add category name to each channel for proper group-title
                    for channel in channels:
                        channel['_category_name'] = cat_name
                    
                    # Group by category name
                    if cat_name not in category_groups:
                        category_groups[cat_name] = []
                    category_groups[cat_name].extend(channels)
                    print(f"  ✅ Added to {cat_name} group (total: {len(category_groups[cat_name])} channels)")
                else:
                    print(f"  ⚠️  No channels found in {cat_name}")
            
            # Second pass: create M3U files for each unique category name
            generated_files = []
            for cat_name, all_channels in category_groups.items():
                print(f"\n📝 Creating M3U for {cat_name} with {len(all_channels)} total channels...")
                
                # Convert combined channels to M3U
                m3u_content = convert_to_m3u(all_channels, config, cat_name)
                
                if m3u_content:
                    # Create filename for this category group
                    safe_category = "".join(c for c in cat_name if c.isalnum() or c in (' ', '-', '_')).rstrip()
                    safe_category = safe_category.replace(' ', '_')
                    filename = f"{safe_category}.m3u"
                    
                    if save_m3u_file(m3u_content, filename):
                        generated_files.append(filename)
                        print(f"  ✅ Created: {filename}")
                    else:
                        print(f"  ❌ Failed to save: {filename}")
                else:
                    print(f"  ❌ Failed to convert channels for {cat_name}")
            
            # Summary
            if generated_files:
                print(f"\n🎉 Successfully generated {len(generated_files)} M3U files:")
                for file in generated_files:
                    print(f"  📁 {file}")
            else:
                print("\n❌ No M3U files were generated")
            
            return  # Exit early since we've already processed and saved files
        else:
            # Fetch all channels without category filter
            channels = fetch_channels(config, action)
    
    if not channels:
        print("❌ No channels retrieved")
        return
    
    # Convert to M3U
    m3u_content = convert_to_m3u(channels, config, 
                                 category_name if 'category_name' in locals() else "API Channels")
    
    if not m3u_content:
        print("❌ Failed to convert channels")
        return
    
    # Save to file
    
    # Create filename with category name (no timestamp)
    if 'category_name' in locals() and category_name:
        # Clean category name for filename (remove invalid characters)
        safe_category = "".join(c for c in category_name if c.isalnum() or c in (' ', '-', '_')).rstrip()
        safe_category = safe_category.replace(' ', '_')
        filename = f"{safe_category}.m3u"
    else:
        filename = f"api_playlist.m3u"
    
    if save_m3u_file(m3u_content, filename):
        print(f"\n🎉 Conversion completed successfully!")
        print(f"📁 Output file: {filename}")
    else:
        print("❌ Failed to save M3U file")

if __name__ == "__main__":
    main()