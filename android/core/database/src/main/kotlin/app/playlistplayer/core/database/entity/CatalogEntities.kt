package app.playlistplayer.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Xtream catalog tables (U4). Bulk tables carry a `generation` and are read only through
// `catalog_sync_state.activeGeneration`, so a catalog refresh swaps atomically. Like every other
// table, they store ids and display attributes only: never stream URLs, hosts or credentials.

/**
 * Per-playlist catalog bookkeeping, separate from [SyncStateEntity] so the playlist and the
 * catalog swap independently.
 *
 * - [activeGeneration]/[pendingGeneration]/[lastGeneration]: same scheme as the playlist's.
 * - [accountExpired]: the last account check reported `auth: 0` or a non-Active status; the
 *   catalog sync stopped before any catalog call. Cleared by the next successful check.
 * - [lastFailureKind]/[lastFailureMessage]: latest failure since the last success (redacted).
 * - Counts describe the active generation.
 */
@Entity(tableName = "catalog_sync_state")
data class CatalogSyncStateEntity(
    @PrimaryKey val playlistId: Long,
    val activeGeneration: Long? = null,
    val pendingGeneration: Long? = null,
    val lastGeneration: Long = 0,
    val contentUpdatedAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastFailureKind: String? = null,
    val lastFailureMessage: String? = null,
    val accountExpired: Boolean = false,
    val liveInfoCount: Int = 0,
    val liveMissingCount: Int = 0,
    val vodCategoryCount: Int = 0,
    val vodCount: Int = 0,
    val seriesCategoryCount: Int = 0,
    val seriesCount: Int = 0,
)

/**
 * The latest `player_api.php` `user_info`/`server_info` for a playlist. Not generational: it is
 * overwritten on every check.
 *
 * @property status provider status (`Active`, `Expired`, `Banned`, `Disabled`), or `AuthFailed`
 *   when the panel answered `auth: 0` / HTTP 401.
 * @property expDate expiry, epoch millis; null = no expiry.
 * @property allowedFormats comma-separated `allowed_output_formats` (e.g. `m3u8,ts,rtmp`).
 * @property serverTimezone `server_info.timezone` (IANA id), used for catch-up and API EPG times.
 */
@Entity(tableName = "account_status")
data class AccountStatusEntity(
    @PrimaryKey val playlistId: Long,
    val status: String?,
    val isActive: Boolean,
    val expDate: Long?,
    val maxConnections: Int?,
    val activeCons: Int?,
    val allowedFormats: String,
    val serverTimezone: String?,
    val isTrial: Boolean?,
    val updatedAt: Long,
) {
    val allowedFormatList: List<String> get() = allowedFormats.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val STATUS_AUTH_FAILED: String = "AuthFailed"
    }
}

/**
 * `get_live_streams` metadata for stream ids that the playlist lists. Joined to channels by
 * stream id; playlist channels with no row here (merged 24/7 channels) stay playable without
 * archive or EPG enrichment.
 */
@Entity(
    tableName = "live_stream_info",
    primaryKeys = ["playlistId", "generation", "streamId"],
    indices = [Index(value = ["playlistId", "generation", "epgChannelId"])],
)
data class LiveStreamInfoEntity(
    val playlistId: Long,
    val generation: Long,
    val streamId: Long,
    val tvArchive: Boolean,
    val archiveDays: Int,
    val epgChannelId: String?,
    val categoryId: String?,
)

@Entity(tableName = "vod_category", primaryKeys = ["playlistId", "generation", "categoryId"])
data class VodCategoryEntity(
    val playlistId: Long,
    val generation: Long,
    val categoryId: String,
    val name: String,
    val parentId: Int?,
    /** Provider order (0-based). */
    val ordinal: Int,
)

/**
 * One movie version (get_vod_streams item). Several rows may form one title (U5 groups them).
 * @property added epoch seconds.
 * @property tmdb normalised tmdb id (null when absent/0).
 * @property providerOrder 0-based position in the provider's list.
 */
@Entity(
    tableName = "vod_stream",
    primaryKeys = ["playlistId", "generation", "streamId"],
    indices = [
        Index(value = ["playlistId", "generation", "categoryId", "providerOrder"]),
        Index(value = ["playlistId", "generation", "tmdb"]),
        Index(value = ["playlistId", "generation", "providerOrder"]),
    ],
)
data class VodStreamEntity(
    val playlistId: Long,
    val generation: Long,
    val streamId: Long,
    val name: String,
    val icon: String?,
    val rating: Double?,
    val added: Long?,
    val categoryId: String?,
    val containerExtension: String?,
    val tmdb: String?,
    val isAdult: Boolean,
    val providerOrder: Int,
)

