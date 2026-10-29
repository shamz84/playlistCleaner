package app.playlistplayer.core.sync.catalog

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.SyncHarness
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CatalogDetailRepositoriesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private lateinit var server: MockWebServer
    private lateinit var catalog: CatalogStore
    private lateinit var account: XtreamAccount
    private lateinit var series: SeriesInfoRepository
    private lateinit var vod: VodInfoRepository
    private val playlistId = 7L

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        catalog = CatalogStore(h.db)
        server = MockWebServer()
        server.start()
        account = XtreamAccount("http", server.hostName, server.port, "alice", "s3cret")
        val client = XtreamClient(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(), Dispatchers.IO)
        val accounts: suspend (Long) -> XtreamAccount? = { if (it == playlistId) account else null }
        series = SeriesInfoRepository(catalog, accounts, client, clock = { h.now }, ttlMillis = 10_000)
        vod = VodInfoRepository(catalog, accounts, client, clock = { h.now }, ttlMillis = 10_000)
    }

    @After
    fun tearDown() {
        server.close()
        h.close()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun seriesInfoIsFetchedOnDemandAndCached() = runBlocking<Unit> {
        server.enqueue(json(fixture("get_series_info.json")))

        val first = series.load(playlistId, 52015) as DetailResult.Loaded
        assertThat(first.fromCache).isFalse()
        val details = first.value
        assertThat(details.info.tmdb).isEqualTo("46511")
        assertThat(details.info.plot).contains("Utopia")
        assertThat(details.seasons.map { it.seasonNumber }).containsExactly(0, 1, 2).inOrder()
        assertThat(details.seasonNumbers).containsExactly(1, 2).inOrder()
        assertThat(details.episodes.first().episodeId).isEqualTo("2210872")
        assertThat(details.episodes.first().durationSecs).isEqualTo(3594L)
        assertThat(details.episodes.map { it.sortOrder }).isInOrder()

        h.now += 1_000
        val second = series.load(playlistId, 52015) as DetailResult.Loaded
        assertThat(second.fromCache).isTrue()
        assertThat(second.value.episodes).isEqualTo(details.episodes)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun seasonsWithTwoDigitKeysSortNumerically() = runBlocking<Unit> {
        server.enqueue(
            json(
                """{"info":{"name":"Long"},"episodes":{"10":[{"id":"10-1","episode_num":1}],"1":[{"id":"1-2","episode_num":2},{"id":"1-1","episode_num":1}],"2":[{"id":"2-1","episode_num":1}]}}""",
            ),
        )

        val loaded = series.load(playlistId, 1) as DetailResult.Loaded

        assertThat(loaded.value.episodes.map { it.episodeId }).containsExactly("1-1", "1-2", "2-1", "10-1").inOrder()
        assertThat(catalog.episodes(playlistId, 1).map { it.season }).containsExactly(1, 1, 2, 10).inOrder()
    }

    @Test
    fun expiredCacheRefreshFailureReturnsStaleCopy() = runBlocking<Unit> {
        server.enqueue(json(fixture("get_series_info.json")))
        series.load(playlistId, 52015)
        h.now += 20_000
        server.enqueue(json("x", code = 500))

        val result = series.load(playlistId, 52015) as DetailResult.Loaded

        assertThat(result.stale).isTrue()
        assertThat(result.fromCache).isTrue()
        assertThat(result.value.episodes).isNotEmpty()
    }

    @Test
    fun newerCatalogLastModifiedInvalidatesCache() = runBlocking<Unit> {
        h.now = 5_000_000
        server.enqueue(json(fixture("get_series_info.json")))
        series.load(playlistId, 52015)
        // The catalog now says the series changed after our fetch.
        val generation = catalog.beginGeneration(playlistId)
        catalog.writer(playlistId, generation).insertSeries(
            listOf(SeriesEntity(playlistId, generation, 52015, "EN - Utopia", null, null, null, null, null, "156", "46511", lastModified = 6_000, providerOrder = 0)),
        )
        catalog.activateGeneration(playlistId, generation) { it }
        h.now += 1_000
        server.enqueue(json(fixture("get_series_info.json")))

        val result = series.load(playlistId, 52015) as DetailResult.Loaded

        assertThat(result.fromCache).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun uncachedFailureIsRedacted() = runBlocking<Unit> {
        server.enqueue(json("""[garbage "alice" "s3cret" ${server.hostName}"""))

        val result = series.load(playlistId, 9) as DetailResult.Failed

        assertThat(result.kind).isEqualTo(CatalogSyncer.KIND_MALFORMED)
        assertThat(result.message).doesNotContain("alice")
        assertThat(result.message).doesNotContain("s3cret")
        assertThat(result.message).doesNotContain(server.hostName)
        assertThat(series.load(99, 9)).isInstanceOf(DetailResult.Failed::class.java)
    }

    @Test
    fun vodInfoIsFetchedAndCached() = runBlocking<Unit> {
        server.enqueue(json(fixture("get_vod_info.json")))

        val loaded = vod.load(playlistId, 2204608) as DetailResult.Loaded
        val info = loaded.value
        assertThat(info.plot).startsWith("Interviews")
        assertThat(info.cast).contains("Bob Proctor")
        assertThat(info.durationSecs).isEqualTo(5400L)
        assertThat(info.backdrop).isNotNull()
        assertThat(info.tmdb).isEqualTo("26594")
        assertThat(info.containerExtension).isEqualTo("mkv")

        assertThat((vod.load(playlistId, 2204608) as DetailResult.Loaded).fromCache).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun credentialBearingArtworkIsDropped() {
        assertThat(SafeImageUrl.filter("http://example.invalid/t/p/w600/x.jpg", account)).isNotNull()
        assertThat(SafeImageUrl.filter("http://example.invalid/images/alice/s3cret/x.jpg", account)).isNull()
        assertThat(SafeImageUrl.filter("http://example.invalid/img.php?username=alice&password=s3cret", account)).isNull()
        assertThat(SafeImageUrl.filter("file:///etc/passwd", account)).isNull()
        assertThat(SafeImageUrl.filter("", account)).isNull()
    }
}
