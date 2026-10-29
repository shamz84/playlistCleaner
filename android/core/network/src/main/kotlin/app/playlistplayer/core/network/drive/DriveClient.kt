package app.playlistplayer.core.network.drive

import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.network.source.SourceConsumerException
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drive access settings. [apiKey] is the restricted Drive API key; [packageName] and [certSha1]
 * (hex SHA-1 of the signing certificate, no colons) are sent as `X-Android-Package` /
 * `X-Android-Cert` so Google can enforce the key's Android-app restriction. The base URLs are
 * injectable for tests.
 */
data class DriveClientConfig(
    val apiKey: String,
    val packageName: String,
    val certSha1: String,
    val apiBaseUrl: HttpUrl = DEFAULT_API_BASE_URL.toHttpUrl(),
    val fallbackBaseUrl: HttpUrl = DEFAULT_FALLBACK_BASE_URL.toHttpUrl(),
) {
    override fun toString(): String =
        "DriveClientConfig(apiKey=${if (apiKey.isBlank()) "<none>" else "***"}, packageName=$packageName)"

    companion object {
        const val DEFAULT_API_BASE_URL = "https://www.googleapis.com/"
        const val DEFAULT_FALLBACK_BASE_URL = "https://drive.usercontent.google.com/"
    }
}

/** Drive `files.get` metadata used as the change-detection gate. */
data class DriveMetadata(
    val md5Checksum: String?,
    val version: String?,
    val size: Long?,
    val modifiedTime: Long?,
) {
    fun toSourceVersion(): SourceVersion =
        SourceVersion(md5 = md5Checksum, version = version, modifiedTime = modifiedTime, size = size)
}

/**
 * Downloads a link-shared Drive file without Google sign-in.
 *
 * 1. Drive API v3 `files.get` with `fields=md5Checksum,version,size,modifiedTime`. When the md5
 *    (or, lacking one, the version) equals the stored fingerprint, nothing is downloaded.
 * 2. Drive API `alt=media`, streamed to the consumer.
 * 3. On an API failure other than 404/network/download-quota (e.g. key rejected, 5xx) and before
 *    the consumer has seen any byte, falls back to the undocumented
 *    `drive.usercontent.google.com/download?id=..&export=download&confirm=t` endpoint. An HTML
 *    body there means the file is not link-shared.
 *
 * The body is never buffered in full; only a small prefix is peeked to detect HTML pages.
 */
