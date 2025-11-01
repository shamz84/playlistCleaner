#!/usr/bin/env python3
"""
Script to check if logo URLs in tv_logos.json return 200 (exist) or 404 (not found)
"""

import json
import requests
import time
from urllib.parse import urlparse

def check_url_exists(url, timeout=10):
    """Check if a URL returns 200 status code"""
    try:
        response = requests.head(url, timeout=timeout, allow_redirects=True)
        return response.status_code == 200, response.status_code
    except requests.exceptions.RequestException as e:
        return False, f"Error: {str(e)}"

def check_logo_urls(input_file, output_file=None):
    """Check all logo URLs and report which ones exist"""
    
    # Read the JSON file
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    # Track statistics
    total_count = len(data)
    exists_count = 0
    not_found_count = 0
    error_count = 0
    
    print(f"🚀 Checking {total_count:,} logo URLs...")
    print(f"📋 This may take a while - checking each URL...")
    
    # Results storage
    results = []
    missing_logos = []
    error_logos = []
    
    # Process each entry
    for i, entry in enumerate(data, 1):
        logo = entry.get('logo', 'Unknown')
        url = entry.get('url', '')
        
        if not url:
            continue
            
        # Show progress every 50 items
        if i % 50 == 0 or i == 1:
            print(f"📊 Progress: {i:,}/{total_count:,} ({i/total_count*100:.1f}%)")
        
        # Check if URL exists
        exists, status = check_url_exists(url)
        
        result = {
            'logo': logo,
            'url': url,
            'exists': exists,
            'status': status
        }
        results.append(result)
        
        if exists:
            exists_count += 1
        elif isinstance(status, int) and status == 404:
            not_found_count += 1
            missing_logos.append({'logo': logo, 'url': url})
        else:
            error_count += 1
            error_logos.append({'logo': logo, 'url': url, 'error': status})
        
        # Small delay to be respectful to GitHub
        time.sleep(0.1)
    
    # Print summary
    print(f"\n✅ URL Check Complete!")
    print(f"📊 Results Summary:")
    print(f"   Total URLs checked: {total_count:,}")
    print(f"   ✅ Found (200): {exists_count:,} ({exists_count/total_count*100:.1f}%)")
    print(f"   ❌ Not Found (404): {not_found_count:,} ({not_found_count/total_count*100:.1f}%)")
    print(f"   ⚠️  Errors: {error_count:,} ({error_count/total_count*100:.1f}%)")
    
    # Show some missing logos
    if missing_logos:
        print(f"\n❌ First 10 missing logos (404):")
        for i, item in enumerate(missing_logos[:10], 1):
            print(f"   {i}. {item['logo']}")
        if len(missing_logos) > 10:
            print(f"   ... and {len(missing_logos) - 10} more")
    
    # Show some errors
    if error_logos:
        print(f"\n⚠️  First 5 error cases:")
        for i, item in enumerate(error_logos[:5], 1):
            print(f"   {i}. {item['logo']}: {item['error']}")
        if len(error_logos) > 5:
            print(f"   ... and {len(error_logos) - 5} more")
    
    # Save detailed results if output file specified
    if output_file:
        detailed_results = {
            'summary': {
                'total_count': total_count,
                'exists_count': exists_count,
                'not_found_count': not_found_count,
                'error_count': error_count,
                'check_date': time.strftime('%Y-%m-%d %H:%M:%S')
            },
            'missing_logos': missing_logos,
            'error_logos': error_logos,
            'all_results': results
        }
        
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(detailed_results, f, indent=2, ensure_ascii=False)
        print(f"\n📁 Detailed results saved to: {output_file}")
    
    return {
        'exists_count': exists_count,
        'not_found_count': not_found_count,
        'error_count': error_count,
        'missing_logos': missing_logos
    }

if __name__ == "__main__":
    input_file = "tv_logos.json"
    output_file = "logo_check_results.json"
    
    print("🔍 Logo URL Checker")
    print("=" * 50)
    print(f"📋 Input file: {input_file}")
    print(f"📊 Will save detailed results to: {output_file}")
    print(f"⏱️  This will take several minutes due to rate limiting...")
    
    try:
        results = check_logo_urls(input_file, output_file)
        
        print(f"\n🎯 Final Summary:")
        print(f"   Found: {results['exists_count']:,}")
        print(f"   Missing: {results['not_found_count']:,}")
        print(f"   Errors: {results['error_count']:,}")
        
    except Exception as e:
        print(f"❌ Error: {e}")