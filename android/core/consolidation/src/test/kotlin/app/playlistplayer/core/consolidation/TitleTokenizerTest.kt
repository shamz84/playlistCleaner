package app.playlistplayer.core.consolidation

import app.playlistplayer.core.consolidation.NameFixtures.liveName
import app.playlistplayer.core.consolidation.NameFixtures.vodName
import app.playlistplayer.core.consolidation.QualityTier.FHD
import app.playlistplayer.core.consolidation.QualityTier.HD
import app.playlistplayer.core.consolidation.QualityTier.LOW_SOURCE
import app.playlistplayer.core.consolidation.QualityTier.RAW
import app.playlistplayer.core.consolidation.QualityTier.SD
import app.playlistplayer.core.consolidation.QualityTier.UHD
import app.playlistplayer.core.consolidation.QualityTier.UHD_8K
import app.playlistplayer.core.consolidation.QualityTier.UNLABELED
import app.playlistplayer.core.consolidation.TokenizeMode.LIVE
import app.playlistplayer.core.consolidation.TokenizeMode.VOD
import app.playlistplayer.core.consolidation.VersionFlag.BACKUP
import app.playlistplayer.core.consolidation.VersionFlag.LOW_SOURCE as LOW
import app.playlistplayer.core.consolidation.VersionFlag.MULTI_AUDIO
import app.playlistplayer.core.consolidation.VersionFlag.MULTI_SUB
import app.playlistplayer.core.consolidation.VersionFlag.RAW_SOURCE
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TitleTokenizerTest {

    private data class Row(
        val name: String,
        val mode: TokenizeMode,
        val base: String,
        val tier: QualityTier = UNLABELED,
        val resolution: Int? = tier.nominalHeight,
        val fps: Int? = null,
        val hdr: Boolean = false,
        val codec: String? = null,
        val language: String? = null,
        val region: String? = null,
        val platforms: List<String> = emptyList(),
        val flags: Set<VersionFlag> = emptySet(),
        val year: Int? = null,
        val date: String? = null,
        val timeshift: Int = 0,
        val separator: Boolean = false,
    )

    private val liveRows = listOf(
        Row(liveName("Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ"), LIVE, "sky sports main event", UHD, hdr = true),
        Row(liveName("Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ (Backup)"), LIVE, "sky sports main event", UHD, hdr = true, flags = setOf(BACKUP)),
        Row(liveName("Sky Sports Main Event ᴴᴰ"), LIVE, "sky sports main event", HD),
        Row(liveName("Sky Sports Main Event UHD 50fps"), LIVE, "sky sports main event", UHD, fps = 50),
        Row(liveName("Sky Sports Main Event 1080p50"), LIVE, "sky sports main event", FHD, fps = 50),
        Row(liveName("Sky Sports Main Event 1080p25"), LIVE, "sky sports main event", FHD, fps = 25),
        Row(liveName("Sky Sports ᵁᴴᴰ ³⁸⁴⁰ᴾ (event)"), LIVE, "sky sports event", UHD, resolution = 2160),
        Row(liveName("Sky Sports Premier League 720p25"), LIVE, "sky sports premier league", HD, fps = 25),
        Row(liveName("TNT Sports 4 (1080p50)"), LIVE, "tnt sports 4", FHD, fps = 50),
        Row(liveName("TNT Sports Ultimate ᵁᴴᴰ ᴴᴰᴿ"), LIVE, "tnt sports ultimate", UHD, hdr = true),
        Row(liveName("Sky Sports 1 ᴴᴰᴿ"), LIVE, "sky sports 1", hdr = true),
        Row(liveName("ONEPLAY Diana Show ᴿᴬᵂ"), LIVE, "oneplay diana show", RAW, flags = setOf(RAW_SOURCE)),
        Row(liveName("DISNEY+ PREMIERE 1 ᴿᴬᵂ"), LIVE, "disney plus premiere 1", RAW, flags = setOf(RAW_SOURCE)),
        Row(liveName("Amc+ Original 1 ᴿᴬᵂ"), LIVE, "amc plus original 1", RAW, flags = setOf(RAW_SOURCE)),
        Row(liveName("NBC SPORTS NOW (A) ᴿᴬᵂ"), LIVE, "nbc sports now a", RAW, flags = setOf(RAW_SOURCE)),
        Row(liveName("ONEPLAY ACTION+1 ᴿᴬᵂ"), LIVE, "oneplay action", RAW, flags = setOf(RAW_SOURCE), timeshift = 1),
        Row(liveName("Channel 4 +1"), LIVE, "channel 4", timeshift = 1),
        Row(liveName("Pop Max+1"), LIVE, "pop max", timeshift = 1),
        Row(liveName("5 Star+1"), LIVE, "5 star", timeshift = 1),
        Row(liveName("ID +1"), LIVE, "id", timeshift = 1),
        Row(liveName("Sky Sports +"), LIVE, "sky sports plus"),
        Row(liveName("Sky Sports + 1080p50"), LIVE, "sky sports plus", FHD, fps = 50),
        Row(liveName("UK: BABY TV 4K"), LIVE, "baby tv", UHD, region = "uk"),
        Row(liveName("UK: DISNEY JR"), LIVE, "disney jr", region = "uk"),
        Row(liveName("IN: MATV NATIONAL"), LIVE, "matv national", region = "in"),
        Row(liveName("ENGLISH: HBO"), LIVE, "hbo", language = "en"),
        Row(liveName("ESPN (720p)"), LIVE, "espn", HD),
        Row(liveName("ESPN (Backup)"), LIVE, "espn", flags = setOf(BACKUP)),
        Row(liveName("NFL Redzone [Backup]"), LIVE, "nfl redzone", flags = setOf(BACKUP)),
        Row(liveName("NFL Redzone [DirecTV]"), LIVE, "nfl redzone directv"),
        Row(liveName("Monday Night Football [ESPN]"), LIVE, "monday night football espn"),
        Row(liveName("FS1 (1080p)"), LIVE, "fs 1", FHD),
        Row(liveName("MLB Network 720p60"), LIVE, "mlb network", HD, fps = 60),
        Row(liveName("SPORTSNET 360 HD"), LIVE, "sportsnet 360", HD),
        Row(liveName("SPORTSNET ONE 4K"), LIVE, "sportsnet one", UHD),
        Row(liveName("BBC One London"), LIVE, "bbc one london"),
        Row(liveName("9Go! (Gold-coast)"), LIVE, "9 go gold coast"),
        Row(liveName("U&Gold"), LIVE, "u and gold"),
        Row(liveName("Channel 4 South & East +1"), LIVE, "channel 4 south and east", timeshift = 1),
        Row(liveName("SPECTRUM NEWS 1 - (KENTUCKY) - COVINGTON ᴴᴰ"), LIVE, "spectrum news 1 kentucky covington", HD),
        Row(liveName("####### EPL PPV ⱽᴵᴾ #######"), LIVE, "epl ppv vip", separator = true),
        Row(liveName("### SPORTS ###"), LIVE, "sports", separator = true),
        Row(liveName("#####LALIGA REPLAY ᴿᴬᵂ #####"), LIVE, "laliga replay", RAW, flags = setOf(RAW_SOURCE), separator = true),
        // Synthetic forms the brief requires.
        Row("|FR| TF1 HD", LIVE, "tf 1", HD, language = "fr"),
        Row("[EN] Sky News", LIVE, "sky news", language = "en"),
        Row("Sky Cinema 8K", LIVE, "sky cinema", UHD_8K),
        Row("Eurosport 2160p HEVC", LIVE, "eurosport", UHD, codec = "hevc"),
        Row("Racing TV 576p", LIVE, "racing tv", SD, resolution = 576),
        Row("Racing TV SD", LIVE, "racing tv", SD),
        Row("Racing TV 480p", LIVE, "racing tv", SD, resolution = 480),
        Row("ꜱᴋʏ ꜱᴘᴏʀᴛꜱ ɴᴇᴡꜱ ᴴᴰ", LIVE, "sky sports news", HD),
        Row("ARTE Français", LIVE, "arte francais"),
        Row("ARTE Français", LIVE, "arte francais"),
        Row("Sky Sports Mainᴴᴰ", LIVE, "sky sports main", HD),
    )

    private val vodRows = listOf(
        Row(vodName("EN - Leo (2023)"), VOD, "leo", language = "en", year = 2023),
        Row(vodName("EN - Leo  (2023)"), VOD, "leo", language = "en", year = 2023),
        Row(vodName("4K-NF - Leo (2023)"), VOD, "leo", UHD, platforms = listOf("NF"), year = 2023),
        Row(vodName("NF - Leo (2023)"), VOD, "leo", platforms = listOf("NF"), year = 2023),
        Row(vodName("IN-MM - Leo (2023)"), VOD, "leo", language = "in-mm", year = 2023),
        Row(vodName("IN-TM  - Leo (2023)"), VOD, "leo", language = "in-tm", year = 2023),
        Row(vodName("IN - Leo (2023)"), VOD, "leo", language = "in", year = 2023),
        Row(vodName("Leo (2023)"), VOD, "leo", year = 2023),
        Row(vodName("IN-EN - Superbad (2007)"), VOD, "superbad", language = "en", year = 2007),
        Row(vodName("4K-EN - Superbad  (2007)"), VOD, "superbad", UHD, language = "en", year = 2007),
        Row(vodName("EN-TOP -63.Avengers.Infinity.War.2018"), VOD, "avengers infinity war", language = "en", platforms = listOf("TOP"), year = 2018),
        Row(vodName("EN-TOP - 237. Into the Wild (2007)"), VOD, "into the wild", language = "en", platforms = listOf("TOP"), year = 2007),
        Row(vodName("TOP - Avengers: Infinity War"), VOD, "avengers infinity war", platforms = listOf("TOP")),
        Row(vodName("D+ - Avengers: Infinity War  (2018)"), VOD, "avengers infinity war", platforms = listOf("D+"), year = 2018),
        Row(vodName("4K-MRVL - Avengers: Infinity War (2018)"), VOD, "avengers infinity war", UHD, platforms = listOf("MRVL"), year = 2018),
        Row(vodName("4K-A+ - Beastie Boys Story  (2020)"), VOD, "beastie boys story", UHD, platforms = listOf("A+"), year = 2020),
        Row(vodName("PRMT - Grease (1978)"), VOD, "grease", platforms = listOf("PRMT"), year = 1978),
        Row(vodName("4K-IN - Michael (2026)"), VOD, "michael", UHD, language = "in", year = 2026),
        Row(vodName("IN-TL - Richie.Kalyanam.2024"), VOD, "richie kalyanam", language = "in-tl", year = 2024),
        Row(vodName("IN-TU -  Final Destination Bloodlines (2025)"), VOD, "final destination bloodlines", language = "in-tu", year = 2025),
        Row(vodName("SOC - Arsenal - Liverpool 04.02.2024"), VOD, "arsenal liverpool", platforms = listOf("SOC"), year = 2024, date = "2024-02-04"),
        Row(vodName("SOC - Portland Trail Blazers at Cleveland Cavaliers 02.03.25"), VOD, "portland trail blazers at cleveland cavaliers", platforms = listOf("SOC"), year = 2025, date = "2025-03-02"),
        Row(vodName("SOC - LaLiga Highlights - Matchweek 12 06.11.2024"), VOD, "laliga highlights matchweek 12", platforms = listOf("SOC"), year = 2024, date = "2024-11-06"),
        Row(vodName("EN - Mission: Impossible - The Final Reckoning (2025) (Telesync)"), VOD, "mission impossible the final reckoning", LOW_SOURCE, language = "en", flags = setOf(LOW), year = 2025),
        Row(vodName("EN - Thunderbolts*  (2025)"), VOD, "thunderbolts", language = "en", year = 2025),
        Row(vodName("4K-D+ - Lilo & Stitch (2002)"), VOD, "lilo and stitch", UHD, platforms = listOf("D+"), year = 2002),
        Row(vodName("EN - Pete's Dragon (2016)"), VOD, "petes dragon", language = "en", year = 2016),
        Row(vodName("EN - The Bride!  (2026)"), VOD, "the bride", language = "en", year = 2026),
        Row(vodName("4K-EN - D-Day: Normandy 1944  (2014)"), VOD, "d day normandy 1944", UHD, language = "en", year = 2014),
        Row(vodName("4K-EN - Three Billboards Outside Ebbing, Missouri  (2017)"), VOD, "three billboards outside ebbing missouri", UHD, language = "en", year = 2017),
        Row(vodName("EN - Headshot"), VOD, "headshot", language = "en"),
        Row(vodName("IN - A Date (2020)"), VOD, "a date", language = "in", year = 2020),
        // Synthetic forms from the plan and brief.
        Row("EN - Dune (2021) 4K", VOD, "dune", UHD, language = "en", year = 2021),
        Row("|FR| Dune (2021)", VOD, "dune", language = "fr", year = 2021),
        Row("[EN] Dune (2021)", VOD, "dune", language = "en", year = 2021),
        Row("EN: Dune (2021)", VOD, "dune", language = "en", year = 2021),
        Row("Dune (2021) FHD", VOD, "dune", FHD, year = 2021),
        Row("EN - Dune (2021) 4K HEVC MULTI-SUB", VOD, "dune", UHD, codec = "hevc", language = "en", flags = setOf(MULTI_SUB), year = 2021),
        Row("EN - Dune (2021) MULTI", VOD, "dune", language = "en", flags = setOf(MULTI_AUDIO), year = 2021),
        Row("EN - Utopia (2013) (GB)", VOD, "utopia", language = "en", region = "gb", year = 2013),
        Row("EN - Amélie (2001)", VOD, "amelie", language = "en", year = 2001),
        Row("EN - WWE Raw  (30/06/2025)", VOD, "wwe raw", language = "en", year = 2025, date = "2025-06-30"),
        Row("EN - Cam (2018)", VOD, "cam", language = "en", year = 2018),
        Row("EN - Dual (2022)", VOD, "dual", language = "en", year = 2022),
        Row("EN - Italian Job, The (2003)", VOD, "the italian job", language = "en", year = 2003),
        Row("UFC - 300 Main Card", VOD, "ufc 300 main card"),
    )

    @Test
    fun tableOfRealAndRequiredNames() {
        for (row in liveRows + vodRows) {
            val t = TitleTokenizer.tokenize(row.name, row.mode)
            val m = assertWithMessage(row.name)
            m.that(t.baseName).isEqualTo(row.base)
            m.that(t.quality).isEqualTo(row.tier)
            m.that(t.resolution).isEqualTo(row.resolution)
            m.that(t.fps).isEqualTo(row.fps)
            m.that(t.hdr).isEqualTo(row.hdr)
            m.that(t.codec).isEqualTo(row.codec)
            m.that(t.language).isEqualTo(row.language)
            m.that(t.region).isEqualTo(row.region)
            m.that(t.platforms).isEqualTo(row.platforms)
            m.that(t.flags).isEqualTo(row.flags)
            m.that(t.year).isEqualTo(row.year)
            m.that(t.date).isEqualTo(row.date)
            m.that(t.timeshiftHours).isEqualTo(row.timeshift)
            m.that(t.isSeparator).isEqualTo(row.separator)
        }
    }

    @Test
    fun tnt1080p50ParsesAsFhd50WithBaseName() {
        val t = TitleTokenizer.tokenize(liveName("TNT Sports 4 (1080p50)"), LIVE)
        assertThat(t.quality).isEqualTo(FHD)
        assertThat(t.fps).isEqualTo(50)
        assertThat(t.baseName).isEqualTo("tnt sports 4")
        assertThat(t.cleanTitle).isEqualTo("TNT Sports 4")
    }

    @Test
    fun superscriptUhdKeysWithIts1080p50Sibling() {
        val uhd = TitleTokenizer.tokenize(liveName("Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ"), LIVE)
        val fhd = TitleTokenizer.tokenize(liveName("Sky Sports Main Event 1080p50"), LIVE)
        assertThat(uhd.baseName).isEqualTo(fhd.baseName)
        assertThat(uhd.quality.rank).isGreaterThan(fhd.quality.rank)
    }

    @Test
    fun foldingRunsBeforeTokenExtraction() {
        assertThat(TitleTokenizer.fold("ᵁᴴᴰ ᴴᴰᴿ ᴿᴬᵂ ³⁸⁴⁰ᴾ ⁴ᴷ ⱽᴵᴾ")).isEqualTo("UHD HDR RAW 3840P 4K VIP")
        assertThat(TitleTokenizer.fold("Eventᴴᴰ")).isEqualTo("Event HD")
        assertThat(TitleTokenizer.fold("ꜱᴋʏ")).isEqualTo("SKY")
    }

    @Test
    fun countryPrefixIsRegionNotLanguage() {
        for (name in listOf("UK: DISNEY JR", "UK: EPL HULL CITY", "IN: SIKH CHANNEL")) {
            val t = TitleTokenizer.tokenize(liveName(name), LIVE)
            assertWithMessage(name).that(t.language).isNull()
            assertWithMessage(name).that(t.region).isNotNull()
        }
    }

    @Test
    fun categoryNamesYieldLanguageHints() {
        assertThat(TitleTokenizer.languageOfCategory("EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ")).isEqualTo("en")
        assertThat(TitleTokenizer.languageOfCategory("IN - HINDI")).isEqualTo("in")
        assertThat(TitleTokenizer.languageOfCategory("TOP MOVIES BLURAY (MULTI-SUBS)")).isNull()
        assertThat(TitleTokenizer.languageOfCategory("NETFLIX")).isNull()
        assertThat(TitleTokenizer.languageOfCategory(null)).isNull()
    }

    @Test
    fun unparseableNamesYieldEmptyBaseWithoutThrowing() {
        for (name in listOf("", "   ", "ᴿᴬᵂ", "###", "(Backup)", "+1", "4K")) {
            val t = TitleTokenizer.tokenize(name, LIVE)
            assertWithMessage(name).that(t.isParseable).isFalse()
            TitleTokenizer.tokenize(name, VOD)
        }
    }

    @Test
    fun everyRealNameTokenizesToANonEmptyBase() {
        for (name in NameFixtures.live) {
            val t = TitleTokenizer.tokenize(name, LIVE)
            assertWithMessage(name).that(t.baseName).isNotEmpty()
            assertWithMessage(name).that(t.baseName).matches("[\\p{L}\\p{N}]+( [\\p{L}\\p{N}]+)*")
        }
        for (name in NameFixtures.vod) {
            val t = TitleTokenizer.tokenize(name, VOD)
            assertWithMessage(name).that(t.baseName).isNotEmpty()
            assertWithMessage(name).that(t.baseName).matches("[\\p{L}\\p{N}]+( [\\p{L}\\p{N}]+)*")
        }
    }

    @Test
    fun everyRealVodPrefixIsConsumed() {
        // No real VOD name should keep a known prefix code in its base name.
        val prefix = Regex("^(4K-)?[A-Z+]{2,4}(-[A-Z+]{2,4})*\\s*-\\s")
        val leaked = NameFixtures.vod.filter { name ->
            prefix.containsMatchIn(TitleTokenizer.tokenize(name, VOD).cleanTitle)
        }
        assertThat(leaked).isEmpty()
    }
}
