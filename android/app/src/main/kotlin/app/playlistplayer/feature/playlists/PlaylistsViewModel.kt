package app.playlistplayer.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.feature.onboarding.SourceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What the last action did, for a one-shot message (snackbar/toast). */
sealed interface PlaylistMessage {
    val playlistId: Long?

    data class Saved(override val playlistId: Long?) : PlaylistMessage
    data class RefreshQueued(override val playlistId: Long?) : PlaylistMessage
    data class Removed(override val playlistId: Long) : PlaylistMessage
    data class NotAuthorized(override val playlistId: Long?) : PlaylistMessage
    data class XtreamUnavailable(override val playlistId: Long) : PlaylistMessage
    data class Invalid(override val playlistId: Long?, val reason: InvalidReason) : PlaylistMessage
    data class Failed(override val playlistId: Long?) : PlaylistMessage
}

data class PlaylistsUiState(
    val loading: Boolean = true,
    val playlists: List<PlaylistSummary> = emptyList(),
    /** Playlists with an action in flight (disable their controls). */
    val busy: Set<Long> = emptySet(),
    val message: PlaylistMessage? = null,
) {
    val isEmpty: Boolean get() = !loading && playlists.isEmpty()
}

/**
 * Settings → Playlists (R23–R27): the list with status, and rename / edit source / Xtream toggle /
 * refresh / remove. "Add playlist" reuses the onboarding flow (U9). [authorize] is the parental
 * PIN gate supplied by the settings unit; Pure Kotlin apart from the ViewModel base
 * ([externalScope] is for tests).
 */
class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val authorize: PlaylistAuthorizer = { true },
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.observePlaylists()
                .catch { _state.update { it.copy(loading = false, message = PlaylistMessage.Failed(null)) } }
                .collect { list -> _state.update { it.copy(loading = false, playlists = list) } }
        }
    }

    fun rename(playlistId: Long, name: String) = act(playlistId) { repository.rename(playlistId, name, authorize) }

    fun editSource(playlistId: Long, source: SourceInput) = act(playlistId) { repository.editSource(playlistId, source, authorize) }

    fun setXtreamEnabled(playlistId: Long, enabled: Boolean) =
        act(playlistId) { repository.setXtreamEnabled(playlistId, enabled, authorize) }

    fun remove(playlistId: Long) = act(playlistId, removed = true) { repository.remove(playlistId, authorize) }

    fun refresh(playlistId: Long) = act(playlistId, refresh = true) { repository.refresh(playlistId) }

    fun refreshAll() {
        scope.launch {
            try {
                repository.refreshAll()
                _state.update { it.copy(message = PlaylistMessage.RefreshQueued(null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(message = PlaylistMessage.Failed(null)) }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun act(
        playlistId: Long,
        removed: Boolean = false,
        refresh: Boolean = false,
        block: suspend () -> PlaylistActionResult,
    ) {
        if (playlistId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + playlistId) }
        scope.launch {
            val message = try {
                when (val result = block()) {
                    PlaylistActionResult.Done -> when {
                        removed -> PlaylistMessage.Removed(playlistId)
                        refresh -> PlaylistMessage.RefreshQueued(playlistId)
                        else -> PlaylistMessage.Saved(playlistId)
                    }
                    is PlaylistActionResult.Added -> PlaylistMessage.Saved(result.playlistId)
                    PlaylistActionResult.NotAuthorized -> PlaylistMessage.NotAuthorized(playlistId)
                    PlaylistActionResult.NotFound -> PlaylistMessage.Failed(playlistId)
                    PlaylistActionResult.XtreamUnavailable -> PlaylistMessage.XtreamUnavailable(playlistId)
                    is PlaylistActionResult.Invalid -> PlaylistMessage.Invalid(playlistId, result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlaylistMessage.Failed(playlistId)
            }
            _state.update { it.copy(busy = it.busy - playlistId, message = message) }
        }
    }
}
