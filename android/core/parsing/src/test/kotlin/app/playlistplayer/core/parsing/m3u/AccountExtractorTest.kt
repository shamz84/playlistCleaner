package app.playlistplayer.core.parsing.m3u

import app.playlistplayer.core.model.XtreamAccount
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountExtractorTest {
    @Test
    fun fixtureYieldsMajorityAccountAndMixedFlag() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/playlist_sample.m3u"))
        val extractor = AccountExtractor()
        M3uParser().open(stream).use { reader -> reader.forEach { extractor.add(it.url) } }
        val result = extractor.result()

        assertThat(result.account).isEqualTo(XtreamAccount("http", "example.invalid", 8080, "user", "pass"))
        assertThat(result.mixedAccounts).isTrue()
        assertThat(result.distinctAccounts).isEqualTo(2)
        assertThat(result.matchingUrls).isEqualTo(9)
        assertThat(result.totalUrls).isEqualTo(10)
        assertThat(result.isXtreamShaped).isTrue()
    }

    @Test
    fun singleAccountIsNotMixed() {
        val result = AccountExtractor.fromUrls(
            listOf(
                "http://example.invalid:8080/live/user/pass/1.ts",
                "http://example.invalid:8080/live/user/pass/2.m3u8",
                "http://example.invalid:8080/movie/user/pass/3.mkv",
            ),
        )
        assertThat(result.mixedAccounts).isFalse()
        assertThat(result.account?.username).isEqualTo("user")
    }

    @Test
    fun differentPasswordOnSameHostCountsAsMixed() {
        val result = AccountExtractor.fromUrls(
            listOf(
                "http://example.invalid/live/user/pass/1.ts",
                "http://example.invalid/live/user/pass/2.ts",
                "http://example.invalid/live/user/other/3.ts",
            ),
        )
        assertThat(result.mixedAccounts).isTrue()
        assertThat(result.account?.password).isEqualTo("pass")
        assertThat(result.account?.port).isNull()
    }

    @Test
    fun getPhpUrlDerivesAccount() {
        val account = AccountExtractor.fromUrl("http://example.invalid:2095/get.php?username=user&password=p%40ss&type=m3u_plus&output=ts")
        assertThat(account).isEqualTo(XtreamAccount("http", "example.invalid", 2095, "user", "p@ss"))
        assertThat(AccountExtractor.fromUrl("https://example.invalid/player_api.php?password=pass&username=user"))
            .isEqualTo(XtreamAccount("https", "example.invalid", null, "user", "pass"))
    }

    @Test
    fun getPhpWithoutCredentialsIsNotXtream() {
        assertThat(AccountExtractor.fromUrl("http://example.invalid/get.php?type=m3u")).isNull()
        assertThat(AccountExtractor.fromUrl("http://example.invalid/get.php?username=&password=")).isNull()
    }

    @Test
    fun nonXtreamUrlsYieldNoAccount() {
        val result = AccountExtractor.fromUrls(
            listOf("https://radio.example.invalid/stream.mp3", "https://cdn.example.invalid/hls/channel/index.m3u8"),
        )
        assertThat(result.account).isNull()
        assertThat(result.isXtreamShaped).isFalse()
        assertThat(result.totalUrls).isEqualTo(2)
    }

    @Test
    fun defaultPortIsNormalisedAndPlusIsLiteralInPath() {
        val account = AccountExtractor.fromUrl("http://example.invalid:80/live/us%20er/pa+ss/1.ts")
        assertThat(account).isEqualTo(XtreamAccount("http", "example.invalid", null, "us er", "pa+ss"))
    }

    @Test
    fun streamIdAndKindExtracted() {
        val live = XtreamUrl.parse("http://example.invalid:8080/live/user/pass/12345.ts")!!
        assertThat(live.kind).isEqualTo(XtreamUrl.Kind.LIVE)
        assertThat(live.streamId).isEqualTo(12345L)
        assertThat(live.extension).isEqualTo("ts")
        val movie = XtreamUrl.parse("http://example.invalid/movie/user/pass/77.mkv")!!
        assertThat(movie.kind).isEqualTo(XtreamUrl.Kind.MOVIE)
        assertThat(XtreamUrl.parse("not a url")).isNull()
        assertThat(XtreamUrl.parse("rtmp://example.invalid/live/user/pass/1")).isNull()
        assertThat(live.toString()).doesNotContain("pass")
    }

    @Test
    fun extractionToStringHidesAccount() {
        val result = AccountExtractor.fromUrls(listOf("http://example.invalid/live/user/pass/1.ts"))
        assertThat(result.toString()).doesNotContain("example.invalid")
    }
}
