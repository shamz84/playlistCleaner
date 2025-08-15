@echo off
REM Docker Build Script for Playlist Processor
REM Run this script to build and test the Docker image

echo === Docker Playlist Processor Build Script ===
echo.

REM Check if Docker is available
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker is not installed or not in PATH
    echo Please install Docker Desktop from https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)

echo Docker found. Starting build process...
echo.

REM Build the image
echo Building playlist-processor image...
docker build -t playlist-processor .
if %errorlevel% neq 0 (
    echo ERROR: Docker build failed
    pause
    exit /b 1
)

echo.
echo === Build completed successfully! ===
echo.

REM Show image size
echo Image details:
docker images playlist-processor

echo.
echo === Available run commands ===
echo.
echo 1. Complete pipeline (skip Google Drive):
echo    docker run --rm -v "%cd%\data:/app/data" playlist-processor
echo.
echo 2. Use existing files (skip download):
echo    docker run --rm -v "%cd%\data:/app/data" -e SKIP_DOWNLOAD="--skip-download" playlist-processor
echo.
echo 3. Filter only:
echo    docker run --rm -v "%cd%\data:/app/data" -e SKIP_DOWNLOAD="--skip-download" -e SKIP_CREDENTIALS="--skip-credentials" playlist-processor
echo.
echo 4. Interactive debug mode:
echo    docker run --rm -it -v "%cd%\data:/app/data" --entrypoint /bin/bash playlist-processor
echo.

pause
