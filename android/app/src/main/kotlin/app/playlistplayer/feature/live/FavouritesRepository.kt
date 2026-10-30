package app.playlistplayer.feature.live

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.UserDataStore
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.feature.library.SavedChannel
import app.playlistplayer.feature.library.toSaved
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.feature.vod.FavouriteKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** A favourite (or resumable) title as home rows show it; open it like a browse card. */
data class SavedTitle(
    val playlistId: Long,
    val kind: VodKind,
    val titleKey: String,
    val anchorId: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val at: Long,
)

/**
 * Favourites across live and VOD (R18), over the same `favourite` table the VOD screens write
 * through `FavouritesStore`.
 *
 * - Channels are keyed by `playlistId + StreamKeys` (stream id, else ordinal), so they survive
 *   re-sync. A favourite whose channel is missing from the active generation is hidden, not
 *   deleted, and reappears when the channel returns.
 * - Titles are keyed by the merged `VodTitle.key` and resolved through the search index (which
 *   follows catalog flips); a title absent from the current catalog is hidden the same way.
 * - Entries in hidden (parental) groups/categories are filtered out ([visibility]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesRepository(
    private val store: UserDataStore,
    private val searchStore: SearchStore,
    private val visibility: () -> ContentVisibility = { ContentVisibility.ALL_VISIBLE },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun visibilityChanges(): Flow<Unit> = visibility().changes.onStart { emit(Unit) }

    /** Favourite channels present now, newest favourite first. */
    fun favouriteChannels(): Flow<List<SavedChannel>> =
        combine(store.observeFavouriteChannels(), visibilityChanges()) { rows, _ ->
            val rules = visibility()
            rows.map { it.toSaved() }.filterNot { rules.isLiveGroupHidden(it.playlistId, it.groupOrdinal, it.groupTitle) }
        }

    /** Favourite movies and series present in the current catalog, newest favourite first. */
    fun favouriteTitles(): Flow<List<SavedTitle>> =
        combine(store.observeFavourites(), searchStore.observeVodIndex(), visibilityChanges()) { favs, _, _ -> favs }
            .mapLatest { favs ->
                val titleFavs = favs.filter { it.kind == FavouriteKind.MOVIE.name || it.kind == FavouriteKind.SERIES.name }
                val indexed = searchStore.vodTitles(titleFavs.map { it.itemKey }).associateBy { it.titleKey }
                val rules = visibility()
                titleFavs.mapNotNull { fav ->
                    val row = indexed[fav.itemKey] ?: return@mapNotNull null
                    if (row.playlistId != fav.playlistId) return@mapNotNull null
                    val kind = runCatching { VodKind.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
                    if (rules.isTitleHidden(row.playlistId, kind, row.categoryIdList)) return@mapNotNull null
                    SavedTitle(row.playlistId, kind, row.titleKey, row.anchorId, row.displayTitle, row.year, row.posterUrl, fav.addedAt)
                }
            }

    suspend fun isChannelFavourite(playlistId: Long, ordinal: Int): Boolean {
        val key = channelKey(playlistId, ordinal) ?: return false
        return store.isFavourite(FavouriteKind.CHANNEL.name, playlistId, key)
    }

    /** Sets the favourite state of the active channel at [ordinal]; false when it does not exist. */
    suspend fun setChannelFavourite(playlistId: Long, ordinal: Int, favourite: Boolean): Boolean {
        val key = channelKey(playlistId, ordinal) ?: return false
        store.setFavourite(FavouriteKind.CHANNEL.name, playlistId, key, favourite, clock())
        return true
    }

    /** Toggles and returns the new state (false when the channel does not exist). */
    suspend fun toggleChannelFavourite(playlistId: Long, ordinal: Int): Boolean {
        val now = !isChannelFavourite(playlistId, ordinal)
        return setChannelFavourite(playlistId, ordinal, now) && now
    }

    private suspend fun channelKey(playlistId: Long, ordinal: Int): String? {
        val channel = store.activeChannel(playlistId, ordinal) ?: return null
        if (channel.isHeader) return null
        return StreamKeys.of(channel.streamId, ordinal)
    }
}
