# QNAP Container Station Deployment Guide

## Overview
Your Playlist Processor will work excellently on QNAP Container Station. QNAP's Container Station is built on Docker, so all your existing containers and configurations are compatible.

## Prerequisites

### QNAP Requirements
- **QNAP NAS** with Container Station installed
- **Docker support** (most modern QNAP models)
- **Sufficient RAM** (4GB+ recommended)
- **Network access** for playlist downloads

### Enable Container Station
1. Open **App Center** on your QNAP
2. Install **Container Station** if not already installed
3. Launch Container Station

## Deployment Methods

### Method 1: Docker Compose (Recommended)

1. **Upload your project** to QNAP:
   ```bash
   # Use QNAP File Station or SCP
   scp -r PlaylistCleaner/ admin@your-qnap-ip:/share/Container/
   ```

2. **Create docker-compose.yml** for QNAP:
   ```yaml
   version: '3.8'
   
   services:
     playlist-processor:
       build: .
       container_name: playlist-processor-qnap
       volumes:
         # QNAP shared folder paths
         - /share/Container/PlaylistCleaner/data:/app/data
         - /share/Container/PlaylistCleaner/config:/app/data/config:ro
         - /share/Container/PlaylistCleaner/gdrive_token.json:/app/gdrive_token.json:ro
         - /share/Container/PlaylistCleaner/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro
       environment:
         - SKIP_DOWNLOAD=
         - SKIP_FILTER=
         - SKIP_CREDENTIALS=
         - SKIP_GDRIVE=--skip-gdrive
       restart: unless-stopped
       networks:
         - qnap-network
   
   networks:
     qnap-network:
       driver: bridge
   ```

3. **Deploy via Container Station**:
   - Open Container Station
   - Go to **Applications** tab
   - Click **Create** → **Create Application**
   - Select **Docker Compose**
   - Paste your docker-compose.yml
   - Click **Create**

### Method 2: Container Station GUI

1. **Build/Pull Image**:
   ```bash
   # SSH to QNAP and build
   ssh admin@your-qnap-ip
   cd /share/Container/PlaylistCleaner
   docker build -t playlist-processor:qnap .
   ```

2. **Create Container via GUI**:
   - Container Station → **Containers** → **Create**
   - **Image**: `playlist-processor:qnap`
   - **Container Name**: `playlist-processor`
   - **Advanced Settings**:
     - **Volume Mounts**:
       - `/share/Container/PlaylistCleaner/data` → `/app/data`
       - `/share/Container/PlaylistCleaner/config` → `/app/data/config`
       - `/share/Container/PlaylistCleaner/gdrive_token.json` → `/app/gdrive_token.json`
     - **Environment Variables**:
       - `SKIP_GDRIVE` = `--skip-gdrive` (or empty for Google Drive)

### Method 3: Command Line (SSH)

```bash
# SSH to your QNAP
ssh admin@your-qnap-ip

# Navigate to your project
cd /share/Container/PlaylistCleaner

# Run container
docker run --name playlist-processor \
  -v /share/Container/PlaylistCleaner/data:/app/data \
  -v /share/Container/PlaylistCleaner/config:/app/data/config:ro \
  -v /share/Container/PlaylistCleaner/gdrive_token.json:/app/gdrive_token.json:ro \
  -e SKIP_GDRIVE="--skip-gdrive" \
  playlist-processor:qnap
```

## QNAP-Specific Paths

### Shared Folders
QNAP uses specific path conventions:
```bash
/share/CACHEDEV1_DATA/Container/PlaylistCleaner/  # Main project
/share/Container/PlaylistCleaner/                 # Symlink (easier)
/share/Public/PlaylistCleaner/                    # If using Public folder
```

### Volume Mapping for QNAP
```yaml
volumes:
  # QNAP paths → Container paths
  - /share/Container/PlaylistCleaner/data:/app/data
  - /share/Container/PlaylistCleaner/config:/app/data/config:ro
  - /share/Container/PlaylistCleaner/gdrive_token.json:/app/gdrive_token.json:ro
```

## Google Drive Authentication on QNAP

### Option 1: Pre-authenticate (Recommended)
1. **Authenticate on your PC**:
   ```powershell
   python upload_to_gdrive.py --setup
   ```

