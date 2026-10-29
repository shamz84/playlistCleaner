package app.playlistplayer.core.sync.catalog

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.FakeSource
import app.playlistplayer.core.sync.PlaylistSyncWorker
import app.playlistplayer.core.sync.SyncHarness
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.core.sync.SyncWorkerFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CatalogSyncWorkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private lateinit var server: MockWebServer
    private lateinit var catalog: CatalogStore
    private lateinit var client: XtreamClient
    private val source = FakeSource()

    /** Per-action response overrides; default is the spike fixture. */
    private val overrides = Collections.synchronizedMap(HashMap<String, () -> MockResponse>())
    private val actions: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        catalog = CatalogStore(h.db)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.url.queryParameter("action") ?: "account"
                actions += action
                overrides[action]?.let { return it() }
                val fixture = when (action) {
                    "account" -> "user_info.json"
                    "get_live_streams" -> "get_live_streams.json"
                    "get_vod_streams" -> "get_vod_streams.json"
                    "get_series" -> "get_series.json"
                    "get_series_info" -> "get_series_info.json"
                    "get_vod_info" -> "get_vod_info.json"
                    "get_vod_categories" -> return json("""[{"category_id":"2048","category_name":"EN - MOVIES","parent_id":0},{"category_id":"429","category_name":"EN - 4K"}]""")
                    "get_series_categories" -> return json("""[{"category_id":"156","category_name":"EN - SERIES","parent_id":0}]""")
                    else -> return MockResponse.Builder().code(404).build()
                }
                return json(fixture(fixture))
            }
        }
        server.start()
        client = XtreamClient(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(), Dispatchers.IO)
        source.body = playlistBody()
    }

    @After
    fun tearDown() {
        server.close()
        h.close()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing $name" }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    /** Two stream ids present in get_live_streams (archive channels) and one absent (merged 24/7). */
    private fun playlistBody(): String {
        val host = "${server.hostName}:${server.port}"
        return buildString {
            append("#EXTM3U\n")
            append("#EXTINF:-1 tvg-id=\"bbconelondon.uk\" catchup-type=\"xc\" catchup-days=\"3\" group-title=\"🇬🇧 UK\",BBC One London\n")
            append("http://$host/live/alice/s3cret/497001.ts\n")
            append("#EXTINF:-1 tvg-id=\"bbc1.uk\" group-title=\"🇬🇧 UK\",BBC 1 HD\n")
            append("http://$host/live/alice/s3cret/162096.ts\n")
            append("#EXTINF:-1 tvg-id=\"\" catchup-days=\"3\" group-title=\"📺 24/7\",Bluey 24/7\n")
            append("http://$host/live/alice/s3cret/999001.ts\n")
        }
    }

    private fun syncer(refreshInterval: Long = CatalogSyncer.DEFAULT_REFRESH_INTERVAL_MILLIS, batchSize: Int = 10) =
        CatalogSyncer(h.store, catalog, { h.secureStore.account(it) }, client, clock = { h.now }, batchSize = batchSize, refreshIntervalMillis = refreshInterval)

    private suspend fun playlist(xtream: Boolean = true): Long {
        val id = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"), xtreamEnabled = xtream)
        val outcome = h.syncer({ source }).sync(id)
        assertThat(outcome).isInstanceOf(SyncOutcome.Updated::class.java)
        return id
    }

    private fun assertRedacted(text: String?) {
        val t = text.orEmpty()
        assertThat(t).doesNotContain("alice")
        assertThat(t).doesNotContain("s3cret")
        assertThat(t).doesNotContain(server.hostName)
    }

    // ---- syncer ----

    @Test
    fun fullSyncWritesAccountLiveInfoVodAndSeriesThenClearsStale() = runBlocking<Unit> {
        val id = playlist()
        assertThat(h.store.syncState(id)!!.catalogStale).isTrue()

        val outcome = syncer().sync(id)

        assertThat(outcome).isEqualTo(
            CatalogSyncOutcome.Updated(
                liveInfoCount = 2, liveMissingCount = 1, vodCategoryCount = 2, vodCount = 47,
                seriesCategoryCount = 1, seriesCount = 16, skippedCount = 0,
            ),
        )
        assertThat(actions).containsExactly(
            "account", "get_live_streams", "get_vod_categories", "get_vod_streams", "get_series_categories", "get_series",
        ).inOrder()

        val status = catalog.accountStatus(id)!!
        assertThat(status.isActive).isTrue()
        assertThat(status.status).isEqualTo("Active")
        assertThat(status.expDate).isEqualTo(1_804_000_000_000L)
        assertThat(status.maxConnections).isEqualTo(1)
        assertThat(status.activeCons).isEqualTo(0)
        assertThat(status.allowedFormatList).containsExactly("m3u8", "ts", "rtmp").inOrder()
        assertThat(status.serverTimezone).isEqualTo("Europe/Amsterdam")

        val state = catalog.state(id)!!
        assertThat(state.activeGeneration).isNotNull()
        assertThat(state.vodCount).isEqualTo(47)
        assertThat(state.accountExpired).isFalse()
        assertThat(state.lastFailureKind).isNull()
        assertThat(h.store.syncState(id)!!.catalogStale).isFalse()

        assertThat(catalog.vodCount(id)).isEqualTo(47)
        assertThat(catalog.seriesCount(id)).isEqualTo(16)
        assertThat(catalog.vodCategories(id).map { it.categoryId }).containsExactly("2048", "429").inOrder()
        val leo = catalog.vodStream(id, 1596838)!!
        assertThat(leo.tmdb).isEqualTo("1075794")
        assertThat(leo.containerExtension).isEqualTo("mkv")
        assertThat(catalog.vodStreamsByTmdb(id, "1075794").size).isAtLeast(2)
        assertThat(catalog.series(id, 52015)!!.tmdb).isEqualTo("46511")
    }

    @Test
    fun channelMissingFromLiveStreamsStaysWithoutArchive() = runBlocking<Unit> {
        val id = playlist()
        syncer().sync(id)

        val channels = catalog.channelsWithLiveInfo(id)

        assertThat(channels.map { it.name }).containsExactly("BBC One London", "BBC 1 HD", "Bluey 24/7").inOrder()
        val bluey = channels.last()
        assertThat(bluey.inCatalog).isFalse()
        assertThat(bluey.tvArchive).isNull()
        // The playlist said catchup-days=3, but in Xtream mode the API is the authority.
        assertThat(bluey.effectiveArchiveDays).isEqualTo(0)
        assertThat(channels[0].effectiveArchiveDays).isEqualTo(3)
        assertThat(channels[0].epgChannelId).isEqualTo("BBCOneOxford.uk")
        assertThat(h.store.channels(id)).hasSize(3)
    }

    @Test
    fun authZeroRecordsAccountExpiredAndMakesNoCatalogCalls() = runBlocking<Unit> {
        val id = playlist()
        overrides["account"] = { json("""{"user_info":{"auth":0}}""") }

        val outcome = syncer().sync(id)

        assertThat(outcome).isEqualTo(CatalogSyncOutcome.AccountExpired("AuthFailed"))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(catalog.state(id)!!.accountExpired).isTrue()
        assertThat(catalog.state(id)!!.lastFailureKind).isEqualTo(CatalogSyncer.KIND_ACCOUNT_EXPIRED)
        assertThat(catalog.state(id)!!.activeGeneration).isNull()
        assertThat(catalog.accountStatus(id)!!.isActive).isFalse()
        assertThat(h.store.syncState(id)!!.catalogStale).isTrue()
    }

    @Test
    fun expiredStatusStopsAfterAccountInfoAndKeepsOldCatalog() = runBlocking<Unit> {
        val id = playlist()
        syncer().sync(id)
        actions.clear()
        overrides["account"] = { json("""{"user_info":{"auth":1,"status":"Expired","exp_date":"1700000000","max_connections":"1"}}""") }

        val outcome = syncer().sync(id, force = true)

        assertThat(outcome).isEqualTo(CatalogSyncOutcome.AccountExpired("Expired"))
        assertThat(actions).containsExactly("account")
        assertThat(catalog.vodCount(id)).isEqualTo(47)
        val status = catalog.accountStatus(id)!!
        assertThat(status.status).isEqualTo("Expired")
        assertThat(status.expDate).isEqualTo(1_700_000_000_000L)
        assertThat(catalog.state(id)!!.accountExpired).isTrue()

        // Renewed: the next sync clears the flag.
        overrides.remove("account")
        syncer().sync(id, force = true)
        assertThat(catalog.state(id)!!.accountExpired).isFalse()
    }

    @Test
    fun http401OnAccountInfoIsAccountExpired() = runBlocking<Unit> {
        val id = playlist()
        overrides["account"] = { json("denied", code = 401) }

        assertThat(syncer().sync(id)).isInstanceOf(CatalogSyncOutcome.AccountExpired::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun failureMidVodKeepsPreviousCatalogActive() = runBlocking<Unit> {
        val id = playlist()
        syncer().sync(id)
        val before = catalog.state(id)!!.activeGeneration!!
        val full = fixture("get_vod_streams.json").toByteArray()
        overrides["get_vod_streams"] = {
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body(Buffer().write(full.copyOf(full.size * 3 / 4)))
                .setHeader("Content-Length", full.size)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build()
        }
        h.now += 1_000

        val outcome = syncer(batchSize = 5).sync(id, force = true)

        assertThat(outcome).isInstanceOf(CatalogSyncOutcome.Failed::class.java)
        outcome as CatalogSyncOutcome.Failed
        assertThat(outcome.kind).isEqualTo(CatalogSyncer.KIND_NETWORK)
        assertThat(outcome.retryable).isTrue()
        assertRedacted(outcome.message)
        val state = catalog.state(id)!!
        assertThat(state.activeGeneration).isEqualTo(before)
        assertThat(state.pendingGeneration).isNull()
        assertThat(state.lastFailureKind).isEqualTo(CatalogSyncer.KIND_NETWORK)
        assertRedacted(state.lastFailureMessage)
        assertThat(catalog.vodCount(id)).isEqualTo(47)
        assertThat(catalog.generationsPresent(id)).containsExactly(before)
    }

    @Test
    fun successfulRefreshFlipsAndCleansUpOldGeneration() = runBlocking<Unit> {
        val id = playlist()
        syncer().sync(id)
        val before = catalog.state(id)!!.activeGeneration!!
        overrides["get_vod_streams"] = { json("""[{"stream_id":1,"name":"EN - New (2026)","tmdb":"5"},{"stream_id":2,"name":"EN - Newer (2026)"}]""") }
        h.now += 1_000

        val outcome = syncer().sync(id, force = true)

        assertThat((outcome as CatalogSyncOutcome.Updated).vodCount).isEqualTo(2)
        val after = catalog.state(id)!!.activeGeneration!!
        assertThat(after).isGreaterThan(before)
        assertThat(catalog.generationsPresent(id)).containsExactly(after)
        assertThat(catalog.vodStreamsPage(id, -1, 100).map { it.streamId }).containsExactly(1L, 2L).inOrder()
        assertThat(catalog.seriesCount(id)).isEqualTo(16)
    }

    @Test
    fun largeVodListIsStoredInBatches() = runBlocking<Unit> {
        val id = playlist()
        val total = 10_000
        overrides["get_vod_streams"] = {
            val body = buildString {
                append('[')
                for (i in 1..total) {
                    if (i > 1) append(',')
                    append("""{"num":$i,"name":"EN - Movie $i (2001)","stream_id":$i,"tmdb":${if (i % 2 == 0) "\"$i\"" else "$i"},"rating":"5","category_id":"2048","container_extension":"mkv"}""")
                }
                append(']')
            }
            json(body)
        }

        val outcome = syncer(batchSize = 500).sync(id) as CatalogSyncOutcome.Updated

        assertThat(outcome.vodCount).isEqualTo(total)
        assertThat(catalog.vodCount(id)).isEqualTo(total)
        assertThat(catalog.vodStream(id, 7)!!.tmdb).isEqualTo("7")
        assertThat(catalog.vodStreamsPage(id, total - 3, 10).map { it.streamId }).containsExactly(9_999L, 10_000L).inOrder()
    }

    @Test
    fun recentCatalogIsNotRefetchedButAccountIsChecked() = runBlocking<Unit> {
        val id = playlist()
        syncer().sync(id)
        actions.clear()
        h.now += 60_000

        assertThat(syncer().sync(id)).isEqualTo(CatalogSyncOutcome.Fresh)
        assertThat(actions).containsExactly("account")

        h.now += CatalogSyncer.DEFAULT_REFRESH_INTERVAL_MILLIS
        assertThat(syncer().sync(id)).isInstanceOf(CatalogSyncOutcome.Updated::class.java)
    }

    @Test
    fun disabledOrAccountlessPlaylistsAreSkipped() = runBlocking<Unit> {
        val disabled = playlist(xtream = false)
        assertThat(syncer().sync(disabled)).isEqualTo(CatalogSyncOutcome.Skipped(CatalogSyncer.REASON_DISABLED))

        val noAccount = h.registry.add("Empty", SourceReference.Drive("OTHER"))
        assertThat(syncer().sync(noAccount)).isEqualTo(CatalogSyncOutcome.Skipped(CatalogSyncer.REASON_NO_ACCOUNT))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun serverErrorIsRetryableAndRedacted() = runBlocking<Unit> {
        val id = playlist()
        overrides["get_live_streams"] = { json("""{"error":"http://${server.hostName}/live/alice/s3cret/1.ts"}""", code = 502) }

        val outcome = syncer().sync(id) as CatalogSyncOutcome.Failed

        assertThat(outcome.kind).isEqualTo(CatalogSyncer.KIND_HTTP)
        assertThat(outcome.retryable).isTrue()
        assertRedacted(outcome.message)
        assertThat(catalog.state(id)!!.activeGeneration).isNull()
        assertThat(catalog.generationsPresent(id)).isEmpty()
    }

    // ---- worker and chaining ----

    private fun catalogWorker(playlistId: Long, attempt: Int = 0, syncer: CatalogSyncer = syncer()): CatalogSyncWorker =
        TestListenableWorkerBuilder<CatalogSyncWorker>(h.context)
            .setWorkerFactory(CatalogSyncWorkerFactory { syncer })
            .setInputData(workDataOf(CatalogSyncWorker.KEY_PLAYLIST_ID to playlistId))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun workerMapsOutcomes() = runBlocking<Unit> {
        val id = playlist()
        val ok = catalogWorker(id).doWork()
        assertThat(ok).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(ok.outputData.getString(CatalogSyncWorker.KEY_OUTCOME)).isEqualTo("Updated")

        overrides["account"] = { json("busy", code = 503) }
        assertThat(catalogWorker(id).doWork()).isEqualTo(ListenableWorker.Result.retry())
        val last = catalogWorker(id, attempt = CatalogSyncWorker.MAX_ATTEMPTS - 1).doWork()
        assertThat(last).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(last.outputData.getString(CatalogSyncWorker.KEY_FAILURE_KIND)).isEqualTo(CatalogSyncer.KIND_HTTP)

        overrides["account"] = { json("""{"user_info":{"auth":0}}""") }
        val expired = catalogWorker(id).doWork()
        assertThat(expired).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(expired.outputData.getString(CatalogSyncWorker.KEY_OUTCOME)).isEqualTo("AccountExpired")
    }

    @Test
    fun playlistSyncChainsCatalogSyncOnlyWhenXtreamEnabled() = runBlocking<Unit> {
        WorkManagerTestInitHelper.initializeTestWorkManager(h.context)
        val workManager = WorkManager.getInstance(h.context)
        val chain = CatalogSyncChain(h.store) { workManager }
        val playlistSyncer = h.syncer({ source })
        val enabled = h.registry.add("A", SourceReference.Drive("A"), xtreamEnabled = true)
        val disabled = h.registry.add("B", SourceReference.Drive("B"), xtreamEnabled = false)

        for (id in listOf(enabled, disabled)) {
            val worker = TestListenableWorkerBuilder<PlaylistSyncWorker>(h.context)
                .setWorkerFactory(SyncWorkerFactory(chain) { playlistSyncer })
                .setInputData(workDataOf(PlaylistSyncWorker.KEY_PLAYLIST_ID to id))
                .build()
            assertThat(worker.doWork()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        }

        val queued = workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.uniqueName(enabled)).get()
        assertThat(queued).hasSize(1)
        assertThat(queued.single().state).isIn(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
        assertThat(workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.uniqueName(disabled)).get()).isEmpty()

        // A failed playlist sync does not chain.
        chain.afterSync(disabled, SyncOutcome.Failed("Network", "x", true))
        assertThat(workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.uniqueName(disabled)).get()).isEmpty()
    }
}
