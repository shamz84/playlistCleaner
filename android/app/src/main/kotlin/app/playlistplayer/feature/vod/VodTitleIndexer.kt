package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.TitleOverride
import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodTitle
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.database.search.SearchVodEntity
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.sync.BackgroundWorkGate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the merged-title index (U20, R35): groups a playlist's whole catalog with the U5
 * [TitleGrouper] and its overrides **once per catalog generation or override change**, computes the
 * browse sort ranks, and writes the result to [index] in one atomic swap. Browse, Trending,
 * Continue watching, detail screens and the search index then read titles with indexed queries.
 *
 * Every build runs inside the shared [gate] (never alongside a catalog sync, EPG import or search
 * rebuild) and on its low-priority dispatcher. Freshness is checked before taking the gate, so an
 * up-to-date index never waits behind other background work. The search indexer calls
 * [ensureFresh] before it rebuilds, so one maintenance loop keeps both indexes current.
 */
class VodTitleIndexer(
    private val source: VodCatalogSource,
    private val index: VodTitleIndex,
    private val overrides: suspend (playlistId: Long) -> List<TitleOverride> = { emptyList() },
    private val overrideStamp: suspend (playlistId: Long) -> String = { "" },
    private val gate: BackgroundWorkGate = BackgroundWorkGate(),
    private val group: (List<VodItem>, List<TitleOverride>) -> List<VodTitle> = TitleGrouper::group,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val runs = AtomicInteger()

    /** Index builds run (tests and diagnostics). */
    val builds: Int get() = runs.get()

    /** Brings every Xtream playlist's index up to date. */
    suspend fun ensureAllFresh() {
        for (playlist in source.playlists()) ensureFresh(playlist.id)
    }

    /** Rebuilds the playlist's index when its catalog generation or overrides changed. Returns true when it did. */
    suspend fun ensureFresh(playlistId: Long): Boolean {
        if (isCurrent(playlistId)) return false
        return gate.run("title-index") {
            // Re-check inside the gate: another caller may have built it while this one waited.
            val generation = source.catalogGeneration(playlistId)
            val state = index.state(playlistId)
            when {
                generation == null -> {
                    if (state == null) return@run false
                    index.clear(playlistId)
                    true
                }
                isCurrent(playlistId) -> false
                else -> {
                    build(playlistId, generation, overrideStamp(playlistId))
                    true
                }
            }
        }
    }

    private suspend fun isCurrent(playlistId: Long): Boolean {
        val generation = source.catalogGeneration(playlistId)
        val state = index.state(playlistId)
        if (generation == null) return state == null
        return state?.activeGeneration != null && state.catalogGeneration == generation && state.overridesStamp == overrideStamp(playlistId)
    }

    private suspend fun build(playlistId: Long, catalogGeneration: Long, stamp: String) {
        val titleOverrides = overrides(playlistId)
        val titles = ArrayList<VodTitleEntity>()
        val items = ArrayList<VodTitleItemEntity>()
        // One kind at a time, so only one kind's grouping is in memory.
        for (kind in VodKind.entries) {
            VodTitleRows.append(playlistId, kind, groupKind(playlistId, kind, titleOverrides), titles, items)
        }
        index.replace(playlistId, catalogGeneration, stamp, titles, items, clock())
        runs.incrementAndGet()
        logger.i("Title index for playlist $playlistId: ${titles.size} titles from ${items.size} items")
    }

    private suspend fun groupKind(playlistId: Long, kind: VodKind, titleOverrides: List<TitleOverride>): List<CatalogTitle> {
        val names = when (kind) {
            VodKind.MOVIE -> source.vodCategories(playlistId).associate { it.categoryId to it.name }
            VodKind.SERIES -> source.seriesCategories(playlistId).associate { it.categoryId to it.name }
        }
        val vodItems = ArrayList<VodItem>()
        val meta = HashMap<String, ItemMeta>()
        var after = -1
        while (true) {
            val count: Int
            when (kind) {
                VodKind.MOVIE -> {
                    val page = source.vodStreamsPage(playlistId, after, pageSize)
                    page.forEach {
                        vodItems += VodMappers.movieItem(it, it.categoryId?.let(names::get))
                        meta[it.streamId.toString()] = VodMappers.movieMeta(it)
                    }
                    count = page.size
                    if (page.isNotEmpty()) after = page.last().providerOrder
                }
                VodKind.SERIES -> {
                    val page = source.seriesPage(playlistId, after, pageSize)
                    page.forEach {
                        vodItems += VodMappers.seriesItem(it, it.categoryId?.let(names::get))
                        meta[it.seriesId.toString()] = VodMappers.seriesMeta(it)
                    }
                    count = page.size
                    if (page.isNotEmpty()) after = page.last().providerOrder
                }
            }
            if (count < pageSize) break
        }
        // Defensive: the grouper scopes by playlist id already; never let foreign rows in.
        val own = vodItems.filter { it.playlistId == playlistId.toString() }
        return group(own, titleOverrides).map { title ->
            CatalogTitle(title, title.versions.mapNotNull { v -> meta[v.item.id] }.associateBy { it.itemId })
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 1000
    }
}

/** Index rows of merged titles: ranks for every browse sort, the Trending keys and the search text. */
internal object VodTitleRows {

    fun append(
        playlistId: Long,
        kind: VodKind,
        titles: List<CatalogTitle>,
        outTitles: MutableList<VodTitleEntity>,
        outItems: MutableList<VodTitleItemEntity>,
    ) {
        val added = VodTitleSorter.ranks(titles, VodSort.RECENTLY_ADDED)
        val az = VodTitleSorter.ranks(titles, VodSort.A_Z)
        val year = VodTitleSorter.ranks(titles, VodSort.RELEASE_YEAR)
        titles.forEachIndexed { i, t ->
            val itemRows = t.title.versions.map { v ->
                VodTitleItemEntity(
                    playlistId = playlistId,
                    generation = 0,
                    kind = kind.name,
                    itemId = v.item.id,
                    titleKey = t.key,
                    categoryId = t.meta[v.item.id]?.categoryId,
                    tmdb = numericTmdb(v.item.tmdb),
                    providerOrder = v.item.providerOrder,
                )
            }
            outItems += itemRows
            val card = t.card()
            outTitles += VodTitleEntity(
                playlistId = playlistId,
                generation = 0,
                kind = kind.name,
                titleKey = t.key,
                ordinal = i,
                addedRank = added[i],
                azRank = az[i],
                yearRank = year[i],
                title = t.title.title,
                normalizedTitle = TitleTokenizer.normalize(t.title.title),
                titleYear = t.title.year,
                year = card.year,
                tmdb = t.title.tmdb,
                hasTmdb = itemRows.any { it.tmdb != null },
                addedAt = t.addedAt,
                anchorId = card.anchorId,
                posterUrl = card.posterUrl,
                bestQuality = card.bestQuality?.name,
                versionCount = card.versionCount,
                searchText = searchText(t),
                categoryIds = SearchVodEntity.joinCategories(t.meta.values.mapNotNull { it.categoryId }),
            )
        }
    }

    /** The search index's names: display title plus every version's base name ("" when none). */
    fun searchText(t: CatalogTitle): String {
        val names = LinkedHashSet<String>()
        TitleTokenizer.normalize(t.title.title).takeIf { it.isNotEmpty() }?.let(names::add)
        t.title.versions.forEach { v -> v.tokens.baseName.takeIf { it.isNotEmpty() }?.let(names::add) }
        return names.joinToString(" | ")
    }

    /** A provider tmdb value as TMDB's numeric id (no leading zeros), or null. */
    fun numericTmdb(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        return trimmed.trimStart('0').ifEmpty { null }
    }
}
