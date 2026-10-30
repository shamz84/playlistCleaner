package app.playlistplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.search.SearchVodEntity
import app.playlistplayer.feature.library.RecentChannelsStore
import app.playlistplayer.feature.library.SavedChannel
import app.playlistplayer.feature.live.FavouritesRepository
import app.playlistplayer.feature.live.SavedTitle
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.feature.vod.PlaybackProgress
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.LiveBrowseSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** A resumable movie or episode with the title it belongs to. */
data class ContinueItem(val progress: PlaybackProgress, val title: SavedTitle) {
    val fraction: Float get() = progress.fraction.toFloat().coerceIn(0f, 1f)
}

/** Home rows, in display order. Empty rows are never emitted. */
sealed interface HomeRow {
    data class ContinueWatching(val items: List<ContinueItem>) : HomeRow
    data class FavouriteChannels(val channels: List<SavedChannel>) : HomeRow
    data class FavouriteTitles(val titles: List<SavedTitle>) : HomeRow
    data class RecentChannels(val channels: List<SavedChannel>) : HomeRow

    /** One playlist's live groups, under its name (R25). */
    data class LiveGroups(val section: PlaylistSection) : HomeRow
}

/**
 * @property showFirstRunHint no personal rows yet (nothing watched or favourited): show a hint
 *   explaining how rows fill up.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val showFirstRunHint: Boolean = false,
)

/**
 * Home (U11): Continue watching, Favourite channels, Favourite titles, Recent channels, then each
 * playlist's live groups. Rows hide when empty; hidden (parental) groups and categories never
 * appear (favourites/recents are filtered by [visibility]; [liveBrowse] sections arrive
 * filtered). Entries whose channel or title disappeared after a re-sync are hidden, not deleted.
 *
 * @param continueWatching `PlaybackProgressRepository::continueWatching` (U10 rules: resumable
 *   between 5% and 95%, not watched, one per title, newest first).
 * @param titles merged-title lookup by key (`SearchStore::vodTitles`).
 * @param changes re-runs Continue watching (progress saved, catalog re-indexed); call
 *   [refresh] when returning to Home as well.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val continueWatching: suspend (limit: Int) -> List<PlaybackProgress>,
    private val titles: suspend (keys: Collection<String>) -> List<SearchVodEntity>,
    favourites: FavouritesRepository,
    recents: RecentChannelsStore,
    liveBrowse: LiveBrowseSource,
    private val visibility: () -> ContentVisibility = { ContentVisibility.ALL_VISIBLE },
    changes: Flow<Unit> = flowOf(Unit),
    private val rowLimit: Int = DEFAULT_ROW_LIMIT,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0)

    private val continueRow: Flow<List<ContinueItem>> =
        combine(refreshes, changes.onStart { emit(Unit) }, visibility().changes.onStart { emit(Unit) }) { _, _, _ -> }
            .mapLatest { loadContinueWatching() }
            .catch { emit(emptyList()) }

    private val recentRow: Flow<List<SavedChannel>> =
        combine(recents.observe(), visibility().changes.onStart { emit(Unit) }) { rows, _ ->
            val rules = visibility()
            rows.filterNot { rules.isLiveGroupHidden(it.playlistId, it.groupOrdinal, it.groupTitle) }.take(rowLimit)
        }.catch { emit(emptyList()) }

    val state: StateFlow<HomeUiState> = combine(
        continueRow,
        favourites.favouriteChannels().catch { emit(emptyList()) },
        favourites.favouriteTitles().catch { emit(emptyList()) },
        recentRow,
        liveBrowse.sections().catch { emit(emptyList()) },
    ) { cont, favChannels, favTitles, recent, sections ->
        val rows = buildList {
            if (cont.isNotEmpty()) add(HomeRow.ContinueWatching(cont))
            if (favChannels.isNotEmpty()) add(HomeRow.FavouriteChannels(favChannels.take(rowLimit)))
            if (favTitles.isNotEmpty()) add(HomeRow.FavouriteTitles(favTitles.take(rowLimit)))
            if (recent.isNotEmpty()) add(HomeRow.RecentChannels(recent))
            sections.filter { it.groups.isNotEmpty() }.forEach { add(HomeRow.LiveGroups(it)) }
        }
        val personal = cont.isEmpty() && favChannels.isEmpty() && favTitles.isEmpty() && recent.isEmpty()
        HomeUiState(loading = false, rows = rows, showFirstRunHint = personal)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    /** Reloads Continue watching (e.g. when Home becomes visible again after playback). */
    fun refresh() {
        refreshes.value++
    }

    private suspend fun loadContinueWatching(): List<ContinueItem> = try {
        val items = continueWatching(rowLimit * 2)
        val indexed = titles(items.map { it.key.titleKey }).associateBy { it.titleKey }
        val rules = visibility()
        items.mapNotNull { p ->
            val row = indexed[p.key.titleKey] ?: return@mapNotNull null
            val kind = runCatching { VodKind.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
            if (rules.isTitleHidden(row.playlistId, kind, row.categoryIdList)) return@mapNotNull null
            ContinueItem(p, SavedTitle(row.playlistId, kind, row.titleKey, row.anchorId, row.displayTitle, row.year, row.posterUrl, p.updatedAt))
        }.take(rowLimit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val DEFAULT_ROW_LIMIT: Int = 20
    }
}
