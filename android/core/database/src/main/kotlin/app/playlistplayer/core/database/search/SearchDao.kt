package app.playlistplayer.core.database.search

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * Search index access. `MATCH` arguments are FTS5 phrase strings built by [SearchStore]
 * (`"<normalised query>"`); LIKE arguments are built from normalised text (letters, digits and
 * spaces only), so they never contain wildcards of their own.
 *
 * Ordering: exact name, then name prefix, then word prefix, then shorter names first; ties by
 * playlist and provider order.
 */
@Dao
interface SearchDao {
    @Insert
    suspend fun insertLive(rows: List<SearchLiveEntity>)

    @Insert
    suspend fun insertVod(rows: List<SearchVodEntity>)

    @Query("DELETE FROM search_live_content WHERE playlistId = :playlistId")
    suspend fun deleteLive(playlistId: Long)

    @Query("DELETE FROM search_vod_content WHERE playlistId = :playlistId")
    suspend fun deleteVod(playlistId: Long)

    @Query("SELECT COUNT(*) FROM search_live_content WHERE playlistId = :playlistId")
    suspend fun countLive(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM search_vod_content WHERE playlistId = :playlistId")
    suspend fun countVod(playlistId: Long): Int

    /** FTS rows (index side) for [playlistId]; diagnostics/tests (proves the triggers ran). */
    @Query(
        """SELECT COUNT(*) FROM search_live f JOIN search_live_content c ON c.id = f.rowid
           WHERE search_live MATCH :match AND c.playlistId = :playlistId""",
    )
    suspend fun countLiveMatches(match: String, playlistId: Long): Int

    // ---- live ----

    @Query(
        """SELECT c.* FROM search_live_content c
           JOIN search_live f ON f.rowid = c.id
           WHERE search_live MATCH :match
           ORDER BY (c.normalizedName = :exact) DESC, (c.normalizedName LIKE :prefix) DESC,
                    (c.normalizedName LIKE :wordPrefix) DESC, length(c.normalizedName), c.playlistId, c.ordinal
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun matchLive(match: String, exact: String, prefix: String, wordPrefix: String, limit: Int, offset: Int): List<SearchLiveEntity>

    @Query(
        """SELECT * FROM search_live_content
           WHERE normalizedName LIKE :prefix OR normalizedName LIKE :wordPrefix
           ORDER BY (normalizedName = :exact) DESC, (normalizedName LIKE :prefix) DESC, length(normalizedName), playlistId, ordinal
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun prefixLive(exact: String, prefix: String, wordPrefix: String, limit: Int, offset: Int): List<SearchLiveEntity>

    // ---- VOD ----

    @Query(
        """SELECT c.* FROM search_vod_content c
           JOIN search_vod f ON f.rowid = c.id
           WHERE search_vod MATCH :match
           ORDER BY (c.normalizedTitle = :exact) DESC, (c.normalizedTitle LIKE :prefix) DESC,
                    (c.normalizedTitle LIKE :wordPrefix) DESC, length(c.normalizedTitle), c.playlistId, c.id
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun matchVod(match: String, exact: String, prefix: String, wordPrefix: String, limit: Int, offset: Int): List<SearchVodEntity>

    @Query(
        """SELECT * FROM search_vod_content
           WHERE normalizedTitle LIKE :prefix OR normalizedTitle LIKE :wordPrefix
           ORDER BY (normalizedTitle = :exact) DESC, (normalizedTitle LIKE :prefix) DESC, length(normalizedTitle), playlistId, id
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun prefixVod(exact: String, prefix: String, wordPrefix: String, limit: Int, offset: Int): List<SearchVodEntity>

    @Query("SELECT * FROM search_vod_content WHERE titleKey IN (:titleKeys)")
    suspend fun vodTitles(titleKeys: List<String>): List<SearchVodEntity>

    // ---- state ----

    @Query("SELECT * FROM search_index_state WHERE playlistId = :playlistId")
    suspend fun state(playlistId: Long): SearchIndexStateEntity?

    @Upsert
    suspend fun upsertState(state: SearchIndexStateEntity)

    @Query("DELETE FROM search_index_state WHERE playlistId = :playlistId")
    suspend fun deleteState(playlistId: Long)
}
