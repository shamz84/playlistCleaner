# Docker quick reference

For the current container status, caveats, and complete command, see [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md). The short version, from the repository root:

```powershell
docker build -t playlist-processor:latest .
docker run --rm `
  --mount "type=bind,source=$($PWD.Path)\data,target=/app/data" `
  -e SKIP_GDRIVE=--skip-gdrive `
  playlist-processor:latest
```

This invokes the enhanced pipeline and can make network requests and overwrite local playlist data. The checked-in Compose examples are not aligned with the current `data/config/` layout; do not use them without reviewing their mounts. The host command is the recommended run path while container limitations remain.
