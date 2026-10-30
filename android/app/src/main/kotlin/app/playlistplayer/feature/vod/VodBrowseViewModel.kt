package app.playlistplayer.feature.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.VodKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

enum class RailStatus { NOT_LOADED, LOADING, LOADED, FAILED }

/** One category rail: one card per merged title. */
data class RailState(
    val category: VodCategory,
    val status: RailStatus = RailStatus.NOT_LOADED,
    val cards: List<VodCard> = emptyList(),
)

/** One playlist section (header = playlist name, R25). */
data class PlaylistSection(
    val playlist: VodPlaylist,
    val rails: List<RailState>,
    val failed: Boolean = false,
)

data class VodBrowseUiState(
    val kind: VodKind,
    val loading: Boolean = true,
    val sections: List<PlaylistSection> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && sections.all { s -> s.rails.isEmpty() }
}

/**
 * Movies or Series browse (R12, R25): sections per playlist name, then category rails, one card
 * per merged title. Rails load in chunks of [railChunk] as they come into view
 * ([onRailVisible]); grouping runs in [VodCatalogRepository] off the main thread. Empty
 * categories are dropped once loaded.
 */
class VodBrowseViewModel(
    val kind: VodKind,
    private val repository: VodCatalogRepository,
    private val railChunk: Int = DEFAULT_RAIL_CHUNK,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VodBrowseUiState(kind))
    val state: StateFlow<VodBrowseUiState> = mutableState.asStateFlow()

    private val railJobs = HashMap<Pair<Long, String>, Job>()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            railJobs.values.forEach { it.cancel() }
            railJobs.clear()
            mutableState.value = VodBrowseUiState(kind, loading = true)
            val sections = repository.playlists().map { playlist ->
                try {
                    PlaylistSection(playlist, repository.categories(playlist.id, kind).map { RailState(it) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaylistSection(playlist, emptyList(), failed = true)
                }
            }
            mutableState.value = VodBrowseUiState(kind, loading = false, sections = sections)
            sections.forEach { loadRails(it.playlist.id, 0 until railChunk) }
        }
    }

    /** A rail scrolled into view: load it and the next chunk ahead. */
    fun onRailVisible(playlistId: Long, railIndex: Int) {
        loadRails(playlistId, railIndex until railIndex + railChunk)
    }

    /** Retry a failed rail. */
    fun retryRail(playlistId: Long, categoryId: String) {
        val section = mutableState.value.sections.firstOrNull { it.playlist.id == playlistId } ?: return
        val index = section.rails.indexOfFirst { it.category.id == categoryId }
        if (index < 0) return
        setRail(playlistId, categoryId) { it.copy(status = RailStatus.NOT_LOADED) }
        loadRails(playlistId, index..index)
    }

    private fun loadRails(playlistId: Long, indices: IntRange) {
        val section = mutableState.value.sections.firstOrNull { it.playlist.id == playlistId } ?: return
        for (i in indices) {
            val rail = section.rails.getOrNull(i) ?: break
            if (rail.status != RailStatus.NOT_LOADED) continue
            val id = playlistId to rail.category.id
            if (railJobs[id]?.isActive == true) continue
            setRail(playlistId, rail.category.id) { it.copy(status = RailStatus.LOADING) }
            railJobs[id] = viewModelScope.launch {
                try {
                    val cards = repository.categoryTitles(rail.category).map { it.card() }
                    setRail(playlistId, rail.category.id) { it.copy(status = RailStatus.LOADED, cards = cards) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setRail(playlistId, rail.category.id) { it.copy(status = RailStatus.FAILED) }
                }
            }
        }
    }

    private fun setRail(playlistId: Long, categoryId: String, change: (RailState) -> RailState) {
        mutableState.update { s ->
            s.copy(
                sections = s.sections.map { section ->
                    if (section.playlist.id != playlistId) {
                        section
                    } else {
                        section.copy(
                            rails = section.rails.mapNotNull { rail ->
                                if (rail.category.id != categoryId) return@mapNotNull rail
                                change(rail).takeUnless { it.status == RailStatus.LOADED && it.cards.isEmpty() }
                            },
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val DEFAULT_RAIL_CHUNK: Int = 6
    }
}
