# 🐳 Container Google Drive Solutions

## 🎯 **Solution 1: Pre-authenticate (RECOMMENDED)**

### **✅ How it Works:**
1. **Authenticate on your host machine** (Windows PC with browser)
2. **Mount the token file** into the container
3. **Container uses existing token** without authentication

### **📝 Implementation Steps:**

#### Step 1: One-time Authentication on Host
```powershell
cd "c:\dev\training\PlaylistCleaner"
python upload_to_gdrive.py --setup
```
*This creates `gdrive_token.json` with your authentication credentials*

#### Step 2: Run Container with Token Mount
```powershell
podman run --rm `
  -v "${PWD}/data:/app/data" `
  -v "${PWD}/config:/app/data/config:ro" `
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" `
  -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" `
  -e SKIP_GDRIVE="" `
  playlist-cleaner:latest
```

#### Step 3: Update docker-compose.yml
```yaml
services:
  playlist-processor:
    volumes:
      - ./data:/app/data
      - ./config:/app/data/config:ro
      - ./raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro
      - ./gdrive_token.json:/app/gdrive_token.json:ro  # Add this line
    environment:
      - SKIP_GDRIVE=""  # Enable Google Drive backup
```

---

## 🎯 **Solution 2: Service Account (AUTOMATED)**

### **✅ Best for Production/CI/CD:**
- No interactive authentication needed
- Fully automated
- Works in any environment

### **📝 Implementation Steps:**

#### Step 1: Create Service Account
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. IAM & Admin → Service Accounts
3. Create new service account
4. Download JSON key file
5. Share your Google Drive folder with the service account email

#### Step 2: Use Service Account in Container
```powershell
podman run --rm `
  -v "${PWD}/data:/app/data" `
  -v "${PWD}/service-account-key.json:/app/gdrive_service_account.json:ro" `
  -e GDRIVE_SERVICE_ACCOUNT="/app/gdrive_service_account.json" `
  playlist-cleaner:latest
```

---

## 🎯 **Solution 3: Skip Google Drive in Containers**

### **✅ Current Default Behavior:**
The container is configured to skip Google Drive by default:
```dockerfile
ENV SKIP_GDRIVE="--skip-gdrive"
```

### **📝 Manual Backup After Container:**
```powershell
# Run container without Google Drive
podman run --rm -v "${PWD}/data:/app/data" playlist-cleaner:latest

# Then backup manually on host
python upload_to_gdrive.py --backup
```

---

## 🏆 **RECOMMENDED APPROACH**

For your current setup, **Solution 1 (Pre-authenticate)** is ideal:

### **Why It's Best:**
- ✅ Simple one-time setup
- ✅ No code changes needed
- ✅ Works with existing OAuth setup
- ✅ Secure token handling
- ✅ Easy to maintain

### **Security Benefits:**
- 🔒 Token is read-only mounted
- 🔒 Token auto-refreshes when needed
- 🔒 No credentials stored in container image
- 🔒 Easy to rotate/revoke access

---

## 🚀 **Quick Setup for Your Environment**

### **1. Authenticate Once:**
```powershell
cd "c:\dev\training\PlaylistCleaner"
python upload_to_gdrive.py --setup
```

### **2. Run Container with Google Drive:**
```powershell
podman run --rm `
  -v "${PWD}/data:/app/data" `
  -v "${PWD}/config:/app/data/config:ro" `
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" `
  -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" `
  -e SKIP_GDRIVE="" `
  playlist-cleaner:latest
```

### **3. Verify Success:**
Check your Google Drive for uploaded playlist files!

---

## 📊 **Current vs Enhanced Container**

| Feature | Current Container | With Google Drive |
|---------|------------------|-------------------|
| Download | ✅ Working | ✅ Working |
| Filter | ✅ Working | ✅ Working |
| Credentials | ✅ Working | ✅ Working |
| Google Drive | ❌ Skipped | ✅ **Working** |
| Automation | ✅ Full | ✅ **Full** |

**The token approach makes Google Drive work seamlessly in containers!**
