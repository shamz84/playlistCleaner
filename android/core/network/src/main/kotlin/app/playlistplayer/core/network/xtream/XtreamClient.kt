@file:OptIn(ExperimentalSerializationApi::class)

package app.playlistplayer.core.network.xtream

import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.parsing.xmltv.GzipSniffer
import app.playlistplayer.core.parsing.xmltv.XtreamEpgListing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Why an Xtream API call failed. Messages never contain the host, username, password or URL
 * (they pass through [Redactor] with the account's secrets masked), and causes are redacted
 * copies.
 */
sealed class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Worth retrying later with backoff (connectivity, 5xx, 429). */
    abstract val retryable: Boolean

    /** Connectivity failure or connection dropped while reading. */
    class Network(message: String, cause: Throwable? = null) : XtreamException(message, cause) {
        override val retryable: Boolean get() = true
    }

    /** Non-2xx HTTP status. 401 means the panel rejected the credentials. */
    class Http(val code: Int) : XtreamException("Xtream API HTTP $code") {
        override val retryable: Boolean get() = code >= 500 || code == 429
        val isAuthFailure: Boolean get() = code == 401
    }

    /** The body was not the expected JSON (HTML page, wrong shape, truncated). */
    class Malformed(message: String, cause: Throwable? = null) : XtreamException(message, cause) {
        override val retryable: Boolean get() = false
    }

    /** A list action answered with a `user_info` object reporting `auth: 0`. */
    class AuthFailed(message: String = "Xtream account rejected (auth 0)") : XtreamException(message) {
        override val retryable: Boolean get() = false
    }
}

/** Counters for one streamed list. */
data class StreamStats(val items: Int, val skipped: Int, val batches: Int)

/**
 * Xtream Codes `player_api.php` client.
 *
 * - The base URL comes from the [XtreamAccount]; credentials are added as query parameters and
 *   never logged or put in exception messages.
 * - OkHttp adds `Accept-Encoding: gzip` itself and transparently decompresses (setting the header
 *   by hand would disable that). A body that is gzip without a `Content-Encoding` header is
 *   detected by its magic bytes.
 * - The big lists (live 22k items, VOD 45k / 18 MB, series 11k) are stream-decoded with
 *   `Json.decodeToSequence` straight from the response stream and handed out in batches; no
 *   full string or list is ever built. Undecodable elements are skipped and counted.
 * - Small responses (account info, categories, series/VOD info, EPG) are decoded in one go.
 */
