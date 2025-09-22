# Docker Google Drive Authentication Guide

## Problem
OAuth 2.0 authentication requires a browser, but Docker containers run headless without GUI access.

## Solutions

### Solution 1: Pre-authenticate Outside Container (Recommended)

1. **Authenticate on Host Machine**
   ```powershell
   # Run authentication on your Windows machine
   cd "c:\dev\training\PlaylistCleaner"
   python upload_to_gdrive.py --setup
   ```
   This creates `gdrive_token.json` with your authentication token.

2. **Mount Token in Container**
   ```powershell
   # Mount the token file for container use
   podman run --rm `
     -v "${PWD}/data:/app/data" `
     -v "${PWD}/config:/app/data/config:ro" `
     -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" `
     -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" `
     -e SKIP_GDRIVE="" `
     playlist-processor:latest
   ```

3. **Update docker-compose.yml**
   ```yaml
   services:
     playlist-processor:
       volumes:
         - ./data:/app/data
         - ./config:/app/data/config:ro
         - ./gdrive_token.json:/app/gdrive_token.json:ro  # Add this line
         - ./raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro
   ```

### Solution 2: Service Account Authentication

1. **Create Service Account Credentials**
   - Go to Google Cloud Console → IAM & Admin → Service Accounts
   - Create service account
   - Download JSON key file
   - Share your Google Drive folder with the service account email

2. **Update Upload Script for Service Account**
   ```python
   # Modify upload_to_gdrive.py to support service account
   from google.oauth2 import service_account
   
   def authenticate_service_account(credentials_file):
       """Authenticate using service account"""
       credentials = service_account.Credentials.from_service_account_file(
           credentials_file, scopes=SCOPES)
       return build('drive', 'v3', credentials=credentials)
   ```

### Solution 3: Headless OAuth with Manual Code Entry

1. **Modify OAuth Flow for Headless Mode**
   ```python
   # Use InstalledAppFlow.run_console() instead of run_local_server()
   flow = InstalledAppFlow.from_client_secrets_file(
       self.credentials_file, SCOPES)
   creds = flow.run_console()  # Manual code entry
   ```

2. **Container with Interactive Mode**
   ```powershell
   # Run container interactively for one-time setup
   podman run -it `
     -v "${PWD}/data:/app/data" `
     -v "${PWD}/config:/app/data/config:ro" `
     playlist-processor:latest `
     python upload_to_gdrive.py --setup
   ```

### Solution 4: Environment Variable Token

1. **Export Token as Environment Variable**
   ```powershell
   # Get token content (after host authentication)
   $token = Get-Content "gdrive_token.json" | ConvertTo-Json -Compress
   ```

2. **Pass to Container**
   ```powershell
   podman run --rm `
     -v "${PWD}/data:/app/data" `
     -v "${PWD}/config:/app/data/config:ro" `
     -e GDRIVE_TOKEN="$token" `
     playlist-processor:latest
   ```

## Cloud Server Deployment

### Option 1: Pre-authenticate + Deploy Token (Recommended)

**For production cloud deployments:**

1. **Authenticate Locally** (one-time):
   ```powershell
   # On your local machine with browser
   python upload_to_gdrive.py --setup
   ```

2. **Deploy Token to Cloud Server**:
   ```bash
   # Upload token to your cloud server
   scp gdrive_token.json user@your-server:/path/to/playlist-cleaner/
   
   # Or include in your deployment script
   rsync -av gdrive_token.json user@server:/app/
   ```

3. **Run on Cloud Server** (headless):
   ```bash
   # Works without browser on cloud server
   podman run --rm \
     -v ./data:/app/data \
     -v ./config:/app/data/config:ro \
     -v ./gdrive_token.json:/app/gdrive_token.json:ro \
     -e SKIP_GDRIVE="" \
     playlist-processor:latest
   ```

### Option 2: Interactive Cloud Setup

**For development/testing:**

1. **SSH with Port Forwarding**:
   ```bash
   ssh -L 8080:localhost:8080 user@your-cloud-server
   ```

2. **Run Setup on Server**:
   ```bash
   python upload_to_gdrive.py --setup
   ```
   Access the OAuth URL through your local browser via port forwarding.

### Option 3: Service Account (Fully Automated)

**For CI/CD and automated deployments:**

1. **Create Service Account**:
   - Google Cloud Console → IAM & Admin → Service Accounts
   - Download JSON key file
   - Share your Drive folder with service account email

2. **Use Service Account in Container**:
   ```bash
   podman run --rm \
     -v ./data/config/gdrive_service_account.json:/app/data/config/gdrive_service_account.json:ro \
     -v ./data:/app/data \
     -e GDRIVE_SERVICE_ACCOUNT="/app/data/config/gdrive_service_account.json" \
     playlist-processor:latest
   ```

### Cloud Provider Examples

#### AWS EC2 / Azure VM / Google Compute
```bash
# Deploy with user data script
#!/bin/bash
cd /opt/playlist-cleaner
# Token already deployed via deployment script
podman-compose up --rm playlist-processor
```

#### Docker Swarm / Kubernetes
```yaml
# kubernetes-playlist-job.yaml
apiVersion: batch/v1
kind: Job
spec:
  template:
    spec:
      containers:
      - name: playlist-processor
        image: playlist-processor:latest
        volumeMounts:
        - name: gdrive-token
          mountPath: /app/gdrive_token.json
          readOnly: true
        env:
        - name: SKIP_GDRIVE
          value: ""
      volumes:
      - name: gdrive-token
        secret:
          secretName: gdrive-token-secret
```

#### GitHub Actions / GitLab CI
```yaml
# .github/workflows/playlist-process.yml
- name: Setup Google Drive
  env:
    GDRIVE_TOKEN: ${{ secrets.GDRIVE_TOKEN }}
  run: |
    echo "$GDRIVE_TOKEN" > gdrive_token.json
    
- name: Run Playlist Processor
  run: |
    podman run --rm \
      -v ./gdrive_token.json:/app/gdrive_token.json:ro \
      playlist-processor:latest
```

## Security Best Practices

### Token Management
- ✅ Store `gdrive_token.json` as encrypted secret
- ✅ Use read-only mounts in containers
- ✅ Set appropriate file permissions (600)
- ✅ Rotate tokens periodically

### CI/CD Integration
- ✅ Use CI/CD secrets for token storage
- ✅ Encrypt tokens in deployment artifacts
- ✅ Use temporary tokens when possible
- ✅ Monitor token usage and expiry

## Recommended Approach

**Pre-authentication (Solution 1)** is the best approach because:
- ✅ Simple and reliable
- ✅ No code changes needed
- ✅ Works with existing OAuth setup
- ✅ Secure token handling
- ✅ Easy to automate

## Implementation Steps

1. **One-time Host Authentication**
   ```powershell
   python upload_to_gdrive.py --setup
   ```

2. **Update docker-compose.yml**
   ```yaml
   volumes:
     - ./gdrive_token.json:/app/gdrive_token.json:ro
   ```

3. **Run Container with Google Drive**
   ```powershell
   podman-compose up --rm playlist-processor
   ```

The token file (`gdrive_token.json`) will be automatically refreshed by the Google API when needed, so this is a one-time setup!
