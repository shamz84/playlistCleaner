# Use an official Python runtime as the base image
FROM python:3.13-slim

# Set the working directory in the container
WORKDIR /app

# Copy all necessary Python scripts and configuration files
COPY process_playlist_complete.py /app/
COPY process_playlist_complete_enhanced.py /app/
COPY download_file.py /app/
COPY filter_comprehensive.py /app/
COPY filter_m3u_with_auto_include.py /app/
COPY replace_credentials_multi.py /app/
COPY upload_to_gdrive.py /app/
COPY gdrive_setup.py /app/
COPY analyze_247_channels.py /app/
COPY merge_247_channels.py /app/
COPY docker-entrypoint.sh /app/
COPY api_to_m3u_converter.py /app/

# Copy UK TV Override system files
COPY uk_tv_override_dynamic.py /app/

# Copy Google Drive authentication setup files
COPY setup_container_gdrive_auth.py /app/
COPY setup_gdrive_for_container.py /app/
COPY setup_service_account_gdrive.py /app/
COPY setup_env_gdrive.py /app/
COPY check_gdrive_token_usage.py /app/
COPY create_never_expiring_auth.py /app/
COPY token_refresh_manager.py /app/

# Copy configuration files (only the ones that are static)
COPY requirements.txt /app/
#COPY data/config/group_titles_with_flags.json /app/
# Note: credentials.json, gdrive_config.json, download_config.json will be mounted at runtime

# Create data and config directories for outputs and mounted configs
RUN mkdir -p /app/data /app/data/config

# Copy UK TV Override configuration to config directory
#COPY uk_tv_overrides_dynamic.conf /app/data/config/

# Copy entrypoint script and make it executable
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Set environment variables
ENV SKIP_DOWNLOAD=""
ENV SKIP_FILTER=""
ENV SKIP_UK_OVERRIDE=""
ENV SKIP_CREDENTIALS=""
ENV SKIP_GDRIVE="--skip-gdrive"
ENV OUTPUT_DIR="/app/data"

# Use entrypoint script for better container management
ENTRYPOINT ["/app/docker-entrypoint.sh"]