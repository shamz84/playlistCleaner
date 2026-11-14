package app.playlistplayer.feature.vod

import app.playlistplayer.core.network.skip.SkipMarkerSource
import app.playlistplayer.core.network.skip.SkipMarkerType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Real skip spans for an item about to play; empty when unknown (the player falls back to guesses). */
fun interface SkipSpanProvider {
    suspend fun spans(request: PlaybackRequest): List<SkipSpan>
}

/**
 * Looks spans up by the item's TMDB id and caches the answers, empty ones included, so replaying
 * or backing out of an episode never repeats the request. Failures and timeouts are not cached
 * and never block playback.
 */
class SkipSpanRepository(
    private val source: SkipMarkerSource,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) : SkipSpanProvider {

    private data class Key(val tmdbId: Long, val season: Int?, val episode: Int?)

    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Key, List<SkipSpan>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<SkipSpan>>?) = size > maxEntries
    }

    override suspend fun spans(request: PlaybackRequest): List<SkipSpan> {
        val tmdbId = request.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        val isEpisode = request.kind == app.playlistplayer.core.consolidation.VodKind.SERIES
        val season = request.progressKey.season.takeIf { isEpisode }
        val episode = request.progressKey.episode.takeIf { isEpisode }
        if (isEpisode && (season == null || episode == null)) return emptyList()
        val key = Key(tmdbId, season, episode)
        lock.withLock { cache[key] }?.let { return it }
        val spans = try {
            withTimeout(timeoutMs) { source.markers(tmdbId, season, episode) }
        } catch (e: TimeoutCancellationException) {
            return emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return emptyList()
        }.mapNotNull { m ->
            val kind = when (m.type) {
                SkipMarkerType.INTRO -> SkipKind.INTRO
                SkipMarkerType.RECAP -> SkipKind.RECAP
                SkipMarkerType.CREDITS -> SkipKind.CREDITS
            }
            SkipSpan(kind, m.startMs ?: 0L, m.endMs)
        }
        lock.withLock { cache[key] = spans }
        return spans
    }

    companion object {
        const val TIMEOUT_MS: Long = 4_000
        const val MAX_ENTRIES: Int = 200
    }
}
