package app.playlistplayer.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Personal data (U11): favourites, playback progress, title picks, last/recent channels and VOD
// grouping overrides. Not generational: rows reference stable keys (stream ids, merged title
// keys, provider item ids), so they survive re-syncs. Every table carries `playlistId`, so
// `PlaylistStore.deletePlaylist` removes them with the playlist. No URLs or credentials.

/**
 * Stable channel key: `s:<streamId>` for Xtream entries, else `o:<ordinal>` (the playlist
 * position, the only identity a plain M3U entry has). Same scheme as the EPG `entryKey`.
 */
object StreamKeys {
    fun of(streamId: Long?, ordinal: Int): String = if (streamId != null) "s:$streamId" else "o:$ordinal"

    /** The stream id of an `s:` key, else null. */
    fun streamId(key: String): Long? = if (key.startsWith("s:")) key.substring(2).toLongOrNull() else null

    /** The ordinal of an `o:` key, else null. */
    fun ordinal(key: String): Int? = if (key.startsWith("o:")) key.substring(2).toIntOrNull() else null
}

/**
 * A favourite channel or title.
 * @property kind `CHANNEL`, `MOVIE` or `SERIES`.
 * @property itemKey [StreamKeys] key for channels, the merged `VodTitle.key` for titles
 *   (`key` is an SQL keyword, hence the column name).
 */
@Entity(
    tableName = "favourite",
    primaryKeys = ["kind", "playlistId", "itemKey"],
    indices = [Index(value = ["playlistId"]), Index(value = ["addedAt"])],
)
data class FavouriteEntity(
    val kind: String,
    val playlistId: Long,
    val itemKey: String,
    val addedAt: Long,
)

/**
 * Saved position of a movie (title key only) or an episode (title key + season + episode).
 * [id] is the composite identity ([idOf]); Room primary keys cannot be nullable.
 * @property watched position reached the watched threshold when saved.
 */
@Entity(
    tableName = "playback_progress",
    indices = [Index(value = ["playlistId"]), Index(value = ["updatedAt"]), Index(value = ["titleKey"])],
)
data class PlaybackProgressEntity(
    @PrimaryKey val id: String,
    val titleKey: String,
    val season: Int?,
    val episode: Int?,
    val playlistId: Long,
    val kind: String,
    val label: String,
    val versionId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val watched: Boolean,
) {
    companion object {
        fun idOf(titleKey: String, season: Int?, episode: Int?): String = "$titleKey#${season ?: "-"}#${episode ?: "-"}"
    }
}

/** The version a user picked for a merged title. */
@Entity(tableName = "title_pick", indices = [Index(value = ["playlistId"])])
data class TitlePickEntity(
    @PrimaryKey val titleKey: String,
    val playlistId: Long,
    val versionId: String,
    val updatedAt: Long,
)

/** The channel last played in a playlist; [streamKey] re-finds it after a re-sync. */
@Entity(tableName = "last_channel")
data class LastChannelEntity(
    @PrimaryKey val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val streamKey: String,
    val updatedAt: Long,
)

/** A recently watched channel (only the latest [RECENT_CHANNEL_LIMIT] rows are kept). */
@Entity(
    tableName = "recent_channel",
    primaryKeys = ["playlistId", "streamKey"],
    indices = [Index(value = ["watchedAt"])],
)
data class RecentChannelEntity(
    val playlistId: Long,
    val streamKey: String,
    val watchedAt: Long,
) {
    companion object {
        const val RECENT_CHANNEL_LIMIT: Int = 30
    }
}

/**
 * A U5 split/merge correction for one playlist's VOD grouping.
 * @property kind `SPLIT` or `MERGE`.
 * @property payload the provider item ids (stream / series ids), comma-separated, sorted.
 */
@Entity(tableName = "title_override", indices = [Index(value = ["playlistId"])])
data class TitleOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: String,
    val payload: String,
    val createdAt: Long,
)

/** A favourite / recent / last channel resolved against the active generation. */
data class ResolvedChannel(
    val playlistId: Long,
    /** The favourite's / recent row's [StreamKeys] key. */
    val itemKey: String,
    val at: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupTitle: String,
    val name: String,
    val streamId: Long?,
    val logo: String?,
)
