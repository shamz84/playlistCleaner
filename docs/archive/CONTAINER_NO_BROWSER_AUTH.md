# Container Authentication Guide 🐳

> Archived reference only: paths and setup steps below are historical and have not been validated against the current pipeline. See [current Drive guidance](../README_GoogleDrive.md).

## ⚠️ **IMPORTANT: No Browser Authentication in Containers**

**Browser-based OAuth authentication is IMPOSSIBLE in containers.** Containers don't have browsers, display servers, or user interaction capabilities.

## 🎯 **Container-Ready Solutions**

### **Option 1: Service Account (Recommended) 🔐**

Perfect for production containers - no browser needed, no token expiration.

#### Setup Steps:
1. **Create Service Account:**
   ```bash
   # Go to Google Cloud Console
   # https://console.cloud.google.com/
   # 1. Create/select project
   # 2. Enable Google Drive API
   # 3. Create Service Account
   # 4. Download JSON key
   ```

2. **Save the JSON file as `gdrive_service_account.json` in `data/config/`**

3. **Share your Google Drive folder with the service account email**

4. **Run container:**
   ```bash
   docker-compose -f docker-compose.container.yml up playlist-cleaner-service
   ```

### **Option 2: Pre-Authenticated Token 📝**

Authenticate once on your local machine, then use the token in containers.

#### Setup Steps:
1. **Authenticate locally (on machine with browser):**
   ```bash
   python gdrive_setup.py
   # Complete browser authentication
   ```

2. **Run container with token:**
   ```bash
   docker-compose -f docker-compose.container.yml up playlist-cleaner-token
   ```

**Note:** Tokens can expire and need refresh!

### **Option 3: Environment Variables 🌍**

Store authentication in environment variables.

#### Setup Steps:
1. **Create environment token (after local auth):**
   ```bash
   python setup_env_gdrive.py
   ```

2. **Set environment and run:**
   ```bash
   # Copy the generated .env.gdrive to .env
   cp .env.gdrive .env
   docker-compose -f docker-compose.container.yml up playlist-cleaner-env
   ```

### **Option 4: Skip Google Drive Entirely 🚫**

Simplest option - just skip the backup.

```bash
docker-compose -f docker-compose.container.yml up playlist-cleaner-no-gdrive
```

## 🛠️ **Container Setup Commands**

### Quick Start (No Google Drive):
```bash
# Build and run without Google Drive backup
docker-compose -f docker-compose.container.yml up playlist-cleaner-no-gdrive
```

### With Service Account:
```bash
# 1. Place your service account JSON in data/config/ as gdrive_service_account.json
# 2. Run container
docker-compose -f docker-compose.container.yml up playlist-cleaner-service
```

### With Pre-authenticated Token:
```bash
# 1. Authenticate locally first
python gdrive_setup.py

# 2. Run container with token
docker-compose -f docker-compose.container.yml up playlist-cleaner-token
```

## 🔍 **Container Detection**

The pipeline automatically detects container environments:

```python
# Container indicators checked:
- /.dockerenv exists (Docker)
- KUBERNETES_SERVICE_HOST set (Kubernetes) 
- CONTAINER=true environment variable
- Linux container cgroup detection
```

When detected, the pipeline:
- ✅ Skips browser authentication attempts
- ✅ Uses container-friendly authentication methods
- ✅ Gracefully skips Google Drive if no auth available
- ✅ Never hangs or blocks the pipeline

## 📋 **Error Handling**

### If Authentication Fails:
```
⚠️  Google Drive backup failed, but this is optional
💡 Pipeline continues normally
💡 All other features work perfectly
```

### Common Issues:

**"No browser available"**
- ✅ **Expected in containers**
- 💡 Use service account or pre-authenticated token

**"Token expired"**
- 🔄 Re-authenticate locally and mount new token
- 💡 Or switch to service account (never expires)

**"Permission denied"**
- 📧 Share Google Drive folder with service account email
- 🔍 Check file permissions on mounted tokens

## 🎉 **Best Practices**

1. **Production: Use Service Accounts**
   - No expiration
   - No browser needed
   - Most secure

2. **Development: Use Pre-authenticated Tokens**
   - Quick to set up
   - Easy to test

3. **CI/CD: Use Environment Variables**
   - Easy to inject secrets
   - Portable across platforms

4. **Simplest: Skip Google Drive**
   - No authentication needed
   - Focus on core playlist processing

## 📁 **File Structure for Containers**

```
your-project/
├── data/config/
│   ├── gdrive_service_account.json # Service account (don't commit!)
│   └── gdrive_token.json          # Pre-auth token (don't commit!)
├── .env                           # Environment variables (don't commit!)
└── docker-compose.container.yml   # Container setup
```

## 🚀 **The Bottom Line**

**Containers can't use browsers for authentication.** 

But that's fine! The enhanced pipeline:
- ✅ Detects container environments automatically
- ✅ Uses container-friendly authentication when available
- ✅ Gracefully skips Google Drive backup when not available
- ✅ Never blocks or hangs the pipeline
- ✅ Continues processing playlists normally

**Your playlist processing will work perfectly in containers, with or without Google Drive backup!**
