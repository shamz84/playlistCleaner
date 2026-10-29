package app.playlistplayer.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgChannelMatcherTest {

    /** The fixture's channels, as the parser reports them. */
    private val fixtureChannels: List<XmltvChannel> = mutableListOf<XmltvChannel>().also { out ->
        XmltvParser(StaxXmlPullSource).parse(
            TestFixtures.stream("xmltv_sample.xml"),
            XmltvParseOptions(wantedChannelIds = null),
            object : XmltvHandler {
                override fun onChannel(channel: XmltvChannel) {
                    out += channel
                }

                override fun onProgrammes(batch: List<XmltvProgramme>) = Unit
            },
        )
    }

    private fun match(vararg entries: EpgPlaylistChannel, byName: Boolean = true) =
        EpgChannelMatcher.match(entries.toList(), fixtureChannels, byName)

    @Test
    fun exactIdWinsOverCaseInsensitiveVariant() {
        val result = match(
            EpgPlaylistChannel("1", tvgId = "SkySportsMainEvent.uk"),
            EpgPlaylistChannel("2", tvgId = "skysportsmainevent.uk"),
        )
        assertThat(result.matches["1"]).isEqualTo(EpgChannelMatch("SkySportsMainEvent.uk", EpgMatchMethod.EXACT_ID))
        assertThat(result.matches["2"]).isEqualTo(EpgChannelMatch("skysportsmainevent.uk", EpgMatchMethod.EXACT_ID))
    }

    @Test
    fun epgChannelIdIsUsedWhenTvgIdDoesNotMatch() {
        val result = match(EpgPlaylistChannel("1", tvgId = "unknown.id", epgChannelId = "ITV1.uk", name = "whatever"))
        assertThat(result.matches["1"]).isEqualTo(EpgChannelMatch("ITV1.uk", EpgMatchMethod.EXACT_ID))
    }

    @Test
    fun caseInsensitiveOnEitherKey() {
        val result = match(
            EpgPlaylistChannel("1", tvgId = "BBC1.UK"),
            EpgPlaylistChannel("2", tvgId = null, epgChannelId = "itv1.UK"),
            EpgPlaylistChannel("3", tvgId = "  bbc2.uk  "),
        )
        assertThat(result.matches["1"]).isEqualTo(EpgChannelMatch("bbc1.uk", EpgMatchMethod.CASE_INSENSITIVE_ID))
        assertThat(result.matches["2"]).isEqualTo(EpgChannelMatch("ITV1.uk", EpgMatchMethod.CASE_INSENSITIVE_ID))
        assertThat(result.matches["3"]).isEqualTo(EpgChannelMatch("BBC2.uk", EpgMatchMethod.CASE_INSENSITIVE_ID))
    }

    @Test
    fun exactOnEpgChannelIdBeatsCaseInsensitiveTvgId() {
        val result = match(EpgPlaylistChannel("1", tvgId = "BBC1.UK", epgChannelId = "BBC2.uk"))
        assertThat(result.matches["1"]).isEqualTo(EpgChannelMatch("BBC2.uk", EpgMatchMethod.EXACT_ID))
    }

    @Test
    fun displayNameFallbackNormalizesSuperscriptsSymbolsAndQuality() {
        val result = match(
            EpgPlaylistChannel("1", tvgId = "", name = "UK: BBC 1 FHD"),
            EpgPlaylistChannel("2", tvgId = "nope", name = "nl: een"),
            EpgPlaylistChannel("3", tvgId = null, name = "IN: Utsav Plus 1080p"),
            EpgPlaylistChannel("4", tvgId = null, name = "UK: BBC 4 / CBeebies ᵁᴴᴰ"),
            EpgPlaylistChannel("5", tvgId = null, name = "DE: BBC 1 HD"), // region prefix kept
        )
        assertThat(result.matches["1"]).isEqualTo(EpgChannelMatch("bbc1.uk", EpgMatchMethod.DISPLAY_NAME))
        assertThat(result.matches["2"]).isEqualTo(EpgChannelMatch("TS", EpgMatchMethod.DISPLAY_NAME))
        assertThat(result.matches["3"]).isEqualTo(EpgChannelMatch("uk.Utsav Plus HD", EpgMatchMethod.DISPLAY_NAME))
        assertThat(result.matches["4"]).isEqualTo(EpgChannelMatch("BBC4.uk", EpgMatchMethod.DISPLAY_NAME))
        assertThat(result.matches).doesNotContainKey("5")
    }

    @Test
    fun nameFallbackCanBeDisabled() {
        val result = match(EpgPlaylistChannel("1", tvgId = null, name = "UK: BBC 1 HD ◉"), byName = false)
        assertThat(result.matches).isEmpty()
    }

    @Test
    fun entriesSharingTvgIdResolveToSameChannel() {
        val result = match(
            EpgPlaylistChannel("100", tvgId = "bbc1.uk", name = "UK: BBC 1 HD"),
            EpgPlaylistChannel("200", tvgId = "bbc1.uk", name = "UK: BBC 1 SD"),
        )
        assertThat(result.matches.values.map { it.xmltvChannelId }.toSet()).containsExactly("bbc1.uk")
        assertThat(result.wantedXmltvChannelIds).containsExactly("bbc1.uk")
    }

    @Test
    fun matchRateAndCounts() {
        val result = match(
            EpgPlaylistChannel("1", tvgId = "bbc1.uk"),
            EpgPlaylistChannel("2", tvgId = "BBC1.UK"),
            EpgPlaylistChannel("3", tvgId = null, name = "HU: Bonum"),
            EpgPlaylistChannel("4", tvgId = "missing.id", name = "US: Nothing"),
            EpgPlaylistChannel("4", tvgId = "bbc2.uk"), // duplicate key: ignored
        )
        assertThat(result.totalEntries).isEqualTo(4)
        assertThat(result.matchedEntries).isEqualTo(3)
        assertThat(result.matchRate).isWithin(1e-9).of(0.75)
        assertThat(result.countBy(EpgMatchMethod.EXACT_ID)).isEqualTo(1)
        assertThat(result.countBy(EpgMatchMethod.CASE_INSENSITIVE_ID)).isEqualTo(1)
        assertThat(result.countBy(EpgMatchMethod.DISPLAY_NAME)).isEqualTo(1)
        assertThat(result.wantedXmltvChannelIds).containsExactly("bbc1.uk", "Bonumtv.hu")

        assertThat(EpgChannelMatcher.match(emptyList(), fixtureChannels).matchRate).isEqualTo(0.0)
    }

    @Test
    fun normalizeName() {
        assertThat(EpgChannelMatcher.normalizeName("NOW: SKY SPORTS MAIN EVENT ᴴᴰ")).isEqualTo("now sky sports main event")
        assertThat(EpgChannelMatcher.normalizeName("NL: Een ᴿᴬᵂ ◉")).isEqualTo("nl een")
        assertThat(EpgChannelMatcher.normalizeName("FR: Télé Monte-Carlo 1080p50")).isEqualTo("fr tele monte carlo")
        assertThat(EpgChannelMatcher.normalizeName("◉ ᴴᴰ")).isEmpty()
    }
}
