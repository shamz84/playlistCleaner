# Docker Deployment Guide

## Overview

The updated Dockerfile now includes the complete playlist processing pipeline with the following components:

- **Main Orchestrator**: `process_playlist_complete.py`
- **Download Module**: `download_file.py`
- **Filter Module**: `filter_comprehensive.py` 
- **Credential Replacement**: `replace_credentials_multi.py`
- **Google Drive Backup**: `upload_to_gdrive.py` (optional)
- **Setup Helper**: `gdrive_setup.py`

## Prerequisites

### Install Docker
- **Windows**: [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop)
- **macOS**: [Docker Desktop for Mac](https://www.docker.com/products/docker-desktop)
- **Linux**: [Docker Engine](https://docs.docker.com/engine/install/)

### Verify Installation
```bash
docker --version
docker-compose --version
```

## Building the Image

### Option 1: Using Build Scripts
```bash
# Windows
.\build-docker.bat

# Linux/macOS
chmod +x build-docker.sh
./build-docker.sh
```

### Option 2: Manual Build
```bash
docker build -t playlist-processor .
```

## Running the Container

### 1. Complete Pipeline (Recommended)
```bash
# Create output directory
mkdir -p data

# Run complete pipeline (downloads, filters, generates playlists)
docker run --rm -v $(pwd)/data:/app/data playlist-processor
```

### 2. Using Existing Files
```bash
# Skip download, use local playlists
docker run --rm \
  -v $(pwd)/data:/app/data \
  -v $(pwd)/raw_playlist_20.m3u:/app/raw_playlist_20.m3u:ro \
  -v $(pwd)/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro \
  -e SKIP_DOWNLOAD="--skip-download" \
  playlist-processor
```

### 3. Custom Credentials
```bash
# Use custom credentials file
docker run --rm \
  -v $(pwd)/data:/app/data \
  -v $(pwd)/my-credentials.json:/app/credentials.json:ro \
  playlist-processor
```

### 4. Filter Only Mode
```bash
# Only filter playlists, skip credential replacement
docker run --rm \
  -v $(pwd)/data:/app/data \
  -e SKIP_DOWNLOAD="--skip-download" \
  -e SKIP_CREDENTIALS="--skip-credentials" \
  playlist-processor
```

## Docker Compose Usage

### Basic Usage
```bash
# Start with default configuration
docker-compose up playlist-processor

# Run and remove container when done
docker-compose up --rm playlist-processor
```

### Service Profiles
```bash
# Download only
docker-compose --profile download-only up playlist-downloader

# Filter only  
docker-compose --profile filter-only up playlist-filter
```

### Custom Configuration
Create `docker-compose.override.yml`:
```yaml
version: '3.8'
services:
  playlist-processor:
    environment:
      - SKIP_DOWNLOAD=--skip-download
    volumes:
      - ./my-custom-config.json:/app/credentials.json:ro
```

Then run:
```bash
docker-compose up playlist-processor
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SKIP_DOWNLOAD` | _(empty)_ | Set to `--skip-download` to use existing files |
| `SKIP_FILTER` | _(empty)_ | Set to `--skip-filter` to skip filtering |
| `SKIP_CREDENTIALS` | _(empty)_ | Set to `--skip-credentials` to skip credential replacement |
| `SKIP_GDRIVE` | `--skip-gdrive` | Set to _(empty)_ to enable Google Drive backup |

## Volume Mounts

### Essential Volumes
```bash
-v $(pwd)/data:/app/data                              # Output directory
```

### Configuration Files
```bash
-v $(pwd)/credentials.json:/app/credentials.json:ro  # User credentials
-v $(pwd)/group_titles_with_flags.json:/app/group_titles_with_flags.json:ro  # Filter config
```

### Source Files
```bash
-v $(pwd)/raw_playlist_20.m3u:/app/raw_playlist_20.m3u:ro      # Main playlist
-v $(pwd)/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro  # Asia UK playlist
```

## Expected Outputs

After successful execution, check the `data/` directory for:

- `filtered_playlist_final.m3u` - Filtered playlist
- `8k_[username].m3u` - Personalized playlists for each user
- `manual_download.m3u` - Downloaded playlist (if download ran)

## Troubleshooting

### Container Logs
```bash
# View container logs
docker logs <container_id>

# Follow logs in real-time
docker logs -f <container_id>

# With Docker Compose
docker-compose logs playlist-processor
```

### Debug Mode
```bash
# Interactive shell access
docker run --rm -it \
  -v $(pwd)/data:/app/data \
  --entrypoint /bin/bash \
  playlist-processor

# Inside container, run individual steps
python download_file.py --direct
python filter_comprehensive.py
python replace_credentials_multi.py
```

### File Permissions (Linux/macOS)
```bash
# Fix ownership after container run
sudo chown -R $USER:$USER data/
```

### Common Issues

1. **Permission Denied**: Ensure output directory is writable
2. **Missing Files**: Mount required configuration files as volumes
3. **Network Issues**: Check if container can access external URLs for downloads
4. **Memory Issues**: Large playlist files may require more memory allocation

## Production Deployment

### Using Docker Swarm
```yaml
version: '3.8'
services:
  playlist-processor:
    image: playlist-processor:latest
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
    volumes:
      - playlist-data:/app/data
    networks:
      - playlist-network

volumes:
  playlist-data:
    driver: local

networks:
  playlist-network:
    driver: overlay
```

### Scheduled Execution
```bash
# Add to crontab for daily processing
0 2 * * * cd /path/to/playlist && docker-compose up --rm playlist-processor
```

### With CI/CD Pipeline
```yaml
# Example GitHub Actions workflow
name: Build and Deploy Playlist Processor
on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Build Docker image
        run: docker build -t playlist-processor .
      - name: Test container
        run: |
          mkdir -p data
          docker run --rm -v $(pwd)/data:/app/data -e SKIP_DOWNLOAD="--skip-download" playlist-processor
```

## Multi-Architecture Support

### Build for Multiple Platforms
```bash
# Setup buildx (one time)
docker buildx create --name mybuilder --use

# Build for multiple architectures
docker buildx build --platform linux/amd64,linux/arm64 -t playlist-processor:latest .

# Push to registry
docker buildx build --platform linux/amd64,linux/arm64 -t your-registry/playlist-processor:latest --push .
```

### Raspberry Pi Deployment
```bash
# Build ARM64 image for Raspberry Pi
docker buildx build --platform linux/arm64 -t playlist-processor:arm64 .

# Run on Raspberry Pi
docker run --rm -v $(pwd)/data:/app/data playlist-processor:arm64
```

## Monitoring and Logging

### Container Resource Usage
```bash
# Monitor resource usage
docker stats playlist-processor

# View container processes
docker exec playlist-processor ps aux
```

### Log Management
```bash
# Configure log rotation in docker-compose.yml
services:
  playlist-processor:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

## Security Considerations

### Secrets Management
```yaml
# Use Docker secrets for sensitive data
version: '3.8'
services:
  playlist-processor:
    secrets:
      - credentials
    environment:
      - CREDENTIALS_FILE=/run/secrets/credentials

secrets:
  credentials:
    file: ./credentials.json
```

### Network Security
```bash
# Run with custom network
docker network create playlist-network
docker run --rm --network playlist-network playlist-processor
```

### Read-Only Root Filesystem
```bash
# Enhance security with read-only filesystem
docker run --rm --read-only -v $(pwd)/data:/app/data playlist-processor
```
