# Find and remove group titles missing from the playlist

`find_and_remove_missing_categories.py` compares configured group titles with the exact `group-title` values in `data/downloaded_file.m3u`. It reports config entries not present in that playlist and can optionally remove them from the selected config.

Run from the repository root:

```powershell
# Uses data/config/group_titles_with_flags.json by default
python .\find_and_remove_missing_categories.py

# Uses data/config/group_titles_with_flags_updated.json when that file exists
python .\find_and_remove_missing_categories.py --updated
```

The script displays the candidate entries and prompts before modifying anything. On confirmation it attempts to create a timestamped backup, then rewrites the chosen JSON without matching missing titles. If backup creation fails, it asks whether to continue anyway. Duplicate entries with a missing group title are all removed. It does not renumber remaining `order` fields.

## Before confirming removal

A group missing from the current download is not necessarily obsolete; it may be seasonal, temporarily absent, or supplied by another playlist. Review the displayed list and keep a separate backup. The script reads `data/downloaded_file.m3u`, not the final filtered output. It changes only the selected config file and can permanently remove intended groups if confirmed.
