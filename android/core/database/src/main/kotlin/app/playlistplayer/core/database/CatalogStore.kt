package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.CatalogSyncStateEntity
import app.playlistplayer.core.database.entity.ChannelLiveInfo
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.SeasonEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import kotlinx.coroutines.flow.Flow

/**
 * Xtream catalog persistence (live stream metadata, VOD, series) with the same generation-based
 * atomic swap as [PlaylistStore], but under its own pointer in `catalog_sync_state`:
 *
 * 1. [beginGeneration] allocates a fresh catalog generation and removes leftovers.
 * 2. [CatalogWriter] inserts rows under that generation in batched transactions; readers keep
 *    seeing the active generation.
 * 3. [activateGeneration] flips the pointer in one short transaction, then deletes the old rows.
 * On failure [discardGeneration] deletes the partial generation; the previous catalog stays active.
 *
 * Also holds the per-playlist [AccountStatusEntity] and the on-demand series/VOD info caches.
 */
class CatalogStore(private val db: AppDatabase) {
    private val stateDao = db.catalogSyncStateDao()
    private val accountDao = db.accountStatusDao()
    private val catalogDao = db.catalogDao()
    private val cacheDao = db.catalogCacheDao()

    // ---- state ----

    suspend fun state(playlistId: Long): CatalogSyncStateEntity? = stateDao.get(playlistId)

    fun observeState(playlistId: Long): Flow<CatalogSyncStateEntity?> = stateDao.observe(playlistId)

    /** Applies [update] to the catalog state without touching generations. */
    suspend fun updateState(playlistId: Long, update: (CatalogSyncStateEntity) -> CatalogSyncStateEntity) = db.withWriteTransaction {
        val state = stateDao.get(playlistId) ?: CatalogSyncStateEntity(playlistId = playlistId)
        stateDao.upsert(
            update(state).copy(
                playlistId = playlistId,
                activeGeneration = state.activeGeneration,
                pendingGeneration = state.pendingGeneration,
                lastGeneration = state.lastGeneration,
            ),
        )
    }

    // ---- account status ----

    suspend fun accountStatus(playlistId: Long): AccountStatusEntity? = accountDao.get(playlistId)

    fun observeAccountStatus(playlistId: Long): Flow<AccountStatusEntity?> = accountDao.observe(playlistId)

    fun observeAccountStatuses(): Flow<List<AccountStatusEntity>> = accountDao.observeAll()

    suspend fun putAccountStatus(status: AccountStatusEntity) = accountDao.upsert(status)

    // ---- generation lifecycle ----

    suspend fun beginGeneration(playlistId: Long): Long = db.withWriteTransaction {
        val state = stateDao.get(playlistId) ?: CatalogSyncStateEntity(playlistId = playlistId)
        deleteAllExcept(playlistId, state.activeGeneration)
        val next = maxOf(state.lastGeneration, state.activeGeneration ?: 0, state.pendingGeneration ?: 0) + 1
        stateDao.upsert(state.copy(pendingGeneration = next, lastGeneration = next))
        next
    }

    fun writer(playlistId: Long, generation: Long, batchSize: Int = PlaylistStore.DEFAULT_BATCH_SIZE): CatalogWriter =
        CatalogWriter(db, playlistId, generation, batchSize)

    /**
     * Makes [generation] active and applies [update] to the state in one transaction, then deletes
     * the previous generation. Returns the previous generation.
     */
    suspend fun activateGeneration(
        playlistId: Long,
        generation: Long,
        update: (CatalogSyncStateEntity) -> CatalogSyncStateEntity,
    ): Long? {
        val previous = db.withWriteTransaction {
            val state = stateDao.get(playlistId) ?: CatalogSyncStateEntity(playlistId = playlistId)
            check(state.pendingGeneration == generation) { "Catalog generation $generation is not pending for playlist $playlistId" }
            stateDao.upsert(
                update(state).copy(
                    playlistId = playlistId,
                    activeGeneration = generation,
                    pendingGeneration = null,
                    lastGeneration = maxOf(state.lastGeneration, generation),
                ),
            )
            state.activeGeneration
        }
        if (previous != null && previous != generation) db.withWriteTransaction { deleteGeneration(playlistId, previous) }
        return previous
    }

    suspend fun discardGeneration(playlistId: Long, generation: Long) = db.withWriteTransaction {
        val state = stateDao.get(playlistId)
        if (state?.activeGeneration == generation) return@withWriteTransaction
        deleteGeneration(playlistId, generation)
        if (state != null && state.pendingGeneration == generation) stateDao.upsert(state.copy(pendingGeneration = null))
    }

