package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.search.SearchLiveEntity
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.core.database.search.SearchVodEntity
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.sync.BackgroundWorkGate
import app.playlistplayer.feature.vod.VodTitleIndex
import app.playlistplayer.feature.vod.VodTitleIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds the FTS search index (`SearchStore`) from the active data.
 *
 * - **Live**: one row per non-header entry of the playlist's active generation, name normalised
 *   with [TitleTokenizer.normalize].
 * - **VOD**: one row per merged title, copied from the precomputed title index ([titles], U20) —
 *   the same grouping and overrides browse uses, so a hit's `titleKey`/`anchorId` open the browse
 *   title. Nothing is regrouped here: [titleIndexer] brings the title index up to date first.
 *
 * **Integration point:** [start] observes Room's invalidation of `sync_state`,
 * `catalog_sync_state`, `title_override` and `playlist` (every generation flip, from any path),
 * debounces, and calls [ensureAllFresh], which keeps the title index and then the search index
 * current. Each playlist's search index records the playlist generation and the title index's
 * catalog generation and override stamp it was built from; only a stale part is rebuilt, inside
 * the shared [gate] (never alongside a catalog sync, EPG import or title index build). Search also
 * calls [ensureAllFresh] before its first query, so an index missing after an upgrade heals itself.
 * Hidden (parental) content is filtered at query time, never here.
 */
class SearchIndexer(
    private val playlistStore: PlaylistStore,
    private val searchStore: SearchStore,
    private val titles: VodTitleIndex,
    private val titleIndexer: VodTitleIndexer? = null,
    private val gate: BackgroundWorkGate = BackgroundWorkGate(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val mutex = Mutex()

    /** Rebuild passes run (tests). */
    @Volatile var liveBuilds: Int = 0
        private set

    @Volatile var vodBuilds: Int = 0
        private set

    /** Keeps the indexes fresh after every generation flip until [scope] ends. */
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
        // The title index first (its own gated job); the search rows are copied from it.
        if (playlist.xtreamEnabled) titleIndexer?.ensureFresh(playlistId)

        val state = searchStore.state(playlistId)
        val liveGeneration = playlistStore.syncState(playlistId)?.activeGeneration
        val liveStale = state == null || state.liveGeneration != liveGeneration

        val source = titles.state(playlistId)?.takeIf { playlist.xtreamEnabled && it.activeGeneration != null }
        val vodStale = state == null || !state.vodIndexed ||
            state.catalogGeneration != source?.catalogGeneration || state.overridesStamp != source?.overridesStamp
        if (!liveStale && !vodStale) return@withLock false

        gate.run("search-index") {
            if (liveStale) rebuildLive(playlistId)
            if (vodStale) rebuildVod(playlistId, source?.activeGeneration, source?.catalogGeneration, source?.overridesStamp)
        }
        true
    }

    private suspend fun rebuildLive(playlistId: Long) {
        // Read the generation before and after the rows: a flip in between triggers another pass.
        val generation = playlistStore.syncState(playlistId)?.activeGeneration
        val rows = playlistStore.channels(playlistId).filterNot { it.isHeader }.map { c ->
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
        val after = playlistStore.syncState(playlistId)?.activeGeneration
        searchStore.replaceLive(playlistId, if (after == generation) generation else null, rows, clock())
        liveBuilds++
    }

    private suspend fun rebuildVod(playlistId: Long, titleGeneration: Long?, catalogGeneration: Long?, stamp: String?) {
        val rows = ArrayList<SearchVodEntity>()
        if (titleGeneration != null) {
            for (kind in VodKind.entries) {
                var after = -1
                while (true) {
                    val page = titles.titlesPage(playlistId, kind, after, pageSize)
                    page.mapNotNullTo(rows) { it.toSearchRow() }
                    if (page.size < pageSize) break
                    after = page.last().ordinal
                }
            }
        }
        // A title index flip while reading (not expected: both are gated) forces another pass.
        val after = titles.state(playlistId)?.activeGeneration
        val built = if (after == titleGeneration) catalogGeneration else null
        searchStore.replaceVod(playlistId, built, stamp, rows, clock())
        vodBuilds++
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 500
        const val DEFAULT_PAGE_SIZE: Int = 1000

        internal fun VodTitleEntity.toSearchRow(): SearchVodEntity? {
            if (searchText.isEmpty()) return null
            return SearchVodEntity(
                playlistId = playlistId,
                kind = kind,
                titleKey = titleKey,
                normalizedTitle = searchText,
                displayTitle = title,
                year = titleYear,
                anchorId = anchorId,
                posterUrl = posterUrl,
                categoryIds = categoryIds,
                versionCount = versionCount,
            )
        }
    }
}
