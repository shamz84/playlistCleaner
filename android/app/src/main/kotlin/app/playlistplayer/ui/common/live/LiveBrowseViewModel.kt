package app.playlistplayer.ui.common.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.toUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-facing texts the view model needs; the UI passes them from string resources. */
data class LiveBrowseText(
    val noPlaylists: String,
    val emptyGroup: String,
    val loadFailed: String,
)

/**
 * Drives [LiveBrowseState] from a [LiveBrowseSource]: loads the sectioned group rail, keeps the
 * selected group (falling back to the first group when it disappears after a sync) and loads that
 * group's channels.
 *
 * [externalScope] is for tests; in the app the view model's own scope is used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveBrowseViewModel(
    private val source: LiveBrowseSource,
    private val text: LiveBrowseText,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val selected = MutableStateFlow<GroupKey?>(null)
    private val _state = MutableStateFlow(LiveBrowseState())
    private var jobs: List<Job> = emptyList()

    val state: StateFlow<LiveBrowseState> = _state.asStateFlow()

    init {
        start()
    }

    fun selectGroup(key: GroupKey) {
        selected.value = key
        _state.update { it.copy(selectedGroup = key) }
    }

    fun retry() {
        jobs.forEach { it.cancel() }
        _state.update { it.copy(sections = UiState.Loading, channels = UiState.Loading) }
        start()
    }

    private fun start() {
        val sectionsJob = scope.launch {
            source.sections()
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(sections = UiState.Error(text.loadFailed, ::retry)) }
                }
                .collect { sections ->
                    val visible = sections.filter { it.groups.isNotEmpty() }
                    val keys = visible.flatMap { s -> s.groups.map { it.key } }
                    val keep = selected.value?.takeIf { it in keys } ?: keys.firstOrNull()
                    _state.update { it.copy(sections = visible.toUiState(text.noPlaylists), selectedGroup = keep) }
                    selected.value = keep
                }
        }
        val channelsJob = scope.launch {
            selected
                .flatMapLatest { key ->
                    if (key == null) {
                        flowOf(UiState.Empty(text.emptyGroup))
                    } else {
                        source.channels(key)
                            .map<List<ChannelRow>, UiState<List<ChannelRow>>> { it.toUiState(text.emptyGroup) }
                            .onStart { emit(UiState.Loading) }
                            .catch { e ->
                                if (e is CancellationException) throw e
                                emit(UiState.Error(text.loadFailed, ::retry))
                            }
                    }
                }
                .collect { channels -> _state.update { it.copy(channels = channels) } }
        }
        jobs = listOf(sectionsJob, channelsJob)
    }
}
