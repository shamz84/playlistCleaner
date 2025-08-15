@echo off
REM Quick Podman build script for playlist processor
echo 🐳 Building Playlist Processor with Podman
echo ==========================================

REM Check if Podman is available
podman --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Podman not found or not working
    echo Try: wsl --shutdown and restart terminal
    pause
    exit /b 1
)

REM Create data directory
if not exist "data" mkdir data

REM Build the container
echo 🔨 Building container...
podman build -t playlist-processor:latest .

if %errorlevel% equ 0 (
    echo ✅ Build successful!
    echo.
    echo 🚀 Quick start commands:
    echo podman run --rm -v %cd%/data:/app/data playlist-processor:latest
    echo podman run --rm -v %cd%/data:/app/data -e SKIP_DOWNLOAD=--skip-download playlist-processor:latest
) else (
    echo ❌ Build failed!
    echo Try running: .\build-podman.ps1 for detailed diagnostics
)

pause
