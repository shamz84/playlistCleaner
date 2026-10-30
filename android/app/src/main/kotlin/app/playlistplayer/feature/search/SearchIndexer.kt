package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.search.SearchLiveEntity
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.core.database.search.SearchVodEntity
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.feature.vod.CatalogTitle
import app.playlistplayer.feature.vod.VodCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds the FTS search index (`SearchStore`) from the active data.
 *
 * - **Live**: one row per non-header entry of the playlist's active generation, name normalised
 *   with [TitleTokenizer.normalize].
 * - **VOD**: one row per merged title from [VodCatalogRepository.index] (the same U5 grouping
 *   and overrides browse uses), so a hit's `titleKey`/`anchorId` open the browse title. The
 *   searchable text is the display title plus every version's base name.
 *
 * **Integration point (least invasive):** no hooks in `PlaylistSyncer`, `CatalogSyncer` or the
 * U15 pipeline. [start] observes Room's invalidation of `sync_state`, `catalog_sync_state`,
 * `title_override` and `playlist` (every generation flip, from any path: pipeline worker,
 * onboarding, legacy workers), debounces, and calls [ensureAllFresh]. Each playlist's index
 * records the playlist generation, catalog generation and override stamp it was built from;
 * only a stale part is rebuilt. Search also calls [ensureAllFresh] before its first query, so
 * an index missing after an upgrade (or a rebuild missed while the process was dead) heals
 * itself. Hidden (parental) content is filtered at query time, never here.
 */
class SearchIndexer(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val searchStore: SearchStore,
    private val vodRepository: VodCatalogRepository,
    private val overrideStamp: suspend (playlistId: Long) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logger: RedactingLogger = RedactingLogger.NONE,
) {
    private val mutex = Mutex()

    /** Rebuild passes run (tests). */
    @Volatile var liveBuilds: Int = 0
        private set

    @Volatile var vodBuilds: Int = 0
        private set

    /** Keeps the index fresh after every generation flip until [scope] ends. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, debounceMillis: Long = DEFAULT_DEBOUNCE_MS): Job = scope.launch {
        searchStore.observeSources().debounce(debounceMillis).collect {
            try {
                ensureAllFresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w("Search index refresh failed", e)
            }
        }
    }

    /** Rebuilds the stale parts of every playlist's index. */
    suspend fun ensureAllFresh() {
        for (playlist in playlistStore.playlists()) ensureFresh(playlist.id)
    }

    /** Rebuilds the stale parts of one playlist's index. Returns true when anything was rebuilt. */
    suspend fun ensureFresh(playlistId: Long): Boolean = mutex.withLock {
        val playlist = playlistStore.playlist(playlistId) ?: return@withLock false
        val state = searchStore.state(playlistId)
        var rebuilt = false

        val liveGeneration = playlistStore.syncState(playlistId)?.activeGeneration
        if (state == null || state.liveGeneration != liveGeneration) {
            rebuildLive(playlistId)
            rebuilt = true
        }

        val catalogGeneration = if (playlist.xtreamEnabled) catalogStore.state(playlistId)?.activeGeneration else null
        val stamp = overrideStamp(playlistId)
        val vodStale = state == null || !state.vodIndexed || state.catalogGeneration != catalogGeneration || state.overridesStamp != stamp
        if (vodStale) {
            // Overrides changed: cached groupings are stale even under the same catalog generation.
            if (state != null && state.overridesStamp != stamp) vodRepository.invalidate(playlistId)
            rebuildVod(playlistId, catalogGeneration, stamp)
            rebuilt = true
        }
        rebuilt
    }

    private suspend fun rebuildLive(playlistId: Long) {
        // Read the generation before and after the rows: a flip in between triggers another pass.
        val generation = playlistStore.syncState(playlistId)?.activeGeneration
        val rows = withContext(dispatcher) {
            playlistStore.channels(playlistId).filterNot { it.isHeader }.map { c ->
                SearchLiveEntity(
                    playlistId = playlistId,
                    ordinal = c.ordinal,
                    groupOrdinal = c.groupOrdinal,
                    groupName = c.groupTitle,
                    streamKey = StreamKeys.of(c.streamId, c.ordinal),
                    normalizedName = TitleTokenizer.normalize(c.name),
                    displayName = c.name,
                    logo = c.logo,
                )
            }.filter { it.normalizedName.isNotEmpty() }
        }
        val after = playlistStore.syncState(playlistId)?.activeGeneration
        searchStore.replaceLive(playlistId, if (after == generation) generation else null, rows, clock())
        liveBuilds++
    }

    private suspend fun rebuildVod(playlistId: Long, catalogGeneration: Long?, stamp: String) {
        val rows = if (catalogGeneration == null) {
            emptyList()
        } else {
            val titles = vodRepository.index(playlistId, VodKind.MOVIE).titles + vodRepository.index(playlistId, VodKind.SERIES).titles
            withContext(dispatcher) { titles.mapNotNull { it.toSearchRow(playlistId) } }
        }
        val after = catalogStore.state(playlistId)?.activeGeneration
        searchStore.replaceVod(playlistId, if (after == catalogGeneration) catalogGeneration else null, stamp, rows, clock())
        vodBuilds++
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 500

        internal fun CatalogTitle.toSearchRow(playlistId: Long): SearchVodEntity? {
            val names = LinkedHashSet<String>()
            TitleTokenizer.normalize(title.title).takeIf { it.isNotEmpty() }?.let(names::add)
            title.versions.forEach { v -> v.tokens.baseName.takeIf { it.isNotEmpty() }?.let(names::add) }
            if (names.isEmpty()) return null
            return SearchVodEntity(
                playlistId = playlistId,
                kind = kind.name,
                titleKey = key,
                normalizedTitle = names.joinToString(" | "),
                displayTitle = title.title,
                year = title.year,
                anchorId = anchorId,
                posterUrl = posterUrl,
                categoryIds = SearchVodEntity.joinCategories(meta.values.mapNotNull { it.categoryId }),
                versionCount = title.versions.size,
            )
        }
    }
}
