package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.parsing.xmltv.StaxXmlPullSource
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import app.playlistplayer.core.parsing.xmltv.XmltvChannel
import app.playlistplayer.core.parsing.xmltv.XmltvHandler
import app.playlistplayer.core.parsing.xmltv.XmltvParseException
import app.playlistplayer.core.parsing.xmltv.XmltvParseOptions
import app.playlistplayer.core.parsing.xmltv.XmltvParseResult
import app.playlistplayer.core.parsing.xmltv.XmltvParser
import app.playlistplayer.core.parsing.xmltv.XmltvProgramme
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Android pull adapter must parse exactly like the JVM StAX source the parser was built on. */
@RunWith(RobolectricTestRunner::class)
class AndroidXmlPullSourceTest {

    private class Collected(val result: XmltvParseResult, val channels: List<XmltvChannel>, val programmes: List<XmltvProgramme>)

    private fun parse(factory: XmlPullSourceFactory, xml: ByteArray, options: XmltvParseOptions = XmltvParseOptions(wantedChannelIds = null)): Collected {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<XmltvProgramme>()
        val result = XmltvParser(factory).parse(
            xml.inputStream(),
            options,
            object : XmltvHandler {
                override fun onChannel(channel: XmltvChannel) {
                    channels += channel
                }

                override fun onProgrammes(batch: List<XmltvProgramme>) {
                    programmes += batch
                }
            },
        )
        return Collected(result, channels, programmes)
    }

    private val fixture: ByteArray = EpgTestEnv.fixture("xmltv_sample.xml").toByteArray()

    @Test
    fun fixtureParsesIdenticallyToStax() {
        val android = parse(AndroidXmlPullSource, fixture)
        val stax = parse(StaxXmlPullSource, fixture)
        assertThat(android.result).isEqualTo(stax.result)
        assertThat(android.channels).isEqualTo(stax.channels)
        assertThat(android.programmes).isEqualTo(stax.programmes)
        assertThat(android.channels).hasSize(10)
        assertThat(android.programmes).hasSize(135)
        // Provider quirks survive: `&apos;` entities and superscript display names.
        assertThat(android.channels.map { it.displayNames.single() }).contains("NL: Een ᴿᴬᵂ ◉")
    }

    @Test
    fun filteredParseMatchesToo() {
        val options = XmltvParseOptions(
            wantedChannelIds = setOf("bbc1.uk", "skysportsmainevent.uk"),
            fromMillis = EpgTestEnv.NOW - EpgTestEnv.HOUR * 3,
            toMillis = EpgTestEnv.NOW + EpgTestEnv.HOUR * 6,
            batchSize = 4,
        )
        val android = parse(AndroidXmlPullSource, fixture, options)
        val stax = parse(StaxXmlPullSource, fixture, options)
        assertThat(android.programmes).isEqualTo(stax.programmes)
        assertThat(android.result).isEqualTo(stax.result)
        assertThat(android.programmes).isNotEmpty()
    }

    @Test
    fun cdataAndEntitiesMatch() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><!-- c --><tv><channel id="a"><display-name>A &amp; B</display-name></channel>
            <programme start="20261029190000 +0100" stop="20261029200000 +0100" channel="a"><title><![CDATA[Tom & Jerry]]> &#169;</title>
            <desc>It&apos;s &lt;live&gt;</desc><category>News</category><episode-num system="onscreen">E1</episode-num></programme></tv>""".toByteArray()
        val android = parse(AndroidXmlPullSource, xml)
        assertThat(android.programmes).isEqualTo(parse(StaxXmlPullSource, xml).programmes)
        assertThat(android.programmes.single().title).isEqualTo("Tom & Jerry ©")
        assertThat(android.programmes.single().description).isEqualTo("It's <live>")
    }

    @Test
    fun malformedAndNonXmlFail() {
        assertThrows(XmltvParseException::class.java) { parse(AndroidXmlPullSource, fixture.copyOf(fixture.size / 2)) }
        assertThrows(XmltvParseException::class.java) { parse(AndroidXmlPullSource, "<html><body>nope</body></html>".toByteArray()) }
        assertThrows(XmltvParseException::class.java) { parse(AndroidXmlPullSource, "{\"error\":1}".toByteArray()) }
    }
}
