# Use an official Python runtime as the base image
FROM python:3.13-slim

# Set the working directory in the container
WORKDIR /app

# Copy all necessary Python scripts and configuration files
COPY process_playlist_complete.py /app/
COPY download_file.py /app/
COPY filter_comprehensive.py /app/
COPY replace_credentials_multi.py /app/
COPY upload_to_gdrive.py /app/
COPY gdrive_setup.py /app/
COPY docker-entrypoint.sh /app/

# Copy configuration files (only the ones that are static)
COPY requirements.txt /app/
COPY group_titles_with_flags.json /app/
# Note: credentials.json, gdrive_config.json, download_config.json will be mounted at runtime

# Create data and config directories for outputs and mounted configs
RUN mkdir -p /app/data /app/data/config

# Copy entrypoint script and make it executable
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Set environment variables
ENV SKIP_DOWNLOAD=""
ENV SKIP_FILTER=""
ENV SKIP_CREDENTIALS=""
ENV SKIP_GDRIVE="--skip-gdrive"
ENV OUTPUT_DIR="/app/data"

# Use entrypoint script for better container management
ENTRYPOINT ["/app/docker-entrypoint.sh"]