#!/usr/bin/env python3
"""
UK TV Guide Override Script - Dynamic Version
Allows replacing complete UK TV Guide entries with other entries from the playlist
Uses group-title and channel name as identifiers for robust matching
"""

import re
import sys
import os
from typing import Dict, List, Tuple

# Fix Unicode encoding issues on Windows
if sys.platform.startswith('win'):
    try:
        import codecs
        sys.stdout = codecs.getwriter('utf-8')(sys.stdout.buffer, 'replace')
        sys.stderr = codecs.getwriter('utf-8')(sys.stderr.buffer, 'replace')
    except:
        pass  # Fallback to default behavior

class PlaylistEntry:
    def __init__(self, extinf_line, url_line):
        self.extinf_line = extinf_line
        self.url_line = url_line
        self.cuid = self._extract_attribute('CUID')
        self.tvg_id = self._extract_attribute('tvg-id')
        self.tvg_name = self._extract_attribute('tvg-name')
        self.tvg_logo = self._extract_attribute('tvg-logo')
        self.group_title = self._extract_attribute('group-title')
        self.channel_name = self._extract_channel_name()
        
        # Create composite identifier using group-title and channel name
        self.group_channel_id = f"{self.group_title}||{self.channel_name}" if self.group_title and self.channel_name else None
    
    def _extract_attribute(self, attr_name):
        """Extract attribute value from EXTINF line"""
        pattern = rf'{attr_name}="([^"]*)"'
        match = re.search(pattern, self.extinf_line)
        return match.group(1) if match else None
    
    def _extract_channel_name(self):
        """Extract channel name (text after the last comma)"""
        parts = self.extinf_line.split(',')
        return parts[-1].strip() if len(parts) > 1 else None

