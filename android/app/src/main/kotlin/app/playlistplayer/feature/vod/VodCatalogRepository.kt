package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodTitle
import app.playlistplayer.core.consolidation.VodVersion
import app.playlistplayer.core.database.entity.VodTitleEntity
import kotlinx.coroutines.flow.Flow

/** A provider category of one playlist and kind. */
data class VodCategory(val playlistId: Long, val kind: VodKind, val id: String, val name: String)

/**
 * Merged VOD titles for the screens, per playlist (never across playlists, R26), read from the
 * precomputed [VodTitleIndex] (U20, R35): every method is a handful of indexed queries; nothing
 * here groups or sorts the catalog. [VodTitleIndexer] builds the index in the background after
 * each catalog sync.
 *
 * Parental hiding: [hiddenCategories] lists a section's hidden category ids (a few hundred
 * categories at most); a title is left out only when every version sits in one of them.
 */
class VodCatalogRepository(
    private val source: VodCatalogSource,
    private val index: VodTitleIndex,
    private val isCategoryHidden: suspend (playlistId: Long, kind: VodKind, categoryId: String) -> Boolean = { _, _, _ -> false },
) {
    suspend fun playlists(): List<VodPlaylist> = source.playlists()

    /** Emits after a playlist's title index is rebuilt or cleared. */
    fun changes(): Flow<Unit> = index.changes()

    /** True while the playlist has a catalog but its title index is not built yet (first sync, upgrade). */
    suspend fun isIndexPending(playlistId: Long): Boolean =
        source.catalogGeneration(playlistId) != null && index.state(playlistId)?.activeGeneration == null

    suspend fun categories(playlistId: Long, kind: VodKind): List<VodCategory> {
        val rows = when (kind) {
            VodKind.MOVIE -> source.vodCategories(playlistId).sortedBy { it.ordinal }.map { it.categoryId to it.name }
            VodKind.SERIES -> source.seriesCategories(playlistId).sortedBy { it.ordinal }.map { it.categoryId to it.name }
        }
        return rows.filterNot { (id, _) -> isCategoryHidden(playlistId, kind, id) }
            .map { (id, name) -> VodCategory(playlistId, kind, id, name) }
    }

    /** The section's parental-hidden category ids. */
    suspend fun hiddenCategories(playlistId: Long, kind: VodKind): Set<String> {
        val ids = when (kind) {
            VodKind.MOVIE -> source.vodCategories(playlistId).map { it.categoryId }
            VodKind.SERIES -> source.seriesCategories(playlistId).map { it.categoryId }
        }
        return ids.filterTo(HashSet()) { isCategoryHidden(playlistId, kind, it) }
    }

    /** Number of visible titles in the section. */
    suspend fun count(playlistId: Long, kind: VodKind, hidden: Set<String>): Int = index.count(playlistId, kind, hidden)

    /** One page of the section's visible titles in [sort] order. */
    suspend fun page(playlistId: Long, kind: VodKind, sort: VodSort, hidden: Set<String>, offset: Int, limit: Int): List<VodCard> =
        index.page(playlistId, kind, sort.order, hidden, offset, limit).map(VodTitleEntity::card)

    /** Visible titles among [keys], by key. */
    suspend fun cards(playlistId: Long, kind: VodKind, keys: Collection<String>, hidden: Set<String>): Map<String, VodCard> =
        if (keys.isEmpty()) emptyMap() else index.titles(playlistId, kind, keys, hidden).associate { it.titleKey to it.card() }

    /** Item ids of a title's versions, in provider order. */
    suspend fun versionIds(playlistId: Long, titleKey: String): List<String> = index.items(playlistId, titleKey).map { it.itemId }

    /**
     * Trending entries (R32) matched to the section's visible titles: by the provider tmdb id, else
     * (for titles with no tmdb id at all) by normalised title + year. Two indexed lookups, then
     * [TrendingMatcher] applies the rules; the result keeps TMDB rank order.
     */
    suspend fun trending(playlistId: Long, kind: VodKind, entries: List<TrendingEntry>, hidden: Set<String>, limit: Int): List<VodCard> {
        if (entries.isEmpty() || limit <= 0) return emptyList()
        val items = index.itemsByTmdb(playlistId, kind, entries.map { it.tmdbId.toString() }.distinct())
        val tmdbTitles = index.titles(playlistId, kind, items.map { it.titleKey }.distinct(), hidden).associateBy { it.titleKey }
        val byTmdb = items.groupBy { it.tmdb.orEmpty() }.mapValues { (_, rows) ->
            rows.map { it.titleKey }.distinct().mapNotNull(tmdbTitles::get).sortedBy { it.ordinal }
        }
        val names = entries.flatMap { listOfNotNull(it.title, it.originalTitle) }.map(TitleTokenizer::normalize).filter { it.isNotEmpty() }.distinct()
        val byName = index.titlesByName(playlistId, kind, names, hidden).sortedBy { it.ordinal }.groupBy { it.normalizedTitle }
        return TrendingMatcher.match(entries, byTmdb, byName, kind, limit).map(VodTitleEntity::card)
    }

    /**
     * The merged title holding [itemId] (a card's anchor) with every version, for the detail
     * screens: the index names the title and its versions; only those few catalog rows are read
     * and parsed. Null when the title or all of its versions are gone.
     */
    suspend fun title(playlistId: Long, kind: VodKind, itemId: String): CatalogTitle? {
        val row = index.titleForItem(playlistId, kind, itemId) ?: return null
        val ids = index.items(playlistId, row.titleKey).mapNotNull { it.itemId.toLongOrNull() }
        if (ids.isEmpty()) return null
        val names = when (kind) {
            VodKind.MOVIE -> source.vodCategories(playlistId).associate { it.categoryId to it.name }
            VodKind.SERIES -> source.seriesCategories(playlistId).associate { it.categoryId to it.name }
        }
        val versions = ArrayList<VodVersion>()
        val meta = ArrayList<ItemMeta>()
        for (id in ids) {
            when (kind) {
                VodKind.MOVIE -> source.vodStream(playlistId, id)?.let {
                    versions += TitleGrouper.versionOf(VodMappers.movieItem(it, it.categoryId?.let(names::get)))
                    meta += VodMappers.movieMeta(it)
                }
                VodKind.SERIES -> source.series(playlistId, id)?.let {
                    versions += TitleGrouper.versionOf(VodMappers.seriesItem(it, it.categoryId?.let(names::get)))
                    meta += VodMappers.seriesMeta(it)
                }
            }
        }
        if (versions.isEmpty()) return null
        val ordered = versions.sortedWith(compareBy({ it.item.providerOrder }, { it.item.id }))
        val title = VodTitle(row.titleKey, playlistId.toString(), kind, row.title, row.titleYear, row.tmdb, ordered)
        return CatalogTitle(title, meta.associateBy { it.itemId })
    }
}
