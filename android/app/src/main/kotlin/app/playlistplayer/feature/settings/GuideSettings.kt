package app.playlistplayer.feature.settings

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.entity.epgEntryKey
import app.playlistplayer.core.network.epg.XmltvDownloadException
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.parsing.xmltv.EpgChannelMatcher
import app.playlistplayer.core.parsing.xmltv.EpgPlaylistChannel
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import app.playlistplayer.core.parsing.xmltv.XmltvChannel
import app.playlistplayer.core.parsing.xmltv.XmltvHandler
import app.playlistplayer.core.parsing.xmltv.XmltvParseException
import app.playlistplayer.core.parsing.xmltv.XmltvParseOptions
import app.playlistplayer.core.parsing.xmltv.XmltvParser
import app.playlistplayer.core.parsing.xmltv.XmltvProgramme
import app.playlistplayer.core.sync.epg.EpgOverrideSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** Guide import status shown under Settings → Guide (from `EpgStore.state`). */
data class GuideStatus(
    val overrideSet: Boolean,
    /** `override`, `provider` or `merged` as recorded by the import; null before the first import. */
    val activeSource: String?,
    val lastImportAt: Long?,
    val lastErrorKind: String?,
    val matchedCount: Int,
    val totalCount: Int,
    val matchRate: Double,
    val overrideMatchedCount: Int,
    val programmeCount: Int,
)

/** Settings → Guide persistence: the per-playlist XMLTV override (R6). */
interface GuideSettingsStore {
    /** The stored override URL (secret; pre-fill only, never log). */
    suspend fun overrideUrl(playlistId: Long): String?

    /** Saves (or clears with null/blank) the override and queues a re-import. False for an invalid URL. */
    suspend fun setOverride(playlistId: Long, url: String?): Boolean

    fun observeStatus(playlistId: Long): Flow<GuideStatus?>
}

/** [GuideSettingsStore] over U7's [EpgOverrideSettings] and [EpgStore]. */
class EpgGuideSettingsStore(
    private val overrides: EpgOverrideSettings,
    private val epgStore: EpgStore,
) : GuideSettingsStore {
    override suspend fun overrideUrl(playlistId: Long): String? = overrides.overrideUrl(playlistId)

    override suspend fun setOverride(playlistId: Long, url: String?): Boolean =
        overrides.setOverride(playlistId, url) == EpgOverrideSettings.Result.Saved

    override fun observeStatus(playlistId: Long): Flow<GuideStatus?> = epgStore.observeState(playlistId).map { s ->
        s?.let {
            GuideStatus(
                overrideSet = it.overrideUrlPresent,
                activeSource = it.activeSource,
                lastImportAt = it.lastImportAt,
                lastErrorKind = it.lastErrorKind,
                matchedCount = it.matchedCount,
                totalCount = it.totalCount,
                matchRate = it.matchRate,
                overrideMatchedCount = it.overrideMatchedCount,
                programmeCount = it.programmeCount,
            )
        }
    }
}

/** Outcome of the "Test" action on an override URL. Never contains the URL. */
sealed interface OverrideTestResult {
    /**
     * The URL serves XMLTV. [matched]/[total] is the preview match rate against the playlist's
     * channels, computed from the `<channel>` list that precedes the programmes. [partial] is true
     * when the byte budget ran out before the channel list ended (the rate is a lower bound).
     */
    data class Reachable(val channels: Int, val matched: Int, val total: Int, val partial: Boolean) : OverrideTestResult {
        val matchRate: Double get() = if (total == 0) 0.0 else matched.toDouble() / total
    }

    data object InvalidUrl : OverrideTestResult

    /** The server answered with a non-2xx status (e.g. 404). */
    data class HttpError(val code: Int) : OverrideTestResult

    /** Connection failure (DNS, TLS, timeout). */
    data object Unreachable : OverrideTestResult

    /** Downloaded, but it is not an XMLTV document (HTML error page, JSON, garbage). */
    data object NotXmltv : OverrideTestResult
}

