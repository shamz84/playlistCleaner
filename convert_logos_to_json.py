#!/usr/bin/env python3
"""
Script to convert logo filenames to JSON format with the structure:
{
    "logo": "filename.png",
    "tvid": "",
    "url": ""
}
"""

import json
import re
import os

def extract_png_files(input_file):
    """Extract all PNG filenames from the input file."""
    png_files = []
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            content = f.read()
            
        # Find all .png files (case insensitive)
        png_pattern = r'([^/\\]+\.png)'
        matches = re.findall(png_pattern, content, re.IGNORECASE)
        
        # Remove duplicates while preserving order
        seen = set()
        for match in matches:
            if match.lower() not in seen:
                png_files.append(match)
                seen.add(match.lower())
                
    except Exception as e:
        print(f"Error reading file: {e}")
        return []
    
    return png_files

def create_json_structure(png_files):
    """Create the JSON structure for each PNG file."""
    json_data = []
    
    for png_file in png_files:
        entry = {
            "logo": png_file,
            "tvid": "",
            "url": ""
        }
        json_data.append(entry)
    
    return json_data

def main():
    # Input file - adjust path as needed
    input_file = "logo_list.txt"  # You'll need to save your list to this file
    output_file = "tv_logos.json"
    
    # For now, let's use the data directly from your attachment
    # Extract PNG files from the provided content
    content = """2RN.png


30A TV Classic Movies.png


3ABN Dare to Dream Network.png


3ABN Francais Network.png


3ABN Kids Network.png


3ABN Latino Network.png


3ABN Praise Him Music Network.png


3ABN Proclaim Network.png


3ABN Russia.png


3ABN.png


4Music.png


4seven.png


5Action.png


5Select.png


5Star Plus 1.png


5Star.png


5USA Plus 1.png


5USA.png


92 News UK.png


ARY Digital.png


ATN Bangla.png


ATN.png
AWE Plus.png


Aaj Tak.png


Aastha.png


Africanews.png


Ahlebait TV.png


Ahlulbayt TV.png


Akaal Channel.png


Al Arabiya.png


Al Hadath.png


Al Jazeera English HD.png


Al Jazeera English.png


Alaraby 2.png


Alaraby TV.png


Alibi HD.png


Alibi Plus 1.png


Alibi.png


Animal Planet Plus 1.png


Animal Planet.png


Animated PPV UK.gif

Arirang HD.png


Arise News.png


ArisePlay.png


Asharq News.png


Astra.png


Auto Allstars.png


Ayozat TV.png


B4U Movies.png


B4U Music.png


BBC Alba.png


BBC Four HD.png


BBC Four.png


BBC Game Shows.png

BBC Information Channel.png


BBC News HD.png


BBC News.png


BBC One Cambridge.png

BBC One Channel Islands.png


BBC One East Midlands.png


BBC One East.png


BBC One HD.png


BBC One London.png


BBC One Midlands.png


BBC One North East and Cumbria.png


BBC One North West.png


BBC One Northern Ireland HD.png


BBC One Northern Ireland.png


BBC One Oxford.png

BBC One Scotland HD.png


BBC One Scotland.png


BBC One South East.png


BBC One South West.png


BBC One South.png


BBC One Wales HD.png


BBC One Wales.png


BBC One West Midlands.png


BBC One West.png


BBC One Yorkshire and Lincolnshire.png


BBC One Yorkshire.png


BBC One.png


BBC Parliament.png


BBC Red Button 1.png


BBC Red Button 2.png


BBC Red Button 3.png


BBC Red Button 4.png


BBC Red Button 5.png


BBC Red Button 6.png


BBC Red Button HD.png


BBC Red Button.png


BBC Sci-Fi.png

BBC Scotland HD.png


BBC Scotland.png


BBC Three HD.png


BBC Three.png


BBC Two HD.png


BBC Two Northern Ireland HD.png


BBC Two Northern Ireland.png


BBC Two Scotland.png

BBC Two Wales HD.png

BBC Two Wales.png

BBC Two.png

BT Sport 1 HD.png

BT Sport 1.png


BT Sport 10 HD.png


BT Sport 10.png


BT Sport 2 HD.png


BT Sport 2.png


BT Sport 3 HD.png


BT Sport 3.png


BT Sport 4 HD.png


BT Sport 4.png


BT Sport 5 HD.png


BT Sport 5.png


BT Sport 6 HD.png


BT Sport 6.png


BT Sport 7 HD.png


BT Sport 7.png


BT Sport 8 HD.png


BT Sport 8.png


BT Sport 9 HD.png


BT Sport 9.png


BT Sport Box Office 2 HD.png


BT Sport Box Office HD.png


BT Sport Box Office WWE HD.png


BT Sport Mosaic 2.png


BT Sport Mosaic.png


BT Sport Ultimate.png


Ben 10.png


Best Direct.png


Billiard TV.png


Birmingham Local TV.png


Blaze Plus 1.png


Blaze.png


Bloomberg Quicktake.png


Bloomberg TV HD.png


Bloomberg TV.png


BoXmas.png


Bollywood Classic.png


Bollywood HD.png


Boomerang HD.png


Boomerang Plus 1.png


Boomerang.png


Bristol Local TV.png


Brit Asia TV.png


British Muslim TV.png


CBBC HD.png


CBBC.png


CBS Reality Plus 1.png


CBS Reality.png


CBeebies HD.png


CBeebies.png


CGTN.png


CITV.png


CNBC HD.png


CNBC.png


CNN HD.png


CNN.png


Cardiff Local TV.png


Cartoon Network HD.png


Cartoon Network Plus 1.png


Cartoon Network.png


Cartoonito.png


Challenge.png


Channel 4 HD.png


Channel 4 Plus 1.png


Channel 4.png


Channel 44.png


Channel 5 HD.png


Channel 5 Plus 1.png


Channel 5.png


Channel 7.png


Channel S.png


Channelbox.png


Channels 24.png


Christmas 24 Plus.png


Christmas 24.png


Clubland TV.png


Colors Cineplex.png


Colors Gujarati.png


Colors HD.png


Colors Rishtey.png


Colors.png


Comedy Central Extra.png


Comedy Central HD.png


Comedy Central Plus 1.png


Comedy Central.png


Court TV.png


Craft Extra.png


Create and Craft HD.png


Create and Craft.png


Crime and Investigation HD.png


Crime and Investigation Plus 1.png


Crime and Investigation.png


Cruise1st.tv.png


Current Time.png


DMAX Plus 1.png


DMAX.png


Dave HD.png


Dave ja vu.png


Dave.png


Daystar HD.png


Daystar.png


Deen TV.png


Deutsche Welle.png


Direct Store TV.png


Discovery HD.png


Discovery History Plus 1.png


Discovery History.png


Discovery Plus 1.png


Discovery Science Plus 1.png


Discovery Science.png


Discovery Turbo Plus 1.png


Discovery Turbo.png


Discovery.png


Drama Plus 1.png


Drama.png


Duck TV.png


Dunamis TV.png


Dunya News.png


E HD.png


E.png


E4 Extra.png


E4 HD.png


E4 Plus 1.png


E4.png


EPL White.png

EPL.png

ESports 247 TV.png

EWTN.png

EarthxTV.png

Eden Plus 1.png


Eden.png


Edgy TV.png


Eman Channel.png


English Football Channel.png


Euronews Russian.png


Euronews.png


European League of Football.png

Eurosport 1 HD.png


Eurosport 1.png


Eurosport 2 HD.png


Eurosport 2.png


Eurosport 3 HD.png


Eurosport 4 HD.png


Eurosport 5 HD.png


Eurosport 6 HD.png


Eurosport 7 HD.png


Eurosport 8 HD.png


Eurosport 9 HD.png


F1.png

FA Player.png

FailArmy.png


Faith UK.png


Faith World TV.png


Fashion TV.png


FidoTV.png


Film4 HD.png


Film4 Plus 1.png


Film4.png


FilmUADrama.png


Food Network Plus 1.png


Food Network.png


Foodxp.png


France 24 HD en Francais.png


France 24 HD in English.png


France 24 en Francais.png


France 24 in Arabic.png


France 24 in English.png


Free Classic Movies.png


Freeview Accessible TV Guide.png


Freeview.png


Fuel TV.png


GB News HD.png


GB News.png


Gems TV.png


Geo News.png


Geo TV.png


Get Lucky TV.png


Ginx eSports TV.png


Go Live Sports Cast.png


God TV.png


Gold HD.png


Gold Plus 1.png


Gold.png


Good News TV.png


Great Movies Action Plus 1.png


Great Movies Action.png


Great Movies Christmas Plus 1.png


Great Movies Christmas.png


Great Movies Plus 1.png


Great Movies.png


Great Romance Plus 1.png


Great Romance.png


Great TV Plus 1.png


Great TV.png


HGTV Plus 1.png


HGTV.png


Hellenic TV.png


HeritagePlus.png


Hidayat TV.png


High Street TV.png


HobbyMaker.png


HorrorXtra Plus 1.png


HorrorXtra.png


Hub Premier 1.png

Hub Premier 10.png

Hub Premier 11.png

Hub Premier 2.png

Hub Premier 3.png

Hub Premier 4.png

Hub Premier 5.png

Hub Premier 6.png

Hub Premier 7.png

Hub Premier 8.png

Hub Premier 9.png

Hum Europe.png


Hum Masala.png


ID Plus 1.png


ID.png


ITV Anglia.png

ITV Border.png


ITV Central.png


ITV Channel Islands.png


ITV Cymru Wales.png


ITV Granada.png


ITV London.png


ITV Meridian.png


ITV Quiz.png

ITV Tyne Tees.png


ITV West Country.png


ITV Yorkshire.png


ITV1 HD.png


ITV1 Plus 1.png


ITV1.png


ITV2 HD.png


ITV2 Plus 1.png


ITV2.png


ITV3 HD.png


ITV3 Plus 1.png


ITV3.png


ITV4 HD.png


ITV4 Plus 1.png


ITV4.png


ITVBe Plus 1.png


ITVBe.png


Ideal Extra.png


Ideal World HD.png


Ideal World.png


Imam Hussein TV3.png


Inspiration TV.png

Ion TV.png

Iqra Bangla.png


Iqra TV.png


Iran International HD.png


Iran International.png


Islam Channel Bangla.png


Islam Channel Urdu.png


Islam Channel.png


Islam TV.png


JML Direct.png


Jewellery Maker.png


KICC TV.png


KMTV.png


Kanshi TV.png


Kerrang.png


Ketchup TV.png


Ketchup Too.png


Kiss.png


LFCTV HD.png


LaLigaTV.png

Latest TV.png

Law and Crime.png


League One EFL.png

League Two EFL.png

Leeds Local TV.png


Legend.png


Ligue1.png

Liverpool Local TV.png


London Live.png


LoveWorld UK HD.png


MATV National.png


MMA-TV.com.png


MTA1 World HD.png


MTV 80s.png


MTV 90s.png


MTV HD.png


MTV Hits.png


MTV Music.png


MTV Pride.png


MTV Xmas.png


MTV.png


MUTV HD.png


MUTV.png


MXGP.png

Madani Channel English.png


Magic TV.png


Matchroom.png

Mono Max.png

More4 HD.png


More4 Plus 1.png


More4.png


Movies 24 Plus.png


Movies 24.png


Music and Memories TV.png


NBC News Now.png


NDTV 24x7.png


NHK World-Japan.png


NTAI.png


NTD.png


NTV.png


NVTV.png


National Geographic HD.png


National Geographic Plus 1.png


National Geographic Wild HD.png


National Geographic Wild.png


National Geographic.png


New Media TV UK.png


New Vision TV.png


News Central.png


Newsmax TV.png


Nick Horrid Henry.png


Nick Jr HD.png


Nick Jr PAW Patrol.png


Nick Jr Peppa.png


Nick Jr Plus 1.png


Nick Jr Too.png


Nick Jr Xmas.png


Nick Jr.png


Nick SpongeBob.png


Nickelodeon HD.png


Nickelodeon Plus 1.png


Nickelodeon.png


Nicktoons.png


NollyAfrica.png


Noor TV.png


North Wales Local TV.png


Notts TV.png


Now 70s.png


Now 80s.png


Now Christmas.png


Now Rock.png


Now.png

Nyx.png


OAN Plus.png


Oireachtas TV.png


OnDemand365.png


PBS America.png


PDC.png

PPV Live.png

PPV UK.png
PPV UK2.png

PPV.png

PTC Punjabi.png

PTV Global.png


Panjab Broadcasting Channel.png


Partyland.png


Phoenix CNE HD.png


Pick.png


Pitaara International.png


Politics Punjab.png


Pop Max Plus 1.png


Pop Max.png


Pop Player.png


Pop Plus 1.png


Pop.png


Premier Sports 1 HD.png


Premier Sports 2 HD.png


Prime Video.png


Propeller TV.png


QVC Beauty.png


QVC Extra.png


QVC HD.png


QVC Style HD.png


QVC Style.png


QVC.png


Quest HD.png


Quest Plus 1.png


Quest Red Plus 1.png


Quest Red.png


Quest.png


RTE Junior.png


RTE News.png


RTE One HD.png


RTE One Plus 1.png


RTE One.png


RTE2 HD.png


RTE2 Plus 1.png


RTE2.png


Racing TV HD.png


Racing TV.png


RallyTV.png

RealityXtra 2.png

RealityXtra.png

Really.png


Revelation TV.png


Rok.png


Rotana Cinema.png


Rotana Classic.png


S4C HD.png


S4C.png


SBN International.png


SES.png


SPFL.png

STV HD.png


STV Plus 1.png


STV.png


SUMTV.png


Sacred SitesPlus.png


Samaa TV.png


Sangat Television.png


Sanskar TV.png


Saorview.png


Scottish Cup.png

Sharjah TV.png


Sheffield Live TV.png


Sikh Channel.png


Siraj TV.png


Sky Arts HD.png


Sky Arts.png


Sky Atlantic HD.png


Sky Atlantic Plus 1.png


Sky Atlantic.png


Sky Cinema 5 Star Movies HD.png


Sky Cinema 5 Star Movies.png


Sky Cinema Action HD.png


Sky Cinema Action.png


Sky Cinema Adventure HD.png


Sky Cinema Adventure.png


Sky Cinema Alien HD.png


Sky Cinema Animation HD.png


Sky Cinema Assassins HD.png


Sky Cinema Back to School HD.png


Sky Cinema Batman HD.png


Sky Cinema Batman.png


Sky Cinema Best of the 00s HD.png


Sky Cinema Best of the 80s HD.png


Sky Cinema Best of the 90s HD.png


Sky Cinema Blockbusters HD.png


Sky Cinema Book Day HD.png


Sky Cinema Book Day.png


Sky Cinema Bourne HD.png


Sky Cinema Bourne.png


Sky Cinema British Icons HD.png


Sky Cinema British Icons.png


Sky Cinema Brits HD.png


Sky Cinema Christmas HD.png


Sky Cinema Christmas.png


Sky Cinema Comedy HD.png


Sky Cinema Cops and Robbers HD.png


Sky Cinema Cops and Robbers.png


Sky Cinema Cornetto HD.png


Sky Cinema Cult Classics HD.png


Sky Cinema Cult Classics.png


Sky Cinema Divergent HD.png


Sky Cinema Drama HD.png


Sky Cinema Dystopia HD.png


Sky Cinema Edgar Wright HD.png


Sky Cinema Epics HD.png


Sky Cinema Family HD.png


Sky Cinema Family.png


Sky Cinema Fast and Furious HD.png


Sky Cinema Feel Good HD.png


Sky Cinema Gangsters HD.png


Sky Cinema Godfather HD.png


Sky Cinema Godfather.png


Sky Cinema Greats HD.png


Sky Cinema Greats.png


Sky Cinema Halloween HD.png


Sky Cinema Hanks-Giving HD.png


Sky Cinema Hanks-Giving.png


Sky Cinema Harry Potter HD.png


Sky Cinema Harry Potter.png


Sky Cinema Hits HD.png


Sky Cinema Indiana Jones HD.png


Sky Cinema Indiana Jones.png


Sky Cinema Jokers HD.png


Sky Cinema Jurassic Park HD.png


Sky Cinema Jurassic Park.png


Sky Cinema Jurassic World HD.png


Sky Cinema Kids Books HD.png


Sky Cinema Kids Books.png


Sky Cinema Killer Movies HD.png


Sky Cinema Lord of the Rings HD.png


Sky Cinema Lord of the Rings.png


Sky Cinema Magic HD.png


Sky Cinema Mega Hits HD.png


Sky Cinema Misfits HD.png


Sky Cinema Misfits.png


Sky Cinema Monsters HD.png


Sky Cinema Monsters.png


Sky Cinema Music HD.png


Sky Cinema Musicals HD.png


Sky Cinema Musicals.png


Sky Cinema Must See Movies HD.png


Sky Cinema Mystery HD.png


Sky Cinema Mystery.png


Sky Cinema Original vs Remake HD.png


Sky Cinema Original vs Remake.png


Sky Cinema Oscars HD.png


Sky Cinema Premiere HD.png


Sky Cinema Premiere Plus 1.png


Sky Cinema Premiere.png


Sky Cinema Pride HD.png


Sky Cinema Race Against Time HD.png


Sky Cinema Resident Evil HD.png


Sky Cinema Road Movies HD.png


Sky Cinema Rom Coms HD.png


Sky Cinema Scifi Horror HD.png


Sky Cinema Select HD.png


Sky Cinema Slasher HD.png


Sky Cinema Space Week HD.png


Sky Cinema Spider-Man HD.png


Sky Cinema Spies HD.png


Sky Cinema Spooky HD.png


Sky Cinema Spooky.png


Sky Cinema Star Wars HD.png


Sky Cinema Summer Scares HD.png


Sky Cinema Summer Scares.png


Sky Cinema Superheroes HD.png


Sky Cinema Supernatural HD.png


Sky Cinema The Lord of the Rings HD.png


Sky Cinema The Matrix HD.png


Sky Cinema The Matrix.png


Sky Cinema Thriller HD.png


Sky Cinema Tom Cruise HD.png


Sky Cinema Tom Cruise.png


Sky Cinema Transformers HD.png


Sky Cinema True Stories HD.png


Sky Cinema Twilight HD.png


Sky Cinema Ultimate Action HD.png


Sky Cinema Undead HD.png


Sky Cinema Valentine HD.png


Sky Cinema Vengeance HD.png


Sky Cinema Vengeance.png


Sky Cinema Villains HD.png


Sky Cinema Villains.png


Sky Cinema Wizarding World HD.png


Sky Cinema Wizarding World.png


Sky Cinema Women in Film HD.png


Sky Cinema Women in Film.png


Sky Comedy HD.png


Sky Comedy.png


Sky Crime HD.png


Sky Crime Plus 1.png


Sky Crime.png


Sky Documentaries HD.png


Sky Documentaries.png


Sky History HD.png


Sky History Plus 1.png


Sky History.png


Sky History2 HD.png


Sky History2.png


Sky Max HD.png


Sky Max.png


Sky Mix.png

Sky Nature HD.png


Sky Nature.png


Sky News Arabia.png


Sky News HD.png


Sky News.png


Sky Replay.png


Sky Sci-Fi HD.png


Sky Sci-Fi.png


Sky Showcase HD.png


Sky Showcase Plus 1.png


Sky Showcase.png


Sky Sports + HD.png

Sky Sports +.png

Sky Sports 1 UHD.png

Sky Sports 2 UHD.png

Sky Sports Action HD.png


Sky Sports Action.png


Sky Sports Arena HD.png


Sky Sports Arena.png


Sky Sports Box Office HD.png


Sky Sports Box Office.png


Sky Sports Cricket HD.png


Sky Sports Cricket.png


Sky Sports Darts HD.png


Sky Sports Darts.png


Sky Sports F1 HD.png


Sky Sports F1 UHD.png

Sky Sports F1.png


Sky Sports Football HD.png


Sky Sports Football.png


Sky Sports Golf HD.png


Sky Sports Golf.png


Sky Sports Main Event HD.png


Sky Sports Main Event UHD.png


Sky Sports Main Event.png


Sky Sports Masters HD.png


Sky Sports Masters.png


Sky Sports Mix HD.png


Sky Sports Mix.png


Sky Sports NFL HD.png


Sky Sports NFL.png


Sky Sports News HD.png


Sky Sports News.png


Sky Sports One UHD.png

Sky Sports Premier League HD.png


Sky Sports Premier League.png


Sky Sports Racing HD.png


Sky Sports Racing.png


Sky Sports Red Button.png


Sky Sports Ryder Cup HD.png


Sky Sports Ryder Cup.png


Sky Sports Tennis HD.png

Sky Sports Tennis.png

Sky Sports The Hundred HD.png


Sky Sports The Hundred.png


Sky Sports The Lions HD.png


Sky Sports The Lions.png


Sky Sports The Open HD.png


Sky Sports The Open.png


Sky Sports The Players HD.png


Sky Sports The Players.png


Sky Sports Two UHD.png

Sky Sports Womens Cricket World Cup HD.png


Sky Sports Womens Cricket World Cup.png


Sky Witness HD.png


Sky Witness Plus 1.png


Sky Witness.png


Sky.png


SmileTV2.png


SmileTV3.png


Sony Entertainment Television HD.png


Sony Entertainment Television.png


Sony Max 2.png


Sony Max HD.png


Sony Max.png


Sony Sab.png


SportyStuff TV.png


Star Cinema.png


Stingray CMusic.png


Stingray Karaoke.png


Stingray Naturescape.png


Stingray Qello.png


Supreme Master TV.png


Syria TV.png


TBN UK.png


TCC.png


TCM Movies Plus 1.png


TCM Movies.png


TG4 HD.png


TG4.png


TJC Beauty.png


TJC HD.png


TJC.png


TLC HD.png


TLC Plus 1.png


TLC.png


TNT Sports 1 HD.png

TNT Sports 1.png

TNT Sports 10 HD.png

TNT Sports 10.png

TNT Sports 11.png

TNT Sports 12.png

TNT Sports 13.png

TNT Sports 14.png

TNT Sports 15.png

TNT Sports 16.png

TNT Sports 17.png

TNT Sports 18.png

TNT Sports 2 HD.png

TNT Sports 2.png

TNT Sports 3 HD.png

TNT Sports 3.png

TNT Sports 4 HD.png

TNT Sports 4.png

TNT Sports 5 HD.png

TNT Sports 5.png

TNT Sports 6 HD.png

TNT Sports 6.png

TNT Sports 7 HD.png

TNT Sports 7.png

TNT Sports 8 HD.png

TNT Sports 8.png

TNT Sports 9 HD.png

TNT Sports 9.png

TNT Sports Box Office 2 HD.png

TNT Sports Box Office 2.png

TNT Sports Box Office HD.png

TNT Sports Box Office.png

TNT Sports Ultimate UHD.png

TNT Sports Ultimate.png

TRT World HD.png


TRT World.png


TV One.png


TV Warehouse Plus 1.png


TV Warehouse.png


TVC News.png


TVP World.png


TVX 40Plus.png


Takbeer TV.png


TalkTV HD.png


TalkTV.png


Talking Pictures TV.png


Teesside Local TV.png


Television X.png


Thats 60s.png


Thats 70s.png


Thats 80s.png


Thats 90s.png


Thats TV Christmas.png


Thats TV.png


The Adult Channel.png


The Box.png


The Craft Channel.png


The Pet Collective.png


The Racing Partnership.png


The Word Network.png


Times Now Navbharat.png


Tiny Pop Plus 1.png


Tiny Pop.png


Together TV Plus 1.png


Together TV.png


Trace Brazuca.png


Trace Hits.png


Trace Latina.png


Trace Urban.png


Trace Vault.png


Trace Xmas.png


Travelxp.png


Tyne and Wear Local TV.png


U&Alibi.png

U&Dave.png

U&Drama.png

U&Eden.png

U&Gold.png

U&W.png

U&Yesterday.png

UK Radio Portal.png


UTV HD.png


UTV Plus 1.png


UTV.png


Ultra HD.png


Unreel.png


Utsav Bharat.png


Utsav Gold HD.png


Utsav Gold.png


Utsav Plus HD.png


Utsav Plus.png


VTC.png


Viaplay Sports 1.png


Viaplay Sports 2.png


Viaplay Xtra.png


Virgin Media Four.png


Virgin Media One HD.png


Virgin Media One Plus 1.png


Virgin Media One.png


Virgin Media Three.png


Virgin Media Two HD.png


Virgin Media Two.png


VisionTV.png


Vivaldi.png


Vox Africa.png


W HD.png


W Plus 1.png


W.png


WBC Live Channel.png


WION.png


WildEarth.png


XXX College.png


XXX Girl Girl.png


XXX Public Pickups.png


Xpanded TV.png


Yaaas.png


Yanga TV.png


Yesterday HD.png


Yesterday Plus 1.png


Yesterday.png


Zee Cinema.png


Zee TV HD.png


Zee TV.png


Zoom.png"""
    
    # Extract PNG files - split by lines and filter for PNG files
    lines = content.split('\n')
    png_files = []
    seen = set()
    
    for line in lines:
        line = line.strip()
        if line and line.lower().endswith('.png'):
            if line.lower() not in seen:
                png_files.append(line)
                seen.add(line.lower())
    
    # Create JSON structure
    json_data = create_json_structure(png_files)
    
    # Write to JSON file
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(json_data, f, indent=2, ensure_ascii=False)
        
        print(f"Successfully created {output_file} with {len(json_data)} entries.")
        print(f"First few entries:")
        for i, entry in enumerate(json_data[:5]):
            print(f"  {i+1}. {entry}")
            
    except Exception as e:
        print(f"Error writing JSON file: {e}")

if __name__ == "__main__":
    main()