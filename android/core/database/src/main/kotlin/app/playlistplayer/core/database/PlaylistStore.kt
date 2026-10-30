package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.PlaylistEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import app.playlistplayer.core.model.Channel
import app.playlistplayer.core.model.ChannelGroup
import app.playlistplayer.core.model.Playlist
import app.playlistplayer.core.model.PlaylistSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Playlist-scoped persistence with generation-based atomic swaps.
 *
 * Writing a refresh:
 * 1. [beginGeneration] allocates a fresh generation id (and removes leftovers of any earlier
 *    abandoned write).
 * 2. [GenerationWriter] inserts groups/channels under that generation in batched transactions;
 *    readers do not see them because DAO reads join on `sync_state.activeGeneration`.
 * 3. [activateGeneration] flips `activeGeneration` in one short transaction.
 * 4. The previous generation's rows are deleted.
 * On failure, [discardGeneration] deletes the incomplete generation; the previous one stays
 * active and queryable throughout.
 */
class PlaylistStore(private val db: AppDatabase) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val syncStateDao = db.syncStateDao()
    private val catalogStore by lazy { CatalogStore(db) }
    private val epgStore by lazy { EpgStore(db) }

    // ---- Playlist registry ----

    suspend fun createPlaylist(
        name: String,
        sourceType: PlaylistSourceType,
        xtreamEnabled: Boolean,
        createdAt: Long,
    ): Long = db.withWriteTransaction {
        val id = playlistDao.insert(
            PlaylistEntity(name = name.ifBlank { Playlist.DEFAULT_NAME }, sourceType = sourceType.name, xtreamEnabled = xtreamEnabled, createdAt = createdAt),
        )
        syncStateDao.upsert(SyncStateEntity(playlistId = id))
        id
    }

    suspend fun playlist(id: Long): Playlist? = playlistDao.get(id)?.toModel()

    suspend fun playlists(): List<Playlist> = playlistDao.getAll().map { it.toModel() }

    fun observePlaylists(): Flow<List<Playlist>> = playlistDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name)

    suspend fun setXtreamEnabled(id: Long, enabled: Boolean) = playlistDao.setXtreamEnabled(id, enabled)

    /** Scoped delete of every row belonging to [id]. */
    suspend fun deletePlaylist(id: Long) = db.withWriteTransaction {
        catalogStore.deleteAllForPlaylist(id)
        epgStore.deleteAllForPlaylist(id)
        channelDao.deleteAllChannels(id)
        channelDao.deleteAllGroups(id)
        syncStateDao.delete(id)
        playlistDao.delete(id)
    }

    // ---- Reads (active generation only) ----

    suspend fun groups(playlistId: Long): List<ChannelGroup> = channelDao.groups(playlistId).map { it.toModel() }

    fun observeGroups(playlistId: Long): Flow<List<ChannelGroup>> =
        channelDao.observeGroups(playlistId).map { list -> list.map { it.toModel() } }

    suspend fun channels(playlistId: Long): List<Channel> = channelDao.channels(playlistId).map { it.toModel() }

    fun observeChannelsInGroup(playlistId: Long, groupOrdinal: Int): Flow<List<Channel>> =
        channelDao.observeChannelsInGroup(playlistId, groupOrdinal).map { list -> list.map { it.toModel() } }

    suspend fun activeChannelCount(playlistId: Long): Int = channelDao.countActiveChannels(playlistId)

    suspend fun syncState(playlistId: Long): SyncStateEntity? = syncStateDao.get(playlistId)

    fun observeSyncState(playlistId: Long): Flow<SyncStateEntity?> = syncStateDao.observe(playlistId)

    suspend fun clearCatalogStale(playlistId: Long) = syncStateDao.clearCatalogStale(playlistId)

    suspend fun clearEpgStale(playlistId: Long) = syncStateDao.clearEpgStale(playlistId)

    // ---- Generation lifecycle ----

    /** Allocates a new pending generation for [playlistId], cleaning up any abandoned one. */
    suspend fun beginGeneration(playlistId: Long): Long = db.withWriteTransaction {
        val state = syncStateDao.get(playlistId) ?: SyncStateEntity(playlistId = playlistId)
        // Anything other than the active generation is a leftover (crash mid-write or before cleanup).
        channelDao.deleteChannelsExcept(playlistId, state.activeGeneration)
        channelDao.deleteGroupsExcept(playlistId, state.activeGeneration)
        val next = maxOf(state.lastGeneration, state.activeGeneration ?: 0, state.pendingGeneration ?: 0) + 1
        syncStateDao.upsert(state.copy(pendingGeneration = next, lastGeneration = next))
        next
    }

    fun writer(playlistId: Long, generation: Long, batchSize: Int = DEFAULT_BATCH_SIZE): GenerationWriter =
        GenerationWriter(channelDao, playlistId, generation, batchSize)

    /**
     * Makes [generation] the active one and applies [update] to the sync state, in one short
     * transaction; then deletes the previous generation's rows. Returns the previous generation.
     */
    suspend fun activateGeneration(
        playlistId: Long,
        generation: Long,
        update: (SyncStateEntity) -> SyncStateEntity,
    ): Long? {
        val previous = db.withWriteTransaction {
            val state = syncStateDao.get(playlistId) ?: SyncStateEntity(playlistId = playlistId)
            check(state.pendingGeneration == generation) { "Generation $generation is not pending for playlist $playlistId" }
            syncStateDao.upsert(
                update(state).copy(
                    playlistId = playlistId,
                    activeGeneration = generation,
                    pendingGeneration = null,
                    lastGeneration = maxOf(state.lastGeneration, generation),
                ),
            )
            state.activeGeneration
        }
        if (previous != null && previous != generation) deleteGenerationRows(playlistId, previous)
        return previous
    }

    /** Deletes an incomplete generation; the active generation is untouched. */
    suspend fun discardGeneration(playlistId: Long, generation: Long) = db.withWriteTransaction {
        val state = syncStateDao.get(playlistId)
        if (state?.activeGeneration == generation) return@withWriteTransaction
        channelDao.deleteChannelGeneration(playlistId, generation)
        channelDao.deleteGroupGeneration(playlistId, generation)
        if (state != null && state.pendingGeneration == generation) {
            syncStateDao.upsert(state.copy(pendingGeneration = null))
        }
    }

    /** Applies [update] to the sync state without touching generations. */
    suspend fun updateSyncState(playlistId: Long, update: (SyncStateEntity) -> SyncStateEntity) = db.withWriteTransaction {
        val state = syncStateDao.get(playlistId) ?: SyncStateEntity(playlistId = playlistId)
        val updated = update(state)
        syncStateDao.upsert(
            updated.copy(
                playlistId = playlistId,
                activeGeneration = state.activeGeneration,
                pendingGeneration = state.pendingGeneration,
                lastGeneration = state.lastGeneration,
            ),
        )
    }

    /** Distinct generations that currently have channel rows (diagnostics/tests). */
    suspend fun generationsPresent(playlistId: Long): List<Long> = channelDao.generationsPresent(playlistId)

    private suspend fun deleteGenerationRows(playlistId: Long, generation: Long) = db.withWriteTransaction {
        channelDao.deleteChannelGeneration(playlistId, generation)
        channelDao.deleteGroupGeneration(playlistId, generation)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 500
    }
}

/**
 * Buffers channel rows for one pending generation and inserts them in batched transactions.
 * Call [flush] at the end, then [writeGroups].
 */
class GenerationWriter internal constructor(
    private val channelDao: app.playlistplayer.core.database.dao.ChannelDao,
    val playlistId: Long,
    val generation: Long,
    private val batchSize: Int,
) {
    private val buffer = ArrayList<ChannelEntity>(batchSize)

    var channelsWritten: Int = 0
        private set

    suspend fun add(channel: ChannelEntity) {
        require(channel.playlistId == playlistId && channel.generation == generation) { "Row belongs to another generation" }
        buffer.add(channel)
        if (buffer.size >= batchSize) flush()
    }

    suspend fun flush() {
        if (buffer.isEmpty()) return
        channelDao.insertChannels(ArrayList(buffer))
        channelsWritten += buffer.size
        buffer.clear()
    }

    suspend fun writeGroups(groups: List<ChannelGroupEntity>) {
        flush()
        if (groups.isNotEmpty()) channelDao.insertGroups(groups)
    }
}
