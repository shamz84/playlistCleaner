package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodVersion
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.url.StreamAccount
import app.playlistplayer.core.player.url.StreamUrlBuilder

/**
 * What the VOD player needs to start: [sources] (chosen version first, then the other versions
 * in comparator order for failover), the progress identity and the resume offer. Built on
 * demand at play time; never part of a UI state. [toString] lists ids and labels only
 * ([PlayableSource.toString] omits the URL).
 *
 * @property startPositionMs start here directly (the user chose Resume on the detail screen).
 * @property resumeOfferMs ask Resume / Start over in the player before starting.
 */
data class PlaybackRequest(
    val sources: List<PlayableSource>,
    val progressKey: ProgressKey,
    val playlistId: Long,
    val kind: VodKind,
    val label: String,
    val startPositionMs: Long? = null,
    val resumeOfferMs: Long? = null,
    /** Title of the following episode, when there is one (drives the next-episode card). */
    val nextEpisodeLabel: String? = null,
    /**
     * Version id (`VodVersion` item id, the [TitlePickStore] value) of each source, keyed by
     * [PlayableSource.id]: a version chosen in the player is persisted as the title's pick.
     */
    val versionIds: Map<String, String> = emptyMap(),
    /** TMDB id of the movie or show, when the playlist provides one (looks up skip markers). */
    val tmdbId: Long? = null,
)

/** Why a play action could not build a request. */
enum class PlayError { NO_ACCOUNT, INVALID_STREAM }

/** Builds [PlayableSource]s from versions; the account is used and dropped, never stored. */
internal object VodSources {

    const val DEFAULT_CONTAINER = "mp4"

    fun movie(account: StreamAccount, playlistId: Long, version: VodVersion, meta: ItemMeta?): PlayableSource =
        PlayableSource(
            id = "movie:${version.item.id}",
            url = StreamUrlBuilder(account).movie(version.item.id, extensionOf(meta?.containerExtension)),
            label = version.item.name,
            isLive = false,
            versionTier = version.version.quality,
            accountKey = playlistId.toString(),
        )

    fun episode(account: StreamAccount, playlistId: Long, sourceName: String, version: EpisodeVersion): PlayableSource =
        PlayableSource(
            id = "episode:${version.episode.episodeId}",
            url = StreamUrlBuilder(account).seriesEpisode(version.episode.episodeId, extensionOf(version.episode.containerExtension)),
            label = sourceName,
            isLive = false,
            versionTier = version.series.version.quality,
            accountKey = playlistId.toString(),
        )

    private fun extensionOf(raw: String?): String =
        raw?.trim()?.removePrefix(".")?.takeIf { it.isNotEmpty() && EXTENSION.matches(it) } ?: DEFAULT_CONTAINER

    private val EXTENSION = Regex("[A-Za-z0-9]{1,10}")
}
