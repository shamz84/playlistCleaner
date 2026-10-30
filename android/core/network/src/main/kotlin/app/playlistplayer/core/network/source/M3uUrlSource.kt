package app.playlistplayer.core.network.source

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.parsing.m3u.AccountExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PlaylistSource] over a plain http(s) M3U URL (U15).
 *
 * Change detection, cheapest first:
 * 1. Conditional GET: the stored `ETag` is sent as `If-None-Match` and the stored `Last-Modified`
 *    as `If-Modified-Since`. `304 Not Modified` → [SourceFetchResult.Unchanged]; nothing is read.
 * 2. Otherwise the body is streamed to the consumer while a SHA-256 content hash is computed
 *    ([SourceVersion.md5] holds `sha256:<hex>`). When the hash equals the stored one the result is
 *    [SourceFetchResult.Unchanged] even though the consumer ran, so the caller discards what it
 *    wrote instead of committing an identical copy. (A server without validators cannot be asked
 *    "changed?" without downloading; the hash only avoids the commit.)
 *
 * Errors: 404/410 → [SourceError.NotFoundOrNotShared]; 401/403 → [SourceError.Unauthorized];
 * 429 → [SourceError.QuotaExceeded]; an HTML page → [SourceError.NotAPlaylist]; connectivity →
 * [SourceError.Network]; anything else → [SourceError.Other] with the HTTP code (5xx retryable).
 *
 * The URL may embed account credentials (`get.php?username=&password=`): it is never logged and
 * every message is redacted. The body is never buffered in full; only a small prefix is peeked.
 */
class M3uUrlSource(
    private val httpClient: OkHttpClient,
    private val reference: SourceReference.M3uUrl,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String? = null,
) : PlaylistSource {

    /** The Xtream account a `get.php?username=&password=` URL names, or null. Secret. */
    val derivedAccount: XtreamAccount? get() = accountFromUrl(reference.url)

    override suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T> {
        val url = parseUrl(reference.url) ?: return SourceFetchResult.Failed(SourceError.Other("The playlist URL is not a valid http(s) URL"))
        val conditional = previous?.etag != null || previous?.lastModified != null
        val request = Request.Builder().url(url).get().apply {
            previous?.etag?.let { header("If-None-Match", it) }
            previous?.lastModified?.let { header("If-Modified-Since", it) }
            userAgent?.let { header("User-Agent", it) }
        }.build()

        val response = try {
            execute(request)
        } catch (e: IOException) {
            return SourceFetchResult.Failed(networkError(e))
        }
        return withContext(ioDispatcher) {
            response.use { handle(it, previous, conditional, consume) }
        }
    }

    private suspend fun <T> handle(
        response: Response,
        previous: SourceVersion?,
        conditional: Boolean,
        consume: suspend (InputStream) -> T,
    ): SourceFetchResult<T> {
        val code = response.code
        if (code == 304) {
            if (!conditional || previous == null) {
                return SourceFetchResult.Failed(SourceError.Other("The server answered 304 to an unconditional request", code))
            }
            return SourceFetchResult.Unchanged(
                previous.copy(
                    etag = response.header("ETag") ?: previous.etag,
                    lastModified = response.header("Last-Modified") ?: previous.lastModified,
                ),
            )
        }
        if (!response.isSuccessful) return SourceFetchResult.Failed(classifyHttpError(code))
        if (looksLikeHtml(response)) return SourceFetchResult.Failed(SourceError.NotAPlaylist())

        val digest = MessageDigest.getInstance("SHA-256")
        val stream = DigestInputStream(response.body.byteStream(), digest)
        val value = try {
            consume(stream).also { drain(stream) }
        } catch (e: SourceConsumerException) {
            return SourceFetchResult.Failed(e.error)
        } catch (e: IOException) {
            return SourceFetchResult.Failed(networkError(e))
        }
        val lastModified = response.header("Last-Modified")
        val version = SourceVersion(
            md5 = HASH_PREFIX + digest.digest().toHex(),
            etag = response.header("ETag"),
            lastModified = lastModified,
            modifiedTime = lastModified?.let(::parseHttpDate),
            size = response.body.contentLength().takeIf { it >= 0 },
        )
        if (previous?.md5 != null && previous.md5 == version.md5) return SourceFetchResult.Unchanged(version)
        return SourceFetchResult.Fetched(value, version)
    }

    private fun classifyHttpError(code: Int): SourceError = when (code) {
        404, 410 -> SourceError.NotFoundOrNotShared("Playlist URL not found (HTTP $code)")
        401, 403 -> SourceError.Unauthorized("The server refused the playlist URL (HTTP $code): check the credentials or the account")
        429 -> SourceError.QuotaExceeded("The server is rate limiting requests (HTTP 429)")
        else -> SourceError.Other("Playlist download failed (HTTP $code)", code)
    }

    /** Best effort, so the hash covers the whole body when the consumer stopped early. */
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

    override fun toString(): String = "M3uUrlSource(***)"

    companion object {
        /** Prefix of the content hash stored in [SourceVersion.md5] (distinguishes it from a Drive md5). */
        const val HASH_PREFIX: String = "sha256:"
        private const val HTML_SNIFF_BYTES = 4L * 1024

        /** The Xtream account named by a `get.php`/`player_api.php` URL with credentials, or null. */
        fun accountFromUrl(url: String): XtreamAccount? = AccountExtractor.fromUrl(url)

        /** True when [url] is an absolute http(s) URL. */
        fun isValidUrl(url: String): Boolean = parseUrl(url) != null

        private fun parseUrl(url: String): HttpUrl? =
            url.trim().toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }

        private fun parseHttpDate(value: String): Long? = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
