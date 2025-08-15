#!/bin/bash
# Docker Build Script for Playlist Processor (Linux/macOS)

echo "=== Docker Playlist Processor Build Script ==="
echo

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker is not installed or not in PATH"
    echo "Please install Docker from https://www.docker.com/get-started"
    exit 1
fi

echo "Docker found. Starting build process..."
echo

# Build the image
echo "Building playlist-processor image..."
if ! docker build -t playlist-processor .; then
    echo "ERROR: Docker build failed"
    exit 1
fi

echo
echo "=== Build completed successfully! ==="
echo

# Show image size
echo "Image details:"
docker images playlist-processor

echo
echo "=== Available run commands ==="
echo
echo "1. Complete pipeline (skip Google Drive):"
echo "   docker run --rm -v \$(pwd)/data:/app/data playlist-processor"
echo
echo "2. Use existing files (skip download):"
echo "   docker run --rm -v \$(pwd)/data:/app/data -e SKIP_DOWNLOAD=\"--skip-download\" playlist-processor"
echo
echo "3. Filter only:"
echo "   docker run --rm -v \$(pwd)/data:/app/data -e SKIP_DOWNLOAD=\"--skip-download\" -e SKIP_CREDENTIALS=\"--skip-credentials\" playlist-processor"
echo
echo "4. Interactive debug mode:"
echo "   docker run --rm -it -v \$(pwd)/data:/app/data --entrypoint /bin/bash playlist-processor"
echo
echo "5. Using Docker Compose:"
echo "   docker-compose up playlist-processor"
echo
