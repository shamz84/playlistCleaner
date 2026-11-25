package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import app.playlistplayer.core.sync.FakeSource
import app.playlistplayer.core.sync.SyncHarness
import app.playlistplayer.core.sync.SyncOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Shared fixture for EPG tests: a 5-entry playlist whose URLs point at a MockWebServer (so the
 * derived Xtream account targets it), a catalog marking two archive streams (3 days), and the
 * server timezone `Europe/Amsterdam`. The clock is the U1 capture time.
 *
 * Entries (ordinal: tvg-id / name / stream id):
 * 0: bbconelondon.uk / BBC One London / 497001 (API epg_channel_id BBCOneOxford.uk, archive 3 d)
 * 1: bbc1.uk / BBC 1 HD / 162096 (archive 3 d)
 * 2: bbc1.uk / BBC 1 HD Backup / 700001 (shares the tvg-id of entry 1)
 * 3: (none) / UK: BBC 2 HD / 700002 (display-name match)
 * 4: bluey.247 / Bluey 24/7 / 999001 (not in the provider guide)
 */
class EpgTestEnv(tempDir: File) {
    val h = SyncHarness(tempDir)
    val catalog = CatalogStore(h.db)
    val epg = EpgStore(h.db)
    val server = MockWebServer()
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val xtream = XtreamClient(http, Dispatchers.IO)
    private val source = FakeSource()

    /** Request log: path plus action, e.g. `/xmltv.php`, `get_simple_data_table:497001`. */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile var provider: () -> MockResponse = { xml(fixture("xmltv_sample.xml")) }
    @Volatile var override: () -> MockResponse = { MockResponse.Builder().code(404).build() }

    init {
        h.now = NOW
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when (path) {
                    "/xmltv.php" -> {
                        requests += path
                        provider()
                    }
                    "/override.xml" -> {
                        requests += path
                        override()
                    }
                    "/player_api.php" -> {
                        val action = request.url.queryParameter("action")
                        requests += "$action:${request.url.queryParameter("stream_id")}"
                        when (action) {
                            "get_simple_data_table" -> json(fixture("get_simple_data_table.json"))
                            "get_short_epg" -> json(fixture("get_short_epg.json"))
                            else -> MockResponse.Builder().code(404).build()
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    val overrideUrl: String get() = server.url("/override.xml").toString()

    fun syncer(
        config: EpgSyncConfig = EpgSyncConfig(backfillDelayMillis = 0),
        pullFactory: XmlPullSourceFactory = AndroidXmlPullSource,
        logger: RedactingLogger = RedactingLogger.NONE,
    ) = EpgSyncer(
        playlistStore = h.store,
        catalogStore = catalog,
        epgStore = epg,
        accounts = { h.secureStore.account(it) },
        overrideUrls = { h.secureStore.epgOverrideUrl(it) },
        downloader = XmltvDownloader(http, Dispatchers.IO),
        xtreamClient = xtream,
        pullFactory = pullFactory,
        clock = { h.now },
        logger = logger,
        config = config,
        pause = {},
    )

    suspend fun playlist(xtream: Boolean = true): Long {
        val host = "${server.hostName}:${server.port}"
        source.body = buildString {
            append("#EXTM3U\n")
            fun entry(attrs: String, name: String, id: Long) {
                append("#EXTINF:-1 $attrs group-title=\"UK\",$name\n")
                append("http://$host/live/alice/s3cret/$id.ts\n")
            }
            entry("tvg-id=\"bbconelondon.uk\" catchup-type=\"xc\" catchup-days=\"3\"", "BBC One London", 497001)
            entry("tvg-id=\"bbc1.uk\" catchup-type=\"xc\" catchup-days=\"3\"", "BBC 1 HD", 162096)
            entry("tvg-id=\"bbc1.uk\"", "BBC 1 HD Backup", 700001)
            entry("", "UK: BBC 2 HD", 700002)
            entry("tvg-id=\"bluey.247\"", "Bluey 24/7", 999001)
        }
        val id = h.registry.add("P", SourceReference.Drive("FILE_ID"), xtreamEnabled = xtream)
        assertThat(h.syncer({ source }).sync(id)).isInstanceOf(SyncOutcome.Updated::class.java)
        if (xtream) {
            val generation = catalog.beginGeneration(id)
            catalog.writer(id, generation).insertLiveInfo(
                listOf(
                    LiveStreamInfoEntity(id, generation, 497001, tvArchive = true, archiveDays = 3, epgChannelId = "BBCOneOxford.uk", categoryId = "1"),
                    LiveStreamInfoEntity(id, generation, 162096, tvArchive = true, archiveDays = 3, epgChannelId = "bbc1.uk", categoryId = "1"),
                    LiveStreamInfoEntity(id, generation, 700001, tvArchive = false, archiveDays = 0, epgChannelId = null, categoryId = "1"),
                ),
            )
            catalog.activateGeneration(id, generation) { it }
            catalog.putAccountStatus(
                AccountStatusEntity(id, "Active", true, null, 1, 0, "m3u8,ts", "Europe/Amsterdam", false, NOW),
            )
        }
        return id
    }

    fun close() {
        server.close()
        h.close()
    }

    companion object {
        /** `server_info.timestamp_now` of the U1 capture: 2026-10-29T21:54:34Z. */
        const val NOW: Long = 1_793_310_874_000L
        const val HOUR: Long = 3_600_000L
        const val DAY: Long = 24 * HOUR

        fun fixture(name: String): String =
            checkNotNull(EpgTestEnv::class.java.getResourceAsStream("/fixtures/$name")) { "missing $name" }
                .use { it.readBytes().toString(Charsets.UTF_8) }

        fun xml(body: String, contentType: String = "application/xml; charset=utf-8") =
            MockResponse.Builder().code(200).setHeader("Content-Type", contentType).body(body).build()

        fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

        private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

        fun xmltvTime(millis: Long): String = XMLTV_TIME.format(Instant.ofEpochMilli(millis))

        /** A small XMLTV document; programmes are (channel, start, stop, title). */
        fun xmltv(channels: List<Pair<String, String>>, programmes: List<Programme>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n<tv>\n")
            channels.forEach { (id, name) -> append("<channel id=\"$id\"><display-name>$name</display-name></channel>\n") }
            programmes.forEach { p ->
                append("<programme start=\"${xmltvTime(p.start)}\" stop=\"${xmltvTime(p.stop)}\" channel=\"${p.channel}\">")
                append("<title>${p.title}</title></programme>\n")
            }
            append("</tv>\n")
        }
    }

    data class Programme(val channel: String, val start: Long, val stop: Long, val title: String)
}
