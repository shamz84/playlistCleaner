#!/bin/bash
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