    /** Generations with any catalog rows (diagnostics/tests). */
    suspend fun generationsPresent(playlistId: Long): List<Long> = catalogDao.generationsPresent(playlistId)

    /**
     * Removes the playlist's whole catalog, caches, account status and catalog state (Xtream
     * processing turned off). Live channels are untouched.
     */
    suspend fun clearPlaylist(playlistId: Long) = db.withWriteTransaction { deleteAllForPlaylist(playlistId) }

    internal suspend fun deleteAllForPlaylist(playlistId: Long) {
        catalogDao.deleteAllLiveInfo(playlistId)
        catalogDao.deleteAllVodCategories(playlistId)
        catalogDao.deleteAllVodStreams(playlistId)
        catalogDao.deleteAllSeriesCategories(playlistId)
        catalogDao.deleteAllSeries(playlistId)
        cacheDao.deleteAllSeriesInfo(playlistId)
        cacheDao.deleteAllSeasons(playlistId)
        cacheDao.deleteAllEpisodes(playlistId)
        cacheDao.deleteAllVodInfo(playlistId)
        accountDao.delete(playlistId)
        stateDao.delete(playlistId)
    }

    private suspend fun deleteGeneration(playlistId: Long, generation: Long) {
        catalogDao.deleteLiveInfoGeneration(playlistId, generation)
        catalogDao.deleteVodCategoryGeneration(playlistId, generation)
        catalogDao.deleteVodStreamGeneration(playlistId, generation)
        catalogDao.deleteSeriesCategoryGeneration(playlistId, generation)
        catalogDao.deleteSeriesGeneration(playlistId, generation)
    }

    private suspend fun deleteAllExcept(playlistId: Long, keep: Long?) {
        catalogDao.deleteLiveInfoExcept(playlistId, keep)
        catalogDao.deleteVodCategoriesExcept(playlistId, keep)
        catalogDao.deleteVodStreamsExcept(playlistId, keep)
        catalogDao.deleteSeriesCategoriesExcept(playlistId, keep)
        catalogDao.deleteSeriesExcept(playlistId, keep)
    }

    // ---- reads: live ----

    /** Stream ids of the playlist's active channels (join keys for `get_live_streams`). */
    suspend fun playlistStreamIds(playlistId: Long): List<Long> = catalogDao.playlistStreamIds(playlistId)

    suspend fun liveInfo(playlistId: Long, streamId: Long): LiveStreamInfoEntity? = catalogDao.liveInfo(playlistId, streamId)

    /** Active catalog streams with `tv_archive = 1` (for EPG backfill and catch-up). */
    suspend fun archiveStreams(playlistId: Long): List<LiveStreamInfoEntity> = catalogDao.archiveStreams(playlistId)

    /** Every active channel with its live metadata (null columns when absent from the API). */
    suspend fun channelsWithLiveInfo(playlistId: Long): List<ChannelLiveInfo> = catalogDao.channelsWithLiveInfo(playlistId)

    suspend fun channelWithLiveInfo(playlistId: Long, ordinal: Int): ChannelLiveInfo? = catalogDao.channelWithLiveInfo(playlistId, ordinal)

    // ---- reads: VOD ----

    suspend fun vodCategories(playlistId: Long): List<VodCategoryEntity> = catalogDao.vodCategories(playlistId)

    fun observeVodCategories(playlistId: Long): Flow<List<VodCategoryEntity>> = catalogDao.observeVodCategories(playlistId)

    suspend fun vodStreamsInCategory(playlistId: Long, categoryId: String, limit: Int = 200, offset: Int = 0): List<VodStreamEntity> =
        catalogDao.vodStreamsInCategory(playlistId, categoryId, limit, offset)

    suspend fun vodStream(playlistId: Long, streamId: Long): VodStreamEntity? = catalogDao.vodStream(playlistId, streamId)

    suspend fun vodStreamsByTmdb(playlistId: Long, tmdb: String): List<VodStreamEntity> = catalogDao.vodStreamsByTmdb(playlistId, tmdb)

    /** Keyset page in provider order; start with `afterOrder = -1`. */
    suspend fun vodStreamsPage(playlistId: Long, afterOrder: Int, limit: Int): List<VodStreamEntity> =
        catalogDao.vodStreamsPage(playlistId, afterOrder, limit)

