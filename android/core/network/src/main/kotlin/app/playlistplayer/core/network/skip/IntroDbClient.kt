package app.playlistplayer.core.network.skip

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class SkipMarkerType { INTRO, RECAP, CREDITS }

/** A skippable span. [endMs] null means "to the end of the item"; [startMs] null means "from the start". */
data class SkipMarker(val type: SkipMarkerType, val startMs: Long?, val endMs: Long?)

/** Failure that never carries the request URL. */
class SkipMarkerException(message: String, val httpCode: Int? = null) : Exception(message)

fun interface SkipMarkerSource {
    /**
     * Markers for a movie ([season] and [episode] null) or an episode of the TMDB show [tmdbId].
     * An empty list means the service has nothing for it; failures throw [SkipMarkerException].
     */
    suspend fun markers(tmdbId: Long, season: Int?, episode: Int?): List<SkipMarker>
}

/**
 * TheIntroDB (`api.theintrodb.org/v2/media`), keyed by TMDB id. Only the id, season and episode
 * are sent: nothing about the user's playlist or provider leaves the device.
 */
class IntroDbClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SkipMarkerSource {

    override suspend fun markers(tmdbId: Long, season: Int?, episode: Int?): List<SkipMarker> = withContext(ioDispatcher) {
        val url = baseUrl.newBuilder().addPathSegments("v2/media").addQueryParameter("tmdb_id", tmdbId.toString())
            .apply {
                if (season != null && episode != null) {
                    addQueryParameter("season", season.toString())
                    addQueryParameter("episode", episode.toString())
                }
            }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            httpClient.newCall(request).execute().use { response ->
                // 400/404: the id or episode is unknown to the service, which is "no markers", not a failure.
                if (response.code == 400 || response.code == 404) return@use emptyList()
                if (!response.isSuccessful) throw SkipMarkerException("Skip markers failed: HTTP ${response.code}", response.code)
                parse(response.body.string())
            }
        } catch (e: SkipMarkerException) {
            throw e
        } catch (e: IOException) {
            throw SkipMarkerException("Skip markers failed: network error (${e.javaClass.simpleName})")
        } catch (e: SerializationException) {
            throw SkipMarkerException("Skip markers failed: unreadable response")
        } catch (e: IllegalArgumentException) {
            throw SkipMarkerException("Skip markers failed: unreadable response")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.theintrodb.org/"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        internal fun parse(body: String): List<SkipMarker> {
            val dto = json.decodeFromString(MediaDto.serializer(), body)
            return buildList {
                dto.intro.forEach { add(it.toMarker(SkipMarkerType.INTRO)) }
                dto.recap.forEach { add(it.toMarker(SkipMarkerType.RECAP)) }
                dto.credits.forEach { add(it.toMarker(SkipMarkerType.CREDITS)) }
            }.filter { m ->
                val s = m.startMs
                val e = m.endMs
                (s == null || s >= 0) && (e == null || e > (s ?: 0))
            }
        }
    }
}

@Serializable
private data class MediaDto(
    val intro: List<SegmentDto> = emptyList(),
    val recap: List<SegmentDto> = emptyList(),
    val credits: List<SegmentDto> = emptyList(),
)

@Serializable
private data class SegmentDto(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
) {
    fun toMarker(type: SkipMarkerType) = SkipMarker(type, startMs, endMs)
}
