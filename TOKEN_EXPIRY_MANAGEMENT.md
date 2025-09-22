# Google Drive Token Expiry Management

## 🕒 **Token Lifespans (Fixed by Google - Cannot Be Changed)**

| **Token Type** | **Expiry Time** | **Auto-Refresh?** | **Best For** |
|---|---|---|---|
| **Access Token** | 1 hour | ✅ Yes (with refresh token) | Short tasks |
| **Refresh Token** | 6 months (unused) | ❌ No | Long-term apps |
| **Service Account** | Never expires | ✅ Auto-generated | **Containers** |

## 🚫 **Why You Can't Increase Token Expiry**

Google OAuth token expiry times are **security features** that cannot be modified:

- **Access Tokens (1 hour):** Prevents long-term exposure if compromised
- **Refresh Tokens (6 months):** Forces periodic re-authentication  
- **Security Best Practice:** Short-lived tokens reduce attack surface

## ✅ **Solutions for Long-Running Applications**

### **1. Service Account (BEST for Containers)**

**✅ Never expires, no browser needed**

```bash
# Create service account (run once)
python create_never_expiring_auth.py

# Use in containers
docker run -v ./service_account:/app/auth:ro your-image
```

**Pros:**
- Never expires
- No user interaction
- Perfect for production
- Container-friendly

### **2. Automatic Token Refresh**

**✅ Keeps OAuth tokens fresh automatically**

```python
# Add to your application
from token_refresh_manager import TokenRefreshManager

manager = TokenRefreshManager()
manager.start_monitoring()  # Runs in background
```

**Features:**
- Auto-refreshes before expiry (10 min warning)
- Background monitoring thread
- Handles refresh failures gracefully

### **3. Pre-Production Token Management**

**✅ Regular token maintenance strategy**

```bash
# Check token status
python check_gdrive_token_usage.py

# Setup automatic refresh service
python token_refresh_manager.py

# Monitor token expiry
python -c "from token_refresh_manager import TokenRefreshManager; TokenRefreshManager().needs_refresh()"
```

## 🐳 **Container Strategies**

### **For Different Container Lifecycles:**

#### **Short-lived containers (<1 hour):**
```bash
# Pre-authenticated tokens work fine
python setup_gdrive_for_container.py
docker run -v ./container_gdrive:/app/gdrive_auth:ro your-image
```

#### **Long-running containers (>1 hour):**
```bash
# Use service accounts (never expire)
python create_never_expiring_auth.py
docker run -v ./service_account:/app/gdrive_auth:ro your-image
```

#### **CI/CD Pipelines:**
```bash
# Environment variables with automatic refresh
python setup_env_gdrive.py
docker run -e GDRIVE_TOKEN_B64="$TOKEN" your-image
```

## ⚙️ **Enhanced Pipeline Integration**

The enhanced pipeline now automatically:

1. **Detects token expiry** (warns if <10 minutes left)
2. **Auto-refreshes tokens** (if refresh token available)  
3. **Falls back gracefully** (skips Google Drive if auth fails)
4. **Provides clear guidance** (shows next steps for re-auth)

```bash
# Pipeline automatically handles token refresh
python process_playlist_complete_enhanced.py

# Example output:
# ✅ Token valid for: 2:34:15
# 🔄 Attempting automatic token refresh...
# ✅ Token refreshed successfully!
```

## 📊 **Token Monitoring Commands**

```bash
# Check all token files and which is being used
python check_gdrive_token_usage.py

# Monitor token expiry in real-time
python token_refresh_manager.py

# Test token refresh manually
python -c "
from token_refresh_manager import TokenRefreshManager
manager = TokenRefreshManager()
if manager.needs_refresh():
    manager.refresh_token()
"
```

## 🎯 **Recommendations by Use Case**

### **Development:**
- Use OAuth with automatic refresh
- Run: `python token_refresh_manager.py`

### **Production Containers:**
- Use Service Accounts (never expire)
- Run: `python create_never_expiring_auth.py`

### **CI/CD:**
- Use Service Accounts or environment variables
- Implement token monitoring alerts

### **Personal/Desktop:**
- Use OAuth with refresh tokens
- Enhanced pipeline handles refresh automatically

## 🔧 **Quick Fixes for Common Issues**

### **"Token Expired" Error:**
```bash
# Auto-fix (if refresh token available)
python token_refresh_manager.py

# Manual fix (if no refresh token)
python gdrive_setup.py
```

### **"No Refresh Token" Error:**
```bash
# Create new token with refresh capability
python gdrive_setup.py

# Or switch to service account
python create_never_expiring_auth.py
```

### **Container Auth Issues:**
```bash
# Use service account (recommended)
python create_never_expiring_auth.py

# Or use pre-authenticated tokens
python setup_gdrive_for_container.py
```

## 🚨 **Important Notes**

1. **Token expiry times are fixed by Google** - you cannot change them
2. **Service accounts are the best solution** for containers and automation
3. **Always have a backup plan** - enhanced pipeline gracefully skips Google Drive if auth fails
4. **Monitor token status** - set up alerts for refresh failures
5. **Refresh tokens can expire** if unused for 6 months

---

**💡 Bottom Line:** You can't increase token expiry times, but you can implement robust refresh strategies or switch to service accounts that never expire!
