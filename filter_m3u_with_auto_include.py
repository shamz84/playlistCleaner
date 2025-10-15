#!/usr/bin/env python3
"""
Enhanced M3U playlist filter that automatically includes unknown groups,
but excludes them if they match patterns of groups marked exclude=true.
"""

import json
import re
import os
from collections import Counter

def find_config_file(filename):
    """Find config file using config-first approach (like container)"""
    config_path = f"data/config/{filename}"
    root_path = filename
    
    if os.path.exists(config_path):
        return config_path
    elif os.path.exists(root_path):
        return root_path
    else:
        return filename  # Return filename for error reporting

def load_group_configuration():
    """Load group titles configuration with include/exclude lists and overrides."""
    try:
        config_file = find_config_file('group_titles_with_flags.json')
        with open(config_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        include_groups = set()  # exclude=false
        exclude_groups = set()  # exclude=true
        all_groups = {}
        group_overrides = {}  # original_title -> override_title
        
        for entry in data:
            group_title = entry.get('group_title')
            exclude_flag = entry.get('exclude')
            override_title = entry.get('override_title')
            
            # Store override mapping if specified
            if override_title and override_title != group_title:
                group_overrides[group_title] = override_title
                # Use the override title for all subsequent processing
                effective_title = override_title
            else:
                effective_title = group_title
            
            all_groups[effective_title] = {
                'original_title': group_title,
                'exclude': exclude_flag,
                'order': entry.get('order'),
                'channel_count': entry.get('channel_count', 0)
            }
            
            if exclude_flag == 'false':
                include_groups.add(effective_title)
            else:
                exclude_groups.add(effective_title)
        
        # Show override summary if any were found
        if group_overrides:
            print(f"\n🏷️  Group Title Overrides: {len(group_overrides)} configured")
            for i, (original, override) in enumerate(list(group_overrides.items())[:5]):
                print(f"   '{original}' → '{override}'")
            if len(group_overrides) > 5:
                print(f"   ... and {len(group_overrides) - 5} more")
        
        return include_groups, exclude_groups, all_groups, group_overrides
    except Exception as e:
        print(f"Error loading group titles: {e}")
        return set(), set(), {}, {}

def analyze_unknown_groups(playlist_file, all_known_groups):
    """Find unknown groups in the playlist."""
    try:
        with open(playlist_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Extract all group titles
        groups = re.findall(r'group-title="([^"]+)"', content)
        group_counts = Counter(groups)
        
        # Find unknown groups
        playlist_groups = set(group_counts.keys())
        unknown_groups = playlist_groups - all_known_groups
        
        return {group: group_counts[group] for group in unknown_groups}
    except Exception as e:
        print(f"Error analyzing unknown groups: {e}")
        return {}

def should_exclude_unknown_group(unknown_group, excluded_groups):
    """
    Determine if an unknown group should be excluded based on 
    pattern matching with existing excluded groups.
    """
    # Extract country/region prefix (e.g., "AU|", "CA|", "UK|", "US|")
    unknown_prefix_match = re.match(r'^([A-Z]{2}\|)', unknown_group)
    if not unknown_prefix_match:
        # If no country prefix, check against excluded patterns more broadly
        unknown_lower = unknown_group.lower()
        for excluded_group in excluded_groups:
            excluded_lower = excluded_group.lower()
            # Check for similar content types
            if any(keyword in unknown_lower and keyword in excluded_lower 
                   for keyword in ['guide', 'adult', 'xxx', 'network', 'affiliates']):
                return True, f"Matches excluded content pattern: '{excluded_group}'"
        return False, "No matching excluded patterns found"
    
    unknown_prefix = unknown_prefix_match.group(1)
    unknown_lower = unknown_group.lower()
    
    # Check if we have excluded groups with the same prefix
    matching_excluded = [g for g in excluded_groups if g.startswith(unknown_prefix)]
    
    if not matching_excluded:
        return False, f"No excluded groups found with prefix '{unknown_prefix}'"
    
    # Pattern matching for content types that are commonly excluded
    exclusion_patterns = {
        'adult': ['adult', 'xxx', 'for adults'],
        'local_networks': ['network', 'affiliates', 'local'],
        'guides': ['guide', 'tv guide'],
        'low_quality': [' sd', 'sd '],
        'specific_sports': ['nfl', 'nba', 'nhl', 'mlb'],
        'hevc': ['hevc', 'h265']
    }
    
    for pattern_type, keywords in exclusion_patterns.items():
        if any(keyword in unknown_lower for keyword in keywords):
            # Check if we have similar excluded content with same prefix
            similar_excluded = [g for g in matching_excluded 
                              if any(keyword in g.lower() for keyword in keywords)]
            if similar_excluded:
                return True, f"Matches excluded {pattern_type} pattern: {similar_excluded[:2]}"
    
    return False, f"No matching exclusion patterns found for prefix '{unknown_prefix}'"

def filter_m3u_playlist_with_unknown_inclusion(input_file, output_file, include_groups, exclude_groups, group_overrides=None, auto_include_unknown=True):
    """Enhanced filter that includes unknown groups by default, but excludes pattern matches.
    Also sorts output by group order from configuration.
    Now includes group title override functionality."""
    
    if group_overrides is None:
        group_overrides = {}
    
    if not os.path.exists(input_file):
        print(f"❌ Error: Input file {input_file} not found!")
        return False
    
    print(f"📁 Loading playlist from {input_file}...")
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"❌ Error reading input file: {e}")
        return False

    # Load group configuration for ordering
    config_file = find_config_file('group_titles_with_flags.json')
    group_order_map = {}
    try:
        with open(config_file, 'r', encoding='utf-8') as f:
            config_data = json.load(f)
            for entry in config_data:
                # Use override title if available, otherwise use original title
                effective_title = entry.get('override_title', entry['group_title'])
                group_order_map[effective_title] = entry.get('order', 9999)
    except Exception as e:
        print(f"⚠️  Warning: Could not load group order configuration: {e}")
    
    # Analyze unknown groups
    all_known_groups = include_groups | exclude_groups
    unknown_groups = analyze_unknown_groups(input_file, all_known_groups)
    
    auto_included_groups = set()
    auto_excluded_groups = set()
    
    if auto_include_unknown and unknown_groups:
        print(f"\n🔍 Analyzing {len(unknown_groups)} unknown groups...")
        
        for group, count in unknown_groups.items():
            should_exclude, reason = should_exclude_unknown_group(group, exclude_groups)
            if should_exclude:
                auto_excluded_groups.add(group)
                print(f"  ❌ AUTO-EXCLUDE: '{group}' ({count} channels) - {reason}")
            else:
                auto_included_groups.add(group)
                print(f"  ✅ AUTO-INCLUDE: '{group}' ({count} channels) - {reason}")
    
    # Final groups to include
    final_include_groups = include_groups | auto_included_groups
    
    print(f"\n📊 Filtering Summary:")
    print(f"  • Known groups to include: {len(include_groups)}")
    print(f"  • Unknown groups auto-included: {len(auto_included_groups)}")
    print(f"  • Unknown groups auto-excluded: {len(auto_excluded_groups)}")
    print(f"  • Total groups to include: {len(final_include_groups)}")
    
    # Process the M3U file - collect channels by group for sorting
    channels_by_group = {}  # group_title -> [(extinf_line, url_line), ...]
    total_entries = 0
    included_entries = 0
    auto_included_entries = 0
    excluded_entries = 0
    header_lines = []
    
    # Add M3U header
    if lines and lines[0].strip() == '#EXTM3U':
        header_lines.append(lines[0])
        i = 1
    else:
        header_lines.append('#EXTM3U\n')
        i = 0
    
    # Process entries and group them
    while i < len(lines):
        line = lines[i].strip()
        
        # Look for EXTINF lines
        if line.startswith('#EXTINF:'):
            total_entries += 1
            
            # Extract group-title using regex
            group_match = re.search(r'group-title="([^"]*)"', line)
            
            if group_match:
                original_group_title = group_match.group(1)
                
                # Apply group title override if configured
                if original_group_title in group_overrides:
                    override_title = group_overrides[original_group_title]
                    # Replace the group title in the line
                    line = re.sub(r'group-title="[^"]*"', f'group-title="{override_title}"', line)
                    group_title = override_title
                else:
                    group_title = original_group_title
                
                # Check if this group should be included
                if group_title in final_include_groups:
                    # Collect this entry by group for later sorting
                    if group_title not in channels_by_group:
                        channels_by_group[group_title] = []
                    
                    # Add the channel (EXTINF line + URL line) - use the modified line
                    if i + 1 < len(lines):
                        channels_by_group[group_title].append((line + '\n', lines[i + 1]))
                        included_entries += 1
                        
                        # Track if this was auto-included
                        if group_title in auto_included_groups:
                            auto_included_entries += 1
                    
                    i += 2
                else:
                    # Skip this entry
                    excluded_entries += 1
                    i += 2
            else:
                # No group-title found, skip
                excluded_entries += 1
                i += 2
        else:
            i += 1
    
    # Sort groups by order and write filtered playlist
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            # Write header
            f.writelines(header_lines)
            
            # Sort groups by their configured order
            sorted_groups = sorted(channels_by_group.keys(), 
                                 key=lambda group: group_order_map.get(group, 9999))
            
            print(f"\n📋 Group Order (first 10):")
            for i, group in enumerate(sorted_groups[:10]):
                order = group_order_map.get(group, 'Unknown')
                count = len(channels_by_group[group])
                print(f"  {i+1:2d}. {group} (order: {order}, channels: {count})")
            
            if len(sorted_groups) > 10:
                print(f"      ... and {len(sorted_groups) - 10} more groups")
            
            # Write channels in group order
            for group in sorted_groups:
                for extinf_line, url_line in channels_by_group[group]:
                    f.write(extinf_line)
                    f.write(url_line)
        
        print(f"\n✅ Successfully created filtered playlist: {output_file}")
        print(f"\n📊 Results:")
        print(f"  • Total entries processed: {total_entries}")
        print(f"  • Entries included (known groups): {included_entries - auto_included_entries}")
        print(f"  • Entries included (auto-included unknown): {auto_included_entries}")
        print(f"  • Total entries included: {included_entries}")
        print(f"  • Entries excluded: {excluded_entries}")
        print(f"  • Inclusion rate: {included_entries/total_entries*100:.1f}%")
        print(f"  • Groups in output: {len(sorted_groups)} (sorted by order)")
        
        if auto_included_groups:
            print(f"\n✅ Auto-included unknown groups:")
            for group in sorted(auto_included_groups):
                count = unknown_groups.get(group, 0)
                print(f"  • {group} ({count} channels)")
        
        if auto_excluded_groups:
            print(f"\n🚫 Auto-excluded unknown groups:")
            for group in sorted(auto_excluded_groups):
                count = unknown_groups.get(group, 0)
                print(f"  • {group} ({count} channels)")
        
        return True
        
    except Exception as e:
        print(f"❌ Error writing output file: {e}")
        return False

def update_config_with_new_groups(auto_included_groups, auto_excluded_groups, unknown_groups_info):
    """Optionally update the configuration file with the new groups found."""
    if not auto_included_groups and not auto_excluded_groups:
        return
    
    try:
        # Load current config
        with open('group_titles_with_flags.json', 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        # Find max order
        max_order = max([entry.get('order', 0) for entry in config]) if config else 1000
        
        # Create new entries
        new_entries = []
        order_counter = 1
        
        # Add auto-included groups
        for group in sorted(auto_included_groups):
            new_entry = {
                "group_title": group,
                "channel_count": unknown_groups_info.get(group, 0),
                "exclude": "false",
                "order": max_order + order_counter
            }
            new_entries.append(new_entry)
            order_counter += 1
        
        # Add auto-excluded groups  
        for group in sorted(auto_excluded_groups):
            new_entry = {
                "group_title": group,
                "channel_count": unknown_groups_info.get(group, 0),
                "exclude": "true",
                "order": max_order + order_counter
            }
            new_entries.append(new_entry)
            order_counter += 1
        
        # Save as a new config file for review
        new_config = config + new_entries
        backup_file = 'group_titles_with_flags_updated.json'
        
        with open(backup_file, 'w', encoding='utf-8') as f:
            json.dump(new_config, f, indent=2, ensure_ascii=False)
        
        print(f"\n💾 Updated configuration saved to: {backup_file}")
        print(f"   Added {len(new_entries)} new groups")
        print(f"   Review and replace group_titles_with_flags.json if satisfied")
        
    except Exception as e:
        print(f"⚠️  Error creating updated config: {e}")

def main():
    input_file = "data/downloaded_file.m3u"
    output_file = "filtered_playlist_final.m3u"
    
    print("🚀 ENHANCED M3U PLAYLIST FILTER")
    print("=" * 60)
    print("Strategy: Include unknown groups by default,")
    print("          but exclude those matching excluded patterns")
    print("=" * 60)
    
    # Load configuration
    print("📋 Loading group configuration...")
    include_groups, exclude_groups, all_groups, group_overrides = load_group_configuration()
    
    if not include_groups and not exclude_groups:
        print("❌ No groups found in configuration!")
        return
    
    print(f"✅ Configuration loaded:")
    print(f"  • Groups to include (exclude=false): {len(include_groups)}")
    print(f"  • Groups to exclude (exclude=true): {len(exclude_groups)}")
    if group_overrides:
        print(f"  • Group title overrides: {len(group_overrides)}")
    
    # Check input file
    if not os.path.exists(input_file):
        print(f"❌ Input file not found: {input_file}")
        return
    
    # Run enhanced filtering
    success = filter_m3u_playlist_with_unknown_inclusion(
        input_file, output_file, include_groups, exclude_groups, group_overrides, auto_include_unknown=True
    )
    
    if success:
        print(f"\n✅ Enhanced filtering completed successfully!")
        print(f"📁 Output saved to: {output_file}")
        
        # Ask about updating config
        print(f"\n💡 To make these changes permanent, review and replace:")
        print(f"   group_titles_with_flags.json with group_titles_with_flags_updated.json")
    else:
        print(f"\n❌ Filtering failed!")

if __name__ == "__main__":
    main()
