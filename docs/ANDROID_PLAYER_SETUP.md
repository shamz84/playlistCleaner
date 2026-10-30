# Android player setup (operator guide)

This guide is for the person who runs this repository's pipeline. It explains how to hand each user a Google Drive file ID that the Android app (`android/`, package `app.playlistplayer`) uses to load that user's playlist.

## Supported devices

The app runs on Fire TV devices with Fire OS 6, 7 or 8 and on Android phones with Android 7.1 (API 25) or newer. Fire OS 6 devices include Fire TV Stick 4K (1st gen, 2018), Fire TV Cube (1st gen, 2018) and Fire TV (3rd gen, 2017). Fire OS 6 receives fewer security updates, and its keystore may be software-backed, so an ID stored on such a device is less well protected. Fire OS 5 devices such as Fire TV Stick (2nd gen) and Vega OS devices are not supported. The full list is in [android/docs/distribution.md](../android/docs/distribution.md).

## Security model

- Every personalized `8k_<username>.m3u` contains that user's provider credentials in every stream URL.
- One Drive file per user is the security boundary. Never share a file with, or give a file ID to, anyone but its owner.
- The file ID acts like a password: anyone who has it can read the file, and therefore the credentials. Do not post IDs in chat channels, tickets, commits, or logs.
- The app stores the ID encrypted and redacts it from its own logs. The pipeline never sets sharing permissions itself.

## One-time setup per user

1. Run the pipeline with Drive upload enabled so `8k_<username>.m3u` exists in the Drive backup folder (see [README_GoogleDrive.md](README_GoogleDrive.md)).
2. In the Drive web UI, open the file, choose Share, and set General access to "Anyone with the link" with role Viewer.
3. Copy the file ID. It is the long string between `/d/` and the next `/` in the share link.
4. Send that ID to that user only, through a private channel. The user enters it in the app's onboarding screen (a pasted share link also works).

Repeat for each `8k_<username>.m3u`. Sharing is set once; later uploads keep it.

## Keeping the file ID stable

`upload_to_gdrive.py --backup` (and the upload step of the pipeline) updates an existing file in place. `_upload_file_internal` calls `find_file_by_name(name, folder_id)` and, when it finds one, calls `update_file`, which does a Drive `files.update` on that ID. The ID and its sharing settings survive. A new file (new ID, no sharing) is created only when no match is found or overwrite is off.

Rules that keep the ID stable:

1. Keep `"overwrite_existing": true` in `data/config/gdrive_config.json`. With it off, every run creates another file with the same name and a new ID.
2. Always upload with the same Google account and the same OAuth client (same `credentials.json` and token). The uploader requests only the `drive.file` scope, so Drive search returns only files that this OAuth client created. A different account, or a different OAuth client under the same account, cannot see the existing file, finds nothing by name, and creates a new file with a new ID. Re-authorising the same client with the same account is fine.
3. Do not use the service-account fallback for these files. `authenticate()` tries a service account first when `gdrive_service_account.json` is present, and it only falls back to OAuth on quota or access errors. A service account is a different identity, so it would create its own copy with a new ID. Remove the service-account file from `data/config/` (and do not mount it in a container) for this workflow.
4. Never rename, move, trash, or delete the shared file or its folder. The lookup is by file name inside the folder named by `default_folder`. Renaming or moving the file, or trashing the folder (the uploader then creates a new folder), makes the next run create a new file.
5. Avoid two files with the same name in the folder. The lookup uses the first match and Drive does not guarantee which one that is, so the update could land on the wrong copy. If duplicates exist, delete the unshared one in the Drive UI.
6. Do not change `upload_to_gdrive.py` so that it deletes and re-creates files or stops updating in place. That would break every installed app.

## Symptoms and fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| App shows "Couldn't open that file…" | File ID wrong, file unshared, trashed, or replaced by a new file | Check the file in Drive. Re-share as "Anyone with the link", or send the user the current ID. |
| App warns that the playlist is out of date (after the configured number of days) | Uploads stopped, or they went to a different file | Run the upload and confirm the log says `Updating existing file` and `(preserved)`. If it said `Uploading ...` the ID changed. |
| Upload log shows a new File ID without `(preserved)` | Identity change, duplicate or renamed file, overwrite disabled, service account in use | Fix the cause using the rules above, share the new file, and send the new ID. Delete the old copy. |

When the ID has changed, the user re-enters it in the app: Settings, Playlists, Edit source. The cached playlist stays usable until then.

## Leaked-ID rotation runbook

Use this if a file ID, a share link, or an `8k_<username>.m3u` was exposed.

1. Unshare or delete the old Drive file now (Share, change General access to Restricted, or move it to trash).
2. Rotate the provider password for that account, or ask the provider to. The old file contains working credentials, so unsharing alone is not enough.
3. Update the credential configuration used by `replace_credentials_multi.py` (see [README_MultiCredentials.md](README_MultiCredentials.md)) and regenerate the playlists.
4. Upload as a new file. Because the old file is gone or unreachable by name, the uploader creates a new file with a new ID. If you only unshared the old file, rename or delete it first so there are no duplicates by name.
5. Share the new file as "Anyone with the link", Viewer, and copy its ID.
6. Send the new ID to the affected user only.

Expected app behaviour: the app gets a not-found/not-shared result (`NotFoundOrNotShared`) and shows the "Couldn't open that file" message. Its cached playlist keeps working until the provider password changes. The user opens Settings, Playlists, Edit source, enters the new ID, and the app re-imports.

## Optional: Drive API key for the app

Without a key the app falls back to the public download URL (`drive.usercontent.google.com`), which is undocumented and may be throttled. With a key it uses the documented Drive API v3. The key grants no access beyond what link sharing already allows.

1. In Google Cloud Console, create or choose a project and enable the Google Drive API.
2. Under APIs and Services, Credentials, create an API key.
3. Restrict it: API restriction = Google Drive API only; Application restriction = Android apps. Add package name `app.playlistplayer` with the SHA-1 of each signing key you use (debug and release are separate entries):
   - Debug: `keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android`
   - Release: `keytool -list -v -keystore <your release keystore> -alias <alias>`
4. Provide the key at build time, either in `android/local.properties` as `drive.apiKey=<key>` or as the environment variable `DRIVE_API_KEY`. Both are untracked and read by `android/app/build.gradle.kts`. Never commit the key.
5. Rebuild the app. See [../android/docs/distribution.md](../android/docs/distribution.md).

## Related

- [Google Drive upload and authentication](README_GoogleDrive.md)
- [Credential replacement](README_MultiCredentials.md)
- [Android app overview](../android/README.md)
