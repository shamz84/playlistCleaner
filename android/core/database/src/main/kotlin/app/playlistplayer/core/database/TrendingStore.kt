package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.TrendingStateEntity
import app.playlistplayer.core.database.entity.TrendingTitleEntity

/** A cached trending list and when it was fetched. */
data class CachedTrending(val fetchedAt: Long, val titles: List<TrendingTitleEntity>)

/** Persistence of the TMDB weekly trending lists (U18), one list per kind. */
class TrendingStore(private val db: AppDatabase) {
    private val dao = db.trendingDao()

    /** The cached list for [kind], or null when it was never fetched. */
    suspend fun cached(kind: String): CachedTrending? {
        val state = dao.state(kind) ?: return null
        return CachedTrending(state.fetchedAt, dao.titles(kind))
    }

    /** Replaces [kind]'s list (ranks are renumbered in the given order) and records [fetchedAt]. */
    suspend fun replace(kind: String, titles: List<TrendingTitleEntity>, fetchedAt: Long) = db.withWriteTransaction {
        dao.deleteTitles(kind)
        if (titles.isNotEmpty()) dao.insertTitles(titles.mapIndexed { i, t -> t.copy(kind = kind, rank = i) })
        dao.upsertState(TrendingStateEntity(kind, fetchedAt))
    }
}
