# Run Playlist Cleaner Container with Google Drive Backup Enabled

Write-Host "🚀 RUNNING PLAYLIST CLEANER WITH GOOGLE DRIVE BACKUP" -ForegroundColor Green
Write-Host ""

# Verify prerequisites
if (-not (Test-Path "gdrive_token.json")) {
    Write-Host "❌ Google Drive token not found!" -ForegroundColor Red
    Write-Host "📝 Please run: python upload_to_gdrive.py --setup" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path "raw_playlist_AsiaUk.m3u")) {
    Write-Host "❌ Asia UK playlist not found!" -ForegroundColor Red
    exit 1
}

Write-Host "✅ All prerequisites found" -ForegroundColor Green
Write-Host "📁 Mounting Google Drive token for backup..." -ForegroundColor Cyan
Write-Host ""

# Run container with Google Drive enabled
podman run --rm `
  -v "${PWD}/data:/app/data" `
  -v "${PWD}/config:/app/data/config:ro" `
  -v "${PWD}/raw_playlist_AsiaUk.m3u:/app/raw_playlist_AsiaUk.m3u:ro" `
  -v "${PWD}/gdrive_token.json:/app/gdrive_token.json:ro" `
  -e SKIP_GDRIVE="" `
  playlist-cleaner:latest

Write-Host ""
Write-Host "🎉 Container execution completed!" -ForegroundColor Green
Write-Host "📂 Check your Google Drive for uploaded playlists" -ForegroundColor Cyan