class DriveClient(
    private val httpClient: OkHttpClient,
    private val config: DriveClientConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Result of one HTTP step; [fallbackAllowed] says whether the usercontent fallback may be tried. */
    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>
        data class Err(val error: SourceError, val fallbackAllowed: Boolean) : Step<Nothing>
    }

    suspend fun metadata(fileId: String, resourceKey: String? = null): SourceFetchResult<DriveMetadata> =
        when (val step = fetchMetadata(fileId, resourceKey)) {
            is Step.Ok -> SourceFetchResult.Fetched(step.value, step.value.toSourceVersion())
            is Step.Err -> SourceFetchResult.Failed(step.error)
        }

    suspend fun <T> fetch(
        fileId: String,
        resourceKey: String?,
        previous: SourceVersion?,
        consume: suspend (InputStream) -> T,
    ): SourceFetchResult<T> {
        if (config.apiKey.isBlank()) return fallbackDownload(fileId, resourceKey, consume)

        val metadata = when (val step = fetchMetadata(fileId, resourceKey)) {
            is Step.Ok -> step.value
            is Step.Err -> {
                if (!step.fallbackAllowed) return SourceFetchResult.Failed(step.error)
                return fallbackDownload(fileId, resourceKey, consume)
            }
        }
        if (isUnchanged(previous, metadata)) return SourceFetchResult.Unchanged(metadata.toSourceVersion())

        val consumerStarted = BooleanArray(1)
        return when (val step = apiDownload(fileId, resourceKey, consumerStarted, consume)) {
            is Step.Ok -> SourceFetchResult.Fetched(step.value, metadata.toSourceVersion())
            is Step.Err ->
                if (step.fallbackAllowed && !consumerStarted[0]) {
                    when (val fallback = fallbackDownload(fileId, resourceKey, consume)) {
                        // Keep the API's fingerprint when it is available.
                        is SourceFetchResult.Fetched -> fallback.copy(version = metadata.toSourceVersion())
                        else -> fallback
                    }
                } else {
                    SourceFetchResult.Failed(step.error)
                }
        }
    }

    private fun isUnchanged(previous: SourceVersion?, current: DriveMetadata): Boolean {
        if (previous == null) return false
        if (previous.md5 != null && current.md5Checksum != null) return previous.md5.equals(current.md5Checksum, ignoreCase = true)
        if (previous.version != null && current.version != null) return previous.version == current.version
        return false
    }

    private fun fileUrl(fileId: String): HttpUrl.Builder =
        config.apiBaseUrl.newBuilder()
            .addPathSegments("drive/v3/files")
            .addPathSegment(fileId)
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("key", config.apiKey)

    private fun apiRequest(url: HttpUrl, fileId: String, resourceKey: String?): Request =
        Request.Builder().url(url).get().apply {
            if (config.packageName.isNotBlank()) header(HEADER_ANDROID_PACKAGE, config.packageName)
            if (config.certSha1.isNotBlank()) header(HEADER_ANDROID_CERT, config.certSha1)
            if (!resourceKey.isNullOrBlank()) header(HEADER_RESOURCE_KEYS, "$fileId/$resourceKey")
        }.build()

    private suspend fun fetchMetadata(fileId: String, resourceKey: String?): Step<DriveMetadata> {
        val url = fileUrl(fileId).addQueryParameter("fields", METADATA_FIELDS).build()
        val response = try {
            execute(apiRequest(url, fileId, resourceKey))
        } catch (e: IOException) {
            return Step.Err(networkError(e), fallbackAllowed = false)
        }
        return withContext(ioDispatcher) {
            response.use {
                if (!it.isSuccessful) return@withContext classifyApiError(it)
                try {
                    Step.Ok(parseMetadata(it.body.string()))
                } catch (e: IOException) {
                    Step.Err(networkError(e), fallbackAllowed = false)
                } catch (e: IllegalArgumentException) {
                    Step.Err(SourceError.Other("Unreadable Drive metadata", it.code), fallbackAllowed = true)
                }
            }
        }
    }

    private suspend fun <T> apiDownload(
        fileId: String,
        resourceKey: String?,
        consumerStarted: BooleanArray,
        consume: suspend (InputStream) -> T,
    ): Step<T> {
        val url = fileUrl(fileId).addQueryParameter("alt", "media").build()
        val response = try {
            execute(apiRequest(url, fileId, resourceKey))
        } catch (e: IOException) {
            return Step.Err(networkError(e), fallbackAllowed = false)
        }
        return withContext(ioDispatcher) {
            response.use {
                if (!it.isSuccessful) return@withContext classifyApiError(it)
                if (looksLikeHtml(it)) {
                    return@withContext Step.Err(SourceError.Other("Drive API returned an HTML page", it.code), fallbackAllowed = true)
                }
                consumerStarted[0] = true
                consumeBody(it, digest = null, consume)
            }
        }
    }

    private suspend fun <T> fallbackDownload(
        fileId: String,
        resourceKey: String?,
        consume: suspend (InputStream) -> T,
    ): SourceFetchResult<T> {
        val url = config.fallbackBaseUrl.newBuilder()
            .addPathSegment("download")
            .addQueryParameter("id", fileId)
            .addQueryParameter("export", "download")
            .addQueryParameter("confirm", "t")
            .apply { if (!resourceKey.isNullOrBlank()) addQueryParameter("resourcekey", resourceKey) }
            .build()
        // OkHttp adds Accept-Encoding: gzip itself and then decompresses transparently.
        val request = Request.Builder().url(url).get().build()
        val response = try {
            execute(request)
        } catch (e: IOException) {
            return SourceFetchResult.Failed(networkError(e))
        }
        return withContext(ioDispatcher) {
            response.use {
                if (!it.isSuccessful) {
                    val error = when (it.code) {
                        404 -> SourceError.NotFoundOrNotShared()
                        401, 403 -> SourceError.NotShared()
                        429 -> SourceError.QuotaExceeded()
                        else -> SourceError.Other("Drive download failed (HTTP ${it.code})", it.code)
                    }
                    return@withContext SourceFetchResult.Failed(error)
                }
                if (it.request.url.host.equals(SIGN_IN_HOST, ignoreCase = true)) {
                    return@withContext SourceFetchResult.Failed(SourceError.NotShared())
                }
                if (looksLikeHtml(it)) {
                    val prefix = try {
                        it.peekBody(HTML_SNIFF_BYTES).string()
                    } catch (_: IOException) {
                        ""
                    }
                    val error = if (QUOTA_PAGE_MARKERS.any { marker -> prefix.contains(marker, ignoreCase = true) }) {
                        SourceError.QuotaExceeded()
                    } else {
                        SourceError.NotShared()
                    }
                    return@withContext SourceFetchResult.Failed(error)
                }
                val digest = MessageDigest.getInstance("MD5")
                when (val step = consumeBody(it, digest, consume)) {
                    is Step.Ok -> SourceFetchResult.Fetched(
                        step.value,
                        SourceVersion(md5 = digest.digest().toHex(), size = it.body.contentLength().takeIf { n -> n >= 0 }),
                    )
                    is Step.Err -> SourceFetchResult.Failed(step.error)
                }
            }
        }
    }

    private suspend fun <T> consumeBody(response: Response, digest: MessageDigest?, consume: suspend (InputStream) -> T): Step<T> {
        val raw = response.body.byteStream()
        val stream = if (digest != null) DigestInputStream(raw, digest) else raw
        return try {
            val value = consume(stream)
            if (digest != null) drain(stream) // complete the checksum if the consumer stopped early
            Step.Ok(value)
        } catch (e: SourceConsumerException) {
            Step.Err(e.error, fallbackAllowed = false)
        } catch (e: IOException) {
            Step.Err(networkError(e), fallbackAllowed = false)
        }
    }

    /** Best effort: a consumer that closed the stream simply leaves the checksum partial. */
    private fun drain(stream: InputStream) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (stream.read(buffer) >= 0) {
                // discard
            }
        } catch (_: IOException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun looksLikeHtml(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.contains("text/html", ignoreCase = true)) return true
        val prefix = try {
            response.peekBody(HTML_SNIFF_BYTES).string()
        } catch (_: IOException) {
            return false
        }
        val start = prefix.trimStart('﻿', ' ', '\t', '\r', '\n').take(15).lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html")
    }

    private fun classifyApiError(response: Response): Step<Nothing> {
        val code = response.code
        if (code == 404) return Step.Err(SourceError.NotFoundOrNotShared(), fallbackAllowed = false)
        if (code == 429) return Step.Err(SourceError.QuotaExceeded(), fallbackAllowed = false)
        val body = try {
            response.peekBody(ERROR_BODY_BYTES).string()
        } catch (_: IOException) {
            ""
        }
        val (domains, reasons) = parseErrorReasons(body)
        if (reasons.any { it in DOWNLOAD_QUOTA_REASONS }) {
            // The file's own download quota: the fallback endpoint hits the same quota.
            return Step.Err(SourceError.QuotaExceeded(), fallbackAllowed = false)
        }
        if (reasons.any { it in RATE_QUOTA_REASONS } ||
            ("usageLimits" in domains && reasons.none { it in KEY_PROBLEM_REASONS })
        ) {
            // API-key quota: the keyless fallback endpoint may still work.
            return Step.Err(SourceError.QuotaExceeded(), fallbackAllowed = true)
        }
        val reasonText = reasons.firstOrNull()?.let { " ($it)" }.orEmpty()
        return Step.Err(SourceError.Other(Redactor.redact("Drive API error HTTP $code$reasonText"), code), fallbackAllowed = true)
    }

    private fun parseErrorReasons(body: String): Pair<Set<String>, Set<String>> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptySet<String>() to emptySet()
        val error = root["error"] as? JsonObject ?: return emptySet<String>() to emptySet()
        val domains = HashSet<String>()
        val reasons = HashSet<String>()
        (error["errors"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            (obj["domain"] as? JsonPrimitive)?.content?.let(domains::add)
            (obj["reason"] as? JsonPrimitive)?.content?.let(reasons::add)
        }
        (error["status"] as? JsonPrimitive)?.content?.let { if (it == "RESOURCE_EXHAUSTED") reasons.add("rateLimitExceeded") }
        return domains to reasons
    }

    private fun parseMetadata(body: String): DriveMetadata {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("not an object")
        fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        return DriveMetadata(
            md5Checksum = field("md5Checksum"),
            version = field("version"),
            size = field("size")?.toLongOrNull(),
            modifiedTime = field("modifiedTime")?.let {
                try {
                    Instant.parse(it).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            },
        )
    }

    private fun networkError(e: IOException): SourceError.Network =
        SourceError.Network("Network error: ${Redactor.redact(e)}", e)

    /** Executes [request] asynchronously so coroutine cancellation cancels the call. */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
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

    companion object {
        const val HEADER_ANDROID_PACKAGE = "X-Android-Package"
        const val HEADER_ANDROID_CERT = "X-Android-Cert"
        const val HEADER_RESOURCE_KEYS = "X-Goog-Drive-Resource-Keys"
        const val METADATA_FIELDS = "md5Checksum,version,size,modifiedTime"
        private const val SIGN_IN_HOST = "accounts.google.com"
        private const val HTML_SNIFF_BYTES = 4L * 1024
        private const val ERROR_BODY_BYTES = 16L * 1024

        private val DOWNLOAD_QUOTA_REASONS = setOf("downloadQuotaExceeded")
        private val RATE_QUOTA_REASONS = setOf(
            "dailyLimitExceeded", "userRateLimitExceeded", "rateLimitExceeded", "quotaExceeded",
            "sharingRateLimitExceeded", "dailyLimitExceededUnreg",
        )
        private val KEY_PROBLEM_REASONS = setOf("keyInvalid", "keyExpired", "accessNotConfigured", "ipRefererBlocked", "forbidden")
        private val QUOTA_PAGE_MARKERS = listOf("Too many users have viewed or downloaded", "download quota", "quota exceeded")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
