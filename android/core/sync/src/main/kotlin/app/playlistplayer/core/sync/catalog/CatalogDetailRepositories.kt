package app.playlistplayer.core.sync.catalog

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.SeasonEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.network.xtream.XtreamException
import app.playlistplayer.core.network.xtream.XtreamSeriesInfo
import app.playlistplayer.core.network.xtream.XtreamVodInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of an on-demand detail load (series info, VOD info). */
sealed interface DetailResult<out T> {
    /**
     * @property fromCache served from Room without a network call.
     * @property stale a refresh was wanted but failed; [value] is the older cached copy.
     */
    data class Loaded<T>(val value: T, val fromCache: Boolean, val stale: Boolean = false) : DetailResult<T>

    /** Nothing cached and the fetch failed. [message] is redacted. */
    data class Failed(val kind: String, val message: String, val retryable: Boolean) : DetailResult<Nothing>
}

/** Cached get_series_info for one series: details, season metadata and ordered episodes. */
data class SeriesDetails(
    val info: SeriesInfoCacheEntity,
    val seasons: List<SeasonEntity>,
    /** Season then episode order (seasons sorted numerically). */
    val episodes: List<EpisodeEntity>,
) {
    /** Season numbers that actually have episodes, ascending. */
    val seasonNumbers: List<Int> get() = episodes.map { it.season }.distinct()
}

/**
 * Fetches get_series_info when a series is opened and caches it in Room. A cached copy is reused
 * while younger than [ttlMillis] and not older than the catalog's `last_modified` for the series;
 * when a refresh fails the cached copy is returned as stale.
 */
class SeriesInfoRepository(
    private val catalogStore: CatalogStore,
    private val accounts: suspend (playlistId: Long) -> XtreamAccount?,
    private val client: XtreamClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val logger: RedactingLogger = RedactingLogger.NONE,
) {
    private val mutex = Mutex()

    suspend fun cached(playlistId: Long, seriesId: Long): SeriesDetails? {
        val info = catalogStore.seriesInfo(playlistId, seriesId) ?: return null
        return SeriesDetails(info, catalogStore.seasons(playlistId, seriesId), catalogStore.episodes(playlistId, seriesId))
    }

    suspend fun load(playlistId: Long, seriesId: Long, forceRefresh: Boolean = false): DetailResult<SeriesDetails> = mutex.withLock {
        val now = clock()
        val cached = cached(playlistId, seriesId)
        if (cached != null && !forceRefresh && isFresh(playlistId, seriesId, cached.info.fetchedAt, now)) {
            return DetailResult.Loaded(cached, fromCache = true)
        }
        val account = accounts(playlistId)
            ?: return cached?.let { DetailResult.Loaded(it, fromCache = true, stale = true) }
                ?: DetailResult.Failed(CatalogSyncer.KIND_NO_ACCOUNT, "No Xtream account stored for this playlist", retryable = false)
        val remote = try {
            client.seriesInfo(account, seriesId)
        } catch (e: XtreamException) {
            logger.w("Series info $seriesId for playlist $playlistId failed", e)
            return cached?.let { DetailResult.Loaded(it, fromCache = true, stale = true) }
                ?: DetailResult.Failed(CatalogSyncer.kindOf(e), e.message.orEmpty(), e.retryable)
        }
        val details = remote.toDetails(playlistId, seriesId, account, now)
        catalogStore.putSeriesInfo(details.info, details.seasons, details.episodes)
        DetailResult.Loaded(details, fromCache = false)
    }

    private suspend fun isFresh(playlistId: Long, seriesId: Long, fetchedAt: Long, now: Long): Boolean {
        if (now - fetchedAt >= ttlMillis) return false
        val lastModifiedMillis = catalogStore.series(playlistId, seriesId)?.lastModified?.let { it * 1000 } ?: return true
        return lastModifiedMillis <= fetchedAt
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 12L * 60 * 60 * 1000

        internal fun XtreamSeriesInfo.toDetails(playlistId: Long, seriesId: Long, account: XtreamAccount, now: Long): SeriesDetails {
            val cacheRow = SeriesInfoCacheEntity(
                playlistId = playlistId,
                seriesId = seriesId,
                name = info.name,
                cover = SafeImageUrl.filter(info.cover, account),
                backdrop = info.backdropPath.firstNotNullOfOrNull { SafeImageUrl.filter(it, account) },
                plot = info.plot,
                cast = info.cast,
                director = info.director,
                genre = info.genre,
                releaseDate = info.releaseDate,
                rating = info.rating,
                tmdb = info.tmdb,
                episodeRunTimeMinutes = info.episodeRunTimeMinutes,
                youtubeTrailer = info.youtubeTrailer,
                fetchedAt = now,
            )
            val seasonRows = seasons.mapNotNull { s ->
                val number = s.seasonNumber ?: return@mapNotNull null
                SeasonEntity(playlistId, seriesId, number, s.name, s.episodeCount, s.airDate, SafeImageUrl.filter(s.coverBig ?: s.cover, account))
            }.distinctBy { it.seasonNumber }
            val episodeRows = episodes.mapIndexedNotNull { index, e ->
                val id = e.id ?: return@mapIndexedNotNull null
                EpisodeEntity(
                    playlistId = playlistId,
                    seriesId = seriesId,
                    season = e.season ?: 0,
                    episodeNum = e.episodeNum,
                    episodeId = id,
                    title = e.title,
                    containerExtension = e.containerExtension,
                    durationSecs = e.info?.durationSecs,
                    plot = e.info?.plot,
                    image = SafeImageUrl.filter(e.info?.movieImage, account),
                    airDate = e.info?.airDate,
                    rating = e.info?.rating,
                    sortOrder = index,
                )
            }.distinctBy { it.episodeId }
            return SeriesDetails(cacheRow, seasonRows, episodeRows)
        }
    }
}

