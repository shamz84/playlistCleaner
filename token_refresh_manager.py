#!/usr/bin/env python3
"""
Automatic Google Drive Token Refresh System
Keeps OAuth tokens fresh automatically
"""
import os
import json
import time
import threading
from datetime import datetime, timedelta

class TokenRefreshManager:
    """Manages automatic token refresh for Google Drive"""
    
    def __init__(self, token_file="data/config/gdrive_token.json"):
        self.token_file = token_file
        self.refresh_interval = 30 * 60  # Refresh every 30 minutes
        self.running = False
        self.refresh_thread = None
    
    def get_token_expiry(self):
        """Get token expiry time"""
        try:
            with open(self.token_file, 'r') as f:
                token_data = json.load(f)
            
            if 'expiry' in token_data and token_data['expiry']:
                expiry_time = datetime.fromisoformat(token_data['expiry'].replace('Z', '+00:00'))
                return expiry_time
            return None
            
        except Exception as e:
            print(f"❌ Error reading token: {e}")
            return None
    
    def needs_refresh(self):
        """Check if token needs refresh (expires in less than 10 minutes)"""
        expiry = self.get_token_expiry()
        if not expiry:
            return True
        
        now = datetime.now(expiry.tzinfo)
        time_left = expiry - now
        
        # Refresh if expires in less than 10 minutes
        return time_left.total_seconds() < 600
    
    def refresh_token(self):
        """Refresh the token using Google API"""
        try:
            from google.auth.transport.requests import Request
            from google.oauth2.credentials import Credentials
            
            # Load current credentials
            creds = Credentials.from_authorized_user_file(self.token_file)
            
            if creds.expired and creds.refresh_token:
                print("🔄 Refreshing Google Drive token...")
                creds.refresh(Request())
                
                # Save refreshed token
                with open(self.token_file, 'w') as f:
                    f.write(creds.to_json())
                
                expiry = datetime.fromisoformat(creds.expiry.replace('Z', '+00:00'))
                print(f"✅ Token refreshed! New expiry: {expiry}")
                return True
            else:
                print("⚠️  Cannot refresh token - no refresh token available")
                return False
                
        except Exception as e:
            print(f"❌ Token refresh failed: {e}")
            return False
    
    def monitor_loop(self):
        """Background monitoring loop"""
        while self.running:
            try:
                if self.needs_refresh():
                    self.refresh_token()
                else:
                    expiry = self.get_token_expiry()
                    if expiry:
                        now = datetime.now(expiry.tzinfo)
                        time_left = expiry - now
                        print(f"✅ Token valid for: {time_left}")
                
                # Wait before next check
                time.sleep(self.refresh_interval)
                
            except Exception as e:
                print(f"❌ Monitor loop error: {e}")
                time.sleep(60)  # Wait 1 minute on error
    
    def start_monitoring(self):
        """Start background token monitoring"""
        if self.running:
            print("⚠️  Token monitoring already running")
            return
        
        self.running = True
        self.refresh_thread = threading.Thread(target=self.monitor_loop, daemon=True)
        self.refresh_thread.start()
        print("🔄 Started automatic token refresh monitoring")
    
    def stop_monitoring(self):
        """Stop background token monitoring"""
        self.running = False
        if self.refresh_thread:
            self.refresh_thread.join(timeout=5)
        print("⏹️  Stopped token monitoring")

def create_token_refresh_service():
    """Create a systemd service for automatic token refresh"""
    service_content = f"""[Unit]
Description=Google Drive Token Refresh Service
After=network.target

[Service]
Type=simple
User=appuser
WorkingDirectory={os.getcwd()}
ExecStart=/usr/bin/python3 {os.path.join(os.getcwd(), 'token_refresh_service.py')}
Restart=always
RestartSec=60

[Install]
WantedBy=multi-user.target
"""
    
    with open('gdrive-token-refresh.service', 'w') as f:
        f.write(service_content)
    
    print("✅ Created systemd service file: gdrive-token-refresh.service")
    print("💡 To install:")
    print("   sudo cp gdrive-token-refresh.service /etc/systemd/system/")
    print("   sudo systemctl enable gdrive-token-refresh")
    print("   sudo systemctl start gdrive-token-refresh")

if __name__ == "__main__":
    # Example usage
    manager = TokenRefreshManager()
    
    # Check current status
    if manager.needs_refresh():
        print("🔄 Token needs refresh")
        manager.refresh_token()
    else:
        expiry = manager.get_token_expiry()
        if expiry:
            now = datetime.now(expiry.tzinfo)
            time_left = expiry - now
            print(f"✅ Token valid for: {time_left}")
    
    # Create service file
    create_token_refresh_service()
    
    print("\n💡 To use automatic refresh in your application:")
    print("   from token_refresh_manager import TokenRefreshManager")
    print("   manager = TokenRefreshManager()")
    print("   manager.start_monitoring()  # Runs in background")
