package app.playlistplayer.core.database.search

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.PlaylistStore
import kotlinx.coroutines.flow.Flow

/**
 * The FTS5 trigram search index (see `SearchEntities.kt`).
 *
 * - **Writes** replace one playlist's live or VOD rows in a single transaction
 *   ([replaceLive]/[replaceVod]) and record the source generation in `search_index_state`, so
 *   readers see either the old or the new index, never a mix. The index builder lives in the app
 *   (`SearchIndexer`), because VOD rows need the app's merged-title grouping.
 * - **Reads** take an already normalised query (`TitleTokenizer.normalize`). Three or more
 *   characters use the trigram index (substring match). Shorter queries cannot use trigrams, so
 *   they fall back to a name/word prefix LIKE over the content table.
 * - [observeSources] emits whenever a playlist or catalog generation flips or an override
 *   changes; the indexer uses it to rebuild without hooks in the syncers.
 */
class SearchStore(private val db: AppDatabase) {
    private val dao = db.searchDao()

    suspend fun state(playlistId: Long): SearchIndexStateEntity? = dao.state(playlistId)

    /** Replaces the playlist's live rows; [generation] is the playlist generation they came from. */
    suspend fun replaceLive(playlistId: Long, generation: Long?, rows: List<SearchLiveEntity>, now: Long) = db.withWriteTransaction {
        dao.deleteLive(playlistId)
        rows.chunked(PlaylistStore.DEFAULT_BATCH_SIZE).forEach { chunk ->
            dao.insertLive(chunk.map { require(it.playlistId == playlistId) { "Row belongs to another playlist" }; it.copy(id = 0) })
        }
        val state = dao.state(playlistId)
        dao.upsertState(
            SearchIndexStateEntity(
                playlistId = playlistId,
                liveGeneration = generation,
                catalogGeneration = state?.catalogGeneration,
                overridesStamp = state?.overridesStamp,
                vodIndexed = state?.vodIndexed ?: false,
                builtAt = now,
            ),
        )
    }

    /**
     * Replaces the playlist's VOD rows; [catalogGeneration] and [overridesStamp] describe what
     * they were grouped from.
     */
    suspend fun replaceVod(
        playlistId: Long,
        catalogGeneration: Long?,
        overridesStamp: String?,
        rows: List<SearchVodEntity>,
        now: Long,
    ) = db.withWriteTransaction {
        dao.deleteVod(playlistId)
        rows.chunked(PlaylistStore.DEFAULT_BATCH_SIZE).forEach { chunk ->
            dao.insertVod(chunk.map { require(it.playlistId == playlistId) { "Row belongs to another playlist" }; it.copy(id = 0) })
        }
        val state = dao.state(playlistId)
        dao.upsertState(
            SearchIndexStateEntity(
                playlistId = playlistId,
                liveGeneration = state?.liveGeneration,
                catalogGeneration = catalogGeneration,
                overridesStamp = overridesStamp,
                vodIndexed = true,
                builtAt = now,
            ),
        )
    }

    suspend fun liveCount(playlistId: Long): Int = dao.countLive(playlistId)

    suspend fun vodCount(playlistId: Long): Int = dao.countVod(playlistId)

    /** Live entries matching [normalizedQuery], best first, across playlists. */
    suspend fun searchLive(normalizedQuery: String, limit: Int, offset: Int = 0): List<SearchLiveEntity> {
        val q = normalizedQuery.trim()
        if (q.isEmpty()) return emptyList()
        return if (usesTrigrams(q)) {
            dao.matchLive(matchExpression(q), q, "$q%", "% $q%", limit, offset)
        } else {
            dao.prefixLive(q, "$q%", "% $q%", limit, offset)
        }
    }

    /** Merged VOD titles matching [normalizedQuery], best first, across playlists and kinds. */
    suspend fun searchVod(normalizedQuery: String, limit: Int, offset: Int = 0): List<SearchVodEntity> {
        val q = normalizedQuery.trim()
        if (q.isEmpty()) return emptyList()
        return if (usesTrigrams(q)) {
            dao.matchVod(matchExpression(q), q, "$q%", "% $q%", limit, offset)
        } else {
            dao.prefixVod(q, "$q%", "% $q%", limit, offset)
        }
    }

    /** Index-side match count for one playlist (tests: proves the FTS index follows its content). */
    suspend fun liveIndexMatches(normalizedQuery: String, playlistId: Long): Int =
        dao.countLiveMatches(matchExpression(normalizedQuery.trim()), playlistId)

    /** Indexed titles by merged title key (display data for favourites / continue watching). */
    suspend fun vodTitles(titleKeys: Collection<String>): List<SearchVodEntity> =
        if (titleKeys.isEmpty()) emptyList() else titleKeys.distinct().chunked(MAX_IN_ARGS).flatMap { dao.vodTitles(it) }

    /**
     * Emits (initially, and) whenever a playlist generation, a catalog generation or a title
     * override changes. Sync bookkeeping writes also emit; consumers compare generations.
     */
    fun observeSources(): Flow<Set<String>> =
        db.invalidationTracker.createFlow("sync_state", "catalog_sync_state", "title_override", "playlist")

    /** Emits (initially, and) whenever any playlist's VOD index rows are replaced. */
    fun observeVodIndex(): Flow<Set<String>> = db.invalidationTracker.createFlow("search_vod_content")

    /** Drops the playlist's whole index (Xtream turned off clears VOD via [replaceVod] instead). */
    suspend fun clearPlaylist(playlistId: Long) = db.withWriteTransaction { deleteAllForPlaylist(playlistId) }

    internal suspend fun deleteAllForPlaylist(playlistId: Long) {
        dao.deleteLive(playlistId)
        dao.deleteVod(playlistId)
        dao.deleteState(playlistId)
    }

    companion object {
        /** Trigram queries need at least three characters. */
        const val MIN_TRIGRAM_LENGTH: Int = 3

        private const val MAX_IN_ARGS: Int = 500

        fun usesTrigrams(normalizedQuery: String): Boolean = normalizedQuery.length >= MIN_TRIGRAM_LENGTH

        /**
         * One FTS5 phrase: the whole normalised query as a substring. Normalised text holds only
         * letters, digits and single spaces, so it needs no escaping inside the quotes.
         */
        fun matchExpression(normalizedQuery: String): String = "\"" + normalizedQuery.replace("\"", "") + "\""
    }
}