@Entity(tableName = "series_category", primaryKeys = ["playlistId", "generation", "categoryId"])
data class SeriesCategoryEntity(
    val playlistId: Long,
    val generation: Long,
    val categoryId: String,
    val name: String,
    val parentId: Int?,
    val ordinal: Int,
)

/**
 * One series (get_series item).
 * @property lastModified provider `last_modified`, epoch seconds; a newer value invalidates the
 *   cached series info.
 */
@Entity(
    tableName = "series",
    primaryKeys = ["playlistId", "generation", "seriesId"],
    indices = [
        Index(value = ["playlistId", "generation", "categoryId", "providerOrder"]),
        Index(value = ["playlistId", "generation", "tmdb"]),
        Index(value = ["playlistId", "generation", "providerOrder"]),
    ],
)
data class SeriesEntity(
    val playlistId: Long,
    val generation: Long,
    val seriesId: Long,
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val categoryId: String?,
    val tmdb: String?,
    val lastModified: Long?,
    val providerOrder: Int,
)

/** On-demand cache of get_series_info details (not generational; refreshed by age/last_modified). */
@Entity(tableName = "series_info_cache", primaryKeys = ["playlistId", "seriesId"])
data class SeriesInfoCacheEntity(
    val playlistId: Long,
    val seriesId: Long,
    val name: String?,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val tmdb: String?,
    val episodeRunTimeMinutes: Int?,
    val youtubeTrailer: String?,
    val fetchedAt: Long,
)

/** Season metadata from get_series_info `seasons[]` (names such as "Specials", covers). */
@Entity(tableName = "season", primaryKeys = ["playlistId", "seriesId", "seasonNumber"])
data class SeasonEntity(
    val playlistId: Long,
    val seriesId: Long,
    val seasonNumber: Int,
    val name: String?,
    val episodeCount: Int?,
    val airDate: String?,
    val cover: String?,
)

/**
 * One episode from get_series_info. [episodeId] is the episode's stream id for the
 * `/series/<u>/<p>/<id>.<ext>` URL, which is rebuilt at play time. [sortOrder] is the position in
 * season-then-episode order.
 */
@Entity(
    tableName = "episode",
    primaryKeys = ["playlistId", "seriesId", "episodeId"],
    indices = [Index(value = ["playlistId", "seriesId", "sortOrder"])],
)
data class EpisodeEntity(
    val playlistId: Long,
    val seriesId: Long,
    val season: Int,
    val episodeNum: Int?,
    val episodeId: String,
    val title: String?,
    val containerExtension: String?,
    val durationSecs: Long?,
    val plot: String?,
    val image: String?,
    val airDate: String?,
    val rating: Double?,
    val sortOrder: Int,
)

/** On-demand cache of get_vod_info details for one movie version. */
@Entity(tableName = "vod_info_cache", primaryKeys = ["playlistId", "streamId"])
data class VodInfoCacheEntity(
    val playlistId: Long,
    val streamId: Long,
    val name: String?,
    val originalName: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val country: String?,
    val releaseDate: String?,
    val cover: String?,
    val backdrop: String?,
    val durationSecs: Long?,
    val rating: Double?,
    val tmdb: String?,
    val youtubeTrailer: String?,
    val containerExtension: String?,
    val fetchedAt: Long,
)

/**
 * A playlist channel (active playlist generation) with its `get_live_streams` metadata from the
 * active catalog generation, if any. [catalogGeneration] is null until a catalog has synced;
 * [tvArchive] is null when the stream id is absent from `get_live_streams`.
 */
data class ChannelLiveInfo(
    val playlistId: Long,
    val ordinal: Int,
    val name: String,
    val streamId: Long?,
    val tvgId: String?,
    val catchupDays: Int,
    val isHeader: Boolean,
    val catalogGeneration: Long?,
    val tvArchive: Boolean?,
    val archiveDays: Int?,
    val epgChannelId: String?,
) {
    /** True when the catalog lists this stream id. */
    val inCatalog: Boolean get() = tvArchive != null

    /**
     * Archive window in days. With a synced catalog the API `tv_archive` is the authority, and a
     * stream id absent from it has no archive; before the first catalog sync the playlist's
     * `catchup-days` applies.
     */
    val effectiveArchiveDays: Int
        get() = when {
            isHeader -> 0
            catalogGeneration == null -> catchupDays
            tvArchive == true -> archiveDays ?: 0
            else -> 0
        }
}
