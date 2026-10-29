package app.playlistplayer.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class XmltvTimeTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun providerOffsetPlusOneHourIsConvertedToUtc() {
        assertThat(XmltvTime.parseMillis("20261029190000 +0100")).isEqualTo(millis("2026-10-29T18:00:00Z"))
    }

    @Test
    fun zeroOffsetAndMissingOffsetAreUtc() {
        val expected = millis("2026-10-29T19:00:00Z")
        assertThat(XmltvTime.parseMillis("20261029190000 +0000")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029190000")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029190000 Z")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029190000 UTC")).isEqualTo(expected)
    }

    @Test
    fun offsetVariants() {
        val expected = millis("2026-10-29T18:00:00Z")
        assertThat(XmltvTime.parseMillis("20261029190000+0100")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029190000 +01:00")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029190000 +01")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029130000 -0500")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("20261029233000 +0530")).isEqualTo(expected)
        assertThat(XmltvTime.parseMillis("  20261029190000 +0100  ")).isEqualTo(expected)
    }

    @Test
    fun shorterFormsDefaultMissingFields() {
        assertThat(XmltvTime.parseMillis("202610291900 +0100")).isEqualTo(millis("2026-10-29T18:00:00Z"))
        assertThat(XmltvTime.parseMillis("2026102919")).isEqualTo(millis("2026-10-29T19:00:00Z"))
        assertThat(XmltvTime.parseMillis("20261029")).isEqualTo(millis("2026-10-29T00:00:00Z"))
        assertThat(XmltvTime.parseMillis("202610")).isEqualTo(millis("2026-10-01T00:00:00Z"))
        assertThat(XmltvTime.parseMillis("2026")).isEqualTo(millis("2026-01-01T00:00:00Z"))
    }

    @Test
    fun calendarEdgesMatchJavaTime() {
        assertThat(XmltvTime.parseMillis("20240229120000")).isEqualTo(millis("2024-02-29T12:00:00Z"))
        assertThat(XmltvTime.parseMillis("19700101000000")).isEqualTo(0L)
        assertThat(XmltvTime.parseMillis("19691231235959")).isEqualTo(-1_000L)
        assertThat(XmltvTime.parseMillis("20991231235959 +0000")).isEqualTo(millis("2099-12-31T23:59:59Z"))
        assertThat(XmltvTime.parseMillis("20000301000000")).isEqualTo(millis("2000-03-01T00:00:00Z"))
    }

    @Test
    fun invalidInputsReturnNull() {
        listOf(
            null, "", "abc", "2026102919000", "202610291900000", "20261329190000", "20260230120000",
            "20250229120000", "20261029250000", "20261029196000", "20261029190000 +01000",
            "20261029190000 CET", "20261029190000 +2500", "20261029190000 0100",
        ).forEach { assertThat(XmltvTime.parseMillis(it)).isNull() }
    }

    @Test
    fun dstCrossingDurationComesFromInstants() {
        // Europe/Amsterdam falls back at 03:00 CEST on 2026-10-25: 02:00 CEST -> 03:00 CET is 2 h.
        val start = XmltvTime.parseMillis("20261025020000 +0200")!!
        val stop = XmltvTime.parseMillis("20261025030000 +0100")!!
        assertThat(Duration.ofMillis(stop - start)).isEqualTo(Duration.ofHours(2))
        // Spring forward: 01:30 CET -> 03:30 CEST on 2026-03-29 is 1 h.
        val springStart = XmltvTime.parseMillis("20260329013000 +0100")!!
        val springStop = XmltvTime.parseMillis("20260329033000 +0200")!!
        assertThat(Duration.ofMillis(springStop - springStart)).isEqualTo(Duration.ofHours(1))
    }
}
