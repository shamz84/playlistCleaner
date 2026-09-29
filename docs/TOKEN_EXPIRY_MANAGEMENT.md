# Google Drive token maintenance

This repository contains OAuth and service-account helper scripts, but token monitoring is **not an automatic background feature of the playlist pipeline**. The enhanced orchestrator performs Drive backup only when that stage is enabled; the default Docker setting skips it.

## OAuth tokens

OAuth access tokens are short-lived and may be refreshed when the saved authorization includes a valid refresh token. Refresh-token validity is not a universal fixed six-month lifetime; it can depend on consent-screen status, app policies, revocation, inactivity, and Google account/security actions. Re-authenticate when Google reports the grant is invalid or revoked.

Use the uploader's supported interactive setup when needed:

```powershell
python .\upload_to_gdrive.py --setup
```

Token location and lookup order are described in [README_GoogleDrive.md](README_GoogleDrive.md). Treat token and client-secret files as secrets and do not commit or publish them.

## Helper scripts and limitations

- `check_gdrive_token_usage.py` reports which token path may be selected.
- `token_refresh_manager.py` can inspect/refresh its configured OAuth token when run directly and has a foreground monitoring helper. Its service-generation code references `token_refresh_service.py`, which is absent from this repository; do not install the generated system service as-is.
- `create_never_expiring_auth.py` and service-account setup scripts create credentials/configuration but are not evidence that every deployment supports non-expiring Drive access. Service-account storage and sharing permissions must be explicitly configured and tested.
- The current pipeline does not start `TokenRefreshManager` as a daemon. Do not rely on it to keep tokens refreshed while idle.

When Drive is not required, use the pipeline's `--skip-gdrive` option.
