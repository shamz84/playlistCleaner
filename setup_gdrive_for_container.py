#!/usr/bin/env python3
"""
Google Drive Setup for Container Deployment
This script helps prepare Google Drive authentication for containerized environments.
"""
import os
import json
import shutil
from pathlib import Path

def setup_container_gdrive():
    """Setup Google Drive authentication for container use"""
    print("🐳 Google Drive Container Setup")
    print("=" * 50)
    
    # Check if we have authenticated locally
    token_files = ['gdrive_token.json', 'data/config/gdrive_token.json']
    creds_files = ['gdrive_credentials.json', 'data/config/gdrive_credentials.json']
    
    # Find existing token
    token_source = None
    for token_file in token_files:
        if os.path.exists(token_file):
            token_source = token_file
            break
    
    # Find existing credentials
    creds_source = None
    for creds_file in creds_files:
        if os.path.exists(creds_file):
            creds_source = creds_file
            break
    
    if not token_source:
        print("❌ No authenticated token found!")
        print("💡 You need to authenticate first:")
        print("   1. Run: python gdrive_setup.py")
        print("   2. Complete the browser authentication")
        print("   3. Then run this script again")
        return False
    
    if not creds_source:
        print("❌ No credentials file found!")
        print("💡 You need gdrive_credentials.json file")
        return False
    
    # Create container-ready directory structure
    container_dir = Path("container_gdrive")
    container_dir.mkdir(exist_ok=True)
    
    # Copy files to container directory
    print(f"📋 Copying token from: {token_source}")
    shutil.copy2(token_source, container_dir / "gdrive_token.json")
    
    print(f"📋 Copying credentials from: {creds_source}")
    shutil.copy2(creds_source, container_dir / "gdrive_credentials.json")
    
    # Validate token
    try:
        with open(container_dir / "gdrive_token.json", 'r') as f:
            token_data = json.load(f)
        
        required_fields = ['token', 'refresh_token', 'token_uri', 'client_id', 'client_secret']
        missing_fields = [field for field in required_fields if field not in token_data]
        
        if missing_fields:
            print(f"⚠️  Token missing fields: {missing_fields}")
            print("💡 You may need to re-authenticate")
        else:
            print("✅ Token appears valid")
            
    except Exception as e:
        print(f"⚠️  Could not validate token: {e}")
    
    # Create docker-compose.yml with volume mounting
    create_docker_compose_gdrive(container_dir)
    
    # Create container entrypoint script
    create_container_entrypoint()
    
    print("\n🎉 Container setup complete!")
    print("📁 Files prepared in: container_gdrive/")
    print("🐳 To use in container:")
    print("   docker-compose -f docker-compose.gdrive.yml up")
    print("\n💡 Or mount the directory:")
    print(f"   -v {os.path.abspath(container_dir)}:/app/gdrive_auth:ro")
    
    return True

def create_docker_compose_gdrive(container_dir):
    """Create docker-compose file with Google Drive authentication"""
    compose_content = f"""version: '3.8'

services:
  playlist-cleaner:
    build: .
    volumes:
      # Mount pre-authenticated Google Drive credentials (read-only)
      - {os.path.abspath(container_dir)}:/app/gdrive_auth:ro
      # Mount data directory for processing
      - ./data:/app/data
      # Mount config directory
      - ./data/config:/app/data/config
    environment:
      - GDRIVE_AUTH_DIR=/app/gdrive_auth
      - PYTHONPATH=/app
    command: python process_playlist_complete_enhanced.py
    restart: unless-stopped
"""
    
    with open("docker-compose.gdrive.yml", 'w') as f:
        f.write(compose_content)
    
    print("✅ Created docker-compose.gdrive.yml")

def create_container_entrypoint():
    """Create container entrypoint that handles Google Drive auth"""
    entrypoint_content = '''#!/bin/bash
# Container entrypoint with Google Drive auth handling

echo "🐳 Starting Playlist Cleaner Container"
echo "=================================="

# Check for mounted Google Drive authentication
if [ -d "/app/gdrive_auth" ]; then
    echo "📁 Google Drive auth directory found"
    
    # Copy auth files to expected locations
    if [ -f "/app/gdrive_auth/gdrive_token.json" ]; then
        echo "📋 Copying Google Drive token..."
        cp /app/gdrive_auth/gdrive_token.json /app/gdrive_token.json
        chmod 600 /app/gdrive_token.json
    fi
    
    if [ -f "/app/gdrive_auth/gdrive_credentials.json" ]; then
        echo "📋 Copying Google Drive credentials..."
        cp /app/gdrive_auth/gdrive_credentials.json /app/gdrive_credentials.json
        chmod 600 /app/gdrive_credentials.json
    fi
    
    echo "✅ Google Drive authentication configured"
else
    echo "⚠️  No Google Drive auth found - backup will be skipped"
fi

# Execute the main command
echo "🚀 Starting main process..."
exec "$@"
'''
    
    with open("docker-entrypoint-gdrive.sh", 'w', encoding='utf-8') as f:
        f.write(entrypoint_content)
    
    # Make executable (on Unix systems)
    try:
        os.chmod("docker-entrypoint-gdrive.sh", 0o755)
    except:
        pass  # Windows doesn't support chmod
    
    print("✅ Created docker-entrypoint-gdrive.sh")

if __name__ == "__main__":
    setup_container_gdrive()
