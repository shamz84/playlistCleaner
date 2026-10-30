package app.playlistplayer.core.network.epg

import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.Redactor
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Why an XMLTV download failed. Messages never contain the URL (the provider URL carries the
 * account credentials; an override URL may carry a token).
 */
sealed class XmltvDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    abstract val retryable: Boolean

    class Network(message: String, cause: Throwable? = null) : XmltvDownloadException(message, cause) {
        override val retryable: Boolean get() = true
    }

    class Http(val code: Int) : XmltvDownloadException("EPG download HTTP $code") {
        override val retryable: Boolean get() = code >= 500 || code == 429 || code == 408
    }

    class InvalidUrl : XmltvDownloadException("EPG URL is not a valid http(s) URL") {
        override val retryable: Boolean get() = false
    }
}

/**
 * Streams an XMLTV document (provider `xmltv.php` or an override URL) into a consumer without
 * buffering it: the 86 MB provider document is read straight from the socket.
 *
 * OkHttp adds `Accept-Encoding: gzip` and transparently decompresses a `Content-Encoding: gzip`
 * body; a `.xml.gz` served as `application/octet-stream` arrives compressed and is detected by the
 * parser's magic-byte sniff (`GzipSniffer`). The content type is not checked: an HTML or JSON
 * error page fails XML parsing instead.
 */
class XmltvDownloader(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Downloads [url] and passes the body stream to [consume] (on the IO dispatcher; blocking reads
     * are fine). The stream is closed afterwards. A read failure inside [consume] propagates as the
     * consumer's own exception; callers map it.
     *
     * @throws XmltvDownloadException for connection failures and non-2xx statuses.
     */
    suspend fun <T> download(url: HttpUrl, consume: (InputStream) -> T): T {
        val response = execute(url)
        return withContext(ioDispatcher) {
            response.use {
                if (!it.isSuccessful) throw XmltvDownloadException.Http(it.code)
                it.body.byteStream().use(consume)
            }
        }
    }

    /** Same as [download] for a user-entered URL string. */
    suspend fun <T> download(url: String, consume: (InputStream) -> T): T {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw XmltvDownloadException.InvalidUrl()
        return download(parsed, consume)
    }

    private suspend fun execute(url: HttpUrl): Response {
        val request = Request.Builder().url(url).get().header("Accept", "application/xml, text/xml, */*").build()
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
            throw XmltvDownloadException.Network("EPG download failed: " + Redactor.redact(e), Redactor.redacted(e))
        }
    }

    companion object {
        /** The provider's full guide: `<base>/xmltv.php?username=…&password=…`. Secret: never log it. */
        fun providerUrl(account: XtreamAccount): HttpUrl? =
            account.baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.encodedPath("/")
                ?.addPathSegment("xmltv.php")
                ?.addQueryParameter("username", account.username)
                ?.addQueryParameter("password", account.password)
                ?.build()
    }
}
