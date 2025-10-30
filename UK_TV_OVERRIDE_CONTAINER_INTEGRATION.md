# UK TV Override Container Integration

## Overview
Updated Docker container configuration to include the UK TV Override system functionality.

## Files Updated

### 1. Dockerfile
- Added UK TV override script files to container:
  - `uk_tv_override_dynamic.py`
- UK TV override configuration copied to config directory:
  - `uk_tv_overrides_dynamic.conf` → `/app/data/config/uk_tv_overrides_dynamic.conf`
- Added environment variable: `SKIP_UK_OVERRIDE=""`

### 2. docker-compose.yml
- Added `SKIP_UK_OVERRIDE=""` environment variable to all service configurations:
  - Main `playlist-processor` service
  - `playlist-processor-download-only` service (set to `--skip-uk-override`)
  - `playlist-processor-filter-only` service

### 3. docker-compose.gdrive.yml
- Added `SKIP_UK_OVERRIDE=""` environment variable to enable UK TV override processing

### 4. docker-compose.container.yml
- Added `SKIP_UK_OVERRIDE=""` environment variable to all service configurations:
  - `playlist-cleaner-service` (Service Account Authentication)
  - `playlist-cleaner-token` (Pre-authenticated Token)
  - `playlist-cleaner-env` (Environment Variable Authentication)
  - `playlist-cleaner-no-gdrive` (No Google Drive backup)

## Environment Variable Usage

### SKIP_UK_OVERRIDE Environment Variable
- **Empty string (`""`)**  → UK TV Override processing enabled (default)
- **`"--skip-uk-override"`** → UK TV Override processing disabled

### Example Usage in Container

#### Enable UK TV Override (Default)
```bash
docker-compose up
# or
docker run -e SKIP_UK_OVERRIDE="" playlist-processor
```

#### Disable UK TV Override
```bash
docker-compose up playlist-processor-download-only
# or  
docker run -e SKIP_UK_OVERRIDE="--skip-uk-override" playlist-processor
```

## Integration with Enhanced Pipeline

The UK TV Override system is integrated as **Step 2.5** in the enhanced pipeline:

1. **Step 1**: API Download → `downloaded_file.m3u`
2. **Step 2**: Filter Processing → `filtered_playlist_final.m3u`
3. **Step 2.5**: UK TV Override → `uk_tv_override_applied.m3u` ✨ **NEW**
4. **Step 3**: Credentials Replacement → `credentials_replaced.m3u`
5. **Step 4**: Google Drive Upload → `final_playlist_clean.m3u`

## Configuration Files

The container includes the UK TV override configuration file in the proper config directory:
- Container path: `/app/data/config/uk_tv_overrides_dynamic.conf`
- Host mount: `./config/uk_tv_overrides_dynamic.conf` (mounted via `./config:/app/data/config:ro`)

The system automatically looks for the configuration file in the following order:
1. `data/config/uk_tv_overrides_dynamic.conf` (Container/mounted config)
2. `config/uk_tv_overrides_dynamic.conf` (Local config directory)  
3. `uk_tv_overrides_dynamic.conf` (Root directory fallback)

### Configuration File Setup

To configure UK TV overrides in containers:

1. **Create/edit** `config/uk_tv_overrides_dynamic.conf` on your host system
2. **Example configuration**:
   ```
   # UK TV Guide Override Configuration
   BBC One = BBC One London
   BBC Two = BBC Two England
   ITV 1 = ITV 1 London
   Channel 4 = Channel 4 London
   ```
3. **Container automatically mounts** this via `./config:/app/data/config:ro`

## Features Available in Container

✅ **Dynamic Channel Identification**: Uses group-title + channel name matching  
✅ **Group-Title Preservation**: Maintains original playlist organization  
✅ **Unicode Support**: Proper handling of emojis and special characters  
✅ **Comprehensive Logging**: Detailed replacement tracking and statistics  
✅ **Error Handling**: Graceful fallback if override processing fails  
✅ **Pipeline Integration**: Seamless integration with existing processing steps  

## Container Requirements

No additional dependencies required - uses standard Python libraries:
- `re` (regex)
- `sys` (system)
- `os` (operating system)
- `codecs` (Unicode handling)

## Testing Container Integration

Build and test the updated container:

```bash
# Build the container
docker build -t playlist-processor .

# Test with UK TV override enabled (default)
docker-compose up

# Test with UK TV override disabled
docker-compose up playlist-processor-download-only
```

## Backward Compatibility

All existing container configurations remain compatible:
- If `SKIP_UK_OVERRIDE` is not set, defaults to enabled
- Existing docker-compose configurations will automatically include UK TV override processing
- Original functionality preserved when UK TV override is disabled