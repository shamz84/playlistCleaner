import json
import re

def parse_group_titles_to_json(input_file, output_file):
    """
    Parse the unique group titles text file and create a JSON file with flag property.
    
    :param input_file: Path to the unique group titles text file
    :param output_file: Path to save the JSON output
    """
    group_titles = []
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        print(f"📂 Reading from: {input_file}")
        
        for line in lines:
            line = line.strip()
            
            # Skip header lines and empty lines
            if not line or line.startswith("Unique Group Titles:") or line.startswith("="):
                continue
            
            # Parse lines that match the format: "  1. Title Name (X channels)"
            match = re.match(r'\s*\d+\.\s+(.+?)\s+\((\d+)\s+channels?\)', line)
            if match:
                title = match.group(1).strip()
                channel_count = int(match.group(2))
                
                group_titles.append({
                    "group_title": title,
                    "channel_count": channel_count,
                    "flag": "exclude"
                })
                
                print(f"✅ Added: {title} ({channel_count} channels)")
        
        # Save to JSON file
        with open(output_file, 'w', encoding='utf-8') as json_file:
            json.dump(group_titles, json_file, indent=2, ensure_ascii=False)
        
        print(f"\n🎉 Successfully created JSON file!")
        print(f"📊 Total group titles processed: {len(group_titles)}")
        print(f"💾 JSON file saved to: {output_file}")
        
        # Display first few entries as preview
        print(f"\n📋 Preview of first 5 entries:")
        for i, entry in enumerate(group_titles[:5], 1):
            print(f"{i}. {entry['group_title']} - {entry['channel_count']} channels - Flag: {entry['flag']}")
        
        return group_titles
        
    except FileNotFoundError:
        print(f"❌ File '{input_file}' not found.")
        return None
    except Exception as e:
        print(f"❌ An error occurred: {e}")
        return None

if __name__ == "__main__":
    input_file = "unique_group_titles_raw20.txt"
    output_file = "group_titles_with_flags.json"
    
    result = parse_group_titles_to_json(input_file, output_file)
    
    if result:
        print(f"\n✨ Task completed successfully!")
    else:
        print(f"\n❌ Task failed!")
