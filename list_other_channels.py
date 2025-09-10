#!/usr/bin/env python3
"""
Extract and list all channel names from the 24/7 Other category
"""

import re

def list_other_channels():
    """Extract all channel names from 247_channels_other.m3u"""
    
    try:
        with open('247_channels_other.m3u', 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        print("❌ File not found: 247_channels_other.m3u")
        print("💡 Run analyze_247_channels.py first to generate the categorized files")
        return
    
    # Parse M3U content to extract channel names
    lines = content.split('\n')
    channels = []
    
    for line in lines:
        line = line.strip()
        if line.startswith('#EXTINF:'):
            # Extract channel name (everything after the last comma)
            name_match = re.search(r',([^,]+)$', line)
            if name_match:
                channel_name = name_match.group(1).strip()
                channels.append(channel_name)
    
    print(f"📊 Found {len(channels)} channels in '24/7 Other' category")
    print("=" * 60)
    
    # Sort channels alphabetically for easier browsing
    channels.sort()
    
    # Print all channels with numbering
    for i, channel in enumerate(channels, 1):
        print(f"{i:4d}. {channel}")
    
    # Create a text file with the list
    output_file = "247_channels_other_list.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(f"24/7 Other Channels List ({len(channels)} channels)\n")
        f.write("=" * 60 + "\n\n")
        
        for i, channel in enumerate(channels, 1):
            f.write(f"{i:4d}. {channel}\n")
    
    print(f"\n✅ Complete list saved to: {output_file}")
    
    # Show some analysis
    print(f"\n📈 Analysis:")
    print(f"   Total channels: {len(channels):,}")
    print(f"   Alphabetically sorted for easy browsing")
    
    # Show first and last few entries for quick reference
    print(f"\n🔤 First 10 channels (alphabetically):")
    for i, channel in enumerate(channels[:10], 1):
        print(f"   {i:2d}. {channel}")
    
    print(f"\n🔤 Last 10 channels (alphabetically):")
    for i, channel in enumerate(channels[-10:], len(channels)-9):
        print(f"   {i:4d}. {channel}")
    
    # Look for common patterns in the "Other" category
    print(f"\n🔍 Common patterns found:")
    
    # Count channels by starting character/number
    first_chars = {}
    for channel in channels:
        first_char = channel[0].upper() if channel else '?'
        if first_char.isdigit():
            first_char = '0-9'
        first_chars[first_char] = first_chars.get(first_char, 0) + 1
    
    # Show top starting characters
    sorted_chars = sorted(first_chars.items(), key=lambda x: x[1], reverse=True)[:10]
    for char, count in sorted_chars:
        print(f"   {char}: {count} channels")
    
    return channels

if __name__ == "__main__":
    list_other_channels()
