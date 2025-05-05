import re
import aiohttp
import asyncio
import os
import json

# Static list of group-titles to ignore
IGNORED_GROUP_TITLES = ["HINDI TAMIL", "BN - BENGALI", "BFBS ᴿᴬᵂ", "GENERAL ʰᵉᵛᶜ","News ʰᵉᵛᶜ","SPORT SD", "LEAGUE ONE PPV", "LEAGUE TWO PPV", "CRUNCHYROLL SERIES (MULTI-SUBS)", "IN - TAMIL", "IN - TELUGU"]

async def fetch_m3u_playlist(url):
    """
    Fetches an .m3u playlist file from a URL asynchronously.

    :param url: URL of the .m3u file
    :return: List of lines from the fetched .m3u file
    """
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(url) as response:
                response.raise_for_status()  # Raise an error for HTTP issues
                text = await response.text()  # Await the response text
                return text.splitlines()  # Split the content into lines
    except aiohttp.ClientError as e:
        print(f"Error fetching the .m3u file: {e}")
        return []

def read_m3u_playlist(lines):
    """
    Reads an .m3u playlist file and extracts the media file paths and metadata.

    :param lines: List of lines from the .m3u file
    :return: A list of dictionaries containing metadata and file paths/URLs
    """
    playlist = []

    # Load the channel-to-group mapping from the JSON file
    try:
        with open("Channel-Grouping-list.json", "r", encoding="utf-8") as json_file:
            channel_group_mapping = json.load(json_file)
    except Exception as e:
        print(f"Error loading channel group mapping: {e}")
        return []

    try:
        current_metadata = {}
        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                # Handle metadata lines (e.g., #EXTINF)
                if line.startswith("#EXTINF:"):
                    # Extract metadata attributes (e.g., tvg-id, tvg-name, group-title)
                    metadata_match = re.search(
                        r'tvg-id="([^"]*)".*?tvg-name="([^"]*)".*?tvg-logo="([^"]*)".*?group-title="([^"]*)"',
                        line
                    )
                    if metadata_match:
                        tvg_id = metadata_match.group(1).strip()
                        tvg_name = metadata_match.group(2).replace("◉", "").replace("4K-","").replace("4K", "").strip()
                        tvg_logo = metadata_match.group(3).strip()
                        group_title = metadata_match.group(4).replace("UK| ","").strip()

                        # Check if group-title is "24/7 ᴴᴰ/ᴿᴬᵂ" and update based on mapping
                        if group_title == "24/7 ᴴᴰ/ᴿᴬᵂ":
                            for category, channels in channel_group_mapping.items():
                                if isinstance(channels, list) and tvg_name in channels:
                                    group_title = category
                                    break

                        current_metadata = {
                            "tvg-id": tvg_id,
                            "tvg-name": tvg_name,
                            "tvg-logo": tvg_logo,
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
                        f'tvg-logo="{metadata.get("tvg-logo", "")}" '
                        f'group-title="{metadata.get("group-title", "")}",'
                        f'{metadata.get("tvg-name", "")}\n'
                    )
                    file.write(extinf_line)
                file.write(f"{file_path}\n")
        print(f"New .m3u file created: {output_file_path}")
    except Exception as e:
        print(f"An error occurred while writing the file: {e}")


if __name__ == "__main__":
    import os

    playlist_url = os.getenv("PLAYLIST_URL").strip()
    output_path = os.getenv("OUTPUT_FILE").strip()

    async def main():
        # Fetch the playlist from the URL
        lines = await fetch_m3u_playlist(playlist_url)

        if lines:
            # Parse the playlist
            playlist_data = read_m3u_playlist(lines)

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
        else:
            print("Failed to fetch or parse the playlist.")

    # Run the async main function
    asyncio.run(main())