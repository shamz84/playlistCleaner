package app.playlistplayer.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.playlistplayer.core.model.Channel
import app.playlistplayer.core.model.ChannelGroup
import app.playlistplayer.core.model.Playlist
import app.playlistplayer.core.model.PlaylistSourceType

/**
 * A playlist registry row. The source reference (Drive file id / M3U URL) and the derived Xtream
 * account are secrets and live only in SecureStore, never here.
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,
    val xtreamEnabled: Boolean,
    val createdAt: Long,
) {
    fun toModel(): Playlist = Playlist(id, name, PlaylistSourceType.valueOf(sourceType), xtreamEnabled, createdAt)
}

/**
 * Group rows are written under a [generation]; readers only see the generation that
 * [SyncStateEntity.activeGeneration] points at.
 */
@Entity(
    tableName = "channel_group",
    primaryKeys = ["playlistId", "generation", "ordinal"],
)
data class ChannelGroupEntity(
    val playlistId: Long,
    val generation: Long,
    val ordinal: Int,
    val name: String,
    val channelCount: Int,
) {
    fun toModel(): ChannelGroup = ChannelGroup(playlistId, ordinal, name, channelCount)
}

/**
 * One playlist entry. Stores the stream id and attributes only: never the stream URL, which
 * embeds account credentials.
 */
@Entity(
    tableName = "channel",
    primaryKeys = ["playlistId", "generation", "ordinal"],
    indices = [
        Index(value = ["playlistId", "generation", "groupOrdinal", "ordinal"]),
        Index(value = ["playlistId", "generation", "tvgId"]),
        Index(value = ["playlistId", "generation", "streamId"]),
    ],
)
data class ChannelEntity(
    val playlistId: Long,
    val generation: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val name: String,
    val streamId: Long?,
    val containerExtension: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val cuid: String?,
    val tvgChno: String?,
    val catchupType: String?,
    val catchupDays: Int,
    val isHeader: Boolean,
)

/** A channel row joined with its group name. */
data class ChannelWithGroup(
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupTitle: String,
    val name: String,
    val streamId: Long?,
    val containerExtension: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val cuid: String?,
    val tvgChno: String?,
    val catchupType: String?,
    val catchupDays: Int,
    val isHeader: Boolean,
) {
    fun toModel(): Channel = Channel(
        playlistId = playlistId,
        ordinal = ordinal,
        groupOrdinal = groupOrdinal,
        groupTitle = groupTitle,
        name = name,
        streamId = streamId,
        containerExtension = containerExtension,
        tvgId = tvgId,
        tvgName = tvgName,
        logo = logo,
        cuid = cuid,
        tvgChno = tvgChno,
        catchupType = catchupType,
        catchupDays = catchupDays,
        isHeader = isHeader,
    )
}

/**
 * Per-playlist sync bookkeeping.
 *
 * - [activeGeneration]: the generation readers see (null before the first successful sync).
 * - [pendingGeneration]: a generation being written; non-null after a crash mid-write, and
 *   cleaned up by the next sync.
 * - [lastGeneration]: highest generation id ever allocated (generation ids never repeat).
 * - [contentUpdatedAt]: when the active generation was written ("using cached copy from <date>").
 * - [lastSuccessAt]: last successful sync check (download or unchanged).
 * - [lastFailureAt]/[lastFailureKind]/[lastFailureMessage]: the latest failure since the last
 *   success (message redacted); cleared on success.
 * - [lastWarning]/[lastWarningAt]: non-fatal problems, e.g. a refresh rejected for a channel drop.
 * - [sourceMd5]/[sourceVersion]/[sourceEtag]/[sourceLastModified]/[sourceModifiedTime]: the
 *   fingerprint of the active content, used to skip unchanged downloads; [sourceModifiedTime]
 *   also drives the staleness warning.
 * - [catalogStale]/[epgStale]: the Xtream catalog / EPG must be re-synced (first sync or
 *   credentials changed).
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
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
    val lastWarning: String? = null,
    val lastWarningAt: Long? = null,
    val sourceMd5: String? = null,
    val sourceVersion: String? = null,
    val sourceEtag: String? = null,
    val sourceLastModified: String? = null,
    val sourceModifiedTime: Long? = null,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val mixedAccounts: Boolean = false,
    val catalogStale: Boolean = false,
    val epgStale: Boolean = false,
)
