import re

# Static list of group-titles to ignore
IGNORED_GROUP_TITLES = ["HINDI TAMIL", "BN - BENGALI", "UK| BFBS ᴿᴬᵂ", "UK| SPORT SD", "UK| LEAGUE ONE PPV", "UK| LEAGUE TWO PPV", "CRUNCHYROLL SERIES (MULTI-SUBS)", "IN - TAMIL", "IN - TELUGU"]

def read_m3u_playlist(file_path):
    """
    Reads an .m3u playlist file and extracts the media file paths and metadata.

    :param file_path: Path to the .m3u file
    :return: A list of dictionaries containing metadata and file paths/URLs
    """
    playlist = []

    try:
        with open(file_path, 'r', encoding='utf-8') as file:
            current_metadata = {}
            for line in file:
                line = line.strip()
                if not line or line.startswith("#"):
                    # Handle metadata lines (e.g., #EXTINF)
                    if line.startswith("#EXTINF:"):
                        # Extract metadata attributes (e.g., tvg-id, tvg-name, group-title)
                        metadata_match = re.search(
                            r'tvg-id="([^"]*)".*?tvg-name="([^"]*)".*?group-title="([^"]*)"',
                            line
                        )
                        if metadata_match:
                            tvg_id = metadata_match.group(1).strip()
                            tvg_name = metadata_match.group(2).replace("◉", "").replace("4K", "").strip()
                            group_title = metadata_match.group(3).strip()
                            current_metadata = {
                                "tvg-id": tvg_id,
                                "tvg-name": tvg_name,
                                "group-title": group_title
                            }
                    continue

                # Ignore entries where tvg-name starts with ####
                if current_metadata.get("tvg-name", "").startswith("####"):
                    current_metadata = {}  # Reset metadata for the next entry
                    continue

                # Ignore entries with group-titles in the ignored list
                if current_metadata.get("group-title") in IGNORED_GROUP_TITLES:
                    current_metadata = {}  # Reset metadata for the next entry
                    continue

                # Handle media file path/URL
                playlist.append({
                    "metadata": current_metadata,
                    "file_path": line
                })
                current_metadata = {}  # Reset metadata for the next entry

        return playlist

    except FileNotFoundError:
        print(f"Error: File not found - {file_path}")
    except Exception as e:
        print(f"An error occurred: {e}")

    return []

def write_m3u_playlist(output_file_path, playlist_data):
    """
    Writes the parsed playlist data to a new .m3u file.

    :param output_file_path: Path to the output .m3u file
    :param playlist_data: List of dictionaries containing metadata and file paths/URLs
    """
    try:
        with open(output_file_path, 'w', encoding='utf-8') as file:
            file.write("#EXTM3U\n")  # Write the header for the .m3u file
            for entry in playlist_data:
                metadata = entry["metadata"]
                file_path = entry["file_path"]
                if metadata:
                    extinf_line = (
                        f'#EXTINF:-1 tvg-id="{metadata.get("tvg-id", "")}" '
                        f'tvg-name="{metadata.get("tvg-name", "")}" '
                        f'group-title="{metadata.get("group-title", "")}",'
                        f'{metadata.get("tvg-name", "")}\n'
                    )
                    file.write(extinf_line)
                file.write(f"{file_path}\n")
        print(f"New .m3u file created: {output_file_path}")
    except Exception as e:
        print(f"An error occurred while writing the file: {e}")


if __name__ == "__main__":
    # Example usage
    playlist_path = input("Enter the path to the .m3u playlist file: ").strip()
    output_path = input("Enter the path for the new .m3u file: ").strip()
    playlist_data = read_m3u_playlist(playlist_path)

    if playlist_data:
        print("\nParsed Playlist:")
        for index, entry in enumerate(playlist_data, start=1):
            metadata = entry["metadata"]
            file_path = entry["file_path"]
            print(f"{index}. File: {file_path}")
            if metadata:
                print(f"   Metadata:")
                print(f"      TVG-ID: {metadata.get('tvg-id')}")
                print(f"      TVG-Name: {metadata.get('tvg-name')}")
                print(f"      Group-Title: {metadata.get('group-title')}")

        # Write the parsed data to a new .m3u file
        write_m3u_playlist(output_path, playlist_data)
    else:
        print("No valid entries found in the playlist.")