class UKTVOverrideProcessor:
    def __init__(self, config_file: str = "uk_tv_overrides_dynamic.conf"):
        self.config_file = config_file
        self.overrides: Dict[str, str] = {}  # source_group_channel -> replacement_group_channel
        self.uk_group_title = "🇬🇧 TV Guide (UK)"
        self.playlist_entries: Dict[str, PlaylistEntry] = {}  # group_channel_id -> PlaylistEntry
        
    def load_overrides(self) -> Dict[str, str]:
        """Load override configuration from file"""
        overrides = {}
        
        if not os.path.exists(self.config_file):
            print(f"Config file {self.config_file} not found.")
            return overrides
        
        try:
            with open(self.config_file, 'r', encoding='utf-8') as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()
                    
                    # Skip comments and empty lines
                    if not line or line.startswith('#'):
                        continue
                    
                    # Parse source_channel = replacement_channel format
                    if '=' in line:
                        source_channel, replacement_spec = line.split('=', 1)
                        source_channel = source_channel.strip()
                        replacement_spec = replacement_spec.strip()
                        
                        if source_channel and replacement_spec:
                            # Create full group+channel identifier for source
                            source_id = f"{self.uk_group_title}||{source_channel}"
                            overrides[source_id] = replacement_spec
                            print(f"Loaded override: {source_channel} -> {replacement_spec}")
                        else:
                            print(f"Warning: Invalid override format on line {line_num}: {line}")
                    else:
                        print(f"Warning: Invalid override format on line {line_num}: {line}")
                        
        except Exception as e:
            print(f"Error reading config file {self.config_file}: {e}")
            
        return overrides
    
    def build_playlist_index(self, m3u_content: str) -> None:
        """Build index of all playlist entries by group_channel_id"""
        lines = m3u_content.strip().split('\n')
        i = 0
        
        while i < len(lines):
            line = lines[i].strip()
            
            if line.startswith('#EXTINF:'):
                # Get the next line as URL
                if i + 1 < len(lines):
                    url_line = lines[i + 1].strip()
                    entry = PlaylistEntry(line, url_line)
                    
                    if entry.group_channel_id:
                        self.playlist_entries[entry.group_channel_id] = entry
                        
                    i += 2  # Skip both EXTINF and URL lines
                else:
                    i += 1
            else:
                i += 1
    
    def find_replacement_entry(self, replacement_spec: str) -> PlaylistEntry:
        """Find replacement entry by group+channel specification"""
        # Parse replacement_spec: could be "group-title||channel-name" or just "channel-name"
        if '||' in replacement_spec:
            # Full specification with group-title
            target_id = replacement_spec
        else:
            # Just channel name - search all groups for matching channel name
            target_channel = replacement_spec
            for group_channel_id, entry in self.playlist_entries.items():
                if entry.channel_name == target_channel:
                    return entry
            return None
        
        return self.playlist_entries.get(target_id)
    
    def list_uk_tv_entries(self, m3u_file: str) -> None:
        """List all UK TV Guide entries"""
        try:
            with open(m3u_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            self.build_playlist_index(content)
            
            print(f"\nUK TV Guide entries found in {m3u_file}:")
            print("=" * 60)
            
            uk_entries = [entry for group_id, entry in self.playlist_entries.items() 
                         if entry.group_title == self.uk_group_title]
            
            if not uk_entries:
                print("No UK TV Guide entries found.")
                return
            
            for entry in sorted(uk_entries, key=lambda x: x.channel_name):
                print(f"Channel: {entry.channel_name}")
                print(f"  TVG-ID: {entry.tvg_id}")
                print(f"  TVG-Name: {entry.tvg_name}")
                print(f"  Group: {entry.group_title}")
                print()
                
        except Exception as e:
            print(f"Error reading playlist file: {e}")
    
    def find_channel(self, m3u_file: str, search_term: str) -> None:
        """Find channels matching search term"""
        try:
            with open(m3u_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            self.build_playlist_index(content)
            
            print(f"\nSearching for channels matching '{search_term}':")
            print("=" * 60)
            
            matches = []
            search_lower = search_term.lower()
            
            for entry in self.playlist_entries.values():
                if (search_lower in entry.channel_name.lower() or 
                    (entry.tvg_name and search_lower in entry.tvg_name.lower())):
                    matches.append(entry)
            
            if not matches:
                print("No matching channels found.")
                return
            
            for entry in sorted(matches, key=lambda x: (x.group_title, x.channel_name)):
                print(f"Channel: {entry.channel_name}")
                print(f"  TVG-ID: {entry.tvg_id}")
                print(f"  TVG-Name: {entry.tvg_name}")
                print(f"  Group: {entry.group_title}")
                print(f"  Identifier: {entry.group_title}||{entry.channel_name}")
                print()
                
        except Exception as e:
            print(f"Error reading playlist file: {e}")
    
    def process_playlist(self, input_file: str, output_file: str) -> None:
        """Process M3U playlist and apply overrides"""
        self.overrides = self.load_overrides()
        
        if not self.overrides:
            print("No overrides configured. Nothing to do.")
            return
        
        try:
            with open(input_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # Build index of all entries
            self.build_playlist_index(content)
            print(f"Indexed {len(self.playlist_entries)} playlist entries")
            
            # Process the playlist line by line
            lines = content.strip().split('\n')
            output_lines = []
            replacements_made = 0
            i = 0
            
            while i < len(lines):
                line = lines[i].strip()
                
                if line.startswith('#EXTINF:'):
                    # Check if this is a UK TV Guide entry to replace
                    if i + 1 < len(lines):
                        url_line = lines[i + 1].strip()
                        entry = PlaylistEntry(line, url_line)
                        
                        # Check if this entry should be replaced
                        if entry.group_channel_id in self.overrides:
                            replacement_spec = self.overrides[entry.group_channel_id]
                            replacement_entry = self.find_replacement_entry(replacement_spec)
                            
                            if replacement_entry:
                                # Create a modified EXTINF line that preserves the original group-title
                                # but uses the replacement entry's other attributes
                                modified_extinf = self._create_hybrid_extinf(entry, replacement_entry)
                                
                                output_lines.append(modified_extinf)
                                output_lines.append(replacement_entry.url_line)
                                replacements_made += 1
                                print(f"Replaced: {entry.channel_name} -> {replacement_entry.channel_name} (preserved group-title)")
                            else:
                                # Replacement not found, keep original
                                output_lines.append(line)
                                output_lines.append(url_line)
                                print(f"Warning: Replacement '{replacement_spec}' not found for {entry.channel_name}")
                        else:
                            # No replacement configured, keep original
                            output_lines.append(line)
                            output_lines.append(url_line)
                        
                        i += 2  # Skip both EXTINF and URL lines
                    else:
                        # Malformed entry, keep as-is
                        output_lines.append(line)
                        i += 1
                else:
                    # Not an EXTINF line, keep as-is
                    output_lines.append(line)
                    i += 1
            
            # Write output
            with open(output_file, 'w', encoding='utf-8') as f:
                f.write('\n'.join(output_lines))
            
            print(f"\nProcessing complete!")
            print(f"Replacements made: {replacements_made}")
            print(f"Output written to: {output_file}")
            
        except Exception as e:
            print(f"Error processing playlist: {e}")
    
    def _create_hybrid_extinf(self, original_entry: PlaylistEntry, replacement_entry: PlaylistEntry) -> str:
        """Create a hybrid EXTINF line using replacement attributes but preserving original group-title"""
        # Start with the replacement EXTINF line
        hybrid_line = replacement_entry.extinf_line
        
        # Replace the group-title with the original one
        if original_entry.group_title and replacement_entry.group_title:
            # Use regex to replace the group-title attribute
            pattern = r'group-title="[^"]*"'
            replacement = f'group-title="{original_entry.group_title}"'
            hybrid_line = re.sub(pattern, replacement, hybrid_line)
        
        return hybrid_line

def main():
    # Set up Windows Unicode output support
    if sys.platform.startswith('win'):
        import codecs
        sys.stdout = codecs.getwriter('utf-8')(sys.stdout.buffer, 'strict')
        sys.stderr = codecs.getwriter('utf-8')(sys.stderr.buffer, 'strict')
    
    if len(sys.argv) < 2:
        print("Usage:")
        print(f"  {sys.argv[0]} --list <playlist.m3u>")
        print(f"  {sys.argv[0]} --find <playlist.m3u> <search_term>")
        print(f"  {sys.argv[0]} <input.m3u> <output.m3u> [--config <config_file>]")
        sys.exit(1)
    
    # Parse arguments for config file
    config_file = "uk_tv_overrides_dynamic.conf"  # default
    args = sys.argv[1:]
    
    # Check for --config argument and remove it from args
    if "--config" in args:
        config_index = args.index("--config")
        if config_index + 1 < len(args):
            config_file = args[config_index + 1]
            # Remove --config and its value from args
            args.pop(config_index)  # remove --config
            args.pop(config_index)  # remove config_file value
        else:
            print("Error: --config requires a config file path")
            sys.exit(1)
    
    processor = UKTVOverrideProcessor(config_file)
    
    if len(args) > 0 and args[0] == '--list':
        if len(args) != 2:
            print("Usage: --list <playlist.m3u>")
            sys.exit(1)
        processor.list_uk_tv_entries(args[1])
    
    elif len(args) > 0 and args[0] == '--find':
        if len(args) != 3:
            print("Usage: --find <playlist.m3u> <search_term>")
            sys.exit(1)
        processor.find_channel(args[1], args[2])
    
    else:
        if len(args) != 2:
            print("Usage: <input.m3u> <output.m3u> [--config <config_file>]")
            sys.exit(1)
        processor.process_playlist(args[0], args[1])

if __name__ == "__main__":
    main()