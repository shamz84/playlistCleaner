package app.playlistplayer.core.network.xtream

import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.parsing.xmltv.XtreamEpgDecoder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class XtreamClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: XtreamClient
    private lateinit var account: XtreamAccount
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = XtreamAccount("http", server.hostName, server.port, "alice", "s3cret")
        client = XtreamClient(http, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun assertRedacted(text: String) {
        assertThat(text).doesNotContain("alice")
        assertThat(text).doesNotContain("s3cret")
        assertThat(text).doesNotContain(server.hostName)
        assertThat(text).doesNotContain(":${server.port}")
    }

    // ---- account info ----

    @Test
    fun userInfoFixtureYieldsExpiryConnectionsFormatsAndTimezone() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("user_info.json")))

        val info = client.accountInfo(account)

        assertThat(info.isActive).isTrue()
        assertThat(info.isAuthenticated).isTrue()
        assertThat(info.userInfo.status).isEqualTo("Active")
        assertThat(info.userInfo.expDate).isEqualTo(1_804_000_000L)
        assertThat(info.userInfo.expiresAtMillis).isEqualTo(1_804_000_000_000L)
        assertThat(info.userInfo.activeConnections).isEqualTo(0)
        assertThat(info.userInfo.maxConnections).isEqualTo(1)
        assertThat(info.userInfo.isTrial).isFalse()
        assertThat(info.userInfo.allowedOutputFormats).containsExactly("m3u8", "ts", "rtmp").inOrder()
        assertThat(info.serverInfo!!.timezone).isEqualTo("Europe/Amsterdam")
        assertThat(info.serverInfo.zoneId).isEqualTo(ZoneId.of("Europe/Amsterdam"))
        assertThat(info.serverInfo.timestampNow).isEqualTo(1_793_310_874L)
        // The model never carries the secrets the response echoes.
        assertThat(info.toString()).doesNotContain("pass")

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/player_api.php")
        assertThat(request.url.queryParameter("username")).isEqualTo("alice")
        assertThat(request.url.queryParameter("password")).isEqualTo("s3cret")
        assertThat(request.url.queryParameter("action")).isNull()
        assertThat(request.headers["Accept-Encoding"]).isEqualTo("gzip")
    }

    @Test
    fun nullOrEmptyExpDateMeansNoExpiry() = runBlocking<Unit> {
        server.enqueue(json("""{"user_info":{"auth":1,"status":"Active","exp_date":null,"max_connections":"2","active_cons":1}}"""))
        server.enqueue(json("""{"user_info":{"auth":"1","status":"Active","exp_date":"","allowed_output_formats":"ts,m3u8"}}"""))

        val first = client.accountInfo(account)
        assertThat(first.userInfo.expDate).isNull()
        assertThat(first.userInfo.expiresAtMillis).isNull()
        assertThat(first.userInfo.maxConnections).isEqualTo(2)
        assertThat(first.userInfo.activeConnections).isEqualTo(1)
        assertThat(first.serverInfo).isNull()

        val second = client.accountInfo(account)
        assertThat(second.userInfo.expiresAtMillis).isNull()
        assertThat(second.userInfo.allowedOutputFormats).containsExactly("ts", "m3u8").inOrder()
        assertThat(second.isActive).isTrue()
    }

    @Test
    fun authZeroOrExpiredStatusIsNotActive() = runBlocking<Unit> {
        server.enqueue(json("""{"user_info":{"auth":0}}"""))
        server.enqueue(json("""{"user_info":{"auth":1,"status":"Expired","exp_date":"1700000000"},"server_info":{"timezone":"Europe/London"}}"""))
        server.enqueue(json("""{"user_info":[]}"""))

        val rejected = client.accountInfo(account)
        assertThat(rejected.isAuthenticated).isFalse()
        assertThat(rejected.isActive).isFalse()

        val expired = client.accountInfo(account)
        assertThat(expired.isAuthenticated).isTrue()
        assertThat(expired.isActive).isFalse()

        try {
            client.accountInfo(account)
            fail("expected Malformed")
        } catch (e: XtreamException.Malformed) {
            assertRedacted(e.message!!)
        }
    }

    // ---- lists ----

    @Test
    fun liveStreamsFixtureStreamsArchiveAndEpgIds() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_live_streams.json")))
        val items = ArrayList<XtreamLiveStream>()

        val stats = client.streamLiveStreams(account, batchSize = 10) { items.addAll(it) }

        assertThat(stats).isEqualTo(StreamStats(items = 28, skipped = 0, batches = 3))
        val bbcOne = items.first { it.streamId == 497001L }
        assertThat(bbcOne.tvArchive).isTrue()
        assertThat(bbcOne.archiveDays).isEqualTo(3)
        assertThat(bbcOne.epgChannelId).isEqualTo("BBCOneOxford.uk")
        assertThat(bbcOne.categoryId).isEqualTo("47")
        assertThat(items.count { it.archiveDays == 7 }).isGreaterThan(0)
        assertThat(items.filter { it.tvArchive != true }.all { it.archiveDays == 0 }).isTrue()
        assertThat(server.takeRequest().url.queryParameter("action")).isEqualTo("get_live_streams")
    }

    @Test
    fun vodStreamsFixtureNormalisesTmdbAndNumbers() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_vod_streams.json")))
        val items = ArrayList<XtreamVodStream>()

        val stats = client.streamVodStreams(account) { items.addAll(it) }

        assertThat(stats.items).isEqualTo(47)
        assertThat(stats.skipped).isEqualTo(0)
        // Int and string tmdb normalise to the same key.
        assertThat(items.first { it.streamId == 1596838L }.tmdb).isEqualTo("1075794")
        assertThat(items.first { it.streamId == 1268490L }.tmdb).isEqualTo("1075794")
        assertThat(items.first { it.streamId == 1596838L }.rating).isEqualTo(7.396)
        assertThat(items.first { it.streamId == 1596838L }.added).isEqualTo(1_760_218_444L)
        assertThat(items.any { it.tmdb == null }).isTrue()
        assertThat(items.mapNotNull { it.containerExtension }.toSet()).containsAtLeast("mkv", "mp4")
    }

    @Test
    fun tmdbVariantsNormalise() = runBlocking<Unit> {
        server.enqueue(
            json(
                """[{"stream_id":1,"name":"a","tmdb":""},{"stream_id":2,"name":"b","tmdb":0},
                   {"stream_id":3,"name":"c","tmdb":"123"},{"stream_id":"4","name":"d","tmdb":123},
                   {"stream_id":5,"name":"e","tmdb":"0"},{"stream_id":6,"name":"f","tmdb":null},
                   {"stream_id":7,"name":"g","tmdb_id":"77"},{"stream_id":8,"name":"h"}]""",
            ),
        )
        val items = ArrayList<XtreamVodStream>()
        client.streamVodStreams(account) { items.addAll(it) }

        assertThat(items.map { it.streamId to it.tmdb }).containsExactly(
            1L to null, 2L to null, 3L to "123", 4L to "123", 5L to null, 6L to null, 7L to "77", 8L to null,
        ).inOrder()
    }

    @Test
    fun lenientValuesAcceptStringsNumbersAndBlanks() {
        val j = XtreamClient.json
        fun el(s: String) = j.parseToJsonElement(s)
        assertThat(XtreamValues.int(el("\"3\""))).isEqualTo(3)
        assertThat(XtreamValues.int(el("3"))).isEqualTo(3)
        assertThat(XtreamValues.int(el("\"\""))).isNull()
        assertThat(XtreamValues.int(el("null"))).isNull()
        assertThat(XtreamValues.boolean(el("\"1\""))).isTrue()
        assertThat(XtreamValues.boolean(el("0"))).isFalse()
        assertThat(XtreamValues.boolean(el("true"))).isTrue()
        assertThat(XtreamValues.double(el("\"7,5\""))).isEqualTo(7.5)
        assertThat(XtreamValues.string(el("\"\""))).isNull()
        assertThat(XtreamValues.tmdb(el("\"abc\""))).isNull()
        assertThat(XtreamValues.stringList(el("[\"a\",null,\"\"]"))).containsExactly("a")
    }

    @Test
    fun seriesAndCategoriesFixturesDecode() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_series.json")))
        server.enqueue(json("""[{"category_id":"156","category_name":"EN - SERIES","parent_id":0},{"category_id":7,"category_name":"X"}]"""))
        val series = ArrayList<XtreamSeries>()

        client.streamSeries(account, batchSize = 5) { series.addAll(it) }
        val categories = client.seriesCategories(account)

        assertThat(series).hasSize(16)
        val utopia = series.first { it.seriesId == 52015L }
        assertThat(utopia.tmdb).isEqualTo("46511")
        assertThat(utopia.rating).isEqualTo(8.0)
        assertThat(utopia.releaseDate).isEqualTo("2013-01-15")
        assertThat(utopia.lastModified).isEqualTo(1_793_233_571L)
        assertThat(categories.map { it.categoryId }).containsExactly("156", "7").inOrder()
    }

    @Test
    fun undecodableElementsAreSkippedNotFatal() = runBlocking<Unit> {
        server.enqueue(json("""[{"stream_id":1,"name":"ok"}, null, 5, {"stream_id":{"x":1},"name":["weird"]}, {"stream_id":2}]"""))
        val items = ArrayList<XtreamVodStream>()

        val stats = client.streamVodStreams(account) { items.addAll(it) }

        // Lenient fields turn odd shapes into nulls; non-objects are skipped.
        assertThat(stats.items).isEqualTo(3)
        assertThat(stats.skipped).isEqualTo(2)
        assertThat(items.map { it.streamId }).containsExactly(1L, null, 2L).inOrder()
    }

    @Test
    fun emptyAndObjectShapedListBodies() = runBlocking<Unit> {
        server.enqueue(json("{}"))
        server.enqueue(json(""))
        server.enqueue(json("""{"0":{"stream_id":9,"name":"x"},"1":{"stream_id":10,"name":"y"}}"""))
        server.enqueue(json("""{"user_info":{"auth":0}}"""))

        assertThat(client.streamVodStreams(account) {}.items).isEqualTo(0)
        assertThat(client.streamVodStreams(account) {}.items).isEqualTo(0)
        val keyed = ArrayList<XtreamVodStream>()
        client.streamVodStreams(account) { keyed.addAll(it) }
        assertThat(keyed.map { it.streamId }).containsExactly(9L, 10L)
        try {
            client.streamVodStreams(account) {}
            fail("expected AuthFailed")
        } catch (_: XtreamException.AuthFailed) {
        }
    }

    @Test
    fun categoryFilterIsSent() = runBlocking<Unit> {
        server.enqueue(json("[]"))
        client.streamVodStreams(account, categoryId = "2048") {}
        val request = server.takeRequest()
        assertThat(request.url.queryParameter("action")).isEqualTo("get_vod_streams")
        assertThat(request.url.queryParameter("category_id")).isEqualTo("2048")
    }

    // ---- gzip ----

    @Test
    fun gzipContentEncodingIsDecoded() = runBlocking<Unit> {
        val body = Buffer().write(gzip(Fixtures.bytes("get_vod_streams.json")))
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").body(body).build(),
        )
        var count = 0
        client.streamVodStreams(account) { count += it.size }
        assertThat(count).isEqualTo(47)
        assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("gzip")
    }

    @Test
    fun gzipBodyWithoutContentEncodingIsSniffed() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream")
                .body(Buffer().write(gzip(Fixtures.bytes("user_info.json")))).build(),
        )
        assertThat(client.accountInfo(account).userInfo.maxConnections).isEqualTo(1)
    }

    // ---- streaming ----

    @Test
    fun largeVodListIsProcessedInBoundedBatches() = runBlocking<Unit> {
        val total = 50_000
        val bytes = SyntheticVodBody.bytes(total)
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").body(Buffer().write(gzip(bytes))).build(),
        )
        var count = 0
        var batches = 0
        var maxBatch = 0
        var lastId = 0L

        val stats = client.streamVodStreams(account, batchSize = 500) { batch ->
            batches++
            maxBatch = maxOf(maxBatch, batch.size)
            count += batch.size
            lastId = batch.last().streamId!!
        }

        assertThat(stats).isEqualTo(StreamStats(total, 0, 100))
        assertThat(count).isEqualTo(total)
        assertThat(batches).isEqualTo(100)
        assertThat(maxBatch).isEqualTo(500)
        assertThat(lastId).isEqualTo(total.toLong())
    }

    @Test
    fun streamingHandsOutBatchesBeforeTheBodyIsRead() = runBlocking<Unit> {
        val total = 50_000
        val input = SyntheticVodBody.Stream(total)
        var bytesAtFirstBatch = -1L
        var count = 0

        val stats = client.decodeArrayInBatches(account, input, XtreamVodStream.serializer(), 500) { batch ->
            if (bytesAtFirstBatch < 0) bytesAtFirstBatch = input.bytesRead
            count += batch.size
        }

        assertThat(stats.items).isEqualTo(total)
        assertThat(count).isEqualTo(total)
        // Incremental: the first batch is emitted after reading only a small prefix of the ~17 MB body.
        assertThat(input.bytesRead).isGreaterThan(10_000_000L)
        assertThat(bytesAtFirstBatch).isLessThan(input.bytesRead / 20)
    }

    // ---- details ----

    @Test
    fun seriesInfoFixtureFlattensAndSortsEpisodes() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_series_info.json")))

        val info = client.seriesInfo(account, 52015)

        assertThat(info.info.tmdb).isEqualTo("46511")
        assertThat(info.info.backdropPath).isNotEmpty()
        assertThat(info.seasons.map { it.seasonNumber }).containsExactly(0, 1, 2).inOrder()
        assertThat(info.seasons.first().episodeCount).isEqualTo(4)
        // Season 0 has metadata but no episodes; episodes is the source of truth.
        assertThat(info.episodes.map { it.season }.distinct()).containsExactly(1, 2).inOrder()
        val first = info.episodes.first()
        assertThat(first.id).isEqualTo("2210872")
        assertThat(first.episodeNum).isEqualTo(1)
        assertThat(first.containerExtension).isEqualTo("mkv")
        assertThat(first.info!!.durationSecs).isEqualTo(3594L)
        assertThat(server.takeRequest().url.queryParameter("series_id")).isEqualTo("52015")
    }

    @Test
    fun seasonKeysSortNumerically() = runBlocking<Unit> {
        server.enqueue(
            json(
                """{"info":[],"seasons":[],"episodes":{
                    "10":[{"id":"1001","episode_num":"1","season":"10","title":"S10E1"}],
                    "2":[{"id":"201","episode_num":2,"title":"S2E2"},{"id":"200","episode_num":1,"title":"S2E1"}],
                    "1":[{"id":"101","episode_num":1,"title":"S1E1","info":[]}]}}""",
            ),
        )

        val info = client.seriesInfo(account, 1)

        assertThat(info.episodes.map { it.title }).containsExactly("S1E1", "S2E1", "S2E2", "S10E1").inOrder()
        assertThat(info.episodes.map { it.season }).containsExactly(1, 2, 2, 10).inOrder()
        assertThat(info.episodes.first().info).isNull()
        assertThat(info.info.name).isNull()
    }

    @Test
    fun vodInfoFixturesDecode() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_vod_info.json")))
        server.enqueue(json(Fixtures.text("get_vod_info_no_tmdb.json")))

        val withTmdb = client.vodInfo(account, 2204608)
        val withoutTmdb = client.vodInfo(account, 1805695)

        assertThat(withTmdb.info!!.tmdbId).isEqualTo("26594")
        assertThat(withTmdb.info.durationSecs).isEqualTo(5400L)
        assertThat(withTmdb.info.effectivePlot).startsWith("Interviews")
        assertThat(withTmdb.info.backdropPath).hasSize(1)
        assertThat(withTmdb.movieData!!.containerExtension).isEqualTo("mkv")
        assertThat(withoutTmdb.info!!.tmdbId).isNull()
        assertThat(withoutTmdb.info.backdropPath).isEmpty()
        assertThat(withoutTmdb.info.effectivePlot).isNull()
        assertThat(server.takeRequest().url.queryParameter("vod_id")).isEqualTo("2204608")
    }

    @Test
    fun epgListingsMapToParsingModel() = runBlocking<Unit> {
        server.enqueue(json(Fixtures.text("get_short_epg.json")))
        server.enqueue(json(Fixtures.text("get_simple_data_table.json")))

        val short = client.shortEpg(account, 497001, limit = 4)
        val table = client.simpleDataTable(account, 497001)

        assertThat(short).hasSize(4)
        assertThat(short.first().start).isEqualTo("2026-10-29 22:30:00")
        assertThat(short.first().end).isEqualTo("2026-10-29 23:00:00")
        assertThat(short.first().streamId).isEqualTo("497001")
        val decoded = XtreamEpgDecoder.decode(short, ZoneId.of("Europe/Amsterdam"))
        assertThat(decoded.programmes.first().title).isEqualTo("Not Going Out")
        assertThat(table).hasSize(23)
        assertThat(table.first().hasArchive).isEqualTo("0")
        assertThat(table.first().nowPlaying).isEqualTo("0")

        val shortRequest = server.takeRequest()
        assertThat(shortRequest.url.queryParameter("action")).isEqualTo("get_short_epg")
        assertThat(shortRequest.url.queryParameter("stream_id")).isEqualTo("497001")
        assertThat(shortRequest.url.queryParameter("limit")).isEqualTo("4")
        assertThat(server.takeRequest().url.queryParameter("action")).isEqualTo("get_simple_data_table")
    }

    // ---- errors ----

    @Test
    fun httpErrorsAreRedactedAndClassified() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"http://${server.hostName}/live/alice/s3cret/1.ts"}""", code = 503))
        server.enqueue(json("nope", code = 401))
        try {
            client.accountInfo(account)
            fail("expected Http")
        } catch (e: XtreamException.Http) {
            assertThat(e.code).isEqualTo(503)
            assertThat(e.retryable).isTrue()
            assertRedacted(e.toString())
        }
        try {
            client.streamLiveStreams(account) {}
            fail("expected Http")
        } catch (e: XtreamException.Http) {
            assertThat(e.isAuthFailure).isTrue()
            assertThat(e.retryable).isFalse()
        }
    }

    @Test
    fun malformedBodyEchoingCredentialsIsRedacted() = runBlocking<Unit> {
        server.enqueue(json("""[{"stream_id":1,"name":"alice s3cret ${server.hostName}"} garbage alice s3cret"""))
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html>alice</html>").build())
        try {
            client.streamVodStreams(account) {}
            fail("expected Malformed")
        } catch (e: XtreamException.Malformed) {
            assertThat(e.retryable).isFalse()
            assertRedacted(e.toString())
        }
        try {
            client.accountInfo(account)
            fail("expected Malformed")
        } catch (e: XtreamException.Malformed) {
            assertRedacted(e.toString())
        }
    }

    @Test
    fun networkErrorsAreRedacted() = runBlocking<Unit> {
        val port = server.port
        server.close()
        val offline = XtreamAccount("http", "127.0.0.1", port, "alice", "s3cret")
        try {
            client.accountInfo(offline)
            fail("expected Network")
        } catch (e: XtreamException.Network) {
            assertThat(e.retryable).isTrue()
            val text = e.toString() + generateSequence(e.cause) { it.cause }.joinToString { it.toString() }
            assertThat(text).doesNotContain("alice")
            assertThat(text).doesNotContain("s3cret")
            assertThat(text).doesNotContain("127.0.0.1")
            assertThat(text).doesNotContain(":$port")
        }
    }

    @Test
    fun connectionDropMidStreamIsNetworkError() = runBlocking<Unit> {
        val bytes = SyntheticVodBody.bytes(2_000)
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body(Buffer().write(bytes.copyOf(bytes.size / 2)))
                .setHeader("Content-Length", bytes.size)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )
        var received = 0
        try {
            client.streamVodStreams(account, batchSize = 100) { received += it.size }
            fail("expected failure")
        } catch (e: XtreamException) {
            assertThat(e).isInstanceOf(XtreamException.Network::class.java)
            assertRedacted(e.toString())
        }
        assertThat(received).isGreaterThan(0)
    }

    // ---- account status checker ----

    @Test
    fun accountStatusCheckerClassifies() = runBlocking<Unit> {
        server.enqueue(json("""{"user_info":{"auth":1,"status":"Active","active_cons":"1","max_connections":"1"}}"""))
        server.enqueue(json("""{"user_info":{"auth":0}}"""))
        server.enqueue(json("x", code = 401))
        server.enqueue(json("x", code = 500))
        val seen = ArrayList<AccountInfo>()
        val checker = XtreamAccountStatusChecker(client, { id -> if (id == 1L) account else null }, { _, info -> seen += info })

        val active = checker.check(1)
        assertThat(active).isEqualTo(AccountCheck.Active(activeConnections = 1, maxConnections = 1))
        assertThat((active as AccountCheck.Active).atConnectionLimit).isTrue()
        assertThat(checker.check(1)).isEqualTo(AccountCheck.Expired)
        assertThat(checker.check(1)).isEqualTo(AccountCheck.Expired)
        assertThat(checker.check(1)).isEqualTo(AccountCheck.Unknown)
        assertThat(checker.check(2)).isEqualTo(AccountCheck.Unknown)
        assertThat(seen).hasSize(2)
        assertThat(server.requestCount).isEqualTo(4)
    }
}
