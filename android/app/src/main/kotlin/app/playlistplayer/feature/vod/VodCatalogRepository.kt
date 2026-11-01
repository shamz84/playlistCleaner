package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.TitleOverride
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** A browse rail = one provider category. */
data class VodCategory(val playlistId: Long, val kind: VodKind, val id: String, val name: String)

/** Every merged title of one playlist and kind, indexed by item id (for detail screens). */
class PlaylistIndex internal constructor(
    val playlistId: Long,
    val kind: VodKind,
    val generation: Long?,
    val titles: List<CatalogTitle>,
) {
    private val byItem: Map<String, CatalogTitle> = buildMap {
        for (t in titles) for (v in t.title.versions) put(v.item.id, t)
    }
    private val byKey: Map<String, CatalogTitle> = titles.associateBy { it.key }

    fun titleForItem(itemId: String): CatalogTitle? = byItem[itemId]
    fun titleForKey(key: String): CatalogTitle? = byKey[key]
}

/**
 * Builds merged titles (U5 [TitleGrouper]) over the catalog, per playlist (never across
 * playlists, R26).
 *
 * - Browse rails group lazily, one category at a time ([categoryTitles]).
 * - Detail screens use the playlist-wide index ([index]), so a title merges versions from every
 *   category of its playlist.
 *
 * Grouping always runs on [groupingDispatcher] (Default in the app), never on the caller's
 * thread, and results are cached per playlist + catalog generation: a new generation evicts the
 * playlist's older entries. Concurrent requests for the same key share one computation.
 */
