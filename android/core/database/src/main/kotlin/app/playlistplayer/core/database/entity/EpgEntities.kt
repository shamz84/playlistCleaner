package app.playlistplayer.core.database.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// EPG tables (U7). Programmes are not generational: each import replaces the future part of the
// guide and keeps the past within the archive window (see EpgStore.commitImport). Rows are staged
// with `pending = 1` under an import id and only become visible when the import commits.

/** Where a programme row came from. */
enum class ProgrammeSource {
    /** An XMLTV document: the provider's `xmltv.php` or the user's override URL. */
    XMLTV,

    /** Xtream `get_simple_data_table` archive backfill (days older than the XMLTV coverage). */
    XTREAM_ARCHIVE,
}

/**
 * One guide programme.
 *
 * @property channelKey the matched guide channel id (XMLTV `channel` id exactly as in the
 *   document, or `xtream:<streamId>` for archive-only channels). Several playlist entries can
 *   share one key (see [ChannelEpgMapEntity]).
 * @property categories newline-separated XMLTV categories, or null.
 * @property episodeNum display form (`onscreen` value, or `S1 E2` from `xmltv_ns`), or null.
 * @property source a [ProgrammeSource] name.
 * @property importId the import that wrote the row; [pending] rows belong to an import that has
 *   not committed and are invisible to every read.
 */
@Entity(
    tableName = "programme",
    indices = [
        Index(value = ["playlistId", "channelKey", "startMillis"]),
        Index(value = ["importId"]),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String?,
    val subTitle: String?,
    val description: String?,
    val categories: String?,
    val episodeNum: String?,
    val source: String,
    val importId: Long = 0,
    val pending: Boolean = false,
) {
    val sourceType: ProgrammeSource get() = ProgrammeSource.valueOf(source)

    val categoryList: List<String> get() = categories?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun isAiringAt(nowMillis: Long): Boolean = nowMillis >= startMillis && nowMillis < stopMillis

    fun hasEndedAt(nowMillis: Long): Boolean = stopMillis <= nowMillis
}

/** How a playlist entry was mapped to [ChannelEpgMapEntity.channelKey]. */
enum class EpgMapMethod { EXACT_ID, CASE_INSENSITIVE_ID, DISPLAY_NAME, STREAM_ID }

/** Which guide supplied the mapping. */
enum class EpgMapOrigin {
    /** The user's override XMLTV URL. */
    OVERRIDE,

    /** The provider's `xmltv.php` (default, or fallback for channels the override lacks). */
    PROVIDER,

    /** No XMLTV channel; archive backfill keyed by the Xtream stream id. */
    XTREAM,
}

/**
 * Playlist entry → guide channel. Keyed by a stable [entryKey] rather than the playlist ordinal,
 * so a playlist refresh that shifts ordinals keeps its guide until the next EPG import:
 * `s:<streamId>` for entries with a stream id, otherwise `o:<ordinal>` (see [epgEntryKey]).
 * Rows are replaced as a whole when an import commits; [lastBackfillAt] carries over.
 */
@Entity(
    tableName = "channel_epg_map",
    primaryKeys = ["playlistId", "entryKey"],
    indices = [Index(value = ["playlistId", "channelKey"])],
)
data class ChannelEpgMapEntity(
    val playlistId: Long,
    val entryKey: String,
    val channelKey: String,
    val method: String,
    val origin: String,
    val lastBackfillAt: Long? = null,
)

/** The mapping key of a playlist entry; mirrors the SQL `COALESCE('s:' || streamId, 'o:' || ordinal)`. */
fun epgEntryKey(streamId: Long?, ordinal: Int): String = if (streamId != null) "s:$streamId" else "o:$ordinal"

/**
 * Per-playlist EPG bookkeeping (the plan's "EpgSource state"). The override URL itself is a
 * secret held in SecureStore; only its presence is recorded here.
 *
 * - [activeSource]: `OVERRIDE` or `PROVIDER` for the last committed import.
 * - [matchedCount]/[totalCount]/[matchRate]: playlist entries with a guide after the last import
 *   (override + provider fallback); [overrideMatchedCount]/[providerMatchedCount] split it.
 * - [coverageFrom]/[coverageTo]: earliest start / latest stop of committed XMLTV programmes.
 * - [lastError]/[lastErrorKind]/[lastErrorAt]: latest failure since the last success (redacted).
 * - [lastImportId]/[pendingImportId]: import id allocation; a non-null pending id after a crash
 *   is cleaned up by the next import.
 */
@Entity(tableName = "epg_source_state")
data class EpgSourceStateEntity(
    @PrimaryKey val playlistId: Long,
    val overrideUrlPresent: Boolean = false,
    val activeSource: String? = null,
    val lastImportAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastErrorKind: String? = null,
    val lastError: String? = null,
    val matchedCount: Int = 0,
    val totalCount: Int = 0,
    val matchRate: Double = 0.0,
    val overrideMatchedCount: Int = 0,
    val providerMatchedCount: Int = 0,
    val programmeCount: Int = 0,
    val coverageFrom: Long? = null,
    val coverageTo: Long? = null,
    val lastBackfillAt: Long? = null,
    val backfilledChannels: Int = 0,
    val lastImportId: Long = 0,
    val pendingImportId: Long? = null,
)

/** A programme with the playlist ordinal of the channel it is shown on (grid rows). */
data class ChannelProgramme(
    val channelOrdinal: Int,
    @Embedded val programme: ProgrammeEntity,
)

/** A playlist entry's guide channel key. */
data class ChannelEpgKey(
    val channelOrdinal: Int,
    val channelKey: String,
)
