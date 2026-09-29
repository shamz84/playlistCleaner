# Personalized playlist generation

`replace_credentials_multi.py` reads a filtered playlist and a credential configuration, then writes one playlist per configured account.

## Config format

Use either one object or a list of objects in `data/config/credentials.json` (the repository-root `credentials.json` is a fallback):

```json
[
  {
    "dns": "your-server.example:80",
    "username": "YOUR_USERNAME",
    "password": "YOUR_PASSWORD"
  }
]
```

Keep real account values local. The config filename is ignored by Git in this repository, but always inspect `git status` before staging files.

## Run

From the repository root:

```powershell
python .\replace_credentials_multi.py
```

The script reads `filtered_playlist_final.m3u` and creates root-level files named `8k_<username>.m3u`. It replaces known placeholder host/user/password tokens in playlist URL lines; output files should be handled as secrets. The current orchestrator runs this after filtering and UK TV overrides.

Do not use `--skip-filter` expecting the standalone script to automatically switch inputs: this script's default input is the root-level filtered playlist. See [the pipeline guide](README_Complete_System.md) for the orchestrator's supported flow and side effects.
