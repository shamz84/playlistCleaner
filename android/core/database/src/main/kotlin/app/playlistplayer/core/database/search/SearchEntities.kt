package app.playlistplayer.core.database.search

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Search index (U11). Two external-content FTS5 tables with the trigram tokenizer (substring
// matching; needs the bundled SQLite driver on API 28/30). Room 3 supports `@Fts5` with
// `tokenize='trigram'` directly, so the virtual tables are ordinary Room entities: they are in
// the exported schema, created by the 3 -> 4 AutoMigration, and every query is verified at
// compile time.
//
// The content tables are plain rows written by `SearchStore.replaceLive/replaceVod` once per
// playlist / catalog generation (a rebuild, not per-row triggers on the channel or catalog
// tables). Room keeps each FTS index in step with its content table through its own
// `room_fts_content_sync_*` triggers, so deleting a playlist's content rows also removes its
// index entries.
//
// Names are stored normalised (`TitleTokenizer.normalize`: folded, diacritics stripped, lower
// case, letters/digits only), and queries are normalised the same way, so case and diacritics
// never matter. Parental filtering is applied at query time by the caller (group ordinal/name
// for live, category ids for VOD), never baked into the index.

/** One searchable live entry (header entries are not indexed). */
@Entity(tableName = "search_live_content", indices = [Index(value = ["playlistId"])])
data class SearchLiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupName: String,
    /** `StreamKeys.of(streamId, ordinal)`. */
    val streamKey: String,
    val normalizedName: String,
    val displayName: String,
    val logo: String?,
)

@Fts5(contentEntity = SearchLiveEntity::class, contentRowId = "id", tokenizer = FtsOptions.TOKENIZER_TRIGRAM)
@Entity(tableName = "search_live")
data class SearchLiveFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val normalizedName: String,
)

/**
 * One merged VOD title (U5 `TitleGrouper` key), so a hit opens the same title as browse.
 * @property normalizedTitle the display title plus every distinct version base name, joined by
 *   ` | ` (normalised queries never contain `|`, so no match spans two names).
 * @property anchorId provider item id of the first version (a browse card's anchor).
 * @property categoryIds every category of the title's versions, newline-separated.
 */
@Entity(
    tableName = "search_vod_content",
    indices = [Index(value = ["playlistId", "kind"]), Index(value = ["titleKey"])],
)
data class SearchVodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: String,
    val titleKey: String,
    val normalizedTitle: String,
    val displayTitle: String,
    val year: Int?,
    val anchorId: String,
    val posterUrl: String?,
    val categoryIds: String,
    val versionCount: Int,
) {
    val categoryIdList: List<String> get() = if (categoryIds.isEmpty()) emptyList() else categoryIds.split('\n')

    companion object {
        fun joinCategories(ids: Collection<String>): String = ids.filter { it.isNotEmpty() }.distinct().joinToString("\n")
    }
}

@Fts5(contentEntity = SearchVodEntity::class, contentRowId = "id", tokenizer = FtsOptions.TOKENIZER_TRIGRAM)
@Entity(tableName = "search_vod")
data class SearchVodFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val normalizedTitle: String,
)

/**
 * What a playlist's index was built from. A source generation (or the override stamp) that no
 * longer matches means the index is stale and is rebuilt.
 */
@Entity(tableName = "search_index_state")
data class SearchIndexStateEntity(
    @PrimaryKey val playlistId: Long,
    /** Playlist generation the live rows came from; null = live never indexed. */
    val liveGeneration: Long?,
    /** Catalog generation the VOD rows came from; null = no catalog indexed. */
    val catalogGeneration: Long?,
    /** Fingerprint of the title overrides the VOD rows were grouped with. */
    val overridesStamp: String?,
    val vodIndexed: Boolean,
    val builtAt: Long,
)

/**
 * Room's content-sync triggers for the two external-content FTS tables, exactly as Room emits
 * them for a fresh database. Room's AutoMigration creates the FTS tables but not these triggers,
 * so the 3 -> 4 migration spec adds them (without them an upgraded install would never index).
 */
internal object SearchFtsTriggers {
    private fun triggers(fts: String, content: String, column: String): List<String> = listOf(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_BEFORE_UPDATE BEFORE UPDATE ON `$content` " +
            "BEGIN DELETE FROM `$fts` WHERE `rowid`=OLD.`id`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_BEFORE_DELETE BEFORE DELETE ON `$content` " +
            "BEGIN DELETE FROM `$fts` WHERE `rowid`=OLD.`id`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_AFTER_UPDATE AFTER UPDATE ON `$content` " +
            "BEGIN INSERT INTO `$fts`(`rowid`, `$column`) VALUES (NEW.`id`, NEW.`$column`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_AFTER_INSERT AFTER INSERT ON `$content` " +
            "BEGIN INSERT INTO `$fts`(`rowid`, `$column`) VALUES (NEW.`id`, NEW.`$column`); END",
    )

    val ALL: List<String> =
        triggers("search_live", "search_live_content", "normalizedName") +
            triggers("search_vod", "search_vod_content", "normalizedTitle")
}
