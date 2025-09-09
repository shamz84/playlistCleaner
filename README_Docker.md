# Docker Playlist Processor

This Docker image contains the complete M3U playlist processing pipeline with download, filtering, credential replacement, and optional Google Drive backup capabilities.

## 🆕 Recent Improvements
- ✅ **Enhanced Google Drive Integration** - File IDs preserved during updates
- ✅ **Container-Friendly Authentication** - Uses pre-generated token files
- ✅ **Config-First Architecture** - Unified configuration approach
- ✅ **Duplicate Prevention** - Smart file discovery prevents duplicate uploads

## Quick Start

### Build the Image
```bash
docker build -t playlist-processor .
```

### Run Complete Pipeline
```bash
# Create data directory for outputs
mkdir -p data

# Recommended: Complete pipeline with Google Drive backup
podman run --rm \
  -v "${PWD}/data:/app/data" \
  -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" \
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" \
  -e SKIP_DOWNLOAD="" \
  -e SKIP_FILTER="" \
  -e SKIP_CREDENTIALS="" \
  -e SKIP_GDRIVE="" \
  playlist-processor:latest

# Alternative: Skip Google Drive backup
podman run --rm \
  -v "${PWD}/data:/app/data" \
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" \
  -e SKIP_GDRIVE="--skip-gdrive" \
  playlist-processor:latest
```

## Using Docker Compose

### Default Configuration
```bash
# Run complete pipeline
docker-compose up playlist-processor

# Run and remove container when done
docker-compose up --rm playlist-processor
```

### Download Only
```bash
docker-compose --profile download-only up playlist-downloader
```

### Filter Only
```bash
docker-compose --profile filter-only up playlist-filter
```

## Environment Variables

Control pipeline execution with these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `SKIP_DOWNLOAD` | _(empty)_ | Set to `--skip-download` to skip download step |
| `SKIP_FILTER` | _(empty)_ | Set to `--skip-filter` to skip filter step |
| `SKIP_CREDENTIALS` | _(empty)_ | Set to `--skip-credentials` to skip credential replacement |
| `SKIP_GDRIVE` | `--skip-gdrive` | Set to empty to enable Google Drive backup |

## Volume Mounts

### Required Volumes
- `/app/data` - Output directory for generated playlists

### Configuration Files (Read-Only)
- `/app/credentials.json` - User credentials for playlist generation
- `/app/group_titles_with_flags.json` - Group filtering configuration
- `/app/data/config/gdrive_config.json` - Google Drive backup settings (mounted from config folder)

### Source Files (Read-Only)
- `/app/raw_playlist_20.m3u` - Main source playlist
- `/app/raw_playlist_AsiaUk.m3u` - Asia UK supplement playlist

## Usage Examples

### 1. Complete Pipeline with Local Files
```bash
docker run --rm \
  -v ${PWD}/data:/app/data \
  -v ${PWD}/credentials.json:/app/credentials.json:ro \
  -v ${PWD}/raw_playlist_20.m3u:/app/raw_playlist_20.m3u:ro \
  -v ${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro \
  -e SKIP_DOWNLOAD="--skip-download" \
  playlist-processor
```

### 2. Download and Filter Only
```bash
docker run --rm \
  -v ${PWD}/data:/app/data \
  -e SKIP_CREDENTIALS="--skip-credentials" \
  -e SKIP_GDRIVE="--skip-gdrive" \
  playlist-processor
```

### 3. Credentials Replacement Only
```bash
docker run --rm \
  -v ${PWD}/data:/app/data \
  -v ${PWD}/credentials.json:/app/credentials.json:ro \
  -v ${PWD}/filtered_playlist_final.m3u:/app/filtered_playlist_final.m3u:ro \
  -e SKIP_DOWNLOAD="--skip-download" \
  -e SKIP_FILTER="--skip-filter" \
  -e SKIP_GDRIVE="--skip-gdrive" \
  playlist-processor
```

