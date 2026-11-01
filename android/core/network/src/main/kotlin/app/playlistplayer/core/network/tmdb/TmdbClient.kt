package app.playlistplayer.core.network.tmdb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * TMDB v3 settings. [apiKey] is the build-time key (`tmdb.apiKey`); [baseUrl] is injectable for
 * tests. [toString] never prints the key.
 */
data class TmdbConfig(
    val apiKey: String,
    val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    override fun toString(): String = "TmdbConfig(apiKey=${if (apiKey.isBlank()) "<none>" else "***"})"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org/"
    }
}

/** The trending lists the app reads (an interface so the app can fake it in tests). */
fun interface TmdbTrendingSource {
    /** TMDB weekly trending for [type], pages 1..[pages] concatenated in rank order. */
    suspend fun weeklyTrending(type: TmdbMediaType, pages: Int): List<TmdbTrendingItem>
}

/**
 * TMDB v3 `trending/{movie|tv}/week` (U18). The key travels as the `api_key` query parameter, so
 * it is part of the request URL: errors are thrown as [TmdbException] whose messages contain
 * neither the URL nor the key, and OkHttp I/O errors are rewrapped the same way.
 */
class TmdbClient(
    private val httpClient: OkHttpClient,
    private val config: TmdbConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TmdbTrendingSource {

    override suspend fun weeklyTrending(type: TmdbMediaType, pages: Int): List<TmdbTrendingItem> {
        if (!config.hasKey) throw TmdbException("TMDB API key not configured")
        val out = ArrayList<TmdbTrendingItem>()
        val seen = HashSet<Long>()
        for (page in 1..pages.coerceAtLeast(1)) {
            val body = fetchPage(type, page)
            body.results.forEach { r -> r.toItem()?.takeIf { seen.add(it.tmdbId) }?.let(out::add) }
            if (page >= body.totalPages) break
        }
        return out
    }

    private suspend fun fetchPage(type: TmdbMediaType, page: Int): TmdbPage = withContext(ioDispatcher) {
        val url = config.baseUrl.newBuilder()
            .addPathSegments("3/trending/${type.path}/week")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("api_key", config.apiKey)
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TmdbException("TMDB trending ${type.path} page $page failed: HTTP ${response.code}", response.code)
                }
                json.decodeFromString(TmdbPage.serializer(), response.body.string())
            }
        } catch (e: TmdbException) {
            throw e
        } catch (e: IOException) {
            // The cause is dropped: OkHttp messages can include the URL, which carries the key.
            throw TmdbException("TMDB trending ${type.path} page $page failed: network error (${e.javaClass.simpleName})")
        } catch (e: SerializationException) {
            throw TmdbException("TMDB trending ${type.path} page $page failed: unreadable response")
        } catch (e: IllegalArgumentException) {
            throw TmdbException("TMDB trending ${type.path} page $page failed: unreadable response")
        }
    }

    companion object {
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
