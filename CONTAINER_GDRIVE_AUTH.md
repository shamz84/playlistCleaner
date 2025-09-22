# Google Drive Authentication in Containers

## 🎯 **Important Update**

The original `Dockerfile` **already includes full Google Drive support**! This guide provides additional container authentication strategies, but the basic functionality is available in the main Dockerfile.

## 📊 **Quick Start with Original Dockerfile**

```bash
# Build with original Dockerfile (includes Google Drive support)
docker build -t playlist-cleaner .

# Run with Google Drive enabled
docker run -e SKIP_GDRIVE="" playlist-cleaner

# Run with mounted authentication
docker run -e SKIP_GDRIVE="" -v ./data/config:/app/data/config playlist-cleaner
```

---

## 🔧 **Enhanced Authentication Methods**

### Method 1: Service Account Authentication (Production)

#### Step 1: Create Service Account
```bash
# Set up service account credentials
python create_never_expiring_auth.py
```

#### Step 2: Grant Drive Access
1. Share your Google Drive folder with the service account email
2. Copy service account JSON to `data/config/gdrive_service_account.json`

#### Step 3: Deploy Container
```bash
# Using original Dockerfile with service account
docker build -t playlist-cleaner .
docker run -e GDRIVE_USE_SERVICE_ACCOUNT=true \
           -v ./data/config/gdrive_service_account.json:/app/data/config/gdrive_service_account.json:ro \
           playlist-cleaner
```

**Pros:** No token expiry, automated deployment  
**Cons:** Requires Google Cloud setup, service account management

---

### Method 2: Token Mounting (Development)

#### Step 1: Authenticate Locally
```bash
# Run authentication on your local machine first
python gdrive_setup.py
# Complete the browser authentication flow
```

#### Step 2: Run Container with Token Mount
```bash
# Mount existing authentication
docker run -e SKIP_GDRIVE="" \
           -v ./gdrive_token.json:/app/gdrive_token.json:ro \
           -v ./gdrive_credentials.json:/app/gdrive_credentials.json:ro \
           playlist-cleaner
```

**Pros:** Simple, works with existing OAuth setup  
**Cons:** Requires local authentication first, tokens can expire

---

### Method 3: Environment Variable Authentication

#### Step 1: Create Environment Token
```bash
# Generate environment variables from existing token
python setup_env_gdrive.py
```

#### Step 2: Run with Environment Variables
```bash
# Export token as environment variable
export GDRIVE_TOKEN_B64="your_base64_encoded_token"

# Run container
docker run -e SKIP_GDRIVE="" \
           -e GDRIVE_TOKEN_B64="$GDRIVE_TOKEN_B64" \
           playlist-cleaner
```

**Pros:** Portable, works in any container platform  
**Cons:** Tokens in environment variables, can expire

---

## 📋 **Quick Start Commands**

### For Local Development:
```bash
# 1. Authenticate locally
python gdrive_setup.py

# 2. Run with original Dockerfile
docker build -t playlist-cleaner .
docker run -e SKIP_GDRIVE="" \
           -v ./gdrive_token.json:/app/gdrive_token.json:ro \
           playlist-cleaner
```

### For Production (Service Account):
```bash
# 1. Setup service account
python create_never_expiring_auth.py

# 2. Build and run with service account
docker build -t playlist-cleaner .
docker run -e GDRIVE_USE_SERVICE_ACCOUNT=true \
           -v ./data/config/gdrive_service_account.json:/app/data/config/gdrive_service_account.json:ro \
           playlist-cleaner
```

### For Environment Variables:
```bash
# 1. Setup environment token
python setup_env_gdrive.py

# 2. Run with environment
export GDRIVE_TOKEN_B64="your_token"
docker run -e SKIP_GDRIVE="" \
           -e GDRIVE_TOKEN_B64="$GDRIVE_TOKEN_B64" \
           playlist-cleaner
```

---

---

## 🔍 **Troubleshooting**

### Token Expired
```bash
# Re-authenticate locally
python gdrive_setup.py

# Update token and restart container
docker run -e SKIP_GDRIVE="" \
           -v ./gdrive_token.json:/app/gdrive_token.json:ro \
           playlist-cleaner
```

### Service Account Issues
- Ensure service account email has access to Drive folder
- Check Google Drive API is enabled
- Verify JSON file is valid

### Container Not Finding Auth
- Check volume mounts are correct
- Verify file permissions (should be readable)
- Ensure environment variables are set

### Pipeline Skips Google Drive
This is normal! The pipeline automatically skips Google Drive backup if authentication fails, preventing the pipeline from hanging.

---

## 📁 **File Structure**

```
data/config/
├── gdrive_token.json         # OAuth token file
├── gdrive_credentials.json   # OAuth credentials
└── gdrive_service_account.json  # Service account credentials (production)
.env                          # Environment variables
docker-compose.gdrive.yml     # Token mounting compose
```

---

## 🎉 **Best Practices**

1. **Use Original Dockerfile** - Already includes all Google Drive support
2. **Service Accounts for Production** - Most secure and reliable
3. **Token Mounting for Development** - Easiest to set up
4. **Environment Variables for CI/CD** - Most portable
5. **Always Test Locally First** - Authenticate manually before containerizing

---

## 📚 **Related Files**

- `token_refresh_manager.py` - Automatic token refresh
- `check_gdrive_token_usage.py` - Token priority checker
- `create_never_expiring_auth.py` - Service account setup
- `process_playlist_complete_enhanced.py` - Enhanced pipeline with Google Drive
5. **Monitor Token Expiration** - Set up alerts for token refresh needs

The enhanced pipeline will automatically detect your authentication method and use it appropriately! 🚀
