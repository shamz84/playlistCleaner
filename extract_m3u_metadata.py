import re
import json
import sys
import os

def extract_m3u_metadata(m3u_file_path, output_json_path):
    """
    Extract metadata from M3U file and save to JSON, removing duplicates.
    
    :param m3u_file_path: Path to the M3U file
    :param output_json_path: Path to save the JSON output
    """
    metadata_list = []
    seen_entries = set()  # To track duplicates
    
    try:
        with open(m3u_file_path, 'r', encoding='utf-8') as file:
            for line_num, line in enumerate(file, 1):
                line = line.strip()
                
                # Skip empty lines and non-EXTINF lines
                if not line or not line.startswith('#EXTINF'):
                    continue
                
                # Extract metadata using regex
                metadata = extract_metadata_from_extinf(line)
                
                if metadata:
                    # Create a unique key for duplicate detection
                    unique_key = (
                        metadata.get('tvg-name', ''),
                        metadata.get('tvg-id', ''),
                        metadata.get('tvg-logo', ''),
                        metadata.get('group-title', '')
                    )
                    
                    # Only add if not a duplicate
                    if unique_key not in seen_entries:
                        seen_entries.add(unique_key)
                        metadata_list.append(metadata)
                        print(f"Processed line {line_num}: {metadata.get('tvg-name', 'Unknown')}")
                    else:
                        print(f"Skipped duplicate on line {line_num}: {metadata.get('tvg-name', 'Unknown')}")
    
    except FileNotFoundError:
        print(f"Error: File '{m3u_file_path}' not found.")
        return False
    except Exception as e:
        print(f"Error reading file: {e}")
        return False
    
    # Save to JSON file
    try:
        with open(output_json_path, 'w', encoding='utf-8') as json_file:
            json.dump(metadata_list, json_file, indent=2, ensure_ascii=False)
        
        print(f"\nExtraction completed!")
        print(f"Total unique entries: {len(metadata_list)}")
        print(f"JSON file saved to: {output_json_path}")
        return True
        
    except Exception as e:
        print(f"Error writing JSON file: {e}")
        return False

def extract_metadata_from_extinf(line):
    """
    Extract metadata from a single EXTINF line.
    
    :param line: EXTINF line from M3U file
    :return: Dictionary with extracted metadata
    """
    metadata = {
        'tvg-name': '',
        'tvg-id': '',
        'tvg-logo': '',
        'group-title': ''
    }
    
    try:
        # Extract tvg-name
        tvg_name_match = re.search(r'tvg-name="([^"]*)"', line)
        if tvg_name_match:
            metadata['tvg-name'] = tvg_name_match.group(1).strip()
        
        # Extract tvg-id
        tvg_id_match = re.search(r'tvg-id="([^"]*)"', line)
        if tvg_id_match:
            metadata['tvg-id'] = tvg_id_match.group(1).strip()
        
        # Extract tvg-logo
        tvg_logo_match = re.search(r'tvg-logo="([^"]*)"', line)
        if tvg_logo_match:
            metadata['tvg-logo'] = tvg_logo_match.group(1).strip()
        
        # Extract group-title
        group_title_match = re.search(r'group-title="([^"]*)"', line)
        if group_title_match:
            metadata['group-title'] = group_title_match.group(1).strip()
        
        # Only return metadata if at least one field was found
        if any(metadata.values()):
            return metadata
        else:
            return None
            
    except Exception as e:
        print(f"Error parsing line: {e}")
        return None

def main():
    # Default file paths
    default_m3u_file = "raw_playlist_6.m3u"
    default_output_file = "extracted_metadata.json"
    
    # Check if M3U file is provided as command line argument
    if len(sys.argv) > 1:
        m3u_file_path = sys.argv[1]
    else:
        m3u_file_path = default_m3u_file
    
    # Check if output file is provided as command line argument
    if len(sys.argv) > 2:
        output_json_path = sys.argv[2]
    else:
        output_json_path = default_output_file
    
    # Check if input file exists
    if not os.path.exists(m3u_file_path):
        print(f"Error: Input file '{m3u_file_path}' does not exist.")
        print(f"Usage: python {sys.argv[0]} <m3u_file> [output_json_file]")
        print(f"Example: python {sys.argv[0]} raw_playlist_6.m3u metadata.json")
        return
    
    print(f"Input M3U file: {m3u_file_path}")
    print(f"Output JSON file: {output_json_path}")
    print("Starting metadata extraction...\n")
    
    # Extract metadata
    success = extract_m3u_metadata(m3u_file_path, output_json_path)
    
    if not success:
        print("Extraction failed.")
        sys.exit(1)

if __name__ == "__main__":
    main()
