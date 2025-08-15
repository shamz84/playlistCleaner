# PowerShell script to build container with Podman
# Handles common Podman issues on Windows

param(
    [string]$ImageName = "playlist-processor",
    [string]$Tag = "latest",
    [switch]$Force,
    [switch]$NoBuildCache,
    [switch]$Verbose
)

Write-Host "🐳 Podman Container Build Script" -ForegroundColor Cyan
Write-Host "=================================" -ForegroundColor Cyan

# Function to check if Podman is working
function Test-PodmanWorking {
    Write-Host "🔍 Testing Podman functionality..." -ForegroundColor Yellow
    
    try {
        $timeout = 30
        $job = Start-Job -ScriptBlock { podman --version }
        
        if (Wait-Job $job -Timeout $timeout) {
            $result = Receive-Job $job
            Remove-Job $job
            Write-Host "✅ Podman version: $result" -ForegroundColor Green
            return $true
        } else {
            Remove-Job $job -Force
            Write-Host "❌ Podman command timed out" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "❌ Podman not working: $_" -ForegroundColor Red
        return $false
    }
}

# Function to check Podman machine status
function Test-PodmanMachine {
    Write-Host "🔍 Checking Podman machine status..." -ForegroundColor Yellow
    
    try {
        $machineStatus = podman machine list 2>&1
        
        if ($machineStatus -match "running") {
            Write-Host "✅ Podman machine is running" -ForegroundColor Green
            return $true
        } elseif ($machineStatus -match "stopped") {
            Write-Host "⚠️  Podman machine is stopped, attempting to start..." -ForegroundColor Yellow
            podman machine start
            Start-Sleep -Seconds 10
            return Test-PodmanMachine
        } else {
            Write-Host "❌ Podman machine status unclear: $machineStatus" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "❌ Error checking Podman machine: $_" -ForegroundColor Red
        return $false
    }
}

# Function to restart Podman machine
function Restart-PodmanMachine {
    Write-Host "🔄 Restarting Podman machine..." -ForegroundColor Yellow
    
    try {
        Write-Host "   Stopping machine..." -ForegroundColor Gray
        podman machine stop 2>&1 | Out-Null
        Start-Sleep -Seconds 5
        
        Write-Host "   Starting machine..." -ForegroundColor Gray
        podman machine start
        Start-Sleep -Seconds 10
        
        return Test-PodmanMachine
    } catch {
        Write-Host "❌ Failed to restart Podman machine: $_" -ForegroundColor Red
        return $false
    }
}

# Function to build with Podman
function Build-WithPodman {
    param([string]$ImageName, [string]$Tag, [bool]$NoBuildCache, [bool]$Verbose)
    
    $buildArgs = @("build", "-t", "${ImageName}:${Tag}", ".")
    
    if ($NoBuildCache) {
        $buildArgs += "--no-cache"
    }
    
    if ($Verbose) {
        $buildArgs += "--progress=plain"
    }
    
    Write-Host "🔨 Building container with Podman..." -ForegroundColor Yellow
    Write-Host "   Command: podman $($buildArgs -join ' ')" -ForegroundColor Gray
    
    try {
        $process = Start-Process -FilePath "podman" -ArgumentList $buildArgs -Wait -PassThru -NoNewWindow
        
        if ($process.ExitCode -eq 0) {
            Write-Host "✅ Container built successfully: ${ImageName}:${Tag}" -ForegroundColor Green
            return $true
        } else {
            Write-Host "❌ Build failed with exit code: $($process.ExitCode)" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "❌ Build error: $_" -ForegroundColor Red
        return $false
    }
}

# Function to test built container
function Test-Container {
    param([string]$ImageName, [string]$Tag)
    
    Write-Host "🧪 Testing built container..." -ForegroundColor Yellow
    
    try {
        # Test if image exists
        $images = podman images --format "{{.Repository}}:{{.Tag}}" | Where-Object { $_ -eq "${ImageName}:${Tag}" }
        
        if (-not $images) {
            Write-Host "❌ Image ${ImageName}:${Tag} not found" -ForegroundColor Red
            return $false
        }
        
        # Test container run
        Write-Host "   Running container test..." -ForegroundColor Gray
        $testResult = podman run --rm "${ImageName}:${Tag}" python --version 2>&1
        
        if ($testResult -match "Python") {
            Write-Host "✅ Container test successful: $testResult" -ForegroundColor Green
            return $true
        } else {
            Write-Host "❌ Container test failed: $testResult" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "❌ Container test error: $_" -ForegroundColor Red
        return $false
    }
}

# Main execution
Write-Host "📋 Build Parameters:" -ForegroundColor White
Write-Host "   Image: ${ImageName}:${Tag}" -ForegroundColor Gray
Write-Host "   Force rebuild: $Force" -ForegroundColor Gray
Write-Host "   No cache: $NoBuildCache" -ForegroundColor Gray
Write-Host "   Verbose: $Verbose" -ForegroundColor Gray
Write-Host ""

# Step 1: Test Podman
if (-not (Test-PodmanWorking)) {
    Write-Host "🚨 Podman is not working properly!" -ForegroundColor Red
    Write-Host "   Try these solutions:" -ForegroundColor Yellow
    Write-Host "   1. wsl --shutdown" -ForegroundColor Gray
    Write-Host "   2. podman machine stop && podman machine start" -ForegroundColor Gray
    Write-Host "   3. Use Docker instead: docker build -t ${ImageName}:${Tag} ." -ForegroundColor Gray
    exit 1
}

# Step 2: Check machine status
if (-not (Test-PodmanMachine)) {
    Write-Host "⚠️  Attempting to restart Podman machine..." -ForegroundColor Yellow
    if (-not (Restart-PodmanMachine)) {
        Write-Host "🚨 Could not get Podman machine running!" -ForegroundColor Red
        Write-Host "   Manual steps:" -ForegroundColor Yellow
        Write-Host "   1. podman machine stop" -ForegroundColor Gray
        Write-Host "   2. podman machine rm podman-machine-default" -ForegroundColor Gray
        Write-Host "   3. podman machine init" -ForegroundColor Gray
        Write-Host "   4. podman machine start" -ForegroundColor Gray
        exit 1
    }
}

# Step 3: Create data directory
if (-not (Test-Path "data")) {
    Write-Host "📁 Creating data directory..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path "data" -Force | Out-Null
}

# Step 4: Check required files
$requiredFiles = @(
    "Dockerfile",
    "process_playlist_complete.py",
    "download_file.py",
    "filter_comprehensive.py",
    "replace_credentials_multi.py",
    "requirements.txt",
    "credentials.json",
    "group_titles_with_flags.json"
)

Write-Host "📋 Checking required files..." -ForegroundColor Yellow
$missingFiles = @()
foreach ($file in $requiredFiles) {
    if (-not (Test-Path $file)) {
        $missingFiles += $file
        Write-Host "   ❌ Missing: $file" -ForegroundColor Red
    } else {
        Write-Host "   ✅ Found: $file" -ForegroundColor Green
    }
}

if ($missingFiles.Count -gt 0) {
    Write-Host "🚨 Missing required files. Build cannot continue." -ForegroundColor Red
    exit 1
}

# Step 5: Build container
if (-not (Build-WithPodman -ImageName $ImageName -Tag $Tag -NoBuildCache $NoBuildCache -Verbose $Verbose)) {
    Write-Host "🚨 Build failed!" -ForegroundColor Red
    Write-Host "   Troubleshooting options:" -ForegroundColor Yellow
    Write-Host "   1. Try with --no-cache: $PSCommandPath -NoBuildCache" -ForegroundColor Gray
    Write-Host "   2. Try with Docker: docker build -t ${ImageName}:${Tag} ." -ForegroundColor Gray
    Write-Host "   3. Check Dockerfile syntax" -ForegroundColor Gray
    exit 1
}

# Step 6: Test container
if (-not (Test-Container -ImageName $ImageName -Tag $Tag)) {
    Write-Host "⚠️  Container built but failed basic test" -ForegroundColor Yellow
} else {
    Write-Host "🎉 Container build and test successful!" -ForegroundColor Green
}

# Step 7: Display usage instructions
Write-Host ""
Write-Host "🚀 Container ready! Usage examples:" -ForegroundColor Cyan
Write-Host "=================================" -ForegroundColor Cyan
Write-Host "# Run complete pipeline:" -ForegroundColor Gray
Write-Host "podman run --rm -v `${PWD}/data:/app/data ${ImageName}:${Tag}" -ForegroundColor White
Write-Host ""
Write-Host "# Run with existing files (skip download):" -ForegroundColor Gray
Write-Host "podman run --rm -v `${PWD}/data:/app/data -e SKIP_DOWNLOAD=--skip-download ${ImageName}:${Tag}" -ForegroundColor White
Write-Host ""
Write-Host "# Interactive debugging:" -ForegroundColor Gray
Write-Host "podman run -it --rm -v `${PWD}/data:/app/data --entrypoint /bin/bash ${ImageName}:${Tag}" -ForegroundColor White
Write-Host ""
Write-Host "# Using Docker Compose:" -ForegroundColor Gray
Write-Host "podman-compose up playlist-processor" -ForegroundColor White

Write-Host ""
Write-Host "✅ Build completed successfully!" -ForegroundColor Green
