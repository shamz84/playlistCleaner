package app.playlistplayer.core.player.url

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamUrlBuilderTest {
    private val account = StreamAccount(scheme = "http", host = "example.invalid", username = "user", password = "pass")
    private val builder = StreamUrlBuilder(account)

    @Test
    fun liveTs() {
        assertThat(builder.live("123", LiveStreamFormat.TS)).isEqualTo("http://example.invalid/live/user/pass/123.ts")
    }

    @Test
    fun liveHls() {
        assertThat(builder.live("123", LiveStreamFormat.HLS)).isEqualTo("http://example.invalid/live/user/pass/123.m3u8")
    }

    @Test
    fun movieUsesContainerExtension() {
        assertThat(builder.movie("456", "mkv")).isEqualTo("http://example.invalid/movie/user/pass/456.mkv")
        assertThat(builder.movie("456", ".MP4")).isEqualTo("http://example.invalid/movie/user/pass/456.mp4")
    }

    @Test
    fun seriesEpisodeUsesEpisodeId() {
        assertThat(builder.seriesEpisode("789", "mkv")).isEqualTo("http://example.invalid/series/user/pass/789.mkv")
    }

    @Test
    fun portIsIncludedWhenSet() {
        val withPort = StreamUrlBuilder(StreamAccount("HTTPS", "example.invalid", 8443, "user", "pass"))
        assertThat(withPort.live("1", LiveStreamFormat.TS)).isEqualTo("https://example.invalid:8443/live/user/pass/1.ts")
    }

    @Test
    fun credentialsAndIdsArePercentEncoded() {
        val tricky = StreamUrlBuilder(StreamAccount("http", "example.invalid", null, "us er/1", "p@ss:w#rd&x=1%+ü"))
        assertThat(tricky.live("12?3", LiveStreamFormat.TS))
            .isEqualTo("http://example.invalid/live/us%20er%2F1/p%40ss%3Aw%23rd%26x%3D1%25%2B%C3%BC/12%3F3.ts")
    }

    @Test
    fun hlsChosenOnlyWhenPreferredAndAllowed() {
        assertThat(StreamUrlBuilder.selectLiveFormat(LiveFormatPreference.HLS, listOf("m3u8", "ts", "rtmp")))
            .isEqualTo(LiveStreamFormat.HLS)
        assertThat(StreamUrlBuilder.selectLiveFormat(LiveFormatPreference.HLS, listOf(" M3U8 ")))
            .isEqualTo(LiveStreamFormat.HLS)
    }

    @Test
    fun tsWhenHlsNotAllowed() {
        assertThat(StreamUrlBuilder.selectLiveFormat(LiveFormatPreference.HLS, listOf("ts", "rtmp")))
            .isEqualTo(LiveStreamFormat.TS)
        assertThat(StreamUrlBuilder.selectLiveFormat(LiveFormatPreference.HLS, emptyList()))
            .isEqualTo(LiveStreamFormat.TS)
    }

    @Test
    fun tsWhenTsPreferredEvenIfHlsAllowed() {
        assertThat(StreamUrlBuilder.selectLiveFormat(LiveFormatPreference.TS, listOf("m3u8", "ts")))
            .isEqualTo(LiveStreamFormat.TS)
    }

    @Test
    fun liveWithPreferenceAppliesSelection() {
        assertThat(builder.live("9", LiveFormatPreference.HLS, listOf("m3u8")))
            .isEqualTo("http://example.invalid/live/user/pass/9.m3u8")
        assertThat(builder.live("9", LiveFormatPreference.HLS, listOf("ts")))
            .isEqualTo("http://example.invalid/live/user/pass/9.ts")
    }

    @Test
    fun rejectsInvalidInput() {
        assertThrows(IllegalArgumentException::class.java) { builder.movie("1", "mk/v") }
        assertThrows(IllegalArgumentException::class.java) { builder.movie(" ", "mkv") }
        assertThrows(IllegalArgumentException::class.java) { StreamAccount("ftp", "example.invalid", null, "u", "p") }
        assertThrows(IllegalArgumentException::class.java) { StreamAccount("http", "example.invalid/x", null, "u", "p") }
        assertThrows(IllegalArgumentException::class.java) { StreamAccount("http", "example.invalid", 0, "u", "p") }
    }

    @Test
    fun accountToStringRedactsCredentials() {
        val text = StreamAccount("http", "example.invalid", null, "secretuser", "secretpass").toString()
        assertThat(text).doesNotContain("secretuser")
        assertThat(text).doesNotContain("secretpass")
        assertThat(text).doesNotContain("example.invalid")
    }
}
