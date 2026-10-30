package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.ChannelEpgKey
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.ChannelProgramme
import app.playlistplayer.core.database.entity.EpgSourceStateEntity
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import kotlinx.coroutines.flow.Flow

/**
 * EPG persistence: programmes, the entry → guide channel mapping and per-playlist EPG state.
 *
 * **Import lifecycle** (programmes are not generational):
 * 1. [beginImport] removes rows left pending by an earlier interrupted import and allocates an
 *    import id.
 * 2. [stage] inserts batches as `pending = 1` rows (one short transaction per batch); every read
 *    ignores pending rows.
 * 3. [commitImport] applies the replacement rule in one transaction: committed XMLTV rows starting
 *    at or after `importStart` are replaced by the import; committed past rows are kept (staged
 *    past rows that overlap them are dropped, so re-imports never duplicate the past); archive
 *    backfill rows overlapping a new XMLTV slot are dropped; the mapping is replaced; rows that
 *    ended before `pruneBefore` are deleted.
 * 4. On any failure, [discardImport] deletes this import's staged rows; the previous guide is
 *    untouched.
 *
 * Archive backfill rows ([insertArchive]) are committed directly, never over an existing slot.
 */
class EpgStore(private val db: AppDatabase) {
    private val stateDao = db.epgSourceStateDao()
    private val mapDao = db.channelEpgMapDao()
    private val programmeDao = db.programmeDao()

    // ---- state ----

    suspend fun state(playlistId: Long): EpgSourceStateEntity? = stateDao.get(playlistId)

    fun observeState(playlistId: Long): Flow<EpgSourceStateEntity?> = stateDao.observe(playlistId)

    /** Applies [update] to the state; import id bookkeeping is preserved. */
    suspend fun updateState(playlistId: Long, update: (EpgSourceStateEntity) -> EpgSourceStateEntity) = db.withWriteTransaction {
        val state = stateDao.get(playlistId) ?: EpgSourceStateEntity(playlistId = playlistId)
        stateDao.upsert(
            update(state).copy(playlistId = playlistId, lastImportId = state.lastImportId, pendingImportId = state.pendingImportId),
        )
    }

    // ---- import lifecycle ----

    suspend fun beginImport(playlistId: Long): Long = db.withWriteTransaction {
        val state = stateDao.get(playlistId) ?: EpgSourceStateEntity(playlistId = playlistId)
        programmeDao.deletePending(playlistId)
        // Import ids are global row tags, so make them unique across playlists.
        val next = maxOf(state.lastImportId, state.pendingImportId ?: 0) + 1
        val importId = playlistId * IMPORT_ID_STRIDE + next
        stateDao.upsert(state.copy(lastImportId = next, pendingImportId = next))
        importId
    }

    /** Inserts one batch of staged rows for [importId]. Rows are forced to pending. */
    suspend fun stage(importId: Long, rows: List<ProgrammeEntity>) {
        if (rows.isEmpty()) return
        val staged = rows.map { it.copy(id = 0, importId = importId, pending = true) }
        db.withWriteTransaction { programmeDao.insert(staged) }
    }

    suspend fun pendingCount(playlistId: Long): Int = programmeDao.countPending(playlistId)

    data class CommitStats(val published: Int, val pruned: Int, val programmeCount: Int)

