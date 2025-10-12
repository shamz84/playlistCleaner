#!/usr/bin/env python3
"""
Analyze and categorize 24/7 Channels into specific subcategories
"""

import re
import json
from collections import defaultdict

def analyze_247_channels():
    """Extract and categorize all 24/7 Channels"""
    
    # Define categorization patterns
    kids_patterns = [
        r'\bkids?\b', r'\bchild(ren)?\b', r'\bcartoon\b', r'\banimated?\b',
        r'\bdisney\b', r'\bnickelodeon\b', r'\bcartoon network\b', r'\bpbs kids\b',
        r'\bsesame street\b', r'\bdora\b', r'\bspongebob\b', r'\bpaw patrol\b',
        r'\bpeppa pig\b', r'\bbaby\b', r'\btoddler\b', r'\bpreschool\b',
        r'\bfamily friendly\b', r'\ball ages\b', r'\byoung\b', r'\bjunior\b',
        r'\bteenage mutant ninja turtles\b', r'\bscooby doo?\b', r'\brugrats\b', r'\bpokemon\b',
        r'\bmr bean\b', r'\bmickey mouse clubhouse\b', r'\btiny toon?\b', r'\bthundercats\b', r'\bflintstones?\b',
        r'\bjetsons?\b', r'\btransformers?\b']

    movies_patterns = [
        r'\bmovies?\b', r'\bfilms?\b', r'\bcinema\b', r'\bhollywood\b',
        r'\bbox office\b', r'\bblockbuster\b', r'\bclassic\b', r'\baction\b',
        r'\bcomedy\b', r'\bdrama\b', r'\bthriller\b', r'\bhorror\b',
        r'\bscifi?\b', r'\bwestern\b', r'\bromance\b', r'\badventure\b',
        r'\bfantasy\b', r'\bmystery\b', r'\bcrime\b', r'\bwar\b', r'\bbiopic\b',
        # Celebrity/Actor channels moved to movies
        r'\badam sandler\b', r'\bal pacino\b', r'\barnold schwarzenegger\b', r'\bbrad pitt\b',
        r'\beddy murphy\b', r'\bjim carrey\b', r'\bwill ferrell\b', r'\bjohnny depp\b',
        r'\bsteven seagal\b', r'\bsylvester stallon\b', r'\bmike tyson\b', r'\bmuhammad ali\b',
        r'\bdenzel washington\b', r'\btom hardy\b', r'\bvan damme\b', r'\bclint eastwood\b',
        r'\bliam nesson\b',
        # Famous movie franchises and characters
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
        r'\bannabelle\b', r'\bparanormal activity\b', r'\bmortal kombat\b',
        # Additional movie patterns found in Other list
        r'\ba christmas carol\b', r'\balfred hitchcock\b', r'\bant.?man\b', r'\bbad boys\b',
        r'\bband of brothers\b', r'\bbatman.*brave\b', r'\bbeauty.*beast\b', r'\bbridget jones\b',
        r'\bcaptain america\b', r'\bdoctor strange\b', r'\bdr strange\b', r'\bfifty shades\b',
        r'\bjurassic park\b', r'\bjurassic world\b', r'\blethal weapon\b', r'\bpolice academy\b',
        r'\brocky\b', r'\bsuperman\b', r'\bwonder woman\b', r'\baquaman\b', r'\bflash\b',
        r'\bgreen lantern\b', r'\bjustice league\b', r'\bsucide squad\b', r'\bshazam\b',
        # Horror movie franchises
        r'\bchucky\b', r'\bpuppet master\b', r'\bchilds play\b', r'\bpoltergeist\b',
        r'\bthe ring\b', r'\bthe grudge\b', r'\bthe conjuring\b', r'\bsinister\b',
        # Action movie franchises  
        r'\bthe expendables\b', r'\btaken\b', r'\bnon.?stop\b', r'\bunknown\b',
        r'\bthe transporter\b', r'\bxxx\b', r'\bkingsman\b', r'\bjohn rambo\b',
        # Comedy movie franchises
        r'\baustin powers\b', r'\bmeet.*parents\b', r'\bzoolander\b', r'\banchorman\b',
        r'\bdodgeball\b', r'\bold school\b', r'\btalladega nights\b', r'\bthe other guys\b',
        r'\bnetflix\b', r'\bhbo\b', r'\bhulu\b', r'\bamazon\b', r'\bapple tv\b',
        r'\bdisney\b', r'\bshowtime\b', r'\bstarz\b', r'\bepix\b', r'\bcinemax\b',
        r'\bamc\b', r'\bparamount\b', r'\bpeacock\b', r'\bmgm\b', r'\bdie hard\b', r'\brush hour\b'
    ]
    
    # Additional patterns for better classification
    tv_shows_patterns = [
        r'\btv shows?\b', r'\bseries\b', r'\bepisodes?\b', r'\bsitcom\b',
        r'\bsoap opera\b', r'\breality\b', r'\btalk show\b', r'\bgame show\b',
        r'\bdocumentary\b', r'\bnews\b', r'\bvariety\b', r'\bclassic tv\b', r'\bshow\b',
        r'\bmarvels\b', r'\bbaking\b', r'\bcooking\b', r'\bchef\b', r'\bkitchen\b',
        # Adult content moved to TV shows
        r'\badult\b', r'\bmature\b', r'\b18\+\b', r'\bxxx\b', r'\bporn\b',
        r'\berotic\b', r'\bsexy?\b', r'\bnude\b', r'\bplayboy\b', r'\bbrazzers\b',
        r'\badults? only\b', r'\bafter dark\b', r'\bfor adults\b',
        # Famous TV shows
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
        r'\bsuits\b', r'\bwhite collar\b', r'\bburn notice\b', r'\bleveraged?\b',
        # Classic sitcoms and shows
        r'\bi love lucy\b', r'\blucille ball\b', r'\bfrasier\b', r'\bcheers\b',
        r'\bfull house\b', r'\bfresh prince\b', r'\bsaved by the bell\b', r'\bboy meets world\b',
        r'\bfamily matters\b', r'\bstep by step\b', r'\bperfect strangers\b', r'\bwebster\b',
        r'\bdiff.*strokes\b', r'\bgood times\b', r'\bsanford.*son\b', r'\bwhat.*happening\b',
        r'\ball.*family\b', r'\bfamily ties\b', r'\bgrowing pains\b', r'\bhead.*class\b',
        r'\bmork.*mindy\b', r'\blaverne.*shirley\b', r'\bhappy days\b', r'\bthree.*company\b',
        r'\bdukes.*hazzard\b', r'\ba.?team\b', r'\bknight rider\b', r'\bmacgyver\b',
        r'\bmagnum.*p\.i\b', r'\bmiami vice\b', r'\btj hooker\b', r'\bchips\b',
        # Additional popular TV shows found in Other
        r'\b30 rock\b', r'\barrested development\b', r'\bmodern family\b', r'\bparks.*recreation\b',
        r'\bhome improvement\b', r'\bhow i met your mother\b', r'\beverybody.*raymond\b',
        r'\beverybody.*chris\b', r'\bcolumbo\b', r'\bcold case\b', r'\bchicago fire\b',
        r'\bchicago med\b', r'\bchicago p\.?d\b', r'\bcougar town\b', r'\bcurb.*enthusiasm\b',
        r'\bdeadwood\b', r'\bdesignated survivor\b', r'\bdownton abbey\b', r'\bentourage\b',
        r'\bfargo\b', r'\bfirefly\b', r'\bbosch\b', r'\bboston legal\b', r'\bbetter call saul\b',
        r'\bbewitched\b', r'\bbaywatch\b', r'\bbarney miller\b', r'\bbates motel\b',
        r'\bblue bloods\b', r'\bbrooklyn nine.?nine\b', r'\bbull\b', r'\bcalifornication\b',
        r'\bcall.*midwife\b', r'\bchappelle.*show\b', r'\bchuck\b', r'\bcobra kai\b',
        r'\bcontinuum\b', r'\bcops\b', r'\bcoroner\b', r'\bdeath.*paradise\b',
        r'\bdegrassi\b', r'\bdivorce court\b', r'\bdog.*bounty\b', r'\bdrunk history\b',
        r'\bduck dynasty\b', r'\ber\b', r'\bfear factor\b', r'\bforged.*fire\b',
        r'\bgirlfriends\b', r'\bgolden girls\b', r'\bhancock\b', r'\bhell.*kitchen\b',
        r'\bhome.*away\b', r'\bmarried.*children\b', r'\bmurder.*wrote\b', r'\broseanne\b',
        r'\bscrubs\b', r'\bthat.*70s\b', r'\bwill.*grace\b', r'\bwings\b'
    ]
    
    # Read the downloaded playlist
    try:
        with open('data/downloaded_file.m3u', 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        print("❌ Downloaded file not found: data/downloaded_file.m3u")
        return
    
    # Parse M3U content
    lines = content.split('\n')
    channels = []
    current_channel = {}
    
    for line in lines:
        line = line.strip()
        if line.startswith('#EXTINF:'):
            # Parse channel info
            # Extract group-title
            group_match = re.search(r'group-title="([^"]*)"', line)
            if group_match and group_match.group(1) == "24/7 Channels":
                # Extract channel name (everything after the last comma)
                name_match = re.search(r',([^,]+)$', line)
                if name_match:
                    current_channel = {
                        'name': name_match.group(1).strip(),
                        'full_extinf': line,
                        'original_group': '24/7 Channels'
                    }
        elif line and not line.startswith('#') and current_channel:
            # This is the URL line
            current_channel['url'] = line
            channels.append(current_channel)
            current_channel = {}
    
    print(f"📊 Found {len(channels)} channels in '24/7 Channels' group")
    
    if not channels:
        print("❌ No 24/7 Channels found!")
        return
    
    # Categorize channels
    categorized = {
        '24/7 Kids': [],
        '24/7 Movies': [],
        '24/7 TV Shows': [],
        '24/7 Other': []
    }
    
    def check_patterns(text, patterns):
        """Check if text matches any pattern"""
        text_lower = text.lower()
        return any(re.search(pattern, text_lower, re.IGNORECASE) for pattern in patterns)
    
    # Categorize each channel
    for channel in channels:
        name = channel['name']
        
        # Check each category in priority order
        if check_patterns(name, kids_patterns):
            categorized['24/7 Kids'].append(channel)
        elif check_patterns(name, movies_patterns):
            categorized['24/7 Movies'].append(channel)
        elif check_patterns(name, tv_shows_patterns):
            categorized['24/7 TV Shows'].append(channel)
        else:
            categorized['24/7 Other'].append(channel)
    
    # Print summary
    print("\n📋 Categorization Summary:")
    for category, channels_list in categorized.items():
        if channels_list:
            print(f"   {category}: {len(channels_list)} channels")
    
    # Show sample channels from each category
    print("\n🔍 Sample Channels by Category:")
    for category, channels_list in categorized.items():
        if channels_list:
            print(f"\n📺 {category} (showing first 10):")
            for i, channel in enumerate(channels_list[:10]):
                print(f"   {i+1:2d}. {channel['name']}")
            if len(channels_list) > 10:
                print(f"   ... and {len(channels_list) - 10} more")
    
    # Create new M3U files for each category
    print("\n📝 Creating categorized M3U files...")
    
    base_extinf_template = '#EXTINF:-1 tvg-id="" tvg-name="{name}" tvg-logo="" group-title="{group}",{name}'
    
    for category, channels_list in categorized.items():
        if channels_list:
            filename = f"247_channels_{category.replace('24/7 ', '').replace(' ', '_').lower()}.m3u"
            with open(filename, 'w', encoding='utf-8') as f:
                f.write('#EXTM3U\n')
                for channel in channels_list:
                    # Update the group title in the EXTINF line
                    extinf_line = re.sub(
                        r'group-title="[^"]*"', 
                        f'group-title="{category}"', 
                        channel['full_extinf']
                    )
                    f.write(f"{extinf_line}\n")
                    f.write(f"{channel['url']}\n")
            print(f"   ✅ Created: {filename} ({len(channels_list)} channels)")
    
    # Create a combined configuration update
    print("\n⚙️  Creating group configuration update...")
    
    # Create new group entries for the configuration
    new_groups = []
    order_start = 19  # After current "24/7 Channels" which is order 18
    
    for i, (category, channels_list) in enumerate(categorized.items()):
        if channels_list and category != '24/7 Other':  # Don't create group for 'Other'
            new_groups.append({
                "group_title": category,
                "channel_count": len(channels_list),
                "exclude": "false",
                "order": order_start + i
            })
    
    # Save new groups configuration
    with open('new_247_groups_config.json', 'w', encoding='utf-8') as f:
        json.dump(new_groups, f, indent=2, ensure_ascii=False)
    
    print(f"   ✅ Created: new_247_groups_config.json")
    print("\n💡 Next steps:")
    print("   1. Review the categorized files")
    print("   2. Add the new groups to your group_titles_with_flags.json")
    print("   3. Update the original '24/7 Channels' group to exclude or modify")
    print("   4. Run the enhanced filter to test the new categorization")
    
    return categorized

if __name__ == "__main__":
    analyze_247_channels()
