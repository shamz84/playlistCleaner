# Test Docker Image with Mounted Config
# This script tests the Docker image to verify 2 M3U files are generated

Write-Host "🧪 Testing Docker Image with Mounted Config" -ForegroundColor Cyan
Write-Host "============================================="

# Clean the data directory first
Write-Host "🧹 Cleaning data directory..."
if (Test-Path "data") {
    Remove-Item "data\*.m3u" -ErrorAction SilentlyContinue
}

# Verify config files exist
Write-Host "`n📁 Checking config files..."
$configFiles = @("config\credentials.json", "config\gdrive_config.json", "config\download_config.json")
foreach ($file in $configFiles) {
    if (Test-Path $file) {
        Write-Host "✅ Found: $file" -ForegroundColor Green
    } else {
        Write-Host "❌ Missing: $file" -ForegroundColor Red
    }
}

# Check credentials.json for expected user count
Write-Host "`n🔍 Checking credentials configuration..."
try {
    $credentials = Get-Content "config\credentials.json" | ConvertFrom-Json
    $userCount = $credentials.Count
    Write-Host "✅ Found $userCount credential set(s)" -ForegroundColor Green
    
    for ($i = 0; $i -lt $userCount; $i++) {
        $cred = $credentials[$i]
        Write-Host "   User $($i+1): $($cred.username) @ $($cred.dns)" -ForegroundColor Gray
    }
} catch {
    Write-Host "❌ Error reading credentials.json: $_" -ForegroundColor Red
    exit 1
}

# Run the container with mounted config
Write-Host "`n🚀 Running container with mounted config..."
Write-Host "Command: podman run --rm -v data:/app/data -v config:/app/data/config:ro -v raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro -e SKIP_GDRIVE=--skip-gdrive shamz84/playlist-cleaner:latest"

$startTime = Get-Date
podman run --rm `
    -v "$(Get-Location)\data:/app/data" `
    -v "$(Get-Location)\config:/app/data/config:ro" `
    -v "$(Get-Location)\raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" `
    -e SKIP_GDRIVE="--skip-gdrive" `
    shamz84/playlist-cleaner:latest

$endTime = Get-Date
$duration = $endTime - $startTime

# Check results
Write-Host "`n📊 Checking results..." -ForegroundColor Cyan
Write-Host "Processing time: $($duration.TotalSeconds) seconds"

# Count M3U files generated
$m3uFiles = Get-ChildItem "data\*.m3u" | Where-Object { $_.Name -like "8k_*.m3u" }
$generatedCount = $m3uFiles.Count

Write-Host "`n📁 Generated files:"
foreach ($file in (Get-ChildItem "data\*.m3u")) {
    $size = [math]::Round($file.Length / 1MB, 2)
    if ($file.Name -like "8k_*.m3u") {
        Write-Host "   🎯 $($file.Name) ($size MB)" -ForegroundColor Green
    } else {
        Write-Host "   📄 $($file.Name) ($size MB)" -ForegroundColor Gray
    }
}

# Verify test results
Write-Host "`n🎯 Test Results:" -ForegroundColor Cyan
Write-Host "Expected personalized playlists: $userCount"
Write-Host "Generated personalized playlists: $generatedCount"

if ($generatedCount -eq $userCount) {
    Write-Host "✅ SUCCESS: Correct number of M3U files generated!" -ForegroundColor Green
    Write-Host "✅ Container config mounting works perfectly!" -ForegroundColor Green
} else {
    Write-Host "❌ FAILED: Expected $userCount M3U files, got $generatedCount" -ForegroundColor Red
    exit 1
}

# Additional validation
Write-Host "`n🔍 Validating M3U file contents..."
foreach ($file in $m3uFiles) {
    $lineCount = (Get-Content $file.FullName | Measure-Object -Line).Lines
    Write-Host "   📄 $($file.Name): $lineCount lines" -ForegroundColor Gray
    
    if ($lineCount -gt 1000) {
        Write-Host "      ✅ File has substantial content" -ForegroundColor Green
    } else {
        Write-Host "      ⚠️  File seems small, check content" -ForegroundColor Yellow
    }
}

Write-Host "`n🎉 Docker image test completed successfully!" -ForegroundColor Green
Write-Host "💡 The image can now be used with mounted config files from /app/data/config"