2. **Upload token to QNAP**:
   ```bash
   scp gdrive_token.json admin@your-qnap:/share/Container/PlaylistCleaner/
   ```

3. **Container uses token automatically**

### Option 2: SSH with Port Forwarding
```bash
# Connect with port forwarding
ssh -L 8080:localhost:8080 admin@your-qnap

# Run authentication
cd /share/Container/PlaylistCleaner
python upload_to_gdrive.py --setup
```

## Scheduling on QNAP

### Using QNAP Cron
1. **Enable SSH** on QNAP
2. **Edit crontab**:
   ```bash
   # SSH to QNAP
   ssh admin@your-qnap
   
   # Edit cron
   crontab -e
   
   # Add daily processing at 2 AM
   0 2 * * * cd /share/Container/PlaylistCleaner && docker-compose up --rm playlist-processor
   ```

### Using Container Station Scheduler
1. Container Station → **Applications**
2. Select your application
3. **Actions** → **Schedule**
4. Set daily/weekly schedule

### Using QNAP Task Scheduler
1. **Control Panel** → **Task Scheduler**
2. **Create** → **User-defined script**
3. **Script**:
   ```bash
   #!/bin/bash
   cd /share/Container/PlaylistCleaner
   docker-compose up --rm playlist-processor
   ```

## Storage Optimization

### QNAP Volume Configuration
```yaml
services:
  playlist-processor:
    volumes:
      # Use QNAP shared storage
      - /share/Multimedia/Playlists:/app/data          # Media share
      - /share/Container/config:/app/data/config:ro    # Config share
      - /share/backup/gdrive_token.json:/app/gdrive_token.json:ro  # Backup location
```

### Backup Integration
```yaml
# Add backup to different QNAP volumes
volumes:
  - /share/Container/PlaylistCleaner/data:/app/data
  - /share/Backup/PlaylistBackups:/app/backup        # QNAP backup folder
```

## Performance Optimization

### QNAP Resource Limits
```yaml
services:
  playlist-processor:
    deploy:
      resources:
        limits:
          memory: 1G      # Adjust based on QNAP RAM
          cpus: '1.0'     # Use 1 CPU core
```

### SSD Cache (if available)
- Enable **SSD Cache** for Container Station
- Place containers on SSD volumes for better performance

## Monitoring

### Container Station Monitoring
- **Resource Usage**: Container Station shows CPU/Memory
- **Logs**: View container logs in Container Station
- **Health Checks**: Monitor container status

### QNAP System Integration
```bash
# View logs via QNAP
ssh admin@your-qnap
docker logs playlist-processor

# Monitor resources
docker stats playlist-processor
```

## Example Complete Setup

1. **Upload project to QNAP**:
   ```bash
   scp -r PlaylistCleaner/ admin@192.168.1.100:/share/Container/
   ```

2. **Create QNAP-optimized docker-compose.yml**:
   ```yaml
   version: '3.8'
   services:
     playlist-processor:
       build: /share/Container/PlaylistCleaner
       container_name: qnap-playlist-processor
       volumes:
         - /share/Container/PlaylistCleaner/data:/app/data
         - /share/Container/PlaylistCleaner/config:/app/data/config:ro
         - /share/Container/PlaylistCleaner/gdrive_token.json:/app/gdrive_token.json:ro
       environment:
         - SKIP_GDRIVE=  # Enable Google Drive
       restart: unless-stopped
   ```

3. **Deploy and schedule**:
   ```bash
   # Deploy
   docker-compose up -d
   
   # Schedule daily run
   echo "0 2 * * * cd /share/Container/PlaylistCleaner && docker-compose restart playlist-processor" | crontab -
   ```

## Benefits on QNAP

- ✅ **24/7 Operation**: NAS runs continuously
- ✅ **Scheduled Processing**: Built-in task scheduler
- ✅ **Network Storage**: Direct access to media shares
- ✅ **Backup Integration**: Integrate with QNAP backup solutions
- ✅ **Remote Access**: Access from anywhere via QNAP's remote access
- ✅ **Resource Efficiency**: Low power consumption
- ✅ **Web Management**: Container Station GUI

Your playlist processor is perfectly suited for QNAP deployment! 🚀
