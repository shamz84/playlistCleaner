package app.playlistplayer.feature.search

import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.UserDataStore
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.core.model.PlaylistSourceType
import app.playlistplayer.feature.library.RoomTitleOverrideStore
import app.playlistplayer.feature.vod.CatalogStoreVodSource
import app.playlistplayer.feature.vod.VodCatalogRepository
import app.playlistplayer.feature.vod.VodPlaylist
import kotlinx.coroutines.Dispatchers

/** A live entry for [SearchTestEnv.syncLive]: group is by name (groups keep first-seen order). */
data class TestChannel(val name: String, val group: String, val streamId: Long? = null, val isHeader: Boolean = false)

data class TestMovie(val streamId: Long, val name: String, val categoryId: String, val tmdb: String? = null)

data class TestSeries(val seriesId: Long, val name: String, val categoryId: String, val tmdb: String? = null)

/** A mutable visibility rule set (parental locks) for tests. */
class TestVisibility : ContentVisibility {
    val hiddenGroups = HashSet<Pair<Long, String>>()
    val hiddenCategories = HashSet<Triple<Long, VodKind, String>>()
    override fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String) = (playlistId to groupName) in hiddenGroups
    override fun isVodCategoryHidden(playlistId: Long, kind: VodKind, categoryId: String) =
        Triple(playlistId, kind, categoryId) in hiddenCategories
}

/**
 * In-memory Room with the real stores, VOD grouping and search index; synthetic names only.
 * Build in a Robolectric test; [close] in @After.
 */
class SearchTestEnv {
    val db: AppDatabase = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    val playlists = PlaylistStore(db)
    val catalog = CatalogStore(db)
    val userData = UserDataStore(db)
    val searchStore = SearchStore(db)
    val visibility = TestVisibility()
    var now: Long = 1_000

    lateinit var vod: VodCatalogRepository
    val overrides = RoomTitleOverrideStore(userData, clock = { now }, onChanged = { vod.invalidate(it) })

    init {
        vod = VodCatalogRepository(
            source = CatalogStoreVodSource(catalog) { playlists.playlists().filter { it.xtreamEnabled }.map { VodPlaylist(it.id, it.name) } },
            groupingDispatcher = Dispatchers.Unconfined,
            overrides = { overrides.overrides(it) },
        )
    }

    val indexer = SearchIndexer(playlists, catalog, searchStore, vod, overrideStamp = { overrides.stamp(it) }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    val repository = SearchRepository(
        searchStore,
        playlists = { playlists.playlists().map { SearchPlaylist(it.id, it.name) } },
        visibility = { visibility },
        indexer = indexer,
    )

    suspend fun playlist(name: String, xtream: Boolean = true): Long =
        playlists.createPlaylist(name, PlaylistSourceType.DRIVE, xtream, createdAt = now++)

    /** Writes and activates a playlist generation. */
    suspend fun syncLive(playlistId: Long, channels: List<TestChannel>) {
        val generation = playlists.beginGeneration(playlistId)
        val writer = playlists.writer(playlistId, generation)
        val groups = LinkedHashMap<String, Int>()
        val counts = HashMap<String, Int>()
        channels.forEachIndexed { i, c ->
            val group = groups.getOrPut(c.group) { groups.size }
            counts[c.group] = (counts[c.group] ?: 0) + 1
            writer.add(
                ChannelEntity(
                    playlistId, generation, i, group, c.name, c.streamId, "ts", null, null, null, null, null, null, 0, c.isHeader,
                ),
            )
        }
        writer.writeGroups(groups.map { (name, ordinal) -> ChannelGroupEntity(playlistId, generation, ordinal, name, counts[name] ?: 0) })
        playlists.activateGeneration(playlistId, generation) { it.copy(channelCount = channels.size) }
    }

    /** Writes and activates a catalog generation with one or more categories. */
    suspend fun syncCatalog(
        playlistId: Long,
        movies: List<TestMovie>,
        series: List<TestSeries> = emptyList(),
        categories: Map<String, String> = mapOf("1" to "EN - Movies", "9" to "Adult"),
        seriesCategories: Map<String, String> = mapOf("2" to "EN - Series"),
    ) {
        val generation = catalog.beginGeneration(playlistId)
        val writer = catalog.writer(playlistId, generation)
        writer.insertVodCategories(categories.entries.mapIndexed { i, (id, name) -> VodCategoryEntity(playlistId, generation, id, name, null, i) })
        writer.insertSeriesCategories(seriesCategories.entries.mapIndexed { i, (id, name) -> SeriesCategoryEntity(playlistId, generation, id, name, null, i) })
        writer.insertVodStreams(
            movies.mapIndexed { i, m ->
                VodStreamEntity(playlistId, generation, m.streamId, m.name, null, null, null, m.categoryId, "mkv", m.tmdb, false, i)
            },
        )
        writer.insertSeries(
            series.mapIndexed { i, s ->
                SeriesEntity(playlistId, generation, s.seriesId, s.name, null, null, null, null, null, s.categoryId, s.tmdb, null, i)
            },
        )
        catalog.activateGeneration(playlistId, generation) { it }
    }

    fun close() = db.close()
}
