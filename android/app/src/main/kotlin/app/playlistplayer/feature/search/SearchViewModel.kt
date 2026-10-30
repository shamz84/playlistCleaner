package app.playlistplayer.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Result sections, in display order. */
enum class SearchSectionKind { LIVE, MOVIES, SERIES }

/** One section (Live / Movies / Series) with its hits grouped under each playlist's name. */
sealed interface SearchSection {
    val kind: SearchSectionKind

    data class Live(val groups: List<PlaylistHits<LiveHit>>) : SearchSection {
        override val kind: SearchSectionKind get() = SearchSectionKind.LIVE
    }

    data class Titles(override val kind: SearchSectionKind, val groups: List<PlaylistHits<VodHit>>) : SearchSection
}

sealed interface SearchUiState {
    /** No query yet: show the input hint. */
    data object Idle : SearchUiState

    data class Searching(val query: String) : SearchUiState

    data class Results(val query: String, val sections: List<SearchSection>) : SearchUiState

    /**
     * Nothing visible matched. The same state whether or not locked content matched, so it never
     * reveals hidden groups.
     */
    data class NoResults(val query: String) : SearchUiState

    /** Search failed; the message is generic (exception text is never shown). */
    data class Error(val query: String) : SearchUiState
}

/**
 * Global search (U11). Typing updates [query] immediately; the search runs [debounceMillis]
 * after the last keystroke, cancelling an older one still in flight. Visibility changes
 * (parental unlock/lock) re-run the current query.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: SearchEngine,
    visibilityChanges: Flow<Unit> = flowOf(Unit),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) : ViewModel() {
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    private val retries = MutableStateFlow(0)

    val state: StateFlow<SearchUiState> = combine(
        mutableQuery.debounce { if (it.isBlank()) 0 else debounceMillis }.map { it.trim() }.distinctUntilChanged(),
        visibilityChanges.onStart { emit(Unit) },
        retries,
    ) { q, _, _ -> q }
        .transformLatest { q ->
            if (q.isEmpty()) {
                emit(SearchUiState.Idle)
                return@transformLatest
            }
            emit(SearchUiState.Searching(q))
            emit(run(q))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState.Idle)

    fun onQueryChange(text: String) {
        mutableQuery.value = text.take(MAX_QUERY_LENGTH)
    }

    fun clear() {
        mutableQuery.value = ""
    }

    fun retry() {
        retries.value++
    }

    private suspend fun run(q: String): SearchUiState = try {
        val results = repository.search(q)
        if (results.isEmpty) {
            SearchUiState.NoResults(q)
        } else {
            SearchUiState.Results(
                q,
                buildList {
                    if (results.live.isNotEmpty()) add(SearchSection.Live(results.live))
                    if (results.movies.isNotEmpty()) add(SearchSection.Titles(SearchSectionKind.MOVIES, results.movies))
                    if (results.series.isNotEmpty()) add(SearchSection.Titles(SearchSectionKind.SERIES, results.series))
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SearchUiState.Error(q)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 250
        const val MAX_QUERY_LENGTH: Int = 80
    }
}
