package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.TitleOrder
import app.playlistplayer.core.database.VodTitleStore
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.database.entity.VodTitleStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The precomputed merged-title index (U20): written once per catalog sync or override change by
 * [VodTitleIndexer], read by browse, Trending, Continue watching, detail screens and the search
 * index with indexed queries. Nothing here groups or sorts the catalog.
 *
 * [hidden] arguments are parental-hidden category ids: a title is left out only when every
 * version sits in one of them.
 */
interface VodTitleIndex {
    suspend fun state(playlistId: Long): VodTitleStateEntity?

    /** Emits after any playlist's index flips or is cleared (no initial emission). */
    fun changes(): Flow<Unit>

    /** Atomically replaces the playlist's titles (both kinds). */
    suspend fun replace(
        playlistId: Long,
        catalogGeneration: Long?,
        overridesStamp: String?,
        titles: List<VodTitleEntity>,
        items: List<VodTitleItemEntity>,
        now: Long,
    )

    suspend fun clear(playlistId: Long)

    suspend fun page(playlistId: Long, kind: VodKind, order: TitleOrder, hidden: Set<String>, offset: Int, limit: Int): List<VodTitleEntity>
    suspend fun count(playlistId: Long, kind: VodKind, hidden: Set<String>): Int
    suspend fun titleForItem(playlistId: Long, kind: VodKind, itemId: String): VodTitleEntity?
    suspend fun items(playlistId: Long, titleKey: String): List<VodTitleItemEntity>
    suspend fun titles(playlistId: Long, kind: VodKind, keys: Collection<String>, hidden: Set<String>): List<VodTitleEntity>
    suspend fun itemsByTmdb(playlistId: Long, kind: VodKind, tmdbIds: Collection<String>): List<VodTitleItemEntity>
    suspend fun titlesByName(playlistId: Long, kind: VodKind, names: Collection<String>, hidden: Set<String>): List<VodTitleEntity>

    /** Keyset page in provider order; start with `afterOrdinal = -1`. */
    suspend fun titlesPage(playlistId: Long, kind: VodKind, afterOrdinal: Int, limit: Int): List<VodTitleEntity>
}

/** [VodTitleIndex] over Room ([VodTitleStore]). */
class RoomVodTitleIndex(private val store: VodTitleStore) : VodTitleIndex {
    override suspend fun state(playlistId: Long) = store.state(playlistId)

    // Room's invalidation flow emits once on collection: skip it.
    override fun changes(): Flow<Unit> = store.observeStates().drop(1).map { }

    override suspend fun replace(
        playlistId: Long,
        catalogGeneration: Long?,
        overridesStamp: String?,
        titles: List<VodTitleEntity>,
        items: List<VodTitleItemEntity>,
        now: Long,
    ) {
        store.replace(playlistId, catalogGeneration, overridesStamp, titles, items, now)
    }

    override suspend fun clear(playlistId: Long) = store.clearPlaylist(playlistId)

    override suspend fun page(playlistId: Long, kind: VodKind, order: TitleOrder, hidden: Set<String>, offset: Int, limit: Int) =
        store.page(playlistId, kind.name, order, hidden, offset, limit)

    override suspend fun count(playlistId: Long, kind: VodKind, hidden: Set<String>) = store.count(playlistId, kind.name, hidden)

    override suspend fun titleForItem(playlistId: Long, kind: VodKind, itemId: String) = store.titleForItem(playlistId, kind.name, itemId)

    override suspend fun items(playlistId: Long, titleKey: String) = store.items(playlistId, titleKey)

    override suspend fun titles(playlistId: Long, kind: VodKind, keys: Collection<String>, hidden: Set<String>) =
        store.titles(playlistId, kind.name, keys, hidden)

    override suspend fun itemsByTmdb(playlistId: Long, kind: VodKind, tmdbIds: Collection<String>) =
        store.itemsByTmdb(playlistId, kind.name, tmdbIds)

    override suspend fun titlesByName(playlistId: Long, kind: VodKind, names: Collection<String>, hidden: Set<String>) =
        store.titlesByName(playlistId, kind.name, names, hidden)

    override suspend fun titlesPage(playlistId: Long, kind: VodKind, afterOrdinal: Int, limit: Int) =
        store.titlesPage(playlistId, kind.name, afterOrdinal, limit)
}

/** In-memory [VodTitleIndex] with the same semantics as the SQL (tests and previews). */
class InMemoryVodTitleIndex : VodTitleIndex {
    private class Entry(val state: VodTitleStateEntity, val titles: List<VodTitleEntity>, val items: List<VodTitleItemEntity>)

    private val entries = HashMap<Long, Entry>()
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Set to make every read of a playlist throw (failure tests). */
    var failingPlaylists: Set<Long> = emptySet()