    suspend fun vodCount(playlistId: Long): Int = catalogDao.countVodStreams(playlistId)

    // ---- reads: series ----

    suspend fun seriesCategories(playlistId: Long): List<SeriesCategoryEntity> = catalogDao.seriesCategories(playlistId)

    fun observeSeriesCategories(playlistId: Long): Flow<List<SeriesCategoryEntity>> = catalogDao.observeSeriesCategories(playlistId)

    suspend fun seriesInCategory(playlistId: Long, categoryId: String, limit: Int = 200, offset: Int = 0): List<SeriesEntity> =
        catalogDao.seriesInCategory(playlistId, categoryId, limit, offset)

    suspend fun series(playlistId: Long, seriesId: Long): SeriesEntity? = catalogDao.series(playlistId, seriesId)

    /** Keyset page in provider order; start with `afterOrder = -1`. */
    suspend fun seriesPage(playlistId: Long, afterOrder: Int, limit: Int): List<SeriesEntity> =
        catalogDao.seriesPage(playlistId, afterOrder, limit)

    suspend fun seriesCount(playlistId: Long): Int = catalogDao.countSeries(playlistId)

    // ---- on-demand caches ----

    suspend fun seriesInfo(playlistId: Long, seriesId: Long): SeriesInfoCacheEntity? = cacheDao.seriesInfo(playlistId, seriesId)

    suspend fun seasons(playlistId: Long, seriesId: Long): List<SeasonEntity> = cacheDao.seasons(playlistId, seriesId)

    suspend fun episodes(playlistId: Long, seriesId: Long): List<EpisodeEntity> = cacheDao.episodes(playlistId, seriesId)

    fun observeEpisodes(playlistId: Long, seriesId: Long): Flow<List<EpisodeEntity>> = cacheDao.observeEpisodes(playlistId, seriesId)

    /** Replaces the cached info, seasons and episodes of one series in one transaction. */
    suspend fun putSeriesInfo(info: SeriesInfoCacheEntity, seasons: List<SeasonEntity>, episodes: List<EpisodeEntity>) =
        db.withWriteTransaction {
            cacheDao.deleteSeasons(info.playlistId, info.seriesId)
            cacheDao.deleteEpisodes(info.playlistId, info.seriesId)
            cacheDao.upsertSeriesInfo(info)
            if (seasons.isNotEmpty()) cacheDao.insertSeasons(seasons)
            if (episodes.isNotEmpty()) cacheDao.insertEpisodes(episodes)
        }

    suspend fun vodInfo(playlistId: Long, streamId: Long): VodInfoCacheEntity? = cacheDao.vodInfo(playlistId, streamId)

    suspend fun putVodInfo(info: VodInfoCacheEntity) = cacheDao.upsertVodInfo(info)
}

/**
 * Inserts catalog rows for one pending generation. Each call is one batched transaction; rows
 * must belong to the writer's playlist and generation.
 */
class CatalogWriter internal constructor(
    private val db: AppDatabase,
    val playlistId: Long,
    val generation: Long,
    private val batchSize: Int,
) {
    private val dao = db.catalogDao()

    suspend fun insertLiveInfo(rows: List<LiveStreamInfoEntity>) {
        require(rows.all { it.playlistId == playlistId && it.generation == generation }) { "Row belongs to another generation" }
        rows.chunked(batchSize).forEach { dao.insertLiveInfo(it) }
    }

    suspend fun insertVodCategories(rows: List<VodCategoryEntity>) {
        require(rows.all { it.playlistId == playlistId && it.generation == generation }) { "Row belongs to another generation" }
        rows.chunked(batchSize).forEach { dao.insertVodCategories(it) }
    }

    suspend fun insertVodStreams(rows: List<VodStreamEntity>) {
        require(rows.all { it.playlistId == playlistId && it.generation == generation }) { "Row belongs to another generation" }
        rows.chunked(batchSize).forEach { dao.insertVodStreams(it) }
    }

    suspend fun insertSeriesCategories(rows: List<SeriesCategoryEntity>) {
        require(rows.all { it.playlistId == playlistId && it.generation == generation }) { "Row belongs to another generation" }
        rows.chunked(batchSize).forEach { dao.insertSeriesCategories(it) }
    }

    suspend fun insertSeries(rows: List<SeriesEntity>) {
        require(rows.all { it.playlistId == playlistId && it.generation == generation }) { "Row belongs to another generation" }
        rows.chunked(batchSize).forEach { dao.insertSeries(it) }
    }
}