/**
 * Fetches get_vod_info details (plot, cast, backdrop, duration) for one movie version and caches
 * them. Note get_vod_info does not fill tmdb gaps, so it is never used for consolidation.
 */
class VodInfoRepository(
    private val catalogStore: CatalogStore,
    private val accounts: suspend (playlistId: Long) -> XtreamAccount?,
    private val client: XtreamClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val logger: RedactingLogger = RedactingLogger.NONE,
) {
    private val mutex = Mutex()

    suspend fun cached(playlistId: Long, streamId: Long): VodInfoCacheEntity? = catalogStore.vodInfo(playlistId, streamId)

    suspend fun load(playlistId: Long, streamId: Long, forceRefresh: Boolean = false): DetailResult<VodInfoCacheEntity> = mutex.withLock {
        val now = clock()
        val cached = catalogStore.vodInfo(playlistId, streamId)
        if (cached != null && !forceRefresh && now - cached.fetchedAt < ttlMillis) return DetailResult.Loaded(cached, fromCache = true)
        val account = accounts(playlistId)
            ?: return cached?.let { DetailResult.Loaded(it, fromCache = true, stale = true) }
                ?: DetailResult.Failed(CatalogSyncer.KIND_NO_ACCOUNT, "No Xtream account stored for this playlist", retryable = false)
        val remote = try {
            client.vodInfo(account, streamId)
        } catch (e: XtreamException) {
            logger.w("VOD info $streamId for playlist $playlistId failed", e)
            return cached?.let { DetailResult.Loaded(it, fromCache = true, stale = true) }
                ?: DetailResult.Failed(CatalogSyncer.kindOf(e), e.message.orEmpty(), e.retryable)
        }
        val row = remote.toCacheRow(playlistId, streamId, account, now)
        catalogStore.putVodInfo(row)
        DetailResult.Loaded(row, fromCache = false)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        internal fun XtreamVodInfo.toCacheRow(playlistId: Long, streamId: Long, account: XtreamAccount, now: Long): VodInfoCacheEntity {
            val d = info
            return VodInfoCacheEntity(
                playlistId = playlistId,
                streamId = streamId,
                name = d?.name,
                originalName = d?.originalName,
                plot = d?.effectivePlot,
                cast = d?.effectiveCast,
                director = d?.director,
                genre = d?.genre,
                country = d?.country,
                releaseDate = d?.releaseDate,
                cover = SafeImageUrl.filter(d?.effectiveCover, account),
                backdrop = d?.backdropPath?.firstNotNullOfOrNull { SafeImageUrl.filter(it, account) },
                durationSecs = d?.durationSecs,
                rating = d?.rating,
                tmdb = d?.tmdbId,
                youtubeTrailer = d?.youtubeTrailer,
                containerExtension = movieData?.containerExtension,
                fetchedAt = now,
            )
        }
    }
}
