# Use an official Python runtime as the base image
FROM python:3.13-slim

# Set the working directory in the container
WORKDIR /app

# Copy the script and requirements into the container
COPY read_m3u_playlist.py /app/
COPY requirements.txt /app/
COPY Channel-Grouping-list.json /app/

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Set environment variables (default values can be overridden at runtime)
ENV PLAYLIST_URL=""
ENV OUTPUT_FILE="/app/data/parsed_playlist.m3u"

# Command to run the script
CMD ["python", "read_m3u_playlist.py"]