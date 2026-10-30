package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.network.xtream.XtreamException
import app.playlistplayer.core.parsing.xmltv.XtreamEpgDecoder
import app.playlistplayer.core.parsing.xmltv.XtreamEpgProgramme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DateTimeException
import java.time.ZoneId

/** One now/next programme for display. [programmeId] is the Room row id (null for short-EPG data). */
data class GuideProgramme(
    val programmeId: Long?,
    val title: String?,
    val description: String?,
    val startMillis: Long,
    val stopMillis: Long,
)

data class NowNext(
    val now: GuideProgramme?,
    val next: GuideProgramme?,
    val source: Source,
) {
    enum class Source { ROOM, SHORT_EPG, NONE }

    companion object {
        val NONE: NowNext = NowNext(null, null, Source.NONE)
    }
}

/**
 * Now/next for a channel: from the imported guide in Room, or — for channels with no programmes
 * there (no guide match, or the import has not run yet) — from Xtream `get_short_epg`, decoded on
 * demand in the server timezone and cached in memory for [cacheTtlMillis] (and never past the end
 * of the cached "now" programme).
 */
class NowNextRepository(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val epgStore: EpgStore,
    private val accounts: suspend (playlistId: Long) -> XtreamAccount?,
    private val xtreamClient: XtreamClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val maxCacheEntries: Int = 256,
) {
    private class CacheEntry(val programmes: List<XtreamEpgProgramme>, val expiresAt: Long)

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Pair<Long, Long>, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, CacheEntry>?): Boolean = size > maxCacheEntries
    }

    suspend fun nowNext(playlistId: Long, channelOrdinal: Int): NowNext {
        val now = clock()
        epgStore.channelKey(playlistId, channelOrdinal)?.let { key ->
            val (current, next) = epgStore.nowNext(playlistId, key, now)
            if (current != null || next != null) return NowNext(current?.toGuide(), next?.toGuide(), NowNext.Source.ROOM)
        }
        return shortEpg(playlistId, channelOrdinal, now)
    }

    private suspend fun shortEpg(playlistId: Long, channelOrdinal: Int, now: Long): NowNext {
        val channel = catalogStore.channelWithLiveInfo(playlistId, channelOrdinal) ?: return NowNext.NONE
        val streamId = channel.streamId ?: return NowNext.NONE
        if (channel.isHeader || playlistStore.playlist(playlistId)?.xtreamEnabled != true) return NowNext.NONE
        val programmes = cachedOrFetch(playlistId, streamId, now) ?: return NowNext.NONE
        val (current, next) = EpgStore.pickNowNext(programmes, now, { it.startMillis }, { it.stopMillis })
        if (current == null && next == null) return NowNext.NONE
        return NowNext(current?.toGuide(), next?.toGuide(), NowNext.Source.SHORT_EPG)
    }

    private suspend fun cachedOrFetch(playlistId: Long, streamId: Long, now: Long): List<XtreamEpgProgramme>? {
        val key = playlistId to streamId
        mutex.withLock { cache[key]?.takeIf { it.expiresAt > now }?.let { return it.programmes } }
        val account = accounts(playlistId) ?: return null
        val zone = catalogStore.accountStatus(playlistId)?.serverTimezone?.let {
            try {
                ZoneId.of(it)
            } catch (_: DateTimeException) {
                null
            }
        } ?: return null
        val programmes = try {
            XtreamEpgDecoder.decode(xtreamClient.shortEpg(account, streamId), zone).programmes
        } catch (e: XtreamException) {
            logger.w("Short EPG for stream $streamId failed: ${e.message}")
            return null
        }
        val airingStop = programmes.firstOrNull { it.isAiringAt(now) }?.stopMillis
        val expiresAt = minOf(now + cacheTtlMillis, airingStop ?: Long.MAX_VALUE)
        mutex.withLock { cache[key] = CacheEntry(programmes, expiresAt) }
        return programmes
    }

    /** Drops cached short-EPG data (e.g. after an import committed). */
    suspend fun clearCache() = mutex.withLock { cache.clear() }

    private fun ProgrammeEntity.toGuide() = GuideProgramme(id, title, description, startMillis, stopMillis)

    private fun XtreamEpgProgramme.toGuide() = GuideProgramme(null, title, description, startMillis, stopMillis)

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 5 * 60 * 1000L
    }
}
