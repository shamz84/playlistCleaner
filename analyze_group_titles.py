import json
from collections import Counter

def analyze_group_titles(json_file):
    """
    Analyze the extracted metadata JSON file to count unique group titles.
    """
    try:
        print(f"📂 Loading JSON file: {json_file}")
        with open(json_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        print(f"✅ Loaded {len(data)} entries")
        
        # Extract all group titles
        print("🔍 Extracting group titles...")
        group_titles = [entry['group-title'] for entry in data if 'group-title' in entry]
        
        # Count unique group titles
        print("📊 Counting unique titles...")
        unique_titles = set(group_titles)
        title_counts = Counter(group_titles)
        
        print(f"📊 Analysis Results:")
        print(f"Total entries: {len(data)}")
        print(f"Total group titles found: {len(group_titles)}")
        print(f"Number of unique group titles: {len(unique_titles)}")
        print()
        
        print("📋 All unique group titles:")
        print("-" * 50)
        for i, title in enumerate(sorted(unique_titles), 1):
            count = title_counts[title]
            print(f"{i:3d}. {title} ({count} channels)")
        
        # Show top 10 most popular group titles
        print()
        print("🔥 Top 10 most popular group titles:")
        print("-" * 50)
        for i, (title, count) in enumerate(title_counts.most_common(10), 1):
            print(f"{i:2d}. {title} ({count} channels)")
        
        return unique_titles, title_counts
        
    except FileNotFoundError:
        print(f"❌ File '{json_file}' not found.")
        return None, None
    except json.JSONDecodeError as e:
        print(f"❌ Error parsing JSON file: {e}")
        return None, None
    except Exception as e:
        print(f"❌ An error occurred: {e}")
        return None, None

if __name__ == "__main__":
    json_file = "extracted_metadata_raw20.json"
    unique_titles, title_counts = analyze_group_titles(json_file)
    
    if unique_titles:
        # Save unique group titles to a separate file
        with open("unique_group_titles_raw20.txt", "w", encoding="utf-8") as f:
            f.write("Unique Group Titles:\n")
            f.write("=" * 50 + "\n\n")
            for i, title in enumerate(sorted(unique_titles), 1):
                count = title_counts[title]
                f.write(f"{i:3d}. {title} ({count} channels)\n")
        
        print(f"\n💾 Unique group titles saved to 'unique_group_titles_raw20.txt'")
