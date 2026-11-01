package app.playlistplayer.core.database.entity

import androidx.room3.Entity

// U18: TMDB weekly trending cache. Global (not per playlist): it holds public TMDB data only,
// matched against each playlist's catalog at read time. No URLs, keys or credentials.

/**
 * One trending entry.
 * @property kind `MOVIE` or `SERIES`.
 * @property rank 0-based position in TMDB's trending order (pages concatenated).
 * @property year release year (movies) or first-air year (TV), when TMDB gives one.
 */
@Entity(tableName = "trending_title", primaryKeys = ["kind", "rank"])
data class TrendingTitleEntity(
    val kind: String,
    val rank: Int,
    val tmdbId: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
)

/** When [kind]'s trending list was last fetched successfully (epoch ms). */
@Entity(tableName = "trending_state", primaryKeys = ["kind"])
data class TrendingStateEntity(
    val kind: String,
    val fetchedAt: Long,
)
