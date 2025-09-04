#!/usr/bin/env python3
"""
Simple test version of the enhanced filter to verify it works
"""

import json
import re
import os
from collections import Counter

def test_enhanced_filter():
    print("🧪 Testing Enhanced Filter Logic")
    print("=" * 40)
    
    # Check if files exist
    config_file = 'group_titles_with_flags.json'
    playlist_file = 'data/downloaded_file.m3u'
    
    if not os.path.exists(config_file):
        print(f"❌ Config file not found: {config_file}")
        return
    
    if not os.path.exists(playlist_file):
        print(f"❌ Playlist file not found: {playlist_file}")
        return
    
    print(f"✅ Files found")
    
    # Load config
    with open(config_file, 'r', encoding='utf-8') as f:
        config = json.load(f)
    
    include_groups = set()
    exclude_groups = set()
    
    for entry in config:
        if entry.get('exclude') == 'false':
            include_groups.add(entry.get('group_title'))
        else:
            exclude_groups.add(entry.get('group_title'))
    
    print(f"📊 Config Analysis:")
    print(f"  • Include groups: {len(include_groups)}")
    print(f"  • Exclude groups: {len(exclude_groups)}")
    
    # Analyze playlist
    with open(playlist_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    groups = re.findall(r'group-title="([^"]+)"', content)
    group_counts = Counter(groups)
    
    all_known = include_groups | exclude_groups
    unknown_groups = set(group_counts.keys()) - all_known
    
    print(f"📊 Playlist Analysis:")
    print(f"  • Total unique groups: {len(group_counts)}")
    print(f"  • Unknown groups: {len(unknown_groups)}")
    
    if unknown_groups:
        print(f"\n🔍 Unknown Groups Found:")
        for group in sorted(unknown_groups):
            count = group_counts[group]
            print(f"  • {group}: {count} channels")
    
    # Test the pattern matching logic
    auto_excluded = []
    auto_included = []
    
    for group in unknown_groups:
        # Simple exclusion logic based on common patterns
        group_lower = group.lower()
        should_exclude = False
        
        # Check for patterns that typically get excluded
        exclude_patterns = [
            'tv guide',
            'network',
            'affiliates', 
            'adult',
            'xxx',
            ' sd',
            'hevc'
        ]
        
        for pattern in exclude_patterns:
            if pattern in group_lower:
                # Check if similar patterns exist in exclude_groups
                similar_excluded = [g for g in exclude_groups if pattern in g.lower()]
                if similar_excluded:
                    should_exclude = True
                    break
        
        if should_exclude:
            auto_excluded.append(group)
        else:
            auto_included.append(group)
    
    print(f"\n🤖 Auto-Classification Results:")
    print(f"  • Would auto-include: {len(auto_included)}")
    print(f"  • Would auto-exclude: {len(auto_excluded)}")
    
    if auto_included:
        print(f"\n✅ Auto-Include:")
        for group in auto_included:
            count = group_counts[group]
            print(f"  • {group}: {count} channels")
    
    if auto_excluded:
        print(f"\n❌ Auto-Exclude:")
        for group in auto_excluded:
            count = group_counts[group]
            print(f"  • {group}: {count} channels")
    
    # Calculate impact
    total_channels = sum(group_counts.values())
    auto_include_channels = sum(group_counts[g] for g in auto_included)
    auto_exclude_channels = sum(group_counts[g] for g in auto_excluded)
    
    print(f"\n📈 Impact Analysis:")
    print(f"  • Additional channels included: {auto_include_channels}")
    print(f"  • Additional channels excluded: {auto_exclude_channels}")
    print(f"  • Net change: +{auto_include_channels - auto_exclude_channels} channels")

if __name__ == "__main__":
    test_enhanced_filter()
