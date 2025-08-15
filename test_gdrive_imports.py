#!/usr/bin/env python3
try:
    import googleapiclient
    print("✅ Google API client available")
    
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from google_auth_oauthlib.flow import InstalledAppFlow
    from googleapiclient.discovery import build
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload
    
    print("✅ All Google Drive API modules imported successfully")
    print("🎉 Ready to use Google Drive upload functionality!")
    
except ImportError as e:
    print("❌ Google Drive API libraries not installed!")
    print(f"   Error: {e}")
    print("📦 Please install required packages:")
    print("   pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib")
