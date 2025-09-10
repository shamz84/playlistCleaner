#!/usr/bin/env python3
"""
Analyze 24/7 Other channels and filter out movies and TV shows to identify truly "other" content
"""

import re
from collections import defaultdict

def analyze_other_channels():
    """Analyze and filter the Other channels list"""
    
    # Define patterns for movies and TV shows (similar to our original categorization)
    movies_patterns = [
        r'\bmovies?\b', r'\bfilms?\b', r'\bcinema\b', r'\bhollywood\b',
        r'\bbox office\b', r'\bblockbuster\b', r'\bclassic\b', r'\baction\b',
        r'\bcomedy\b', r'\bdrama\b', r'\bthriller\b', r'\bhorror\b',
        r'\bscifi?\b', r'\bwestern\b', r'\bromance\b', r'\badventure\b',
        r'\bfantasy\b', r'\bmystery\b', r'\bcrime\b', r'\bwar\b', r'\bbiopic\b'
    ]
    
    tv_shows_patterns = [
        r'\btv shows?\b', r'\bseries\b', r'\bepisodes?\b', r'\bsitcom\b',
        r'\bsoap opera\b', r'\breality\b', r'\btalk show\b', r'\bgame show\b',
        r'\bdocumentary\b', r'\bnews\b', r'\bvariety\b', r'\bclassic tv\b'
    ]
    
    # Famous movie franchises and characters
    famous_movies = [
        r'\bstar wars\b', r'\bharry potter\b', r'\bback to the future\b', r'\bjurassic\b',
        r'\bindiana jones\b', r'\bjames bond\b', r'\bfast.*furious\b', r'\btransformers\b',
        r'\bmatrix\b', r'\bdie hard\b', r'\bterminator\b', r'\bmission impossible\b',
        r'\blethal weapon\b', r'\brambo\b', r'\brocky\b', r'\bbatman\b', r'\bspider.?man\b',
        r'\biron man\b', r'\bcaptain america\b', r'\bmarvel\b', r'\bavengers\b',
        r'\bguardians.*galaxy\b', r'\bx.?men\b', r'\bmen in black\b', r'\balien\b',
        r'\bscream\b', r'\bsaw\b', r'\bhalloween\b', r'\bfriday.*13th\b', r'\bnightmare\b',
        r'\bpredator\b', r'\btwilight\b', r'\bunderworld\b', r'\bresident evil\b',
        r'\btomb raider\b', r'\bjohn wick\b', r'\bjason bourne\b', r'\bpirates.*caribbean\b',
        r'\blord.*rings\b', r'\bhobbit\b', r'\bice age\b', r'\bmighty ducks\b',
        r'\bgrease\b', r'\bbridget jones\b', r'\bfifty shades\b', r'\bpitch perfect\b',
        r'\bhangover\b', r'\bstep up\b', r'\bbad boys\b', r'\bpolice academy\b',
        r'\bamerican pie\b', r'\bjumanji\b', r'\bplanet.*apes\b', r'\bmaze runner\b',
        r'\bhunger games\b', r'\bwrong turn\b', r'\bjeepers creepers\b', r'\binsidious\b',
        r'\bannabelle\b', r'\bparanormal activity\b', r'\bmortal kombat\b'
    ]
    
    # Famous TV shows
    famous_tv_shows = [
        r'\bfriends\b', r'\bseinfeld\b', r'\bthe office\b', r'\bbig bang theory\b',
        r'\btwo.*half.*men\b', r'\bhow.*met.*mother\b', r'\bbreaking bad\b', r'\bgame.*thrones\b',
        r'\bwalking dead\b', r'\bstranger things\b', r'\bsupernatural\b', r'\barrow\b',
        r'\bflash\b', r'\bsupergirl\b', r'\blegends.*tomorrow\b', r'\bgotham\b',
        r'\bsimpsons\b', r'\bfamily guy\b', r'\bsouth park\b', r'\bamerican dad\b',
        r'\bfuturama\b', r'\bking.*hill\b', r'\bbobs.*burgers\b', r'\briginals\b',
        r'\bbuffy\b', r'\bangel\b', r'\bcharmed\b', r'\bsmalville\b', r'\blost\b',
        r'\b24\b', r'\bprison break\b', r'\bncis\b', r'\bcsi\b', r'\blaw.*order\b',
        r'\bcriminal minds\b', r'\bones\b', r'\bhouse\b', r'\bgrey.*anatomy\b',
        r'\bfringe\b', r'\bx.?files\b', r'\bstar trek\b', r'\bstargate\b',
        r'\bdoctor who\b', r'\btorchwood\b', r'\bsherlock\b', r'\belementary\b',
        r'\bdexter\b', r'\bmad men\b', r'\bsopranos\b', r'\boz\b', r'\bwire\b',
        r'\bboardwalk empire\b', r'\bsons.*anarchy\b', r'\bshield\b', r'\bpower\b',
        r'\bsuits\b', r'\bwhite collar\b', r'\bburn notice\b', r'\bleveraged?\b'
    ]
    
    # Actor/celebrity channels
    celebrity_patterns = [
        r'\badam sandler\b', r'\bal pacino\b', r'\barnold schwarzenegger\b', r'\bbrad pitt\b',
        r'\beddy murphy\b', r'\bjim carrey\b', r'\bwill ferrell\b', r'\bjohnny depp\b',
        r'\bsteven seagal\b', r'\bsylvester stallon\b', r'\bmike tyson\b', r'\bmuhammad ali\b',
        r'\bdenzel washington\b', r'\btom hardy\b', r'\bvan damme\b', r'\bclint eastwood\b',
        r'\bliam nesson\b'
    ]
    
    # Streaming service content
    streaming_patterns = [
        r'\bnetflix\b', r'\bhbo\b', r'\bhulu\b', r'\bamazon\b', r'\bapple tv\b',
        r'\bdisney\b', r'\bshowtime\b', r'\bstarz\b', r'\bepix\b', r'\bcinemax\b',
        r'\bamc\b', r'\bparamount\b', r'\bpeacock\b', r'\bmgm\b'
    ]
    
    # Read the channel list
    channels = []
    try:
        with open('247_channels_other_list.txt', 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except FileNotFoundError:
        print("❌ File not found: 247_channels_other_list.txt")
        return
    
    # Parse the numbered list
    for line in lines[3:]:  # Skip header lines
        line = line.strip()
        if line and '. ' in line:
            # Extract channel name after number
            parts = line.split('. ', 1)
            if len(parts) == 2:
                channels.append(parts[1])
    
    print(f"📊 Analyzing {len(channels)} channels from 24/7 Other category")
    
    def check_patterns(text, patterns):
        """Check if text matches any pattern"""
        text_lower = text.lower()
        return any(re.search(pattern, text_lower, re.IGNORECASE) for pattern in patterns)
    
    # Categorize channels
    categorized = {
        'Known Movies': [],
        'Known TV Shows': [],
        'Celebrity/Actor Channels': [],
        'Streaming Service Content': [],
        'Local News/Networks': [],
        'Music Channels': [],
        'Sports Content': [],
        'International Content': [],
        'Truly Other/Unclear': []
    }
    
    # Additional specific patterns
    local_news_patterns = [
        r'\babc\d+\b', r'\bcbs\b', r'\bfox\d+\b', r'\bnbc\b', r'\bwls\b', r'\bkabc\b',
        r'\bktrk\b', r'\bwpvi\b', r'\bwsb\b', r'\bwsoc\b', r'\bwpxi\b', r'\bwtv\b',
        r'\blocal now\b', r'\bnews\b', r'\bweathernation\b', r'\baccuweather\b'
    ]
    
    music_patterns = [
        r'\bvevo\b', r'\biheart\b', r'\bmusic\b', r'\bradio\b', r'\bhip.?hop\b',
        r'\bcountry\b', r'\br&b\b', r'\brock\b', r'\byacht rock\b', r'\breggaeton\b',
        r'\btiktok radio\b'
    ]
    
    sports_patterns = [
        r'\bsports?\b', r'\besp\b', r'\bnfl\b', r'\bnba\b', r'\bnhl\b', r'\bmlb\b',
        r'\bufc\b', r'\bmma\b', r'\bgolf\b', r'\btennis\b', r'\bsoccer\b', r'\bfootball\b',
        r'\bbasketball\b', r'\bhockey\b', r'\bbaseball\b', r'\bwrestling\b', r'\bwwe\b'
    ]
    
    international_patterns = [
        r'\bespañol\b', r'\benglish\b', r'\bespanol\b', r'\blatino\b', r'\blatina\b',
        r'\binternacional\b', r'\btelemundo\b', r'\bunivis\b', r'\bazteca\b',
        r'\bcanela\b', r'\bvix\b', r'\boro tv\b', r'\brcn\b'
    ]
    
    # Categorize each channel
    for channel in channels:
        name = channel.strip()
        
        # Check each category
        if check_patterns(name, famous_movies):
            categorized['Known Movies'].append(name)
        elif check_patterns(name, famous_tv_shows):
            categorized['Known TV Shows'].append(name)
        elif check_patterns(name, celebrity_patterns):
            categorized['Celebrity/Actor Channels'].append(name)
        elif check_patterns(name, streaming_patterns):
            categorized['Streaming Service Content'].append(name)
        elif check_patterns(name, local_news_patterns):
            categorized['Local News/Networks'].append(name)
        elif check_patterns(name, music_patterns):
            categorized['Music Channels'].append(name)
        elif check_patterns(name, sports_patterns):
            categorized['Sports Content'].append(name)
        elif check_patterns(name, international_patterns):
            categorized['International Content'].append(name)
        else:
            categorized['Truly Other/Unclear'].append(name)
    
    # Print results
    print("\n📋 Analysis Results:")
    print("=" * 60)
    
    total_categorized = 0
    for category, channels_list in categorized.items():
        if channels_list:
            print(f"\n🎯 {category}: {len(channels_list)} channels")
            total_categorized += len(channels_list)
            
            # Show first 10 examples
            for i, channel in enumerate(channels_list[:10]):
                print(f"   {i+1:2d}. {channel}")
            if len(channels_list) > 10:
                print(f"   ... and {len(channels_list) - 10} more")
    
    print(f"\n📊 Summary:")
    print(f"   Total channels analyzed: {len(channels)}")
    print(f"   Total categorized: {total_categorized}")
    print(f"   Verification: {'✅ Match' if total_categorized == len(channels) else '❌ Mismatch'}")
    
    # Create filtered lists
    print(f"\n📝 Creating filtered channel lists...")
    
    # Movies and TV Shows combined
    entertainment_channels = categorized['Known Movies'] + categorized['Known TV Shows']
    with open('247_other_entertainment_filtered.txt', 'w', encoding='utf-8') as f:
        f.write(f"24/7 Other - Movies & TV Shows ({len(entertainment_channels)} channels)\n")
        f.write("=" * 60 + "\n\n")
        for i, channel in enumerate(sorted(entertainment_channels), 1):
            f.write(f"{i:4d}. {channel}\n")
    
    # Truly other content (non-entertainment)
    truly_other = categorized['Truly Other/Unclear']
    with open('247_other_truly_other.txt', 'w', encoding='utf-8') as f:
        f.write(f"24/7 Other - Non-Entertainment Content ({len(truly_other)} channels)\n")
        f.write("=" * 60 + "\n\n")
        for i, channel in enumerate(sorted(truly_other), 1):
            f.write(f"{i:4d}. {channel}\n")
    
    # All non-entertainment categories combined
    non_entertainment = (categorized['Celebrity/Actor Channels'] + 
                        categorized['Streaming Service Content'] + 
                        categorized['Local News/Networks'] + 
                        categorized['Music Channels'] + 
                        categorized['Sports Content'] + 
                        categorized['International Content'] + 
                        categorized['Truly Other/Unclear'])
    
    with open('247_other_non_entertainment.txt', 'w', encoding='utf-8') as f:
        f.write(f"24/7 Other - All Non-Entertainment Content ({len(non_entertainment)} channels)\n")
        f.write("=" * 60 + "\n\n")
        for i, channel in enumerate(sorted(non_entertainment), 1):
            f.write(f"{i:4d}. {channel}\n")
    
    print(f"   ✅ Created: 247_other_entertainment_filtered.txt ({len(entertainment_channels)} channels)")
    print(f"   ✅ Created: 247_other_truly_other.txt ({len(truly_other)} channels)")
    print(f"   ✅ Created: 247_other_non_entertainment.txt ({len(non_entertainment)} channels)")
    
    # Detailed breakdown
    print(f"\n💡 Recommendations for further categorization:")
    print(f"   🎬 Movies & TV Shows: {len(entertainment_channels)} channels - could be moved to existing categories")
    print(f"   📺 Local News: {len(categorized['Local News/Networks'])} channels - could become '24/7 Local News'")
    print(f"   🎵 Music: {len(categorized['Music Channels'])} channels - could become '24/7 Music'")
    print(f"   🏈 Sports: {len(categorized['Sports Content'])} channels - could be merged with existing sports categories")
    print(f"   🌍 International: {len(categorized['International Content'])} channels - could become '24/7 International'")
    print(f"   ⭐ Celebrity: {len(categorized['Celebrity/Actor Channels'])} channels - could stay in current categories")
    print(f"   📱 Streaming: {len(categorized['Streaming Service Content'])} channels - could become '24/7 Streaming Originals'")
    print(f"   ❓ Truly Other: {len(truly_other)} channels - need manual review for further categorization")
    
    return categorized

if __name__ == "__main__":
    analyze_other_channels()
