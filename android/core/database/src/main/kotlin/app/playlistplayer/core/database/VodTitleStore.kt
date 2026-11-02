package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.database.entity.VodTitleStateEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Browse orders of a title list; each maps to a precomputed rank column of `vod_title`. */
enum class TitleOrder { ADDED, NAME, YEAR }

/**
 * The precomputed merged-title index (U20, see `VodTitleEntities.kt`).
 *
 * **Writes:** [replace] writes a whole playlist's titles (both kinds) under a fresh generation in
 * batched transactions, then flips `vod_title_state.activeGeneration` in one short transaction and
 * deletes the previous generation. Readers see either the old or the new index, never a mix; a
 * failed write discards the partial generation and keeps the old one.
 *
 * **Reads** are indexed queries over the active generation. [hidden] category ids apply the
 * parental rule (hidden only when every version is in a hidden category).
 */
class VodTitleStore(private val db: AppDatabase) {
    private val dao = db.vodTitleDao()

    suspend fun state(playlistId: Long): VodTitleStateEntity? = dao.state(playlistId)

    /** Emits (initially, and) whenever any playlist's title index flips or is cleared. */
    fun observeStates(): Flow<Set<String>> = db.invalidationTracker.createFlow("vod_title_state")

    /**
     * Replaces the playlist's titles. Rows' `generation` is ignored (the new generation is used);
     * rows must belong to [playlistId]. Returns the new active generation.
     */
    suspend fun replace(
        playlistId: Long,
        catalogGeneration: Long?,
        overridesStamp: String?,
        titles: List<VodTitleEntity>,
        items: List<VodTitleItemEntity>,
        now: Long,
        batchSize: Int = PlaylistStore.DEFAULT_BATCH_SIZE,
    ): Long {
        require(titles.all { it.playlistId == playlistId } && items.all { it.playlistId == playlistId }) { "Row belongs to another playlist" }
        val generation = beginGeneration(playlistId)
        try {
            titles.chunked(batchSize).forEach { chunk ->
                db.withWriteTransaction { dao.insertTitles(chunk.map { it.copy(generation = generation) }) }
            }
            items.chunked(batchSize).forEach { chunk ->
                db.withWriteTransaction { dao.insertItems(chunk.map { it.copy(generation = generation) }) }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { discard(playlistId, generation) }
            throw e
        }
        val previous = db.withWriteTransaction {
            val state = dao.state(playlistId) ?: VodTitleStateEntity(playlistId)
            check(state.pendingGeneration == generation) { "Title generation $generation is not pending for playlist $playlistId" }
            dao.upsertState(
                state.copy(
                    activeGeneration = generation,
                    pendingGeneration = null,
                    lastGeneration = maxOf(state.lastGeneration, generation),
                    catalogGeneration = catalogGeneration,
                    overridesStamp = overridesStamp,
                    builtAt = now,
                    titleCount = titles.size,
                ),
            )
            state.activeGeneration
        }
        if (previous != null && previous != generation) db.withWriteTransaction { deleteGeneration(playlistId, previous) }
        return generation
    }

    /** Generations with title rows (tests). */
    suspend fun generationsPresent(playlistId: Long): List<Long> = dao.generationsPresent(playlistId)

    suspend fun page(playlistId: Long, kind: String, order: TitleOrder, hidden: Collection<String>, offset: Int, limit: Int): List<VodTitleEntity> {
        val filter = hidden.isNotEmpty()
        val list = hidden.toList()
        return when (order) {
            TitleOrder.ADDED -> dao.pageByAdded(playlistId, kind, filter, list, limit, offset)
            TitleOrder.NAME -> dao.pageByName(playlistId, kind, filter, list, limit, offset)
            TitleOrder.YEAR -> dao.pageByYear(playlistId, kind, filter, list, limit, offset)
        }
    }

    suspend fun count(playlistId: Long, kind: String, hidden: Collection<String>): Int =
        dao.count(playlistId, kind, hidden.isNotEmpty(), hidden.toList())

    /** The title holding catalog item [itemId] (a card's anchor), whatever its visibility. */
    suspend fun titleForItem(playlistId: Long, kind: String, itemId: String): VodTitleEntity? = dao.titleForItem(playlistId, kind, itemId)

    /** A title's versions in provider order. */
    suspend fun items(playlistId: Long, titleKey: String): List<VodTitleItemEntity> = dao.items(playlistId, titleKey)

    /** Visible titles among [keys] (any order). */
    suspend fun titles(playlistId: Long, kind: String, keys: Collection<String>, hidden: Collection<String>): List<VodTitleEntity> =
        keys.distinct().chunked(MAX_IN_ARGS).flatMap { dao.titles(playlistId, kind, it, hidden.isNotEmpty(), hidden.toList()) }

    suspend fun itemsByTmdb(playlistId: Long, kind: String, tmdbIds: Collection<String>): List<VodTitleItemEntity> =
        tmdbIds.distinct().chunked(MAX_IN_ARGS).flatMap { dao.itemsByTmdb(playlistId, kind, it) }

    suspend fun titlesByName(playlistId: Long, kind: String, names: Collection<String>, hidden: Collection<String>): List<VodTitleEntity> =
        names.distinct().chunked(MAX_IN_ARGS).flatMap { dao.titlesByName(playlistId, kind, it, hidden.isNotEmpty(), hidden.toList()) }

    /** Keyset page in provider order; start with `afterOrdinal = -1`. */
    suspend fun titlesPage(playlistId: Long, kind: String, afterOrdinal: Int, limit: Int): List<VodTitleEntity> =
        dao.titlesPage(playlistId, kind, afterOrdinal, limit)

    /** Drops the playlist's whole title index. */
    suspend fun clearPlaylist(playlistId: Long) = db.withWriteTransaction { deleteAllForPlaylist(playlistId) }

    internal suspend fun deleteAllForPlaylist(playlistId: Long) {
        dao.deleteAllTitles(playlistId)
        dao.deleteAllItems(playlistId)
        dao.deleteState(playlistId)
    }

    private suspend fun beginGeneration(playlistId: Long): Long = db.withWriteTransaction {
        val state = dao.state(playlistId) ?: VodTitleStateEntity(playlistId)
        dao.deleteTitlesExcept(playlistId, state.activeGeneration)
        dao.deleteItemsExcept(playlistId, state.activeGeneration)
        val next = maxOf(state.lastGeneration, state.activeGeneration ?: 0, state.pendingGeneration ?: 0) + 1
        dao.upsertState(state.copy(pendingGeneration = next, lastGeneration = next))
        next
    }

    private suspend fun discard(playlistId: Long, generation: Long) = db.withWriteTransaction {
        val state = dao.state(playlistId)
        if (state?.activeGeneration == generation) return@withWriteTransaction
        deleteGeneration(playlistId, generation)
        if (state != null && state.pendingGeneration == generation) dao.upsertState(state.copy(pendingGeneration = null))
    }

    private suspend fun deleteGeneration(playlistId: Long, generation: Long) {
        dao.deleteTitleGeneration(playlistId, generation)
        dao.deleteItemGeneration(playlistId, generation)
    }

    private companion object {
        const val MAX_IN_ARGS: Int = 500
    }
}
