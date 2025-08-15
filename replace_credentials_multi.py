#!/usr/bin/env python3
"""
M3U Credential Replacement Script
This script replaces DNS, USERNAME, and PASSWORD placeholders in M3U files
based on configuration from a JSON file. Supports multiple credential sets.
"""
import json
import re
import sys
import os
from pathlib import Path

def load_config(config_file):
    """Load configuration from JSON file"""
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        # Check if it's a single credential object or an array
        if isinstance(config, dict):
            # Single credential object - convert to array for consistent processing
            if all(field in config for field in ['dns', 'username', 'password']):
                return [config]
            else:
                print(f"❌ Missing required fields in config. Required: dns, username, password")
                return None
        elif isinstance(config, list):
            # Multiple credential objects
            valid_configs = []
            for i, cred in enumerate(config):
                if not isinstance(cred, dict):
                    print(f"❌ Entry {i+1} is not a valid credential object")
                    continue
                
                missing_fields = [field for field in ['dns', 'username', 'password'] if field not in cred]
                if missing_fields:
                    print(f"❌ Entry {i+1} missing required fields: {', '.join(missing_fields)}")
                    continue
                
                valid_configs.append(cred)
            
            if not valid_configs:
                print(f"❌ No valid credential entries found")
                return None
            
            return valid_configs
        else:
            print(f"❌ Config must be either a credential object or an array of credential objects")
            return None
    
    except FileNotFoundError:
        print(f"❌ Config file not found: {config_file}")
        return None
    except json.JSONDecodeError as e:
        print(f"❌ Invalid JSON in config file: {e}")
        return None
    except Exception as e:
        print(f"❌ Error loading config: {e}")
        return None

def replace_credentials(line, config):
    """Replace DNS, USERNAME, and PASSWORD in a single line"""
    # Replace placeholders with actual values
    line = line.replace('DNS', config['dns'])
    line = line.replace('USERNAME', config['username'])
    line = line.replace('PASSWORD', config['password'])
    
    return line

def process_m3u_file(input_file, output_file, config):
    """Process M3U file and replace credentials"""
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        print(f"   📖 Reading {input_file} ({len(lines)} lines)")
        
        # Process each line
        processed_lines = []
        url_replacements = 0
        
        for i, line in enumerate(lines):
            original_line = line
            processed_line = replace_credentials(line, config)
            
            # Count URL replacements (lines containing http://)
            if 'http://' in original_line and original_line != processed_line:
                url_replacements += 1
            
            processed_lines.append(processed_line)
        
        # Write processed file
        with open(output_file, 'w', encoding='utf-8') as f:
            f.writelines(processed_lines)
        
        print(f"   ✅ Created {output_file}")
        print(f"   📊 Replaced credentials in {url_replacements} URLs")
        
        # Calculate file sizes
        input_size = os.path.getsize(input_file)
        output_size = os.path.getsize(output_file)
        
        print(f"   📏 Output file size: {output_size:,} bytes")
        
        return True
        
    except FileNotFoundError:
        print(f"   ❌ Input file not found: {input_file}")
        return False
    except Exception as e:
        print(f"   ❌ Error processing file: {e}")
        return False

def main():
    """Main function"""
    print("=== M3U Credential Replacement Tool ===")
    
    # Default file paths
    config_file = "credentials.json"
    input_file = "filtered_playlist_final.m3u"
    
    # Check if config file exists, if not create a template
    if not os.path.exists(config_file):
        print(f"📝 Creating template config file: {config_file}")
        template_config = [
            {
                "dns": "server1.example.com:8080",
                "username": "user1", 
                "password": "pass1"
            },
            {
                "dns": "server2.example.com:8080",
                "username": "user2",
                "password": "pass2"
            }
        ]
        
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(template_config, f, indent=2)
        
        print(f"⚠️  Please edit {config_file} with your actual credentials and run the script again.")
        print(f"📋 Template created with support for multiple credential sets.")
        return False
    
    # Load configuration
    configs = load_config(config_file)
    if not configs:
        return False
    
    # Check if input file exists
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        print("Available M3U files:")
        for file in Path('.').glob('*.m3u'):
            print(f"   - {file.name}")
        return False
    
    print(f"🔧 Found {len(configs)} credential set(s) to process")
    print(f"📥 Input file: {input_file}")
    
    # Process each credential set
    all_success = True
    created_files = []
    
    for i, config in enumerate(configs, 1):
        print(f"\n{'='*50}")
        print(f"🔄 Processing credential set {i}/{len(configs)}")
        print(f"   DNS: {config['dns']}")
        print(f"   Username: {config['username']}")
        print(f"   Password: {'*' * len(config['password'])}")
        
        # Generate output filename based on username
        output_file = f"8k_{config['username']}.m3u"
        
        print(f"   Output: {output_file}")
        
        # Process the file
        success = process_m3u_file(input_file, output_file, config)
        
        if success:
            created_files.append(output_file)
            print(f"   ✅ SUCCESS: {output_file}")
        else:
            print(f"   ❌ FAILED: {output_file}")
            all_success = False
    
    # Final summary
    print(f"\n{'='*50}")
    print(f"📊 PROCESSING SUMMARY")
    print(f"   Total credential sets: {len(configs)}")
    print(f"   Successful: {len(created_files)}")
    print(f"   Failed: {len(configs) - len(created_files)}")
    
    if created_files:
        print(f"\n🎉 Files created:")
        for file in created_files:
            print(f"   ✅ {file}")
    
    if all_success:
        print(f"\n🚀 All playlists are ready to use!")
    else:
        print(f"\n⚠️  Some playlists failed to process")
    
    return all_success

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