    /**
     * Publishes [importId] (source [ProgrammeSource.XMLTV]) using the replacement rule, replaces the
     * playlist's mapping with [mappings] (keeping each entry's `lastBackfillAt`) and applies
     * [update] to the state, all in one transaction.
     */
    suspend fun commitImport(
        playlistId: Long,
        importId: Long,
        importStart: Long,
        pruneBefore: Long,
        mappings: List<ChannelEpgMapEntity>,
        update: (EpgSourceStateEntity, CommitStats, coverageFrom: Long?, coverageTo: Long?) -> EpgSourceStateEntity,
    ): CommitStats = db.withWriteTransaction {
        val xmltv = ProgrammeSource.XMLTV.name
        programmeDao.deleteCommittedFrom(playlistId, xmltv, importStart)
        programmeDao.dropStagedPastOverlaps(importId, importStart, MAX_PROGRAMME_DURATION_MILLIS)
        programmeDao.dropArchiveOverlapping(playlistId, importId, MAX_PROGRAMME_DURATION_MILLIS)
        val published = programmeDao.publish(importId)
        val pruned = programmeDao.deleteEndedBefore(playlistId, pruneBefore)

        val backfilled = mapDao.all(playlistId).associate { it.entryKey to it.lastBackfillAt }
        mapDao.deleteAll(playlistId)
        if (mappings.isNotEmpty()) {
            mappings.chunked(PlaylistStore.DEFAULT_BATCH_SIZE).forEach { chunk ->
                mapDao.insert(
                    chunk.map { m ->
                        require(m.playlistId == playlistId) { "Mapping belongs to another playlist" }
                        m.copy(lastBackfillAt = m.lastBackfillAt ?: backfilled[m.entryKey])
                    },
                )
            }
        }

        val stats = CommitStats(published, pruned, programmeDao.count(playlistId))
        val state = stateDao.get(playlistId) ?: EpgSourceStateEntity(playlistId = playlistId)
        val updated = update(state, stats, programmeDao.xmltvCoverageFrom(playlistId), programmeDao.xmltvCoverageTo(playlistId))
        stateDao.upsert(
            updated.copy(
                playlistId = playlistId,
                lastImportId = state.lastImportId,
                pendingImportId = null,
                programmeCount = stats.programmeCount,
            ),
        )
        stats
    }

    /** Deletes the staged rows of [importId]; committed rows are untouched. */
    suspend fun discardImport(playlistId: Long, importId: Long) = db.withWriteTransaction {
        programmeDao.deleteImport(importId)
        val state = stateDao.get(playlistId)
        if (state?.pendingImportId != null && playlistId * IMPORT_ID_STRIDE + state.pendingImportId == importId) {
            stateDao.upsert(state.copy(pendingImportId = null))
        }
    }

    // ---- archive backfill ----

    /**
     * Inserts committed [ProgrammeSource.XTREAM_ARCHIVE] rows for [channelKey], skipping any row
     * that overlaps an existing programme of that channel (XMLTV or an earlier backfill), in one
     * transaction. Returns the number inserted.
     */
    suspend fun insertArchive(playlistId: Long, channelKey: String, rows: List<ProgrammeEntity>): Int {
        if (rows.isEmpty()) return 0
        return db.withWriteTransaction {
            val from = rows.minOf { it.startMillis }
            val to = rows.maxOf { it.stopMillis }
            val existing = programmeDao.forChannels(playlistId, listOf(channelKey), from, to, MAX_PROGRAMME_DURATION_MILLIS)
            val accepted = ArrayList<ProgrammeEntity>(rows.size)
            for (row in rows.sortedBy { it.startMillis }) {
                val overlaps = existing.any { it.startMillis < row.stopMillis && it.stopMillis > row.startMillis } ||
                    accepted.any { it.startMillis < row.stopMillis && it.stopMillis > row.startMillis }
                if (!overlaps) {
                    accepted += row.copy(
                        id = 0,
                        playlistId = playlistId,
                        channelKey = channelKey,
                        source = ProgrammeSource.XTREAM_ARCHIVE.name,
                        importId = 0,
                        pending = false,
                    )
                }
            }
            if (accepted.isNotEmpty()) programmeDao.insert(accepted)
            accepted.size
        }
    }

    suspend fun earliestStart(playlistId: Long, channelKey: String): Long? = programmeDao.earliestStart(playlistId, channelKey)

    suspend fun markBackfilled(playlistId: Long, entryKey: String, at: Long) = mapDao.markBackfilled(playlistId, entryKey, at)

    /** Adds mapping rows (e.g. archive-only channels keyed by stream id) without replacing others. */
    suspend fun putMappings(rows: List<ChannelEpgMapEntity>) {
        if (rows.isNotEmpty()) mapDao.insert(rows)
    }

    // ---- mapping reads ----

    suspend fun mappings(playlistId: Long): List<ChannelEpgMapEntity> = mapDao.all(playlistId)

    suspend fun mapping(playlistId: Long, entryKey: String): ChannelEpgMapEntity? = mapDao.get(playlistId, entryKey)

    /** Guide key of the active channel at [ordinal], or null when it has no guide. */
    suspend fun channelKey(playlistId: Long, ordinal: Int): String? = mapDao.channelKey(playlistId, ordinal)

    /** Guide keys of every mapped active channel, in playlist order. */
    suspend fun channelKeys(playlistId: Long): List<ChannelEpgKey> = mapDao.channelKeys(playlistId)

