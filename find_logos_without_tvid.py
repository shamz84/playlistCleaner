#!/usr/bin/env python3
"""
Script to find logos in tv_logos.json that don't have tvid set
"""

import json

def find_logos_without_tvid(input_file):
    """Find all logos that don't have tvid set"""
    
    # Read the JSON file
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    # Track statistics
    total_count = len(data)
    no_tvid_count = 0
    has_tvid_count = 0
    
    # Lists to store results
    no_tvid_logos = []
    
    # Process each entry
    for entry in data:
        logo = entry.get('logo', 'Unknown')
        tvid = entry.get('tvid', '')
        url = entry.get('url', '')
        
        # Check if tvid is empty (empty string, empty list, or None)
        has_tvid = False
        if tvid:
            if isinstance(tvid, str) and tvid.strip():
                has_tvid = True
            elif isinstance(tvid, list) and len(tvid) > 0:
                # Check if list has any non-empty strings
                has_tvid = any(item.strip() for item in tvid if isinstance(item, str))
        
        if has_tvid:
            has_tvid_count += 1
        else:
            no_tvid_count += 1
            no_tvid_logos.append({
                'logo': logo,
                'tvid': tvid,
                'url': url
            })
    
    # Print summary
    print(f"📊 TV ID Analysis Results:")
    print(f"   Total logos: {total_count:,}")
    print(f"   ✅ Has TV ID: {has_tvid_count:,} ({has_tvid_count/total_count*100:.1f}%)")
    print(f"   ❌ No TV ID: {no_tvid_count:,} ({no_tvid_count/total_count*100:.1f}%)")
    
    # Show all logos without tvid
    if no_tvid_logos:
        print(f"\n❌ Logos without TV ID ({no_tvid_count:,} total):")
        print("=" * 60)
        
        for i, item in enumerate(no_tvid_logos, 1):
            tvid_display = item['tvid'] if item['tvid'] else "empty"
            print(f"{i:3d}. {item['logo']}")
            if i % 50 == 0 and i < len(no_tvid_logos):
                print(f"    ... showing {i}/{len(no_tvid_logos)} ...")
    
    # Save detailed results
    output_file = "logos_without_tvid.json"
    results = {
        'summary': {
            'total_count': total_count,
            'has_tvid_count': has_tvid_count,
            'no_tvid_count': no_tvid_count,
            'percentage_without_tvid': no_tvid_count/total_count*100
        },
        'logos_without_tvid': no_tvid_logos
    }
    
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    
    print(f"\n📁 Detailed results saved to: {output_file}")
    
    return results

if __name__ == "__main__":
    input_file = "tv_logos.json"
    
    print("🔍 TV ID Analysis")
    print("=" * 50)
    print(f"📋 Analyzing: {input_file}")
    
    try:
        results = find_logos_without_tvid(input_file)
        
        print(f"\n🎯 Quick Summary:")
        print(f"   {results['summary']['no_tvid_count']:,} logos need TV IDs")
        print(f"   {results['summary']['has_tvid_count']:,} logos already have TV IDs")
        
    except Exception as e:
        print(f"❌ Error: {e}")