package app.playlistplayer.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    private val parser = XmltvParser(StaxXmlPullSource)

    /** "Now" when the fixture was captured (provider spike, 2026-10-29 ~22:30 UTC). */
    private val captureNow = millis("2026-10-29T22:30:00Z")

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private class Collecting(private val resolve: (List<XmltvChannel>) -> Set<String>? = { null }) : XmltvHandler {
        val channels = mutableListOf<XmltvChannel>()
        val batches = mutableListOf<List<XmltvProgramme>>()
        var channelsCompleteCalls = 0
        val programmes get() = batches.flatten()

        override fun onChannel(channel: XmltvChannel) {
            channels += channel
        }

        override fun onChannelsComplete(): Set<String>? {
            channelsCompleteCalls++
            return resolve(channels)
        }

        override fun onProgrammes(batch: List<XmltvProgramme>) {
            batches += batch
        }
    }

    private fun parse(input: InputStream, options: XmltvParseOptions, handler: Collecting = Collecting()) =
        parser.parse(input, options, handler) to handler

    private fun xml(body: String) = ByteArrayInputStream(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><tv>$body</tv>".toByteArray(Charsets.UTF_8),
    )

    @Test
    fun fixtureImportsOnlyWantedChannelsWithinWindow() {
        val options = XmltvParseOptions(
            wantedChannelIds = setOf("BBC1.UK", "ITV1.uk", "not-in-feed"),
            fromMillis = millis("2026-10-29T00:00:00Z"),
            toMillis = millis("2026-10-30T00:00:00Z"),
        )
        val (result, handler) = parse(TestFixtures.stream("xmltv_sample.xml"), options)

        assertThat(handler.channels.map { it.id }).containsExactly("bbc1.uk", "ITV1.uk").inOrder()
        val bbc1 = handler.channels.first()
        assertThat(bbc1.displayNames).containsExactly("UK: BBC 1 HD ◉")
        assertThat(bbc1.iconUrl).startsWith("http://example.invalid/")

        val programmes = handler.programmes
        assertThat(programmes).hasSize(18)
        assertThat(programmes.groupingBy { it.channelId }.eachCount())
            .containsExactly("bbc1.uk", 10, "ITV1.uk", 8)
        programmes.forEach {
            assertThat(it.stopMillis!!).isGreaterThan(options.fromMillis)
            assertThat(it.startMillis).isLessThan(options.toMillis)
        }
        // A programme straddling the window start is kept (overlap semantics).
        assertThat(programmes.first().startMillis).isLessThan(options.fromMillis)

        assertThat(result.channelsSeen).isEqualTo(10)
        assertThat(result.channelsKept).isEqualTo(2)
        assertThat(result.programmesSeen).isEqualTo(135)
        assertThat(result.programmesKept).isEqualTo(18)
        assertThat(result.programmesInvalid).isEqualTo(0)
        assertThat(result.batchesEmitted).isEqualTo(1)
        assertThat(handler.channelsCompleteCalls).isEqualTo(1)
    }

    @Test
    fun pastProgrammesRetainedWhenWindowStartsInThePast() {
        // Window = archive (3 days back) .. +2 days, like the import does.
        val options = XmltvParseOptions(
            wantedChannelIds = null,
            fromMillis = captureNow - 3 * 86_400_000L,
            toMillis = captureNow + 2 * 86_400_000L,
        )
        val (_, handler) = parse(TestFixtures.stream("xmltv_sample.xml"), options)
        assertThat(handler.programmes).hasSize(135)
        assertThat(handler.programmes.count { it.startMillis < captureNow }).isEqualTo(81)
        assertThat(handler.channels).hasSize(10)

        // A window starting now drops programmes that already ended.
        val (_, fromNow) = parse(TestFixtures.stream("xmltv_sample.xml"), options.copy(fromMillis = captureNow))
        assertThat(fromNow.programmes.none { it.stopMillis!! <= captureNow }).isTrue()
        assertThat(fromNow.programmes.size).isLessThan(135)
    }

    @Test
    fun fixtureFieldsAndEntitiesDecoded() {
        val (_, handler) = parse(TestFixtures.stream("xmltv_sample.xml"), XmltvParseOptions(wantedChannelIds = null))
        val first = handler.programmes.first()
        assertThat(first.channelId).isEqualTo("bbc1.uk")
        assertThat(first.startMillis).isEqualTo(millis("2026-10-28T22:00:00Z"))
        assertThat(first.stopMillis).isEqualTo(millis("2026-10-28T22:30:00Z"))
        assertThat(first.title).isEqualTo("BBC News at Ten")
        assertThat(first.description).startsWith("The latest national")
        val text = handler.programmes.joinToString { "${it.title} ${it.description}" }
        assertThat(text).doesNotContain("&apos;")
        assertThat(text).doesNotContain("&amp;")
        assertThat(text).contains("'")
    }

    @Test
    fun categoriesEpisodeNumbersSubTitleAndMissingStop() {
        val (result, handler) = parse(
            xml(
                """
                <channel id="a"><display-name lang="en">A One</display-name><display-name>A1</display-name>
                  <icon src=" http://example.invalid/a.png "/><url>http://example.invalid</url></channel>
                <programme start="20261029190000 +0000" stop="20261029200000 +0000" channel="a">
                  <title lang="en">Show</title><title lang="nl">Toon</title><sub-title>Pilot</sub-title>
                  <desc><![CDATA[Fish & chips]]></desc>
                  <credits><actor>Someone</actor></credits>
                  <category>Drama</category><category>Comedy</category>
                  <episode-num system="xmltv_ns">0.1.</episode-num><episode-num system="onscreen">S01E02</episode-num>
                  <rating system="x"><value>12</value></rating>
                </programme>
                <programme start="20261029200000 +0000" channel="a"><title>Open ended</title></programme>
                """,
            ),
            XmltvParseOptions(wantedChannelIds = setOf("A")),
        )
        assertThat(handler.channels.single()).isEqualTo(XmltvChannel("a", listOf("A One", "A1"), "http://example.invalid/a.png"))
        val (show, open) = handler.programmes
        assertThat(show.title).isEqualTo("Show")
        assertThat(show.subTitle).isEqualTo("Pilot")
        assertThat(show.description).isEqualTo("Fish & chips")
        assertThat(show.categories).containsExactly("Drama", "Comedy").inOrder()
        assertThat(show.episodeNumbers).containsExactly(
            XmltvEpisodeNumber("xmltv_ns", "0.1."),
            XmltvEpisodeNumber("onscreen", "S01E02"),
        ).inOrder()
        assertThat(open.stopMillis).isNull()
        assertThat(open.categories).isEmpty()
        assertThat(result.programmesKept).isEqualTo(2)
    }

    @Test
    fun invalidProgrammesAreSkippedAndCounted() {
        val (result, handler) = parse(
            xml(
                """
                <programme start="garbage" stop="20261029200000" channel="a"><title>x</title></programme>
                <programme start="20261029200000" stop="20261029200000" channel="a"><title>zero</title></programme>
                <programme start="20261029200000" stop="20261029210000"><title>no channel</title></programme>
                <programme start="20261029200000" stop="20261029210000" channel="a"><title>ok</title></programme>
                """,
            ),
            XmltvParseOptions(wantedChannelIds = null),
        )
        assertThat(handler.programmes.map { it.title }).containsExactly("ok")
        assertThat(result.programmesInvalid).isEqualTo(3)
    }

    @Test
    fun onChannelsCompleteNarrowsProgrammesForNameMatching() {
        // Collect every channel, match by name, then keep programmes only for the matched id.
        val handler = Collecting { channels ->
            EpgChannelMatcher.match(
                listOf(EpgPlaylistChannel(key = "1", tvgId = null, name = "UK: BBC 2 ᴴᴰ")),
                channels,
            ).wantedXmltvChannelIds
        }
        val (result, _) = parse(TestFixtures.stream("xmltv_sample.xml"), XmltvParseOptions(wantedChannelIds = null), handler)
        assertThat(handler.channels).hasSize(10)
        assertThat(handler.programmes.map { it.channelId }.toSet()).containsExactly("BBC2.uk")
        assertThat(result.programmesKept).isEqualTo(15)
        assertThat(handler.channelsCompleteCalls).isEqualTo(1)
    }

    @Test
    fun batchesRespectConfiguredSize() {
        val (result, handler) = parse(
            TestFixtures.stream("xmltv_sample.xml"),
            XmltvParseOptions(wantedChannelIds = null, batchSize = 40),
        )
        assertThat(handler.batches.map { it.size }).containsExactly(40, 40, 40, 15).inOrder()
        assertThat(result.batchesEmitted).isEqualTo(4)
    }

    @Test
    fun gzippedFixtureWithoutContentTypeIsDetected() {
        val raw = TestFixtures.bytes("xmltv_sample.xml")
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        assertThat(gz[0]).isEqualTo(0x1f.toByte())
        val options = XmltvParseOptions(wantedChannelIds = setOf("bbc1.uk", "itv1.uk", "skysportsmainevent.uk"))

        val (plainResult, plain) = parse(ByteArrayInputStream(raw), options)
        val (gzResult, zipped) = parse(ByteArrayInputStream(gz), options)

        assertThat(gzResult).isEqualTo(plainResult)
        assertThat(zipped.channels).isEqualTo(plain.channels)
        assertThat(zipped.programmes).isEqualTo(plain.programmes)
        // Case-insensitive wanted set keeps both case variants of the Sky Sports id.
        assertThat(plain.channels.map { it.id }).containsAtLeast("skysportsmainevent.uk", "SkySportsMainEvent.uk")
        assertThat(plainResult.programmesKept).isEqualTo(60)
    }

    @Test
    fun malformedXmlMidwayThrowsTypedErrorAndReportsEmittedBatches() {
        val body = buildString {
            append("<channel id=\"a\"><display-name>A</display-name></channel>")
            for (n in 0 until 2500) {
                append("<programme start=\"${XmltvTestData.stamp(n)}\" stop=\"${XmltvTestData.stamp(n + 1)}\" channel=\"a\"><title>P$n</title></programme>")
            }
            append("<programme start=\"20261029200000\" stop=\"20261029210000\" channel=\"a\"><title>broken</titl></programme>")
            append("<programme start=\"20261029200000\" stop=\"20261029210000\" channel=\"a\"><title>after</title></programme>")
        }
        val handler = Collecting()
        val error = assertThrows(XmltvParseException::class.java) {
            parser.parse(xml(body), XmltvParseOptions(wantedChannelIds = null, batchSize = 1000), handler)
        }
        assertThat(error.batchesEmitted).isEqualTo(2)
        assertThat(error.programmesEmitted).isEqualTo(2000)
        assertThat(handler.batches).hasSize(2) // the partial third batch (500) was dropped
        assertThat(error.location).isNotNull()
        assertThat(error.cause).isInstanceOf(XmlPullException::class.java)
    }

    @Test
    fun truncatedDocumentThrows() {
        val raw = TestFixtures.bytes("xmltv_sample.xml")
        val error = assertThrows(XmltvParseException::class.java) {
            parser.parse(ByteArrayInputStream(raw, 0, raw.size / 2), XmltvParseOptions(wantedChannelIds = null), Collecting())
        }
        assertThat(error.batchesEmitted).isEqualTo(0)
    }

    @Test
    fun truncatedGzipThrows() {
        val raw = TestFixtures.bytes("xmltv_sample.xml")
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        assertThrows(XmltvParseException::class.java) {
            parser.parse(ByteArrayInputStream(gz, 0, gz.size / 2), XmltvParseOptions(wantedChannelIds = null), Collecting())
        }
    }

    @Test
    fun nonXmlAndWrongRootAreRejected() {
        val inputs = listOf(
            "<!DOCTYPE html><html><body>404 Not Found</body></html>",
            "{\"error\":\"not found\"}",
            "",
            "<?xml version=\"1.0\"?><rss><channel id=\"x\"/></rss>",
        )
        for (input in inputs) {
            assertThrows(input, XmltvParseException::class.java) {
                parser.parse(ByteArrayInputStream(input.toByteArray()), XmltvParseOptions(wantedChannelIds = null), Collecting())
            }
        }
    }

    @Test
    fun externalEntitiesAreNotResolved() {
        val input = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
            <tv><programme start="20261029200000" stop="20261029210000" channel="a"><title>&xxe;</title></programme></tv>"""
        val handler = Collecting()
        val outcome = runCatching {
            parser.parse(ByteArrayInputStream(input.toByteArray()), XmltvParseOptions(wantedChannelIds = null), handler)
        }
        // Either rejected, or parsed without the entity's content; never file contents.
        if (outcome.isFailure) {
            assertThat(outcome.exceptionOrNull()).isInstanceOf(XmltvParseException::class.java)
        } else {
            assertThat(handler.programmes.single().title).isNull()
        }
    }
}

internal object XmltvTestData {
    private val base = Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()
    private val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)

    /** Minute [n] after 2026-10-29T00:00Z as an XMLTV UTC timestamp. */
    fun stamp(n: Int): String = format.format(Instant.ofEpochMilli(base + n * 60_000L)) + " +0000"
}
