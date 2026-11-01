package app.playlistplayer.ui.common.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.toUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Live TV destination: group rail (playlist-name sections, R25) and the guide grid of the
 * selected group.
 *
 * @property restoreChannel ordinal of the channel to focus in [selectedGroup]'s grid once (the
 *   last channel played, restored on first open); the screen clears it with
 *   [LiveTvViewModel.onRestoreHandled].
 */
data class LiveTvState(
    val sections: UiState<List<PlaylistSection>> = UiState.Loading,
    val selectedGroup: GroupKey? = null,
    val restoreChannel: Int? = null,
)

/**
 * Coordinates Live TV (group rail + guide grid; the grid itself is the shared `EpgGridViewModel`).
 *
 * - Sections come from [source] (PIN-hidden groups already filtered out); empty playlists are dropped.
 * - On first load the group of the last channel played is selected ([lastChannel], playlists in
 *   rail order, first hit wins) and that channel is offered as [LiveTvState.restoreChannel];
 *   otherwise the first group. A group chosen by the user before that lookup finishes wins.
 * - The selection is kept while it stays listed (falls back to the first group after a sync or a
 *   lock). The ViewModel lives in the activity store, so returning from a player keeps it.
 *
 * [externalScope] is for tests; in the app the view model's own scope is used.
 */
class LiveTvViewModel(
    private val source: LiveBrowseSource,
    private val lastChannel: suspend (playlistId: Long) -> LastChannel?,
    private val text: LiveBrowseText,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(LiveTvState())
    private var job: Job? = null
    private var restoreTried = false
    private var userSelected = false

    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    init {
        start()
    }

    /** The user picked [key] (rail, or a group opened from Home). */
    fun selectGroup(key: GroupKey) {
        userSelected = true
        _state.update { current ->
            current.copy(selectedGroup = key, restoreChannel = current.restoreChannel?.takeIf { current.selectedGroup == key })
        }
    }

    fun onRestoreHandled() {
        _state.update { it.copy(restoreChannel = null) }
    }

    fun retry() {
        job?.cancel()
        _state.update { it.copy(sections = UiState.Loading) }
        start()
    }

    private fun start() {
        job = scope.launch {
            source.sections()
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(sections = UiState.Error(text.loadFailed, ::retry)) }
                }
                .collect { sections ->
                    val visible = sections.filter { it.groups.isNotEmpty() }
                    val keys = visible.flatMap { s -> s.groups.map { it.key } }
                    val restored = if (!restoreTried && keys.isNotEmpty()) {
                        restoreTried = true
                        restore(visible, keys)
                    } else {
                        null
                    }
                    _state.update { current ->
                        val keep = current.selectedGroup?.takeIf { it in keys }
                        val restoredHere = restored?.takeIf { !userSelected }
                        current.copy(
                            sections = visible.toUiState(text.noPlaylists),
                            selectedGroup = restoredHere?.first ?: keep ?: keys.firstOrNull(),
                            restoreChannel = restoredHere?.second ?: current.restoreChannel?.takeIf { keep != null },
                        )
                    }
                }
        }
    }

    /** Group and channel ordinal of the last channel played, when its group is still listed. */
    private suspend fun restore(sections: List<PlaylistSection>, keys: List<GroupKey>): Pair<GroupKey, Int>? {
        for (section in sections) {
            val last = try {
                lastChannel(section.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val key = GroupKey(section.playlistId, last.groupOrdinal)
            if (key in keys) return key to last.ordinal
        }
        return null
    }
}
