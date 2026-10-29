package app.playlistplayer.core.network.source

import java.io.IOException
import java.io.InputStream

/**
 * Change-detection fingerprint of a fetched playlist, persisted in SyncState. Each source fills
 * what it has: Drive gives [md5]/[version]/[modifiedTime]; an M3U URL gives [etag]/[lastModified]
 * (or a content hash in [md5]).
 */
data class SourceVersion(
    val md5: String? = null,
    val version: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Source-side modification time (epoch millis), for the staleness warning. */
    val modifiedTime: Long? = null,
    val size: Long? = null,
)

/** Why a fetch failed. Messages are already redacted. */
sealed class SourceError(val kind: String, val message: String) {
    /** Drive API 404: the file id is wrong, or the file is not shared with "anyone with the link". */
    class NotFoundOrNotShared(message: String = "File not found or not shared") : SourceError(KIND_NOT_FOUND, message)

    /** The fallback download returned an HTML/sign-in page instead of the file. */
    class NotShared(message: String = "File is not shared with anyone with the link") : SourceError(KIND_NOT_SHARED, message)

    /** Drive `downloadQuotaExceeded` / `usageLimits` / 429. */
    class QuotaExceeded(message: String = "Download quota exceeded") : SourceError(KIND_QUOTA, message)

    /** Connectivity failure, including a connection dropped mid-download. */
    class Network(message: String, val cause: IOException? = null) : SourceError(KIND_NETWORK, message)

    class Other(message: String, val httpCode: Int? = null, val cause: Throwable? = null) : SourceError(KIND_OTHER, message)

    override fun toString(): String = "SourceError.$kind($message)"

    companion object {
        const val KIND_NOT_FOUND = "NotFoundOrNotShared"
        const val KIND_NOT_SHARED = "NotShared"
        const val KIND_QUOTA = "QuotaExceeded"
        const val KIND_NETWORK = "Network"
        const val KIND_OTHER = "Other"
    }
}

sealed interface SourceFetchResult<out T> {
    /** The source reports the same content as [previous]; nothing was downloaded. */
    data class Unchanged(val version: SourceVersion) : SourceFetchResult<Nothing>

    /** The body was downloaded and handed to the consumer, which produced [value]. */
    data class Fetched<T>(val value: T, val version: SourceVersion) : SourceFetchResult<T>

    data class Failed(val error: SourceError) : SourceFetchResult<Nothing>
}

/**
 * Thrown by a consumer to abort a fetch with its own error (e.g. the body was not an M3U). The
 * source reports it as [SourceFetchResult.Failed] with [error].
 */
class SourceConsumerException(val error: SourceError) : IOException(error.message)

/**
 * A place a playlist M3U is fetched from (Drive file id or M3U URL).
 *
 * [fetch] streams the body to [consume] while the connection is open, so the playlist is never
 * buffered in full. When [previous] matches the source's current fingerprint it returns
 * [SourceFetchResult.Unchanged] without downloading. An [IOException] thrown while [consume]
 * reads the stream (connection drop) becomes [SourceError.Network]; other consumer exceptions
 * propagate unchanged so the caller can clean up.
 */
interface PlaylistSource {
    suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T>
}
