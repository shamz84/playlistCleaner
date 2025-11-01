#!/usr/bin/env python3
"""
Fast script to check if logo URLs in tv_logos.json return 200 (exist) or 404 (not found)
Uses concurrent requests with rate limiting
"""

import json
import requests
import time
import concurrent.futures
from threading import Lock

# Global counters with thread safety
stats_lock = Lock()
stats = {
    'checked': 0,
    'exists': 0,
    'not_found': 0,
    'errors': 0
}

def check_url_exists(url, timeout=10):
    """Check if a URL returns 200 status code"""
    try:
        response = requests.head(url, timeout=timeout, allow_redirects=True)
        return response.status_code == 200, response.status_code
    except requests.exceptions.RequestException as e:
        return False, f"Error: {str(e)[:50]}"

def check_single_logo(entry):
    """Check a single logo URL and update stats"""
    logo = entry.get('logo', 'Unknown')
    url = entry.get('url', '')
    
    if not url:
        return None
    
    exists, status = check_url_exists(url)
    
    # Update global stats thread-safely
    with stats_lock:
        stats['checked'] += 1
        if exists:
            stats['exists'] += 1
        elif isinstance(status, int) and status == 404:
            stats['not_found'] += 1
        else:
            stats['errors'] += 1
        
        # Show progress every 25 items
        if stats['checked'] % 25 == 0:
            total = stats['checked']
            print(f"📊 Progress: {total:,} checked - ✅ {stats['exists']} found, ❌ {stats['not_found']} missing, ⚠️ {stats['errors']} errors")
    
    return {
        'logo': logo,
        'url': url,
        'exists': exists,
        'status': status
    }

def check_logo_urls_fast(input_file, output_file=None, max_workers=10):
    """Check all logo URLs using concurrent requests"""
    
    # Read the JSON file
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    total_count = len(data)
    print(f"🚀 Checking {total_count:,} logo URLs using {max_workers} concurrent threads...")
    
    results = []
    missing_logos = []
    error_logos = []
    
    start_time = time.time()
    
    # Use ThreadPoolExecutor for concurrent requests
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        # Submit all tasks
        future_to_entry = {executor.submit(check_single_logo, entry): entry for entry in data}
        
        # Collect results as they complete
        for future in concurrent.futures.as_completed(future_to_entry):
            result = future.result()
            if result:
                results.append(result)
                
                if not result['exists']:
                    if isinstance(result['status'], int) and result['status'] == 404:
                        missing_logos.append({'logo': result['logo'], 'url': result['url']})
                    else:
                        error_logos.append({'logo': result['logo'], 'url': result['url'], 'error': result['status']})
    
    end_time = time.time()
    duration = end_time - start_time
    
    # Final summary
    exists_count = stats['exists']
    not_found_count = stats['not_found']
    error_count = stats['errors']
    
    print(f"\n✅ URL Check Complete!")
    print(f"⏱️  Total time: {duration:.1f} seconds")
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
    
    # Save detailed results
    if output_file:
        detailed_results = {
            'summary': {
                'total_count': total_count,
                'exists_count': exists_count,
                'not_found_count': not_found_count,
                'error_count': error_count,
                'check_duration_seconds': duration,
                'check_date': time.strftime('%Y-%m-%d %H:%M:%S')
            },
            'missing_logos': missing_logos,
            'error_logos': error_logos,
            'all_results': sorted(results, key=lambda x: x['logo'].lower())
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
    
    print("🔍 Fast Logo URL Checker")
    print("=" * 50)
    print(f"📋 Input file: {input_file}")
    print(f"📊 Will save detailed results to: {output_file}")
    
    try:
        results = check_logo_urls_fast(input_file, output_file, max_workers=15)
        
        print(f"\n🎯 Final Summary:")
        print(f"   Found: {results['exists_count']:,}")
        print(f"   Missing: {results['not_found_count']:,}")
        print(f"   Errors: {results['error_count']:,}")
        
    except Exception as e:
        print(f"❌ Error: {e}")