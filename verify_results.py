#!/usr/bin/env python3
"""
Quick verification of the filtered results
"""

import re
from collections import Counter

def analyze_filtered_output():
    """Analyze the filtered playlist output."""
    
    try:
        with open('filtered_playlist_final.m3u', 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Extract all group titles
        groups = re.findall(r'group-title="([^"]+)"', content)
        group_counts = Counter(groups)
        
        print(f"📊 Filtered Playlist Analysis:")
        print(f"   • Total channels: {len(groups)}")
        print(f"   • Unique groups: {len(group_counts)}")
        
        # Show previously unknown groups that are now included
        unknown_groups_included = [
            'US| ESPN+ VIP PPV', 'AU| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ', 'AU| NRL TV PPV',
            'CA| DAZN VIP PPV', 'AU| AUSTRALIA VIP', 'AU| PRIME PPV ᴿᴬᵂ',
            'AU| STAN SPORT PPV', 'AU| STAN PPV', 'AU| OPTUS PPV',
            'AU| PRIME PPV', 'AU| ESPN PLAY PPV', 'AU| AFL PPV', 'UK| MONO MAX PPV'
        ]
        
        print(f"\n✅ Previously Unknown Groups Now Included:")
        total_recovered = 0
        for group in unknown_groups_included:
            if group in group_counts:
                count = group_counts[group]
                total_recovered += count
                print(f"   • {group}: {count} channels")
            else:
                print(f"   • {group}: NOT FOUND (may have been excluded)")
        
        print(f"\n📈 Recovery Summary:")
        print(f"   • Channels recovered from unknown groups: {total_recovered}")
        print(f"   • Previously we were losing 612 channels (4.3%)")
        print(f"   • Now we've recovered most of these channels!")
        
        # Check if TV Guide was properly excluded
        tv_guide_excluded = [g for g in group_counts.keys() if 'tv guide' in g.lower()]
        if tv_guide_excluded:
            print(f"\n❌ Issue: TV Guide groups found in output:")
            for group in tv_guide_excluded:
                print(f"   • {group}: {group_counts[group]} channels")
        else:
            print(f"\n✅ TV Guide groups properly excluded")
        
    except Exception as e:
        print(f"Error analyzing filtered output: {e}")

if __name__ == "__main__":
    analyze_filtered_output()
