#!/usr/bin/env python3
"""
Script to analyze and summarize the populated tv_logos.json file.
"""

import json
from collections import Counter

def analyze_tv_logos_json(json_file_path):
    """Analyze the tv_logos.json file and provide statistics."""
    
    try:
        with open(json_file_path, 'r', encoding='utf-8') as f:
            tv_logos = json.load(f)
    except Exception as e:
        print(f"Error reading JSON file: {e}")
        return
    
    stats = {
        'total_entries': len(tv_logos),
        'populated_tvid': 0,
        'empty_tvid': 0,
        'single_tvid': 0,
        'multiple_tvid': 0,
        'populated_url': 0,
        'empty_url': 0
    }
    
    tvid_counts = Counter()
    domain_counts = Counter()
    
    for entry in tv_logos:
        # Analyze tvid field
        if entry['tvid']:
            stats['populated_tvid'] += 1
            if isinstance(entry['tvid'], list):
                stats['multiple_tvid'] += 1
                for tvid in entry['tvid']:
                    tvid_counts[tvid] += 1
                    if '.' in tvid:
                        domain = tvid.split('.')[-1]
                        domain_counts[domain] += 1
            else:
                stats['single_tvid'] += 1
                tvid_counts[entry['tvid']] += 1
                if '.' in entry['tvid']:
                    domain = entry['tvid'].split('.')[-1]
                    domain_counts[domain] += 1
        else:
            stats['empty_tvid'] += 1
        
        # Analyze url field
        if entry['url']:
            stats['populated_url'] += 1
        else:
            stats['empty_url'] += 1
    
    # Print comprehensive analysis
    print("=== TV Logos JSON Analysis ===")
    print(f"Total entries: {stats['total_entries']}")
    print(f"Entries with tvid populated: {stats['populated_tvid']} ({stats['populated_tvid']/stats['total_entries']*100:.1f}%)")
    print(f"Entries with empty tvid: {stats['empty_tvid']} ({stats['empty_tvid']/stats['total_entries']*100:.1f}%)")
    print(f"Entries with single tvid: {stats['single_tvid']}")
    print(f"Entries with multiple tvid: {stats['multiple_tvid']}")
    print(f"Entries with url populated: {stats['populated_url']}")
    print(f"Entries with empty url: {stats['empty_url']}")
    
    print(f"\n=== Top 20 Most Common tvid Values ===")
    for tvid, count in tvid_counts.most_common(20):
        print(f"{tvid}: {count} occurrences")
    
    print(f"\n=== Top Domains ===")
    for domain, count in domain_counts.most_common(10):
        print(f".{domain}: {count} occurrences")
    
    print(f"\n=== Sample Entries with Multiple tvid Values ===")
    count = 0
    for entry in tv_logos:
        if isinstance(entry['tvid'], list) and len(entry['tvid']) > 1:
            print(f"Logo: {entry['logo']}")
            print(f"  tvid: {entry['tvid']}")
            count += 1
            if count >= 5:
                break
    
    print(f"\n=== Sample Entries with Single tvid Values ===")
    count = 0
    for entry in tv_logos:
        if entry['tvid'] and not isinstance(entry['tvid'], list):
            print(f"Logo: {entry['logo']} -> tvid: {entry['tvid']}")
            count += 1
            if count >= 5:
                break
    
    print(f"\n=== Unpopulated Entries (Sample) ===")
    count = 0
    for entry in tv_logos:
        if not entry['tvid']:
            print(f"Logo: {entry['logo']} (no tvid found)")
            count += 1
            if count >= 10:
                break

if __name__ == "__main__":
    import sys
    
    json_file = "tv_logos.json" if len(sys.argv) == 1 else sys.argv[1]
    analyze_tv_logos_json(json_file)