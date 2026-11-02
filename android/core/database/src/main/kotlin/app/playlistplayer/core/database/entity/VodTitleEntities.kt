package app.playlistplayer.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Precomputed merged VOD titles (U20). The app groups the catalog (U5 TitleGrouper) once per
// catalog sync or override change, in the background, and stores the result here, so browse,
// Trending, Continue watching and the search index read titles with indexed queries instead of
// grouping ~45k movies and ~11k series on the device at browse time. Rows carry their own
// `generation` and are read only through `vod_title_state.activeGeneration`, so a rebuild swaps
// atomically. Like every other table: ids and display attributes only, never URLs or credentials.

/**
 * Per-playlist title index bookkeeping (both kinds share one generation).
 *
 * @property catalogGeneration the catalog generation the active titles were grouped from.
 * @property overridesStamp the title-override stamp they were grouped with.
 */
@Entity(tableName = "vod_title_state")
data class VodTitleStateEntity(
    @PrimaryKey val playlistId: Long,
    val activeGeneration: Long? = null,
    val pendingGeneration: Long? = null,
    val lastGeneration: Long = 0,
    val catalogGeneration: Long? = null,
    val overridesStamp: String? = null,
    val builtAt: Long? = null,
    val titleCount: Int = 0,
)

/**
 * One merged title of one playlist and kind (`MOVIE`/`SERIES`).
 *
 * The three `*Rank` columns are the title's 0-based position in each browse sort, decided at index
 * time with the app's collator (SQL cannot sort A–Z the way the UI does): Recently added (newest
 * first, unknown last), A–Z, Release year (newest first, unknown years last, A–Z within a year).
 * [ordinal] is the provider order of the grouped list.
 *
 * @property titleYear the grouper's title year; [year] is the display year (title year, else the
 *   year of a version's release date).
 * @property normalizedTitle `TitleTokenizer.normalize(title)`, the Trending title fallback key.
 * @property hasTmdb some version carries a numeric tmdb id (Trending then matches by id only).
 * @property bestQuality highest labelled `QualityTier` name, or null.
 * @property searchText the search index's normalised names (`" | "`-joined).
 * @property categoryIds the versions' category ids, newline-joined.
 */
@Entity(
    tableName = "vod_title",
    primaryKeys = ["playlistId", "generation", "titleKey"],
    indices = [
        Index(value = ["playlistId", "kind", "generation", "addedRank"]),
        Index(value = ["playlistId", "kind", "generation", "azRank"]),
        Index(value = ["playlistId", "kind", "generation", "yearRank"]),
        Index(value = ["playlistId", "kind", "generation", "ordinal"]),
        Index(value = ["playlistId", "kind", "generation", "normalizedTitle"]),
    ],
)
data class VodTitleEntity(
    val playlistId: Long,
    val generation: Long,
    val kind: String,
    val titleKey: String,
    val ordinal: Int,
    val addedRank: Int,
    val azRank: Int,
    val yearRank: Int,
    val title: String,
    val normalizedTitle: String,
    val titleYear: Int?,
    val year: Int?,
    val tmdb: String?,
    val hasTmdb: Boolean,
    val addedAt: Long?,
    val anchorId: String,
    val posterUrl: String?,
    val bestQuality: String?,
    val versionCount: Int,
    val searchText: String,
    val categoryIds: String,
) {
    val categoryIdList: List<String> get() = categoryIds.split('\n').filter { it.isNotEmpty() }
}

/**
 * One version (catalog item) of a merged title: the item → title lookup, the parental filter
 * (a title is hidden only when every version sits in a hidden category) and Trending's tmdb match.
 *
 * @property itemId movie stream id or series id, as a string.
 * @property tmdb numeric tmdb id without leading zeros, or null.
 */
@Entity(
    tableName = "vod_title_item",
    primaryKeys = ["playlistId", "generation", "kind", "itemId"],
    indices = [
        Index(value = ["playlistId", "generation", "titleKey"]),
        Index(value = ["playlistId", "kind", "generation", "tmdb"]),
    ],
)
data class VodTitleItemEntity(
    val playlistId: Long,
    val generation: Long,
    val kind: String,
    val itemId: String,
    val titleKey: String,
    val categoryId: String?,
    val tmdb: String?,
    val providerOrder: Int,
)