class VodCatalogRepository(
    private val source: VodCatalogSource,
    private val groupingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val overrides: suspend (playlistId: Long) -> List<TitleOverride> = { emptyList() },
    private val isCategoryHidden: suspend (playlistId: Long, kind: VodKind, categoryId: String) -> Boolean = { _, _, _ -> false },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private data class CacheKey(val playlistId: Long, val generation: Long?, val kind: VodKind, val categoryId: String?)

    private val mutex = Mutex()
    private val cache = HashMap<CacheKey, CompletableDeferred<Any>>()

    /** Number of grouping passes run (diagnostics and cache tests). */
    private val runs = AtomicInteger()
    val groupingRuns: Int get() = runs.get()

    /** Observes the context each grouping pass runs in (tests assert the dispatcher). */
    internal var onGrouping: ((CoroutineContext) -> Unit)? = null

    suspend fun playlists(): List<VodPlaylist> = source.playlists()

    suspend fun categories(playlistId: Long, kind: VodKind): List<VodCategory> {
        val rows = when (kind) {
            VodKind.MOVIE -> source.vodCategories(playlistId).sortedBy { it.ordinal }.map { it.categoryId to it.name }
            VodKind.SERIES -> source.seriesCategories(playlistId).sortedBy { it.ordinal }.map { it.categoryId to it.name }
        }
        return rows.filterNot { (id, _) -> isCategoryHidden(playlistId, kind, id) }
            .map { (id, name) -> VodCategory(playlistId, kind, id, name) }
    }

    /** Merged titles of one category, in provider order. */
    suspend fun categoryTitles(category: VodCategory): List<CatalogTitle> {
        val generation = source.catalogGeneration(category.playlistId)
        val key = CacheKey(category.playlistId, generation, category.kind, category.id)
        @Suppress("UNCHECKED_CAST")
        return cached(key) {
            val categoryName = categoryNames(category.playlistId, category.kind)[category.id]
            val (items, meta) = when (category.kind) {
                VodKind.MOVIE -> loadAll { offset ->
                    source.vodStreamsInCategory(category.playlistId, category.id, pageSize, offset)
                }.let { rows ->
                    rows.map { VodMappers.movieItem(it, categoryName) } to rows.map(VodMappers::movieMeta)
                }
                VodKind.SERIES -> loadAll { offset ->
                    source.seriesInCategory(category.playlistId, category.id, pageSize, offset)
                }.let { rows ->
                    rows.map { VodMappers.seriesItem(it, categoryName) } to rows.map(VodMappers::seriesMeta)
                }
            }
            group(category.playlistId, items, meta)
        } as List<CatalogTitle>
    }

    /** Playlist-wide index of merged titles for [kind]. */
    suspend fun index(playlistId: Long, kind: VodKind): PlaylistIndex {
        val generation = source.catalogGeneration(playlistId)
        val key = CacheKey(playlistId, generation, kind, null)
        return cached(key) {
            val names = categoryNames(playlistId, kind)
            val items = ArrayList<VodItem>()
            val meta = ArrayList<ItemMeta>()
            var after = -1
            while (true) {
                val count: Int
                when (kind) {
                    VodKind.MOVIE -> {
                        val page = source.vodStreamsPage(playlistId, after, pageSize)
                        page.forEach {
                            items += VodMappers.movieItem(it, it.categoryId?.let(names::get))
                            meta += VodMappers.movieMeta(it)
                        }
                        count = page.size
                        if (page.isNotEmpty()) after = page.last().providerOrder
                    }
                    VodKind.SERIES -> {
                        val page = source.seriesPage(playlistId, after, pageSize)
                        page.forEach {
                            items += VodMappers.seriesItem(it, it.categoryId?.let(names::get))
                            meta += VodMappers.seriesMeta(it)
                        }
                        count = page.size
                        if (page.isNotEmpty()) after = page.last().providerOrder
                    }
                }
                if (count < pageSize) break
            }
            PlaylistIndex(playlistId, kind, generation, group(playlistId, items, meta))
        } as PlaylistIndex
    }

    /**
     * Every merged title of a playlist and kind that parental controls let through, in provider
     * order. A title is hidden when all of its versions sit in hidden categories (the rule Search
     * and Home use); versions without a category never hide it.
     */
    suspend fun visibleTitles(playlistId: Long, kind: VodKind): List<CatalogTitle> {
        val titles = index(playlistId, kind).titles
        val categoryIds = HashSet<String>()
        titles.forEach { t -> t.meta.values.forEach { m -> m.categoryId?.let(categoryIds::add) } }
        val hidden = categoryIds.filterTo(HashSet()) { isCategoryHidden(playlistId, kind, it) }
        if (hidden.isEmpty()) return titles
        return titles.filterNot { t ->
            val categories = t.title.versions.map { v -> t.meta[v.item.id]?.categoryId }
            categories.isNotEmpty() && categories.all { it != null && it in hidden }
        }
    }

    /** The merged title holding [itemId] (a browse card's anchor), across all categories. */
    suspend fun title(playlistId: Long, kind: VodKind, itemId: String): CatalogTitle? =
        index(playlistId, kind).titleForItem(itemId)

    /** Drops every cached grouping (e.g. after an override edit). */
    suspend fun invalidate(playlistId: Long? = null) = mutex.withLock {
        if (playlistId == null) cache.clear() else cache.keys.removeAll { it.playlistId == playlistId }
    }

    private suspend fun categoryNames(playlistId: Long, kind: VodKind): Map<String, String> = when (kind) {
        VodKind.MOVIE -> source.vodCategories(playlistId).associate { it.categoryId to it.name }
        VodKind.SERIES -> source.seriesCategories(playlistId).associate { it.categoryId to it.name }
    }

    private suspend fun <T> loadAll(page: suspend (offset: Int) -> List<T>): List<T> {
        val out = ArrayList<T>()
        while (true) {
            val rows = page(out.size)
            out += rows
            if (rows.size < pageSize) return out
        }
    }

    private suspend fun group(playlistId: Long, items: List<VodItem>, meta: List<ItemMeta>): List<CatalogTitle> {
        val titleOverrides = overrides(playlistId)
        return withContext(groupingDispatcher) {
            onGrouping?.invoke(coroutineContext)
            runs.incrementAndGet()
            // Defensive: the grouper scopes by playlist id already; never let foreign rows in.
            val own = items.filter { it.playlistId == playlistId.toString() }
            val metaById = meta.associateBy { it.itemId }
            TitleGrouper.group(own, titleOverrides).map { title ->
                CatalogTitle(title, title.versions.mapNotNull { v -> metaById[v.item.id] }.associateBy { it.itemId })
            }
        }
    }

    private suspend fun cached(key: CacheKey, compute: suspend () -> Any): Any {
        var owner = false
        val deferred = mutex.withLock {
            // A newer generation for this playlist makes every older entry unreachable.
            cache.keys.removeAll { it.playlistId == key.playlistId && it.generation != key.generation }
            cache.getOrPut(key) { owner = true; CompletableDeferred() }
        }
        if (owner) {
            try {
                deferred.complete(compute())
            } catch (e: Throwable) {
                mutex.withLock { if (cache[key] === deferred) cache.remove(key) }
                if (e is CancellationException) deferred.cancel(e) else deferred.completeExceptionally(e)
                throw e
            }
        }
        return deferred.await()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 1000
    }
}