    suspend fun channelKeysInGroup(playlistId: Long, groupOrdinal: Int): List<ChannelEpgKey> =
        mapDao.channelKeysInGroup(playlistId, groupOrdinal)

    // ---- programme reads ----

    suspend fun programme(id: Long): ProgrammeEntity? = programmeDao.get(id)

    /** Programmes overlapping `[fromMillis, toMillis)` for [channelKeys], ordered by key then start. */
    suspend fun programmes(playlistId: Long, channelKeys: Collection<String>, fromMillis: Long, toMillis: Long): List<ProgrammeEntity> {
        if (channelKeys.isEmpty()) return emptyList()
        return channelKeys.distinct().chunked(MAX_KEYS_PER_QUERY).flatMap {
            programmeDao.forChannels(playlistId, it, fromMillis, toMillis, MAX_PROGRAMME_DURATION_MILLIS)
        }
    }

    /**
     * Guide grid for one group: programmes overlapping `[fromMillis, toMillis)` for every channel
     * of the group that has a guide, tagged with the channel ordinal (entries sharing a guide each
     * get the rows). Channels absent from the result have "No guide data".
     */
    suspend fun gridForGroup(playlistId: Long, groupOrdinal: Int, fromMillis: Long, toMillis: Long): List<ChannelProgramme> =
        programmeDao.forGroup(playlistId, groupOrdinal, fromMillis, toMillis, MAX_PROGRAMME_DURATION_MILLIS)

    /**
     * The programme airing at [nowMillis] (if any) and the following one, from Room only. Provider
     * feeds contain overlapping entries (regional variants), so "next" is the first programme that
     * starts once the current one has ended.
     */
    suspend fun nowNext(playlistId: Long, channelKey: String, nowMillis: Long): Pair<ProgrammeEntity?, ProgrammeEntity?> =
        pickNowNext(programmeDao.upcoming(playlistId, channelKey, nowMillis, MAX_PROGRAMME_DURATION_MILLIS, NOW_NEXT_LOOKAHEAD), nowMillis)

    /** Ended programmes of [channelKey] that started at or after [fromMillis], newest first. */
    suspend fun pastProgrammes(playlistId: Long, channelKey: String, fromMillis: Long, nowMillis: Long): List<ProgrammeEntity> =
        programmeDao.past(playlistId, channelKey, fromMillis, nowMillis)

    suspend fun programmeCount(playlistId: Long): Int = programmeDao.count(playlistId)

    // ---- scoped delete ----

    /** Removes every EPG row of [playlistId] (playlist removed). */
    suspend fun clearPlaylist(playlistId: Long) = db.withWriteTransaction { deleteAllForPlaylist(playlistId) }

    internal suspend fun deleteAllForPlaylist(playlistId: Long) {
        programmeDao.deleteAll(playlistId)
        mapDao.deleteAll(playlistId)
        stateDao.delete(playlistId)
    }

    companion object {
        /** Upper bound on one programme's length, used to keep range reads on the index. */
        const val MAX_PROGRAMME_DURATION_MILLIS: Long = 24L * 60 * 60 * 1000 + 1

        /** Import ids are `playlistId * stride + n`. */
        const val IMPORT_ID_STRIDE: Long = 1_000_000_000L

        private const val MAX_KEYS_PER_QUERY = 900
        private const val NOW_NEXT_LOOKAHEAD = 12

        /**
         * Now/next from programmes that have not ended at [nowMillis], ordered by start: "now" is the
         * earliest-starting airing one, "next" the first that starts at or after its end (or the first
         * upcoming one when nothing airs).
         */
        fun <T> pickNowNext(rows: List<T>, nowMillis: Long, start: (T) -> Long, stop: (T) -> Long): Pair<T?, T?> {
            val sorted = rows.filter { stop(it) > nowMillis }.sortedBy(start)
            val current = sorted.firstOrNull { start(it) <= nowMillis }
            val next = if (current == null) {
                sorted.firstOrNull()
            } else {
                sorted.firstOrNull { start(it) >= stop(current) } ?: sorted.firstOrNull { it !== current && start(it) > start(current) }
            }
            return current to next
        }

        fun pickNowNext(rows: List<ProgrammeEntity>, nowMillis: Long): Pair<ProgrammeEntity?, ProgrammeEntity?> =
            pickNowNext(rows, nowMillis, { it.startMillis }, { it.stopMillis })
    }
}