class XtreamClient(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // ---- account ----

    /** `player_api.php` without an action: `user_info` + `server_info`. */
    suspend fun accountInfo(account: XtreamAccount): AccountInfo = getSmall(account, action = null) { input ->
        val dto = json.decodeFromStream(AccountInfoDto.serializer(), input)
        val userInfo = dto.userInfo ?: throw XtreamException.Malformed("Account response has no user_info")
        AccountInfo(userInfo, dto.serverInfo)
    }

    // ---- categories ----

    suspend fun liveCategories(account: XtreamAccount): List<XtreamCategory> = categories(account, "get_live_categories")

    suspend fun vodCategories(account: XtreamAccount): List<XtreamCategory> = categories(account, "get_vod_categories")

    suspend fun seriesCategories(account: XtreamAccount): List<XtreamCategory> = categories(account, "get_series_categories")

    private suspend fun categories(account: XtreamAccount, action: String): List<XtreamCategory> {
        val out = ArrayList<XtreamCategory>()
        streamList(account, action, emptyMap(), XtreamCategory.serializer(), Int.MAX_VALUE) { out.addAll(it) }
        return out
    }

    // ---- streamed lists ----

    /** get_live_streams (optionally one [categoryId]), in batches of [batchSize]. */
    suspend fun streamLiveStreams(
        account: XtreamAccount,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        categoryId: String? = null,
        onBatch: suspend (List<XtreamLiveStream>) -> Unit,
    ): StreamStats = streamList(account, "get_live_streams", categoryParams(categoryId), XtreamLiveStream.serializer(), batchSize, onBatch)

    /** get_vod_streams (optionally one [categoryId]), in batches of [batchSize]. */
    suspend fun streamVodStreams(
        account: XtreamAccount,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        categoryId: String? = null,
        onBatch: suspend (List<XtreamVodStream>) -> Unit,
    ): StreamStats = streamList(account, "get_vod_streams", categoryParams(categoryId), XtreamVodStream.serializer(), batchSize, onBatch)

    /** get_series (optionally one [categoryId]), in batches of [batchSize]. */
    suspend fun streamSeries(
        account: XtreamAccount,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        categoryId: String? = null,
        onBatch: suspend (List<XtreamSeries>) -> Unit,
    ): StreamStats = streamList(account, "get_series", categoryParams(categoryId), XtreamSeries.serializer(), batchSize, onBatch)

    // ---- details ----

    /** get_series_info: details, season metadata and episodes sorted by season then episode. */
    suspend fun seriesInfo(account: XtreamAccount, seriesId: Long): XtreamSeriesInfo =
        getSmall(account, "get_series_info", mapOf("series_id" to seriesId.toString())) { input ->
            val root = json.decodeFromStream(JsonElement.serializer(), input) as? JsonObject
                ?: throw XtreamException.Malformed("Series info is not an object")
            parseSeriesInfo(root)
        }

    /** get_vod_info: plot, cast, backdrop, duration and the movie's container extension. */
    suspend fun vodInfo(account: XtreamAccount, vodId: Long): XtreamVodInfo =
        getSmall(account, "get_vod_info", mapOf("vod_id" to vodId.toString())) { input ->
            val element = json.decodeFromStream(JsonElement.serializer(), input)
            if (element !is JsonObject) throw XtreamException.Malformed("VOD info is not an object")
            json.decodeFromJsonElement(XtreamVodInfo.serializer(), element)
        }

    // ---- EPG ----

    /** get_short_epg. Times are server-local strings; decode with `XtreamEpgDecoder`. */
    suspend fun shortEpg(account: XtreamAccount, streamId: Long, limit: Int? = null): List<XtreamEpgListing> {
        val params = buildMap {
            put("stream_id", streamId.toString())
            if (limit != null) put("limit", limit.toString())
        }
        return getSmall(account, "get_short_epg", params, ::decodeEpg)
    }

    /** get_simple_data_table: the full archive-range listing for one stream (≈ −4.6 d … +5 d). */
    suspend fun simpleDataTable(account: XtreamAccount, streamId: Long): List<XtreamEpgListing> =
        getSmall(account, "get_simple_data_table", mapOf("stream_id" to streamId.toString()), ::decodeEpg)

    private fun decodeEpg(input: InputStream): List<XtreamEpgListing> {
        val element = json.decodeFromStream(JsonElement.serializer(), input)
        if (element !is JsonObject) return emptyList()
        return json.decodeFromJsonElement(EpgResponseDto.serializer(), element).listings.map { it.toListing() }
    }

    // ---- plumbing ----

    private fun categoryParams(categoryId: String?): Map<String, String> =
        if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)

    private fun url(account: XtreamAccount, action: String?, params: Map<String, String>): HttpUrl {
        val base = account.baseUrl.toHttpUrlOrNull() ?: throw XtreamException.Malformed("Invalid Xtream account address")
        return base.newBuilder()
            .encodedPath("/")
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
    }

    private suspend fun <T> getSmall(
        account: XtreamAccount,
        action: String?,
        params: Map<String, String> = emptyMap(),
        decode: (InputStream) -> T,
    ): T {
        val response = execute(account, url(account, action, params))
        return withContext(ioDispatcher) {
            response.use {
                val input = openBody(account, it)
                try {
                    decode(input)
                } catch (e: XtreamException) {
                    throw e
                } catch (e: IOException) {
                    throw networkError(account, e)
                } catch (e: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException.
                    throw decodeFailure(account, e)
                }
            }
        }
    }

    private suspend fun <T : Any> streamList(
        account: XtreamAccount,
        action: String,
        params: Map<String, String>,
        serializer: KSerializer<T>,
        batchSize: Int,
        onBatch: suspend (List<T>) -> Unit,
    ): StreamStats {
        require(batchSize > 0) { "batchSize must be positive" }
        val response = execute(account, url(account, action, params))
        return withContext(ioDispatcher) {
            response.use {
                decodeArrayInBatches(account, openBody(account, it), serializer, batchSize, onBatch)
            }
        }
    }

    /**
     * Streams a JSON array from [input] into batches. A `{}`/`null`/empty body is an empty list;
     * an object carrying `user_info.auth = 0` is [XtreamException.AuthFailed]; an object keyed by
     * index (some panels) is read as its values.
     */
    internal suspend fun <T : Any> decodeArrayInBatches(
        account: XtreamAccount,
        input: InputStream,
        serializer: KSerializer<T>,
        batchSize: Int,
        onBatch: suspend (List<T>) -> Unit,
    ): StreamStats {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        val first = try {
            skipBomAndPeek(buffered)
        } catch (e: IOException) {
            throw networkError(account, e)
        }
        val elements: Iterator<JsonElement> = when (first) {
            -1, 'n'.code -> return StreamStats(0, 0, 0)
            '['.code -> json.decodeToSequence(buffered, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            '{'.code -> objectValues(account, buffered).iterator()
            else -> throw XtreamException.Malformed("Xtream list response is not JSON")
        }
        var items = 0
        var skipped = 0
        var batches = 0
        val batch = ArrayList<T>(minOf(batchSize, 1024))
        val context = currentCoroutineContext()
        while (true) {
            val element = try {
                if (!elements.hasNext()) break
                elements.next()
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw networkError(account, e)
            } catch (e: IllegalArgumentException) {
                throw decodeFailure(account, e)
            }
            val item = (element as? JsonObject)?.let {
                try {
                    json.decodeFromJsonElement(serializer, it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (item == null) {
                skipped++
                continue
            }
            batch.add(item)
            items++
            if (batch.size >= batchSize) {
                context.ensureActive()
                onBatch(ArrayList(batch))
                batches++
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            context.ensureActive()
            onBatch(ArrayList(batch))
            batches++
        }
        return StreamStats(items, skipped, batches)
    }

    private fun objectValues(account: XtreamAccount, input: InputStream): List<JsonElement> {
        val root = try {
            json.decodeFromStream(JsonElement.serializer(), input) as JsonObject
        } catch (e: IOException) {
            throw networkError(account, e)
        } catch (e: IllegalArgumentException) {
            throw decodeFailure(account, e)
        }
        val userInfo = root["user_info"] as? JsonObject
        if (userInfo != null) {
            if (XtreamValues.boolean(userInfo["auth"]) == false) throw XtreamException.AuthFailed()
            throw XtreamException.Malformed("Xtream list action answered with account info")
        }
        return root.values.filter { it is JsonObject || it is JsonArray }.flatMap { if (it is JsonArray) it else listOf(it) }
    }

    /** Skips a UTF-8 BOM and whitespace, returning the next byte without consuming it. */
    private fun skipBomAndPeek(input: BufferedInputStream): Int {
        while (true) {
            input.mark(3)
            val b = input.read()
            if (b == -1) return -1
            if (b == 0xEF) {
                val b1 = input.read()
                val b2 = input.read()
                if (b1 == 0xBB && b2 == 0xBF) continue
                input.reset()
                return b
            }
            if (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) continue
            input.reset()
            return b
        }
    }

    private fun openBody(account: XtreamAccount, response: Response): InputStream {
        if (!response.isSuccessful) throw XtreamException.Http(response.code)
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.contains("text/html", ignoreCase = true)) {
            throw XtreamException.Malformed("Xtream API returned an HTML page (HTTP ${response.code})")
        }
        return try {
            GzipSniffer.wrap(response.body.byteStream())
        } catch (e: IOException) {
            throw networkError(account, e)
        }
    }

    /** Executes asynchronously so coroutine cancellation cancels the call. */
    private suspend fun execute(account: XtreamAccount, url: HttpUrl): Response {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return try {
            suspendCancellableCoroutine { cont ->
                val call = httpClient.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            cont.resume(response) { _, value, _ -> value.close() }
                        }
                    },
                )
            }
        } catch (e: IOException) {
            throw networkError(account, e)
        }
    }

    private fun secrets(account: XtreamAccount): List<String> =
        listOfNotNull(account.host, account.username, account.password, account.port?.let { ":$it" })

    private fun networkError(account: XtreamAccount, e: IOException): XtreamException.Network =
        XtreamException.Network(
            "Xtream network error: " + Redactor.redact(Redactor.redact(e), secrets(account)),
            Redactor.redacted(e),
        )

    /**
     * A decoding failure. kotlinx.serialization wraps stream IOExceptions (connection dropped
     * mid-body) in its own exception; those are network errors, not malformed JSON.
     */
    private fun decodeFailure(account: XtreamAccount, e: IllegalArgumentException): XtreamException {
        val io = generateSequence<Throwable>(e) { it.cause }.take(8).filterIsInstance<IOException>().firstOrNull()
        return if (io != null) networkError(account, io) else malformed(account, e)
    }

    /** Serialization messages quote the JSON input (which may echo credentials): mask and trim. */
    private fun malformed(account: XtreamAccount, e: Exception): XtreamException.Malformed {
        val message = Redactor.redact(e.message.orEmpty().lineSequence().firstOrNull().orEmpty(), secrets(account)).take(MAX_MESSAGE)
        return XtreamException.Malformed("Unreadable Xtream response: ${e.javaClass.simpleName}: $message")
    }

    private fun parseSeriesInfo(root: JsonObject): XtreamSeriesInfo {
        val info = (root["info"] as? JsonObject)?.let { decodeOrNull(XtreamSeriesDetails.serializer(), it) } ?: XtreamSeriesDetails()
        val seasons = (root["seasons"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> decodeOrNull(XtreamSeason.serializer(), o) } }
        val episodes = ArrayList<XtreamEpisode>()
        when (val raw = root["episodes"]) {
            is JsonObject -> raw.forEach { (key, value) ->
                val keySeason = key.trim().toIntOrNull()
                (value as? JsonArray)?.forEach { e -> decodeEpisode(e, keySeason)?.let(episodes::add) }
                if (value is JsonObject) value.values.forEach { e -> decodeEpisode(e, keySeason)?.let(episodes::add) }
            }
            // Some panels send a list of per-season lists.
            is JsonArray -> raw.forEach { item ->
                if (item is JsonArray) item.forEach { e -> decodeEpisode(e, null)?.let(episodes::add) } else decodeEpisode(item, null)?.let(episodes::add)
            }
            else -> Unit
        }
        episodes.sortWith(EPISODE_ORDER)
        return XtreamSeriesInfo(info, seasons, episodes)
    }

    private fun decodeEpisode(element: JsonElement, keySeason: Int?): XtreamEpisode? {
        val obj = element as? JsonObject ?: return null
        val episode = decodeOrNull(XtreamEpisode.serializer(), obj) ?: return null
        if (episode.id == null) return null
        return if (keySeason != null) episode.copy(season = keySeason) else episode
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonObject): T? = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 500
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_MESSAGE = 200

        /** Numeric season (absent last), then episode number (absent last), then id. */
        private val EPISODE_ORDER: Comparator<XtreamEpisode> = compareBy<XtreamEpisode>(
            { it.season ?: Int.MAX_VALUE },
            { it.episodeNum ?: Int.MAX_VALUE },
            { it.id?.toLongOrNull() ?: Long.MAX_VALUE },
            { it.id },
        )

        internal val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            allowSpecialFloatingPointValues = true
        }

        private fun EpgListingDto.toListing() = XtreamEpgListing(
            id = id,
            epgId = epgId,
            channelId = channelId,
            streamId = streamId,
            title = title,
            description = description,
            lang = lang,
            start = start,
            end = end,
            startTimestamp = startTimestamp,
            stopTimestamp = stopTimestamp,
            nowPlaying = nowPlaying,
            hasArchive = hasArchive,
        )
    }
}
