#!/usr/bin/env python3
"""
Compress tv_logos.json by removing whitespace and unnecessary data
"""

import json

def compress_tv_logos():
    """Compress the TV logos JSON file"""
    
    # Load original file
    with open('tv_logos.json', 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    # Remove entries with empty tvid and url (keep only useful entries)
    useful_data = []
    for entry in data:
        if entry.get('tvid') or entry.get('url'):
            useful_data.append(entry)
    
    # Save compressed version (no indentation)
    with open('tv_logos_compressed.json', 'w', encoding='utf-8') as f:
        json.dump(useful_data, f, separators=(',', ':'), ensure_ascii=False)
    
    # Show size difference
    import os
    original_size = os.path.getsize('tv_logos.json')
    compressed_size = os.path.getsize('tv_logos_compressed.json')
    
    print(f"Original size: {original_size:,} bytes")
    print(f"Compressed size: {compressed_size:,} bytes")
    print(f"Reduction: {original_size - compressed_size:,} bytes ({(1 - compressed_size/original_size)*100:.1f}%)")
    print(f"Original entries: {len(data)}")
    print(f"Useful entries: {len(useful_data)}")

if __name__ == "__main__":
    compress_tv_logos()