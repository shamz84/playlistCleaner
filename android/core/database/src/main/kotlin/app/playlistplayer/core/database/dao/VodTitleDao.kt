package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.database.entity.VodTitleStateEntity

/**
 * Precomputed merged titles (U20). Reads join `vod_title_state.activeGeneration`, so a rebuild
 * that is being written is never visible.
 *
 * Parental filter: with `filter = 1` a title is returned only when some version has no category
 * or a category outside `hidden` (the rule browse, Search and Home share).
 */
@Dao
interface VodTitleDao {
    // ---- state ----

    @Query("SELECT * FROM vod_title_state WHERE playlistId = :playlistId")
    suspend fun state(playlistId: Long): VodTitleStateEntity?

    @Upsert
    suspend fun upsertState(state: VodTitleStateEntity)

    @Query("DELETE FROM vod_title_state WHERE playlistId = :playlistId")
    suspend fun deleteState(playlistId: Long)

    // ---- writes ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTitles(rows: List<VodTitleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(rows: List<VodTitleItemEntity>)

    @Query("DELETE FROM vod_title WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteTitleGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM vod_title_item WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteItemGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM vod_title WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteTitlesExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM vod_title_item WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteItemsExcept(playlistId: Long, keep: Long?)

    @Query("SELECT DISTINCT generation FROM vod_title WHERE playlistId = :playlistId ORDER BY generation")
    suspend fun generationsPresent(playlistId: Long): List<Long>

    // ---- browse pages (one per sort; each walks its rank index) ----

    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))
           ORDER BY t.addedRank LIMIT :limit OFFSET :offset""",
    )
    suspend fun pageByAdded(playlistId: Long, kind: String, filter: Boolean, hidden: List<String>, limit: Int, offset: Int): List<VodTitleEntity>

    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))
           ORDER BY t.azRank LIMIT :limit OFFSET :offset""",
    )
    suspend fun pageByName(playlistId: Long, kind: String, filter: Boolean, hidden: List<String>, limit: Int, offset: Int): List<VodTitleEntity>

    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))
           ORDER BY t.yearRank LIMIT :limit OFFSET :offset""",
    )
    suspend fun pageByYear(playlistId: Long, kind: String, filter: Boolean, hidden: List<String>, limit: Int, offset: Int): List<VodTitleEntity>

    @Query(
        """SELECT COUNT(*) FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))""",
    )
    suspend fun count(playlistId: Long, kind: String, filter: Boolean, hidden: List<String>): Int

    // ---- lookups ----

    @Query(
        """SELECT t.* FROM vod_title_item i
           JOIN vod_title_state s ON s.playlistId = i.playlistId AND s.activeGeneration = i.generation
           JOIN vod_title t ON t.playlistId = i.playlistId AND t.generation = i.generation AND t.titleKey = i.titleKey
           WHERE i.playlistId = :playlistId AND i.kind = :kind AND i.itemId = :itemId""",
    )
    suspend fun titleForItem(playlistId: Long, kind: String, itemId: String): VodTitleEntity?

    @Query(
        """SELECT i.* FROM vod_title_item i
           JOIN vod_title_state s ON s.playlistId = i.playlistId AND s.activeGeneration = i.generation
           WHERE i.playlistId = :playlistId AND i.titleKey = :titleKey
           ORDER BY i.providerOrder, i.itemId""",
    )
    suspend fun items(playlistId: Long, titleKey: String): List<VodTitleItemEntity>

    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind AND t.titleKey IN (:keys)
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))""",
    )
    suspend fun titles(playlistId: Long, kind: String, keys: List<String>, filter: Boolean, hidden: List<String>): List<VodTitleEntity>

    /** Versions carrying one of [tmdbIds] (Trending's id match). */
    @Query(
        """SELECT i.* FROM vod_title_item i
           JOIN vod_title_state s ON s.playlistId = i.playlistId AND s.activeGeneration = i.generation
           WHERE i.playlistId = :playlistId AND i.kind = :kind AND i.tmdb IN (:tmdbIds)""",
    )
    suspend fun itemsByTmdb(playlistId: Long, kind: String, tmdbIds: List<String>): List<VodTitleItemEntity>

    /** Titles without any tmdb id whose normalised title is one of [names] (Trending's fallback). */
    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind AND t.hasTmdb = 0 AND t.normalizedTitle IN (:names)
             AND (:filter = 0 OR EXISTS (SELECT 1 FROM vod_title_item i
                  WHERE i.playlistId = t.playlistId AND i.generation = t.generation AND i.titleKey = t.titleKey
                    AND (i.categoryId IS NULL OR i.categoryId NOT IN (:hidden))))""",
    )
    suspend fun titlesByName(playlistId: Long, kind: String, names: List<String>, filter: Boolean, hidden: List<String>): List<VodTitleEntity>

    /** Keyset page over every title of a kind in provider order (search index rebuild); start at -1. */
    @Query(
        """SELECT t.* FROM vod_title t
           JOIN vod_title_state s ON s.playlistId = t.playlistId AND s.activeGeneration = t.generation
           WHERE t.playlistId = :playlistId AND t.kind = :kind AND t.ordinal > :afterOrdinal
           ORDER BY t.ordinal LIMIT :limit""",
    )
    suspend fun titlesPage(playlistId: Long, kind: String, afterOrdinal: Int, limit: Int): List<VodTitleEntity>

    // ---- scoped delete ----

    @Query("DELETE FROM vod_title WHERE playlistId = :playlistId")
    suspend fun deleteAllTitles(playlistId: Long)

    @Query("DELETE FROM vod_title_item WHERE playlistId = :playlistId")
    suspend fun deleteAllItems(playlistId: Long)
}
