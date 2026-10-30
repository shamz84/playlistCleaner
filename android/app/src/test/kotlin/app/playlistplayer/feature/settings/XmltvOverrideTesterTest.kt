package app.playlistplayer.feature.settings

import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.parsing.xmltv.EpgPlaylistChannel
import app.playlistplayer.core.parsing.xmltv.StaxXmlPullSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Synthetic guide only (local MockWebServer, `*.example.invalid` ids). */
class XmltvOverrideTesterTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val entries = listOf(
        EpgPlaylistChannel("s:1", "bbc1.uk"),
        EpgPlaylistChannel("s:2", "ITV1.uk"),
        EpgPlaylistChannel("s:3", "unknown.uk"),
        EpgPlaylistChannel("s:4", null, name = "Channel Four HD"),
    )

    private fun tester(maxBytes: Long = XmltvOverrideTester.DEFAULT_MAX_BYTES) =
        XmltvOverrideTester(XmltvDownloader(http, Dispatchers.IO), StaxXmlPullSource.Factory, { entries }, maxBytes)

    @After
    fun tearDown() = server.close()

    @Test
    fun validGuideReportsReachableWithMatchRatePreview() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body(Guides.xmltv(programmes = 2_000)).build())

        val result = tester().test(1, server.url("/epg.xml").toString())

        assertThat(result).isEqualTo(OverrideTestResult.Reachable(channels = 3, matched = 3, total = 4, partial = false))
        assertThat((result as OverrideTestResult.Reachable).matchRate).isWithin(1e-9).of(0.75)
    }

    @Test
    fun notFoundIsAnHttpError() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        assertThat(tester().test(1, server.url("/missing.xml").toString())).isEqualTo(OverrideTestResult.HttpError(404))
    }

    @Test
    fun htmlPageIsNotXmltv() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("<html><body>Login</body></html>").build())
        assertThat(tester().test(1, server.url("/login").toString())).isEqualTo(OverrideTestResult.NotXmltv)
    }

    @Test
    fun invalidUrlsAreRejectedWithoutANetworkCall() = runBlocking<Unit> {
        assertThat(tester().test(1, "not a url")).isEqualTo(OverrideTestResult.InvalidUrl)
        assertThat(tester().test(1, "ftp://guide.example.invalid/epg.xml")).isEqualTo(OverrideTestResult.InvalidUrl)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun unreachableHostIsReported() = runBlocking<Unit> {
        val port = server.port
        server.close()
        assertThat(tester().test(1, "http://127.0.0.1:$port/epg.xml")).isEqualTo(OverrideTestResult.Unreachable)
    }

    @Test
    fun byteBudgetGivesAPartialPreview() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body(Guides.xmltv(programmes = 0, padChannels = 200)).build())
        val result = tester(maxBytes = 2_000).test(1, server.url("/big.xml").toString())
        assertThat(result).isInstanceOf(OverrideTestResult.Reachable::class.java)
        assertThat((result as OverrideTestResult.Reachable).partial).isTrue()
    }
}

object Guides {
    /** Three matching channels (exact, case-insensitive, display name) then [programmes] programmes. */
    fun xmltv(programmes: Int, padChannels: Int = 0): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        append("<channel id=\"bbc1.uk\"><display-name>BBC One</display-name></channel>\n")
        append("<channel id=\"itv1.uk\"><display-name>ITV1</display-name></channel>\n")
        append("<channel id=\"c4.example.invalid\"><display-name>Channel Four</display-name></channel>\n")
        repeat(padChannels) { append("<channel id=\"pad$it.example.invalid\"><display-name>Padding $it</display-name></channel>\n") }
        repeat(programmes) {
            append("<programme start=\"20261030120000 +0000\" stop=\"20261030130000 +0000\" channel=\"bbc1.uk\"><title>Show $it</title></programme>\n")
        }
        append("</tv>\n")
    }
}
