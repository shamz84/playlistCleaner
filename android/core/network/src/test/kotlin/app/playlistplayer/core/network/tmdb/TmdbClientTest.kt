package app.playlistplayer.core.network.tmdb

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

class TmdbClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val fakeKey = "FAKE_TMDB_KEY_123"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(key: String = fakeKey) = TmdbClient(http, TmdbConfig(key, server.url("/")), Dispatchers.IO)

    private fun page(page: Int, total: Int, vararg results: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"page":$page,"total_pages":$total,"results":[${results.joinToString(",")}]}""")
        .build()

    private fun movie(id: Long, title: String, date: String?) =
        """{"id":$id,"media_type":"movie","title":"$title","original_title":"$title","release_date":${date?.let { "\"$it\"" } ?: "null"},"popularity":1.5}"""

    private fun tv(id: Long, name: String, date: String) =
        """{"id":$id,"media_type":"tv","name":"$name","original_name":"$name","first_air_date":"$date"}"""

    @Test
    fun weeklyMovieTrendingReadsPagesInRankOrderWithTheKeyAsQueryParam() = runBlocking<Unit> {
        server.enqueue(page(1, 500, movie(438631, "Dune", "2021-09-15"), movie(1, "Leo", "2023-11-21")))
        server.enqueue(page(2, 500, movie(2, "No Date", ""), movie(438631, "Dune", "2021-09-15")))
        server.enqueue(page(3, 500, movie(3, "Third", null)))

        val items = client().weeklyTrending(TmdbMediaType.MOVIE, pages = 3)

        assertThat(items.map { it.tmdbId }).containsExactly(438631L, 1L, 2L, 3L).inOrder()
        assertThat(items.first()).isEqualTo(TmdbTrendingItem(438631, "Dune", "Dune", 2021))
        assertThat(items[2].year).isNull()
        assertThat(server.requestCount).isEqualTo(3)
        val first = server.takeRequest()
        assertThat(first.url.encodedPath).isEqualTo("/3/trending/movie/week")
        assertThat(first.url.queryParameter("api_key")).isEqualTo(fakeKey)
        assertThat(first.url.queryParameter("page")).isEqualTo("1")
        assertThat(server.takeRequest().url.queryParameter("page")).isEqualTo("2")
    }

    @Test
    fun tvTrendingUsesNameAndFirstAirDateAndStopsAtTheLastPage() = runBlocking<Unit> {
        server.enqueue(page(1, 1, tv(46896, "Utopia", "2013-01-15")))

        val items = client().weeklyTrending(TmdbMediaType.TV, pages = 3)

        assertThat(items).containsExactly(TmdbTrendingItem(46896, "Utopia", "Utopia", 2013))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/3/trending/tv/week")
    }

    @Test
    fun httpErrorsNeverCarryTheKeyOrUrl() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(401).body("""{"status_message":"Invalid API key"}""").build())

        val error = runCatching { client().weeklyTrending(TmdbMediaType.MOVIE, 3) }.exceptionOrNull()

        assertThat(error).isInstanceOf(TmdbException::class.java)
        assertThat((error as TmdbException).httpCode).isEqualTo(401)
        assertThat(error.toString()).doesNotContain(fakeKey)
        assertThat(error.stackTraceToString()).doesNotContain(fakeKey)
        assertThat(error.toString()).doesNotContain(server.hostName)
    }

    @Test
    fun networkErrorsDropTheUrlBearingCause() = runBlocking<Unit> {
        val dead = server.url("/")
        server.close()

        val error = runCatching {
            TmdbClient(http, TmdbConfig(fakeKey, dead), Dispatchers.IO).weeklyTrending(TmdbMediaType.TV, 1)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(TmdbException::class.java)
        assertThat(error!!.stackTraceToString()).doesNotContain(fakeKey)
        assertThat(error.cause).isNull()
    }

    @Test
    fun malformedBodyIsATmdbException() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("<html>").build())
        val error = runCatching { client().weeklyTrending(TmdbMediaType.MOVIE, 1) }.exceptionOrNull()
        assertThat(error).isInstanceOf(TmdbException::class.java)
        assertThat(error!!.stackTraceToString()).doesNotContain(fakeKey)
    }

    @Test
    fun missingKeyMakesNoRequest() = runBlocking<Unit> {
        val error = runCatching { client(key = " ").weeklyTrending(TmdbMediaType.MOVIE, 3) }.exceptionOrNull()
        assertThat(error).isInstanceOf(TmdbException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(TmdbConfig(fakeKey).toString()).doesNotContain(fakeKey)
    }
}
