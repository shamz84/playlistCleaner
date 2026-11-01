package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.TrendingStateEntity
import app.playlistplayer.core.database.entity.TrendingTitleEntity

/** TMDB trending cache (U18). */
@Dao
interface TrendingDao {
    @Query("SELECT * FROM trending_title WHERE kind = :kind ORDER BY rank")
    suspend fun titles(kind: String): List<TrendingTitleEntity>

    @Query("SELECT * FROM trending_state WHERE kind = :kind")
    suspend fun state(kind: String): TrendingStateEntity?

    @Query("DELETE FROM trending_title WHERE kind = :kind")
    suspend fun deleteTitles(kind: String)

    @Insert
    suspend fun insertTitles(rows: List<TrendingTitleEntity>)

    @Upsert
    suspend fun upsertState(row: TrendingStateEntity)
}
