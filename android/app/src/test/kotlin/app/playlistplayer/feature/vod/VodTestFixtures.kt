package app.playlistplayer.feature.vod

import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.player.url.StreamAccount
import app.playlistplayer.core.sync.catalog.DetailResult
import app.playlistplayer.core.sync.catalog.SeriesDetails
import app.playlistplayer.feature.settings.ContentPreferences
import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.TitleOverride
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** In-memory catalog. Generations can be bumped to simulate a catalog refresh. */
class FakeVodCatalogSource : VodCatalogSource {
    val playlistList = mutableListOf<VodPlaylist>()
    val generations = HashMap<Long, Long>()
    val vodCategoryRows = mutableListOf<VodCategoryEntity>()
    val vodRows = mutableListOf<VodStreamEntity>()
    val seriesCategoryRows = mutableListOf<SeriesCategoryEntity>()
    val seriesRows = mutableListOf<SeriesEntity>()
    var pageCalls = 0

    fun playlist(id: Long, name: String) = apply {
        playlistList += VodPlaylist(id, name)
        generations[id] = 1
    }

    fun vodCategory(playlistId: Long, id: String, name: String) = apply {
        vodCategoryRows += VodCategoryEntity(playlistId, 1, id, name, null, vodCategoryRows.count { it.playlistId == playlistId })
    }

    fun movie(playlistId: Long, streamId: Long, name: String, categoryId: String, tmdb: String? = null, ext: String = "mkv", added: Long? = null) = apply {
        vodRows += VodStreamEntity(
            playlistId = playlistId, generation = 1, streamId = streamId, name = name, icon = "https://img.example/$streamId.jpg",
            rating = 7.0, added = added, categoryId = categoryId, containerExtension = ext, tmdb = tmdb, isAdult = false,
            providerOrder = vodRows.count { it.playlistId == playlistId },
        )
    }

    fun seriesCategory(playlistId: Long, id: String, name: String) = apply {
        seriesCategoryRows += SeriesCategoryEntity(playlistId, 1, id, name, null, seriesCategoryRows.count { it.playlistId == playlistId })
    }

    fun series(
        playlistId: Long,
        seriesId: Long,
        name: String,
        categoryId: String,
        tmdb: String? = null,
        releaseDate: String? = null,
        lastModified: Long? = null,
    ) = apply {
        seriesRows += SeriesEntity(
            playlistId = playlistId, generation = 1, seriesId = seriesId, name = name, cover = null, plot = "Plot of $name",
            genre = "Drama", releaseDate = releaseDate, rating = 8.0, categoryId = categoryId, tmdb = tmdb, lastModified = lastModified,
            providerOrder = seriesRows.count { it.playlistId == playlistId },
        )
    }

    override suspend fun playlists() = playlistList.toList()
    override suspend fun catalogGeneration(playlistId: Long) = generations[playlistId]
    override suspend fun vodCategories(playlistId: Long) = vodCategoryRows.filter { it.playlistId == playlistId }
    override suspend fun vodStreamsInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int) =
        vodRows.filter { it.playlistId == playlistId && it.categoryId == categoryId }.sortedBy { it.providerOrder }.drop(offset).take(limit)
    override suspend fun vodStreamsPage(playlistId: Long, afterOrder: Int, limit: Int): List<VodStreamEntity> {
        pageCalls++
        return vodRows.filter { it.playlistId == playlistId && it.providerOrder > afterOrder }.sortedBy { it.providerOrder }.take(limit)
    }
    override suspend fun seriesCategories(playlistId: Long) = seriesCategoryRows.filter { it.playlistId == playlistId }
    override suspend fun seriesInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int) =
        seriesRows.filter { it.playlistId == playlistId && it.categoryId == categoryId }.sortedBy { it.providerOrder }.drop(offset).take(limit)
    override suspend fun seriesPage(playlistId: Long, afterOrder: Int, limit: Int) =
        seriesRows.filter { it.playlistId == playlistId && it.providerOrder > afterOrder }.sortedBy { it.providerOrder }.take(limit)
    override suspend fun vodStream(playlistId: Long, streamId: Long) = vodRows.firstOrNull { it.playlistId == playlistId && it.streamId == streamId }
    override suspend fun series(playlistId: Long, seriesId: Long) = seriesRows.firstOrNull { it.playlistId == playlistId && it.seriesId == seriesId }
}

