package app.playlistplayer.core.network.skip

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class IntroDbClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = IntroDbClient(http, server.url("/"), Dispatchers.IO)

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun episodeRequestSendsOnlyIdSeasonAndEpisodeAndReadsMarkers() = runBlocking<Unit> {
        server.enqueue(
            json(
                """{"tmdb_id":1396,"type":"tv","season":1,"episode":1,
                   "intro":[{"start_ms":228647,"end_ms":246339}],
                   "credits":[{"start_ms":3433269,"end_ms":null}]}""",
            ),
        )

        val markers = client().markers(1396, 1, 1)

        assertThat(markers).containsExactly(
            SkipMarker(SkipMarkerType.INTRO, 228_647, 246_339),
            SkipMarker(SkipMarkerType.CREDITS, 3_433_269, null),
        ).inOrder()
        val url = server.takeRequest().url
        assertThat(url.encodedPath).isEqualTo("/v2/media")
        assertThat(url.queryParameterNames).containsExactly("tmdb_id", "season", "episode")
    }

    @Test
    fun movieRequestOmitsSeasonAndAcceptsANullIntroStart() = runBlocking<Unit> {
        server.enqueue(json("""{"tmdb_id":603,"type":"movie","intro":[{"start_ms":null,"end_ms":40000}],"credits":[]}"""))

        val markers = client().markers(603, null, null)

        assertThat(markers).containsExactly(SkipMarker(SkipMarkerType.INTRO, null, 40_000))
        assertThat(server.takeRequest().url.queryParameterNames).containsExactly("tmdb_id")
    }

    @Test
    fun unknownIdIsNoMarkersAndServerErrorsAreFailures() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(400).build())
        assertThat(client().markers(99999999, null, null)).isEmpty()

        server.enqueue(MockResponse.Builder().code(500).build())
        val failure = runCatching { client().markers(1, null, null) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SkipMarkerException::class.java)
        assertThat(failure!!.message).doesNotContain("localhost")
    }

    @Test
    fun invalidSpansAreDropped() {
        val markers = IntroDbClient.parse("""{"intro":[{"start_ms":50,"end_ms":10},{"start_ms":10,"end_ms":50}],"recap":null}""")
        assertThat(markers).containsExactly(SkipMarker(SkipMarkerType.INTRO, 10, 50))
    }
}
