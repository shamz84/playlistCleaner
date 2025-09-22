#!/usr/bin/env python3
"""
Container-friendly Google Drive authentication using environment variables
"""
import os
import json
import base64

def setup_env_based_auth():
    """Setup environment variable based authentication"""
    print("🌍 Environment Variable Authentication Setup")
    print("=" * 45)
    
    # Check for existing token
    token_files = ['gdrive_token.json', 'data/config/gdrive_token.json']
    token_source = None
    
    for token_file in token_files:
        if os.path.exists(token_file):
            token_source = token_file
            break
    
    if not token_source:
        print("❌ No token file found!")
        print("💡 Run authentication first: python gdrive_setup.py")
        return False
    
    # Read and encode token
    with open(token_source, 'r') as f:
        token_data = json.load(f)
    
    # Base64 encode the token JSON
    token_json = json.dumps(token_data)
    encoded_token = base64.b64encode(token_json.encode()).decode()
    
    # Create .env file for docker
    env_content = f"""# Google Drive Authentication (Base64 encoded)
GDRIVE_TOKEN_B64={encoded_token}

# Alternative: Direct JSON (escape quotes properly in docker-compose)
# GDRIVE_TOKEN_JSON='{token_json.replace("'", "\\'")}'
"""
    
    with open('.env.gdrive', 'w') as f:
        f.write(env_content)
    
    print("✅ Created .env.gdrive with encoded token")
    print("💡 Add this to your docker-compose.yml:")
    print(f"   environment:")
    print(f"     - GDRIVE_TOKEN_B64={encoded_token[:50]}...")
    
    # Create helper script for container
    create_env_auth_helper()
    
    return True

def create_env_auth_helper():
    """Create helper script to handle environment-based auth in containers"""
    helper_content = '''#!/usr/bin/env python3
"""
Helper to setup Google Drive auth from environment variables in containers
"""
import os
import json
import base64

def setup_gdrive_from_env():
    """Setup Google Drive authentication from environment variables"""
    
    # Try base64 encoded token first
    encoded_token = os.getenv('GDRIVE_TOKEN_B64')
    if encoded_token:
        try:
            token_json = base64.b64decode(encoded_token).decode()
            token_data = json.loads(token_json)
            
            with open('gdrive_token.json', 'w') as f:
                json.dump(token_data, f)
            
            print("✅ Google Drive token restored from environment")
            return True
            
        except Exception as e:
            print(f"❌ Failed to decode token from environment: {e}")
    
    # Try direct JSON token
    token_json = os.getenv('GDRIVE_TOKEN_JSON')
    if token_json:
        try:
            token_data = json.loads(token_json)
            
            with open('gdrive_token.json', 'w') as f:
                json.dump(token_data, f)
            
            print("✅ Google Drive token restored from JSON environment")
            return True
            
        except Exception as e:
            print(f"❌ Failed to parse token JSON from environment: {e}")
    
    print("⚠️  No Google Drive token found in environment variables")
    print("💡 Set GDRIVE_TOKEN_B64 or GDRIVE_TOKEN_JSON")
    return False

if __name__ == "__main__":
    setup_gdrive_from_env()
'''
    
    with open('setup_gdrive_from_env.py', 'w') as f:
        f.write(helper_content)
    
    print("✅ Created setup_gdrive_from_env.py")

if __name__ == "__main__":
    setup_env_based_auth()