/** A grouping function that counts its calls (asserts browse never groups). */
class CountingGrouper : (List<VodItem>, List<TitleOverride>) -> List<VodTitle> {
    val calls = AtomicInteger()
    override fun invoke(items: List<VodItem>, overrides: List<TitleOverride>): List<VodTitle> {
        calls.incrementAndGet()
        return TitleGrouper.group(items, overrides)
    }
}

/**
 * Groups [source] into a fresh in-memory title index, as the background indexer does after each
 * catalog sync.
 */
fun indexOf(source: VodCatalogSource, grouper: CountingGrouper = CountingGrouper()): InMemoryVodTitleIndex = runBlocking {
    InMemoryVodTitleIndex().also { VodTitleIndexer(source, it, group = grouper).ensureAllFresh() }
}

/** A repository over a freshly built index of [source]; [hidden] category ids are parental-hidden. */
fun indexedRepository(source: VodCatalogSource, hidden: Set<String> = emptySet(), index: VodTitleIndex = indexOf(source)) =
    VodCatalogRepository(source, index, isCategoryHidden = { _, _, id -> id in hidden })

/** A syntactically valid account with obviously fake values. */
val testAccount = StreamAccount(scheme = "http", host = "provider.invalid", port = 8080, username = "user-secret", password = "pass-secret")

val defaultPreferences = VersionPreferencesProvider { ContentPreferences().versionPreferences() }

class FakeMovieInfoLoader(var fail: Boolean = false) : MovieInfoLoader {
    val calls = mutableListOf<Long>()
    override suspend fun load(playlistId: Long, streamId: Long, forceRefresh: Boolean): DetailResult<VodInfoCacheEntity> {
        calls += streamId
        if (fail) return DetailResult.Failed("network", "timeout", retryable = true)
        return DetailResult.Loaded(
            VodInfoCacheEntity(
                playlistId = playlistId, streamId = streamId, name = "Info $streamId", originalName = null,
                plot = "A desert planet.", cast = "Cast", director = "Director", genre = "Sci-Fi", country = null,
                releaseDate = "2021-10-22", cover = null, backdrop = "https://img.example/backdrop.jpg", durationSecs = 9_360,
                rating = 8.1, tmdb = null, youtubeTrailer = null, containerExtension = "mkv", fetchedAt = 0,
            ),
            fromCache = false,
        )
    }
}

/** Series info per series id; ids in [failing] fail (retryable). [cached] serves the same data without counting a fetch. */
class FakeSeriesInfoLoader : SeriesInfoLoader {
    val episodesBySeries = HashMap<Long, List<Pair<Int, Int>>>()
    val failing = HashSet<Long>()
    val calls = mutableListOf<Long>()

    /** Series ids whose info is cached (what [cached] returns); all of them by default. */
    var cachedIds: Set<Long>? = null

    override suspend fun cached(playlistId: Long, seriesId: Long): SeriesDetails? {
        if (cachedIds?.contains(seriesId) == false || seriesId in failing) return null
        return details(playlistId, seriesId)
    }

    fun episodes(seriesId: Long, vararg seasonEpisode: Pair<Int, Int>) = apply { episodesBySeries[seriesId] = seasonEpisode.toList() }

    override suspend fun load(playlistId: Long, seriesId: Long, forceRefresh: Boolean): DetailResult<SeriesDetails> {
        calls += seriesId
        if (seriesId in failing) return DetailResult.Failed("network", "timeout", retryable = true)
        return DetailResult.Loaded(details(playlistId, seriesId), fromCache = false)
    }

    private fun details(playlistId: Long, seriesId: Long): SeriesDetails {
        val list = episodesBySeries[seriesId] ?: emptyList()
        val info = SeriesInfoCacheEntity(
            playlistId, seriesId, "Series $seriesId", null, null, "Series plot", "Cast", null, "Drama", null, 8.0, null, 45, null, 0,
        )
        val episodes = list.mapIndexed { i, (season, ep) ->
            EpisodeEntity(
                playlistId = playlistId, seriesId = seriesId, season = season, episodeNum = ep, episodeId = "${seriesId}0$season$ep",
                title = "Episode $ep", containerExtension = "mp4", durationSecs = 2_700, plot = null, image = null, airDate = null,
                rating = null, sortOrder = i,
            )
        }
        return SeriesDetails(info, emptyList(), episodes)
    }
}