/** The "Test" action: checks an override URL without saving it. */
fun interface EpgOverrideTester {
    suspend fun test(playlistId: Long, url: String): OverrideTestResult
}

/**
 * Downloads at most [maxBytes] of the document and reads its `<channel>` list (XMLTV puts all
 * channels before the programmes, so the download stops at the first `<programme>`), then
 * matches the channels against the playlist exactly like the import does ([EpgChannelMatcher]).
 */
class XmltvOverrideTester(
    private val downloader: XmltvDownloader,
    private val pullFactory: XmlPullSourceFactory,
    private val playlistChannels: suspend (playlistId: Long) -> List<EpgPlaylistChannel>,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : EpgOverrideTester {

    override suspend fun test(playlistId: Long, url: String): OverrideTestResult {
        val parsed = url.trim().toHttpUrlOrNull() ?: return OverrideTestResult.InvalidUrl
        if (parsed.scheme != "http" && parsed.scheme != "https") return OverrideTestResult.InvalidUrl
        val entries = playlistChannels(playlistId)
        val scan: Scan? = try {
            downloader.download(parsed) { input -> scanChannels(input) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: XmltvDownloadException.Http) {
            return OverrideTestResult.HttpError(e.code)
        } catch (_: XmltvDownloadException.InvalidUrl) {
            return OverrideTestResult.InvalidUrl
        } catch (_: IOException) {
            return OverrideTestResult.Unreachable
        }
        if (scan == null) return OverrideTestResult.NotXmltv
        val match = EpgChannelMatcher.match(entries, scan.channels)
        return OverrideTestResult.Reachable(scan.channels.size, match.matchedEntries, match.totalEntries, scan.partial)
    }

    private class Scan(val channels: List<XmltvChannel>, val partial: Boolean)

    /** Stops parsing at the first programme. */
    private class StopScan : RuntimeException(null, null, false, false)

    /** Null when the document is not XMLTV. */
    private fun scanChannels(input: InputStream): Scan? {
        val limited = LimitedInputStream(input, maxBytes)
        val channels = ArrayList<XmltvChannel>()
        val handler = object : XmltvHandler {
            override fun onChannel(channel: XmltvChannel) {
                channels += channel
            }

            override fun onChannelsComplete(): Set<String>? = throw StopScan()

            override fun onProgrammes(batch: List<XmltvProgramme>) = Unit
        }
        return try {
            XmltvParser(pullFactory).parse(limited, XmltvParseOptions(wantedChannelIds = null), handler)
            Scan(channels, partial = false) // a document with no programmes
        } catch (_: StopScan) {
            Scan(channels, partial = false)
        } catch (e: XmltvParseException) {
            // Hitting the byte budget truncates the document: keep what was read if it looked like XMLTV.
            if (limited.exhausted && channels.isNotEmpty()) return Scan(channels, partial = true)
            // A dropped connection mid-body is a reachability problem, not a format problem.
            ioCause(e)?.let { throw it }
            null
        }
    }

    private fun ioCause(e: Throwable): IOException? {
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is IOException) return cause
            cause = cause.cause
        }
        return null
    }

    /** Reports end-of-stream after [limit] bytes and records that it did. */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        var exhausted = false
            private set

        override fun read(): Int {
            if (count >= limit) {
                exhausted = true
                return -1
            }
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (count >= limit) {
                exhausted = true
                return -1
            }
            val n = super.read(b, off, minOf(len.toLong(), limit - count).toInt())
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, limit - count).coerceAtLeast(0))
            count += skipped
            return skipped
        }
    }

    companion object {
        /** Byte budget for the test download (compressed bytes when the body is gzip). */
        const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024

        /** The playlist's channels as the guide import sees them (headers excluded). */
        fun channelsFrom(catalogStore: CatalogStore): suspend (Long) -> List<EpgPlaylistChannel> = { playlistId ->
            catalogStore.channelsWithLiveInfo(playlistId)
                .filter { !it.isHeader }
                .map { EpgPlaylistChannel(epgEntryKey(it.streamId, it.ordinal), it.tvgId, it.epgChannelId, it.name) }
                .distinctBy { it.key }
        }
    }
}
