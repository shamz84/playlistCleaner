package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.core.database.search.SearchVodEntity

/** A playlist as search labels it (R25: results sit under the playlist's name). */
data class SearchPlaylist(val id: Long, val name: String)

/** A live hit: one playlist entry. Open it with (playlistId, ordinal). */
data class LiveHit(
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupName: String,
    val name: String,
    val logo: String?,
    val streamKey: String,
)

/**
 * A VOD hit: one merged title. Open it like a browse card: `TitleDetailViewModel(playlistId,
 * anchorId)` for movies, the series screen for series.
 */
data class VodHit(
    val playlistId: Long,
    val kind: VodKind,
    val titleKey: String,
    val anchorId: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val versionCount: Int,
)

/** Hits of one playlist, labelled with its name. Playlists never mix (R26). */
data class PlaylistHits<T>(val playlist: SearchPlaylist, val hits: List<T>)

data class SearchResults(
    val query: String,
    val live: List<PlaylistHits<LiveHit>>,
    val movies: List<PlaylistHits<VodHit>>,
    val series: List<PlaylistHits<VodHit>>,
) {
    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
    val liveCount: Int get() = live.sumOf { it.hits.size }
    val movieCount: Int get() = movies.sumOf { it.hits.size }
    val seriesCount: Int get() = series.sumOf { it.hits.size }

    companion object {
        fun empty(query: String) = SearchResults(query, emptyList(), emptyList(), emptyList())
    }
}

/** What [SearchViewModel] queries; [SearchRepository] in the app. */
fun interface SearchEngine {
    suspend fun search(query: String): SearchResults
}

/**
 * Global search over live entries and merged VOD titles (R17).
 *
 * The query is normalised like the index ([TitleTokenizer.normalize]: case, diacritics,
 * punctuation and letter/digit boundaries ignored). Hidden (parental) content is dropped while
 * paging through ranked hits, so limits count visible results only and nothing reveals that a
 * locked match exists. Before the first query the index is brought up to date ([indexer]).
 */
class SearchRepository(
    private val store: SearchStore,
    private val playlists: suspend () -> List<SearchPlaylist>,
    private val visibility: () -> ContentVisibility = { ContentVisibility.ALL_VISIBLE },
    private val indexer: SearchIndexer? = null,
) : SearchEngine {
    @Volatile private var indexChecked = false

    override suspend fun search(query: String): SearchResults = search(query, DEFAULT_LIVE_LIMIT)

    suspend fun search(
        query: String,
        liveLimit: Int,
        movieLimit: Int = DEFAULT_VOD_LIMIT,
        seriesLimit: Int = DEFAULT_VOD_LIMIT,
    ): SearchResults {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return SearchResults.empty(query)
        ensureIndex()
        val rules = visibility()
        val order = playlists()
        val known = order.associateBy { it.id }

        val live = collect(liveLimit, { limit, offset -> store.searchLive(normalized, limit, offset) }) { row ->
            row.playlistId in known && !rules.isLiveGroupHidden(row.playlistId, row.groupOrdinal, row.groupName)
        }.map { LiveHit(it.playlistId, it.ordinal, it.groupOrdinal, it.groupName, it.displayName, it.logo, it.streamKey) }

        val movies = ArrayList<VodHit>()
        val series = ArrayList<VodHit>()
        val vodRows = collect(movieLimit + seriesLimit, { limit, offset -> store.searchVod(normalized, limit, offset) }) { row ->
            val kind = row.vodKind() ?: return@collect false
            row.playlistId in known && !rules.isTitleHidden(row.playlistId, kind, row.categoryIdList)
        }
        for (row in vodRows) {
            val hit = row.toHit() ?: continue
            when (hit.kind) {
                VodKind.MOVIE -> if (movies.size < movieLimit) movies += hit
                VodKind.SERIES -> if (series.size < seriesLimit) series += hit
            }
        }
        return SearchResults(
            query = query,
            live = groupByPlaylist(order, live) { it.playlistId },
            movies = groupByPlaylist(order, movies) { it.playlistId },
            series = groupByPlaylist(order, series) { it.playlistId },
        )
    }

    private suspend fun ensureIndex() {
        if (indexChecked || indexer == null) return
        indexer.ensureAllFresh()
        indexChecked = true
    }

    /** Pages through ranked rows until [limit] pass [keep] or the hits run out. */
    private suspend fun <T> collect(limit: Int, page: suspend (limit: Int, offset: Int) -> List<T>, keep: (T) -> Boolean): List<T> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<T>(limit)
        var offset = 0
        val pageSize = maxOf(limit * 2, MIN_PAGE)
        repeat(MAX_PAGES) {
            val rows = page(pageSize, offset)
            for (row in rows) {
                if (keep(row)) out += row
                if (out.size >= limit) return out
            }
            if (rows.size < pageSize) return out
            offset += rows.size
        }
        return out
    }

    private fun <T> groupByPlaylist(order: List<SearchPlaylist>, hits: List<T>, playlistOf: (T) -> Long): List<PlaylistHits<T>> {
        val byPlaylist = hits.groupBy(playlistOf)
        return order.mapNotNull { p -> byPlaylist[p.id]?.let { PlaylistHits(p, it) } }
    }

    companion object {
        const val DEFAULT_LIVE_LIMIT: Int = 100
        const val DEFAULT_VOD_LIMIT: Int = 60
        private const val MIN_PAGE: Int = 50
        private const val MAX_PAGES: Int = 20

        fun normalize(query: String): String = TitleTokenizer.normalize(query)

        internal fun SearchVodEntity.vodKind(): VodKind? = runCatching { VodKind.valueOf(kind) }.getOrNull()

        internal fun SearchVodEntity.toHit(): VodHit? {
            val k = vodKind() ?: return null
            return VodHit(playlistId, k, titleKey, anchorId, displayTitle, year, posterUrl, versionCount)
        }
    }
}