### 4. With Google Drive Backup
```bash
# Recommended: Complete pipeline with Google Drive backup
# Requires pre-configured gdrive_token.json (run python gdrive_setup.py first)
podman run --rm \
  -v "${PWD}/data:/app/data" \
  -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" \
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" \
  -e SKIP_DOWNLOAD="" \
  -e SKIP_FILTER="" \
  -e SKIP_CREDENTIALS="" \
  -e SKIP_GDRIVE="" \
  playlist-processor:latest

# Alternative: Docker syntax (replace 'podman' with 'docker')
docker run --rm \
  -v ${PWD}/data:/app/data \
  -v ${PWD}/gdrive_token.json:/app/gdrive_token.json:ro \
  -v ${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro \
  -e SKIP_DOWNLOAD="" \
  -e SKIP_FILTER="" \
  -e SKIP_CREDENTIALS="" \
  -e SKIP_GDRIVE="" \
  playlist-processor:latest
```

**Important Notes:**
- ✅ **Google Drive token file is required** for container authentication
- ✅ **No browser needed** - Uses pre-authenticated token
- ✅ **File IDs preserved** - Updates existing files instead of creating duplicates
- ⚠️ **Setup required** - Run `python gdrive_setup.py` first to generate token

## Output Files

After successful execution, the following files will be available in the `data/` directory:

- `filtered_playlist_final.m3u` - Filtered and processed playlist
- `8k_[username].m3u` - Personalized playlists for each user
- `manual_download.m3u` - Downloaded playlist (if download step was run)

## Configuration File Examples

### credentials.json
```json
[
  {
    "dns": "server1.example.com:8080",
    "username": "user1", 
    "password": "password1"
  },
  {
    "dns": "server2.example.com:8080",
    "username": "user2",
    "password": "password2"
  }
]
```

### Docker Compose Override
Create `docker-compose.override.yml` for custom settings:
```yaml
version: '3.8'
services:
  playlist-processor:
    environment:
      - SKIP_DOWNLOAD=--skip-download
      - SKIP_GDRIVE=
    volumes:
      - ./my_custom_credentials.json:/app/credentials.json:ro
```

## Troubleshooting

### Container Logs
```bash
# View logs
docker-compose logs playlist-processor

# Follow logs in real-time
docker-compose logs -f playlist-processor
```

### Debug Mode
```bash
# Run with interactive shell
docker run --rm -it \
  -v ${PWD}/data:/app/data \
  --entrypoint /bin/bash \
  playlist-processor

# Inside container, run individual steps
python download_file.py --direct
python filter_comprehensive.py
python replace_credentials_multi.py
```

### File Permissions
```bash
# Fix file permissions after container run
sudo chown -R $USER:$USER data/
```

## Production Deployment

### With Secrets Management
```yaml
version: '3.8'
services:
  playlist-processor:
    build: .
    volumes:
      - playlist-data:/app/data
    secrets:
      - credentials
      - gdrive-creds
    environment:
      - CREDENTIALS_FILE=/run/secrets/credentials
      - GDRIVE_CREDS_FILE=/run/secrets/gdrive-creds

secrets:
  credentials:
    file: ./credentials.json
  gdrive-creds:
    file: ./gdrive_credentials.json

volumes:
  playlist-data:
```

### Scheduled Execution
```bash
# Add to crontab for daily execution
0 2 * * * cd /path/to/playlist && docker-compose up --rm playlist-processor
```

## Building for Different Architectures

```bash
# Build for ARM64 (Raspberry Pi, Apple Silicon)
docker buildx build --platform linux/arm64 -t playlist-processor:arm64 .

# Build for AMD64 (Intel/AMD)
docker buildx build --platform linux/amd64 -t playlist-processor:amd64 .

# Multi-platform build
docker buildx build --platform linux/arm64,linux/amd64 -t playlist-processor:latest .
```
