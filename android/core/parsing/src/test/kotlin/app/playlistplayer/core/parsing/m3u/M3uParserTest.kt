package app.playlistplayer.core.parsing.m3u

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class M3uParserTest {
    private fun parseFixture(): Pair<List<M3uEntry>, M3uReader> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/playlist_sample.m3u"))
        val reader = M3uParser().open(stream)
        val entries = reader.use { it.asSequence().toList() }
        return entries to reader
    }

    private fun parse(text: String): Pair<List<M3uEntry>, M3uReader> {
        val reader = M3uParser().open(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val entries = reader.use { it.asSequence().toList() }
        return entries to reader
    }

    @Test
    fun fixtureParsesToExpectedCountsAndOrder() {
        val (entries, reader) = parseFixture()

        assertThat(entries).hasSize(10)
        assertThat(reader.entryCount).isEqualTo(10)
        assertThat(reader.skippedWithoutUrl).isEqualTo(2)
        assertThat(reader.groups.map { it.first })
            .containsExactly("🇬🇧 TV Guide (UK)", "⚽ Sports", "🎬 Movies 24/7").inOrder()
        assertThat(reader.groups.map { it.second }).containsExactly(5, 3, 2).inOrder()
        assertThat(entries.map { it.ordinal }).isEqualTo((0 until 10).toList())
        // File order is preserved: the late UK entry keeps its file position but the UK group ordinal.
        assertThat(entries.last().name).isEqualTo("Channel 4")
        assertThat(entries.last().groupOrdinal).isEqualTo(0)
        assertThat(entries.first { it.name == "BBC One London" }.groupOrdinal).isEqualTo(0)
        assertThat(entries.first { it.name.startsWith("Movies 24/7 — Action") }.groupOrdinal).isEqualTo(2)
    }

    @Test
    fun emojiAndUnicodeNamesRoundTripUnchanged() {
        val (entries, _) = parseFixture()
        assertThat(entries.map { it.groupTitle }.distinct())
            .containsExactly("🇬🇧 TV Guide (UK)", "⚽ Sports", "🎬 Movies 24/7").inOrder()
        assertThat(entries.map { it.name }).contains("ITV1 ᴴᴰ")
        assertThat(entries.map { it.name }).contains("Sky Sports Main Event ³⁸⁴⁰ᴾ")
        assertThat(entries.map { it.name }).contains("Movies 24/7 — Comedy")
    }

    @Test
    fun attributesAreParsed() {
        val (entries, _) = parseFixture()
        val bbcOne = entries.first { it.name == "BBC One London" }
        assertThat(bbcOne.tvgId).isEqualTo("bbconelondon.uk")
        assertThat(bbcOne.tvgName).isEqualTo("BBC One London")
        assertThat(bbcOne.tvgLogo).isEqualTo("https://logos.example.invalid/bbc1.png")
        assertThat(bbcOne.cuid).isEqualTo("101")
        assertThat(bbcOne.catchupType).isEqualTo("xc")
        assertThat(bbcOne.catchupDays).isEqualTo(3)
        assertThat(bbcOne.streamId).isEqualTo(1001L)
        assertThat(bbcOne.containerExtension).isEqualTo("ts")

        val itv = entries.first { it.name == "ITV1 ᴴᴰ" }
        assertThat(itv.tvgChno).isEqualTo("103")
        assertThat(itv.catchupDays).isEqualTo(0)
        assertThat(itv.catchupType).isNull()

        assertThat(entries.first { it.name == "Channel 4" }.catchupDays).isEqualTo(7)
        // "null" literal is treated as absent.
        assertThat(entries.first().tvgChno).isNull()
    }

    @Test
    fun missingTvgIdOrLogoStillParses() {
        val (entries, _) = parseFixture()
        val bbcTwo = entries.first { it.name == "BBC Two" }
        assertThat(bbcTwo.tvgId).isNull()
        assertThat(bbcTwo.streamId).isEqualTo(1002L)
        val itv = entries.first { it.name == "ITV1 ᴴᴰ" }
        assertThat(itv.tvgLogo).isNull()
        val comedy = entries.first { it.name == "Movies 24/7 — Comedy" }
        assertThat(comedy.tvgId).isNull()
        assertThat(comedy.tvgLogo).isNull()
    }

    @Test
    fun extinfWithoutUrlIsSkipped() {
        val (entries, reader) = parseFixture()
        assertThat(entries.map { it.name }).doesNotContain("Broken Entry")
        assertThat(entries.map { it.name }).doesNotContain("Trailing Without Url")
        assertThat(reader.groups.map { it.first }).doesNotContain("Ελληνικά 🇬🇷")
        // The entry after the broken one keeps the URL that follows it.
        assertThat(entries.first { it.name.startsWith("Sky Sports Main Event ³") }.streamId).isEqualTo(2001L)
    }

    @Test
    fun headerEntriesAreFlagged() {
        val (entries, _) = parseFixture()
        assertThat(entries.filter { it.isHeader }.map { it.name }).containsExactly("### NATIONAL NETWORKS ###")
        assertThat(M3uParser.isHeaderName("####### NETFLIX PPV #######")).isTrue()
        assertThat(M3uParser.isHeaderName("#### PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ #####")).isTrue()
        assertThat(M3uParser.isHeaderName("===== NEWS =====")).isTrue()
        assertThat(M3uParser.isHeaderName("-----")).isTrue()
        assertThat(M3uParser.isHeaderName("BBC One #1")).isFalse()
        assertThat(M3uParser.isHeaderName("Sky Sports F1")).isFalse()
        assertThat(M3uParser.isHeaderName("")).isFalse()
    }

    @Test
    fun displayNameMayContainCommasAndQuotedAttributeCommasAreIgnored() {
        val (entries, _) = parseFixture()
        assertThat(entries.map { it.name }).contains("Match, Day & Night")
        val (quoted, _) = parse(
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"A, B\" group-title=\"G, H\",Display, Name\nhttp://example.invalid/live/user/pass/5.m3u8\n",
        )
        assertThat(quoted.single().tvgName).isEqualTo("A, B")
        assertThat(quoted.single().groupTitle).isEqualTo("G, H")
        assertThat(quoted.single().name).isEqualTo("Display, Name")
        assertThat(quoted.single().containerExtension).isEqualTo("m3u8")
    }

    @Test
    fun bomCrlfExtgrpAndMissingNameAreHandled() {
        val text = "﻿#EXTM3U url-tvg=\"https://epg.example.invalid/guide.xml\"\r\n" +
            "#EXTINF:-1 tvg-name=\"Named By Attribute\",\r\n" +
            "#EXTGRP:Fallback Group\r\n" +
            "http://example.invalid/user/pass/77\r\n"
        val (entries, reader) = parse(text)
        assertThat(reader.headerAttributes["url-tvg"]).isEqualTo("https://epg.example.invalid/guide.xml")
        val entry = entries.single()
        assertThat(entry.name).isEqualTo("Named By Attribute")
        assertThat(entry.groupTitle).isEqualTo("Fallback Group")
        assertThat(entry.streamId).isEqualTo(77L)
        assertThat(entry.url).isEqualTo("http://example.invalid/user/pass/77")
    }

    @Test
    fun nonXtreamUrlHasNoStreamId() {
        val (entries, _) = parse("#EXTM3U\n#EXTINF:-1 group-title=\"Radio\",Radio One\nhttps://radio.example.invalid/stream.mp3\n")
        assertThat(entries.single().streamId).isNull()
        assertThat(entries.single().containerExtension).isEqualTo("mp3")
    }

    @Test
    fun entryWithoutGroupGoesToUngrouped() {
        val (entries, reader) = parse("#EXTM3U\n#EXTINF:-1,Lonely\nhttp://example.invalid/live/user/pass/9.ts\n")
        assertThat(entries.single().groupTitle).isEqualTo(M3uParser.UNGROUPED)
        assertThat(reader.groups.single().first).isEqualTo(M3uParser.UNGROUPED)
    }

    @Test
    fun toStringNeverContainsUrl() {
        val (entries, _) = parseFixture()
        entries.forEach { assertThat(it.toString()).doesNotContain("pass") }
    }
}
