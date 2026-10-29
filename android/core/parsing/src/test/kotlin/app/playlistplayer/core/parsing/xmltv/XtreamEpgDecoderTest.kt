package app.playlistplayer.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

class XtreamEpgDecoderTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    /** `server_info.timestamp_now` / `time_now` from the spike: 22:54:34 local = 21:54:34 UTC. */
    private val spikeNow = millis("2026-10-29T21:54:34Z")

    @Test
    fun shortEpgTitlesDecodedAndTimesFromWallClockStrings() {
        val result = XtreamEpgDecoder.decode(TestFixtures.listings("get_short_epg.json"), amsterdam)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(result.programmes.map { it.title })
            .containsExactly("Not Going Out", "BBC News and Weather", "South Today", "Question Time").inOrder()
        val first = result.programmes.first()
        assertThat(first.description).startsWith("Lee finds an undiscovered Banksy")
        assertThat(first.channelId).isEqualTo("BBCOneOxford.uk")
        assertThat(first.streamId).isEqualTo("497001")
        // 22:30 Amsterdam (CET, +01:00) = 21:30 UTC.
        assertThat(first.startMillis).isEqualTo(millis("2026-10-29T21:30:00Z"))
        assertThat(first.stopMillis).isEqualTo(millis("2026-10-29T22:00:00Z"))
        // get_short_epg's first entry is the one airing at capture time.
        assertThat(first.isAiringAt(spikeNow)).isTrue()
    }

    @Test
    fun skewedProviderTimestampsAreIgnored() {
        val listings = TestFixtures.listings("get_simple_data_table.json") + TestFixtures.listings("get_short_epg.json")
        val programmes = XtreamEpgDecoder.decode(listings, amsterdam).programmes
        assertThat(programmes).hasSize(listings.size)
        listings.zip(programmes).forEach { (raw, decoded) ->
            val skewedStart = raw.startTimestamp!!.toLong() * 1000
            val skewedStop = raw.stopTimestamp!!.toLong() * 1000
            // Every fixture date is CET; the provider fields are exactly one hour late.
            assertThat(decoded.startMillis).isEqualTo(skewedStart - 3_600_000L)
            assertThat(decoded.stopMillis).isEqualTo(skewedStop - 3_600_000L)
        }
        // Changing the timestamps does not change the result.
        val tampered = listings.map { it.copy(startTimestamp = "0", stopTimestamp = "garbage") }
        assertThat(XtreamEpgDecoder.decode(tampered, amsterdam).programmes).isEqualTo(programmes)
    }

    @Test
    fun wallTimeParsedInServerZoneForCetAndCestDates() {
        // 2026-10-25 08:45 is after the 03:00 fall-back: CET (+01:00).
        assertThat(XtreamEpgDecoder.parseServerWallTime("2026-10-25 08:45:00", amsterdam))
            .isEqualTo(millis("2026-10-25T07:45:00Z"))
        // The day before is still CEST (+02:00).
        assertThat(XtreamEpgDecoder.parseServerWallTime("2026-10-24 20:30:00", amsterdam))
            .isEqualTo(millis("2026-10-24T18:30:00Z"))
        // Summer.
        assertThat(XtreamEpgDecoder.parseServerWallTime("2026-07-01 12:00", amsterdam))
            .isEqualTo(millis("2026-07-01T10:00:00Z"))
        assertThat(XtreamEpgDecoder.parseServerWallTime("not a date", amsterdam)).isNull()
        assertThat(XtreamEpgDecoder.parseServerWallTime(null, amsterdam)).isNull()

        val fixtureCet = TestFixtures.listings("get_simple_data_table.json").first()
        assertThat(fixtureCet.start).isEqualTo("2026-10-25 08:45:00")
        assertThat(XtreamEpgDecoder.decodeListing(fixtureCet, amsterdam)!!.startMillis)
            .isEqualTo(millis("2026-10-25T07:45:00Z"))
    }

    @Test
    fun programmeSpanningFallBackUsesInstants() {
        val listing = XtreamEpgListing(start = "2026-10-25 01:30:00", end = "2026-10-25 03:30:00", title = b64("Late"))
        val p = XtreamEpgDecoder.decodeListing(listing, amsterdam)!!
        assertThat(p.startMillis).isEqualTo(millis("2026-10-24T23:30:00Z"))
        assertThat(p.stopMillis).isEqualTo(millis("2026-10-25T02:30:00Z"))
        assertThat(p.stopMillis - p.startMillis).isEqualTo(3 * 3_600_000L)

        // Inside the repeated hour: 02:45 (CEST) -> 02:15 (CET) is 30 minutes, not negative.
        val overlap = XtreamEpgListing(start = "2026-10-25 02:45:00", end = "2026-10-25 02:15:00")
        val o = XtreamEpgDecoder.decodeListing(overlap, amsterdam)!!
        assertThat(o.startMillis).isEqualTo(millis("2026-10-25T00:45:00Z"))
        assertThat(o.stopMillis).isEqualTo(millis("2026-10-25T01:15:00Z"))
    }

    @Test
    fun nowPlayingRecomputedAndRawFlagsExposed() {
        val programmes = XtreamEpgDecoder.decode(TestFixtures.listings("get_simple_data_table.json"), amsterdam).programmes
        val providerFlagged = programmes.single { it.providerNowPlaying == true }
        assertThat(providerFlagged.id).isEqualTo("35018")
        assertThat(providerFlagged.isAiringAt(spikeNow)).isFalse() // ended 20:05 UTC per the real clock

        val now = XtreamEpgDecoder.nowPlaying(programmes, spikeNow)!!
        assertThat(now.startMillis).isEqualTo(millis("2026-10-29T21:30:00Z"))
        assertThat(now.title).isEqualTo("Not Going Out")
        assertThat(now.providerNowPlaying).isFalse()

        assertThat(programmes.first { it.id == "34948" }.providerHasArchive).isTrue()
        assertThat(programmes.first().providerHasArchive).isFalse()
        val shortEpg = XtreamEpgDecoder.decode(TestFixtures.listings("get_short_epg.json"), amsterdam).programmes
        assertThat(shortEpg.first().providerHasArchive).isNull()
        assertThat(shortEpg.first().providerNowPlaying).isNull()
    }

    @Test
    fun archiveWindowHelper() {
        val p = XtreamEpgDecoder.decodeListing(
            XtreamEpgListing(start = "2026-10-26 21:30:00", end = "2026-10-26 22:00:00"),
            amsterdam,
        )!!
        // Starts 2026-10-26T20:30Z: 3 days 1 h 24 min before spikeNow.
        assertThat(p.isInArchiveWindow(spikeNow, archiveDays = 4)).isTrue()
        assertThat(p.isInArchiveWindow(spikeNow, archiveDays = 3)).isFalse()
        assertThat(p.isInArchiveWindow(p.startMillis + 1, archiveDays = 3)).isFalse() // still airing
    }

    @Test
    fun base64DecodingIsTolerant() {
        assertThat(XtreamEpgDecoder.decodeBase64Text("Tm90IEdvaW5nIE91dA==")).isEqualTo("Not Going Out")
        assertThat(XtreamEpgDecoder.decodeBase64Text("Tm90IEdvaW5nIE91dA")).isEqualTo("Not Going Out") // no padding
        assertThat(XtreamEpgDecoder.decodeBase64Text("Tm90IEdv\naW5nIE91dA==")).isEqualTo("Not Going Out")
        assertThat(XtreamEpgDecoder.decodeBase64Text(b64("Café ᴴᴰ"))).isEqualTo("Café ᴴᴰ")
        assertThat(XtreamEpgDecoder.decodeBase64Text("Plain title!")).isEqualTo("Plain title!") // not base64
        assertThat(XtreamEpgDecoder.decodeBase64Text("")).isNull()
        assertThat(XtreamEpgDecoder.decodeBase64Text(null)).isNull()
    }

    @Test
    fun invalidTimesAreSkipped() {
        val result = XtreamEpgDecoder.decode(
            listOf(
                XtreamEpgListing(start = null, end = "2026-10-29 22:00:00"),
                XtreamEpgListing(start = "2026-10-29 22:00:00", end = "2026-10-29 21:00:00"),
                XtreamEpgListing(start = "2026-13-01 10:00:00", end = "2026-13-01 11:00:00"),
                XtreamEpgListing(start = "2026-10-29 22:00:00", end = "2026-10-29 23:00:00"),
            ),
            amsterdam,
        )
        assertThat(result.programmes).hasSize(1)
        assertThat(result.skipped).isEqualTo(3)
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