    override suspend fun state(playlistId: Long): VodTitleStateEntity? = synchronized(entries) { entries[playlistId]?.state }

    override fun changes(): Flow<Unit> = changeEvents

    override suspend fun replace(
        playlistId: Long,
        catalogGeneration: Long?,
        overridesStamp: String?,
        titles: List<VodTitleEntity>,
        items: List<VodTitleItemEntity>,
        now: Long,
    ) {
        synchronized(entries) {
            val generation = (entries[playlistId]?.state?.lastGeneration ?: 0) + 1
            entries[playlistId] = Entry(
                VodTitleStateEntity(playlistId, generation, null, generation, catalogGeneration, overridesStamp, now, titles.size),
                titles.map { it.copy(generation = generation) },
                items.map { it.copy(generation = generation) },
            )
        }
        changeEvents.tryEmit(Unit)
    }

    override suspend fun clear(playlistId: Long) {
        synchronized(entries) { entries.remove(playlistId) }
        changeEvents.tryEmit(Unit)
    }

    override suspend fun page(playlistId: Long, kind: VodKind, order: TitleOrder, hidden: Set<String>, offset: Int, limit: Int): List<VodTitleEntity> {
        val rank: (VodTitleEntity) -> Int = when (order) {
            TitleOrder.ADDED -> VodTitleEntity::addedRank
            TitleOrder.NAME -> VodTitleEntity::azRank
            TitleOrder.YEAR -> VodTitleEntity::yearRank
        }
        return visible(playlistId, kind, hidden).sortedBy(rank).drop(offset).take(limit)
    }

    override suspend fun count(playlistId: Long, kind: VodKind, hidden: Set<String>): Int = visible(playlistId, kind, hidden).size

    override suspend fun titleForItem(playlistId: Long, kind: VodKind, itemId: String): VodTitleEntity? {
        val e = entry(playlistId) ?: return null
        val key = e.items.firstOrNull { it.kind == kind.name && it.itemId == itemId }?.titleKey ?: return null
        return e.titles.firstOrNull { it.titleKey == key }
    }

    override suspend fun items(playlistId: Long, titleKey: String): List<VodTitleItemEntity> =
        entry(playlistId)?.items?.filter { it.titleKey == titleKey }?.sortedWith(compareBy({ it.providerOrder }, { it.itemId })).orEmpty()

    override suspend fun titles(playlistId: Long, kind: VodKind, keys: Collection<String>, hidden: Set<String>): List<VodTitleEntity> {
        val wanted = keys.toSet()
        return visible(playlistId, kind, hidden).filter { it.titleKey in wanted }
    }

    override suspend fun itemsByTmdb(playlistId: Long, kind: VodKind, tmdbIds: Collection<String>): List<VodTitleItemEntity> {
        val wanted = tmdbIds.toSet()
        return entry(playlistId)?.items?.filter { it.kind == kind.name && it.tmdb != null && it.tmdb in wanted }.orEmpty()
    }

    override suspend fun titlesByName(playlistId: Long, kind: VodKind, names: Collection<String>, hidden: Set<String>): List<VodTitleEntity> {
        val wanted = names.toSet()
        return visible(playlistId, kind, hidden).filter { !it.hasTmdb && it.normalizedTitle in wanted }
    }

    override suspend fun titlesPage(playlistId: Long, kind: VodKind, afterOrdinal: Int, limit: Int): List<VodTitleEntity> =
        entry(playlistId)?.titles?.filter { it.kind == kind.name && it.ordinal > afterOrdinal }?.sortedBy { it.ordinal }?.take(limit).orEmpty()

    private fun entry(playlistId: Long): Entry? {
        check(playlistId !in failingPlaylists) { "title index unavailable" }
        return synchronized(entries) { entries[playlistId] }
    }

    private fun visible(playlistId: Long, kind: VodKind, hidden: Set<String>): List<VodTitleEntity> {
        val e = entry(playlistId) ?: return emptyList()
        val titles = e.titles.filter { it.kind == kind.name }
        if (hidden.isEmpty()) return titles
        val shown = e.items.filter { it.categoryId == null || it.categoryId !in hidden }.mapTo(HashSet()) { it.titleKey }
        return titles.filter { it.titleKey in shown }
    }
}

/** The browse card of an indexed title. */
fun VodTitleEntity.card(): VodCard = VodCard(
    playlistId = playlistId,
    kind = VodKind.valueOf(kind),
    anchorId = anchorId,
    titleKey = titleKey,
    title = title,
    year = year,
    posterUrl = posterUrl,
    bestQuality = bestQuality?.let { name -> QualityTier.entries.firstOrNull { it.name == name } },
    versionCount = versionCount,
)
