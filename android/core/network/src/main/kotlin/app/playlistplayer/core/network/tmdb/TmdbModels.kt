package app.playlistplayer.core.network.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a trending list is for: TMDB `movie` or `tv`. */
enum class TmdbMediaType(val path: String) { MOVIE("movie"), TV("tv") }

/**
 * One entry of a TMDB trending list, in TMDB's rank order.
 * @property title movie `title` or TV `name`.
 * @property originalTitle movie `original_title` or TV `original_name`.
 * @property year from `release_date` (movies) or `first_air_date` (TV), when present.
 */
data class TmdbTrendingItem(
    val tmdbId: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
)

/** A TMDB failure. Messages never carry the request URL or the API key. */
class TmdbException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

@Serializable
internal data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
internal data class TmdbResult(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
) {
    fun toItem(): TmdbTrendingItem? {
        val display = (title ?: name)?.takeIf { it.isNotBlank() } ?: return null
        val date = releaseDate ?: firstAirDate
        val year = date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        return TmdbTrendingItem(id, display, originalTitle ?: originalName, year)
    }
}
