package app.playlistplayer.core.database

import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.SeasonEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: PlaylistStore
    private lateinit var catalog: CatalogStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = PlaylistStore(db)
        catalog = CatalogStore(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun playlistWithChannels(streamIds: List<Long?>): Long {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, xtreamEnabled = true, createdAt = 1)
        val generation = store.beginGeneration(id)
        val writer = store.writer(id, generation)
        streamIds.forEachIndexed { i, streamId ->
            writer.add(
                ChannelEntity(id, generation, i, 0, "Channel $i", streamId, "ts", "ch$i.uk", null, null, null, null, null, if (i == 0) 3 else 0, false),
            )
        }
        writer.writeGroups(listOf(ChannelGroupEntity(id, generation, 0, "🇬🇧 UK", streamIds.size)))
        store.activateGeneration(id, generation) { it }
        return id
    }

    private fun vod(playlistId: Long, generation: Long, streamId: Long, order: Int, tmdb: String? = null) =
        VodStreamEntity(playlistId, generation, streamId, "EN - Movie $streamId (2020)", null, 7.0, 1L, "10", "mkv", tmdb, false, order)

    private suspend fun writeCatalog(playlistId: Long, vodIds: List<Long>, liveIds: List<Long> = emptyList()): Long {
        val generation = catalog.beginGeneration(playlistId)
        val writer = catalog.writer(playlistId, generation, batchSize = 2)
        writer.insertVodCategories(listOf(VodCategoryEntity(playlistId, generation, "10", "EN - MOVIES", null, 0)))
        writer.insertVodStreams(vodIds.mapIndexed { i, id -> vod(playlistId, generation, id, i) })
        writer.insertLiveInfo(liveIds.map { LiveStreamInfoEntity(playlistId, generation, it, tvArchive = true, archiveDays = 7, epgChannelId = "e$it", categoryId = "1") })
        return generation
    }

    @Test
    fun pendingCatalogIsInvisibleUntilActivatedThenOldIsDeleted() = runBlocking<Unit> {
        val id = playlistWithChannels(listOf(1L))
        val first = writeCatalog(id, listOf(100, 101, 102))
        assertThat(catalog.vodCount(id)).isEqualTo(0)

        catalog.activateGeneration(id, first) { it.copy(vodCount = 3) }
        assertThat(catalog.vodCount(id)).isEqualTo(3)
        assertThat(catalog.vodCategories(id).map { it.name }).containsExactly("EN - MOVIES")

        val second = writeCatalog(id, listOf(200, 201))
        assertThat(catalog.vodCount(id)).isEqualTo(3)
        assertThat(catalog.generationsPresent(id)).containsExactly(first, second).inOrder()

        catalog.activateGeneration(id, second) { it.copy(vodCount = 2) }
        assertThat(catalog.vodStreamsPage(id, -1, 10).map { it.streamId }).containsExactly(200L, 201L).inOrder()
        assertThat(catalog.generationsPresent(id)).containsExactly(second)
        assertThat(catalog.state(id)!!.vodCount).isEqualTo(2)
    }

    @Test
    fun discardKeepsPreviousCatalogAndNextBeginCleansLeftovers() = runBlocking<Unit> {
        val id = playlistWithChannels(listOf(1L))
        val first = writeCatalog(id, listOf(100))
        catalog.activateGeneration(id, first) { it }

        val failed = writeCatalog(id, listOf(300, 301))
        catalog.discardGeneration(id, failed)
        assertThat(catalog.vodStreamsPage(id, -1, 10).map { it.streamId }).containsExactly(100L)
        assertThat(catalog.generationsPresent(id)).containsExactly(first)

        writeCatalog(id, listOf(400)) // abandoned (crash) — not discarded
        val next = catalog.beginGeneration(id)
        assertThat(catalog.generationsPresent(id)).containsExactly(first)
        assertThat(next).isGreaterThan(first)
    }

    @Test
    fun channelsJoinLiveInfoAndToleratesMissingIds() = runBlocking<Unit> {
        val id = playlistWithChannels(listOf(1L, 2L, null))

        // Before any catalog sync the playlist's catchup-days apply.
        val before = catalog.channelsWithLiveInfo(id)
        assertThat(before.map { it.effectiveArchiveDays }).containsExactly(3, 0, 0).inOrder()
        assertThat(before.all { it.catalogGeneration == null }).isTrue()

        val generation = writeCatalog(id, emptyList(), liveIds = listOf(2L, 99L))
        catalog.activateGeneration(id, generation) { it }

        val after = catalog.channelsWithLiveInfo(id)
        assertThat(after).hasSize(3)
        // Stream 1 is absent from the API: still listed, no archive (API is the authority).
        assertThat(after[0].inCatalog).isFalse()
        assertThat(after[0].effectiveArchiveDays).isEqualTo(0)
        assertThat(after[1].inCatalog).isTrue()
        assertThat(after[1].effectiveArchiveDays).isEqualTo(7)
        assertThat(after[1].epgChannelId).isEqualTo("e2")
        assertThat(after[2].streamId).isNull()
        assertThat(catalog.channelWithLiveInfo(id, 1)!!.archiveDays).isEqualTo(7)
        assertThat(catalog.archiveStreams(id).map { it.streamId }).containsExactly(2L, 99L).inOrder()
        assertThat(catalog.playlistStreamIds(id)).containsExactly(1L, 2L)
    }

    @Test
    fun seriesInfoCacheReplacesEpisodes() = runBlocking<Unit> {
        val id = playlistWithChannels(listOf(1L))
        fun info() = SeriesInfoCacheEntity(id, 5, "S", null, null, null, null, null, null, null, null, null, null, null, 10)
        fun ep(n: Int, season: Int, order: Int) = EpisodeEntity(id, 5, season, n, "e$season$n", "t", "mkv", 60, null, null, null, null, order)

        catalog.putSeriesInfo(info(), listOf(SeasonEntity(id, 5, 1, "Season 1", 2, null, null)), listOf(ep(1, 1, 0), ep(2, 1, 1)))
        catalog.putSeriesInfo(info(), emptyList(), listOf(ep(1, 2, 0)))

        assertThat(catalog.episodes(id, 5).map { it.episodeId }).containsExactly("e21")
        assertThat(catalog.seasons(id, 5)).isEmpty()
    }

    @Test
    fun deletePlaylistAndClearRemoveCatalogRows() = runBlocking<Unit> {
        val a = playlistWithChannels(listOf(1L))
        val b = playlistWithChannels(listOf(1L))
        for (id in listOf(a, b)) {
            catalog.activateGeneration(id, writeCatalog(id, listOf(100), liveIds = listOf(1))) { it }
            catalog.putAccountStatus(AccountStatusEntity(id, "Active", true, null, 1, 0, "ts", "Europe/Amsterdam", false, 1))
        }

        store.deletePlaylist(a)
        assertThat(catalog.generationsPresent(a)).isEmpty()
        assertThat(catalog.accountStatus(a)).isNull()
        assertThat(catalog.state(a)).isNull()
        assertThat(catalog.vodCount(b)).isEqualTo(1)

        catalog.clearPlaylist(b)
        assertThat(catalog.vodCount(b)).isEqualTo(0)
        assertThat(store.channels(b)).hasSize(1)
    }

    @Test
    fun catalogTablesHaveNoCredentialOrUrlColumns() = runBlocking<Unit> {
        val tables = listOf(
            "catalog_sync_state", "account_status", "live_stream_info", "vod_category", "vod_stream", "series_category",
            "series", "series_info_cache", "season", "episode", "vod_info_cache",
        )
        val columns = db.useReaderConnection { connection ->
            val names = mutableListOf<String>()
            for (table in tables) {
                connection.usePrepared("PRAGMA table_info($table)") { statement ->
                    while (statement.step()) names += statement.getText(1).lowercase()
                }
            }
            names
        }
        assertThat(columns.size).isGreaterThan(tables.size)
        for (forbidden in listOf("url", "password", "username", "host", "user", "pass", "credential", "token")) {
            assertThat(columns.filter { it.contains(forbidden) }).isEmpty()
        }
    }
}
