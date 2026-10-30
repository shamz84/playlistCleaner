package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val mainPlaylist = SearchPlaylist(1, "Main")
    private val family = SearchPlaylist(2, "Family")

    private class FakeEngine(val answer: (String) -> SearchResults) : SearchEngine {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): SearchResults {
            queries += query
            return answer(query)
        }
    }

    private fun live(p: SearchPlaylist, name: String) = LiveHit(p.id, 0, 0, "G", name, null, "s:1")
    private fun title(p: SearchPlaylist, kind: VodKind, name: String) = VodHit(p.id, kind, "${p.id}|$name", "1", name, null, null, 1)

    @Test
    fun typingIsDebouncedAndOnlyTheLastQueryRuns() = runTest(main.dispatcher) {
        val engine = FakeEngine { q ->
            SearchResults(
                q,
                live = listOf(PlaylistHits(mainPlaylist, listOf(live(mainPlaylist, "BBC One"))), PlaylistHits(family, listOf(live(family, "BBC One")))),
                movies = listOf(PlaylistHits(mainPlaylist, listOf(title(mainPlaylist, VodKind.MOVIE, "Dune")))),
                series = listOf(PlaylistHits(mainPlaylist, listOf(title(mainPlaylist, VodKind.SERIES, "Dune: Prophecy")))),
            )
        }
        val vm = SearchViewModel(engine)
        runCurrent()
        assertThat(vm.state.value).isEqualTo(SearchUiState.Idle)

        vm.onQueryChange("b")
        advanceTimeBy(100)
        vm.onQueryChange("bb")
        advanceTimeBy(100)
        vm.onQueryChange("bbc one")
        assertThat(vm.query.value).isEqualTo("bbc one")
        advanceTimeBy(249)
        runCurrent()
        assertThat(engine.queries).isEmpty()
        advanceTimeBy(2)
        advanceUntilIdle()

        assertThat(engine.queries).containsExactly("bbc one")
        val state = vm.state.value as SearchUiState.Results
        assertThat(state.sections.map { it.kind })
            .containsExactly(SearchSectionKind.LIVE, SearchSectionKind.MOVIES, SearchSectionKind.SERIES).inOrder()
        val liveSection = state.sections.first() as SearchSection.Live
        assertThat(liveSection.groups.map { it.playlist.name }).containsExactly("Main", "Family").inOrder()
    }

    @Test
    fun zeroResultsAndClearing() = runTest(main.dispatcher) {
        val vm = SearchViewModel(FakeEngine { SearchResults.empty(it) })
        vm.onQueryChange("zzz")
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SearchUiState.NoResults("zzz"))

        vm.clear()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SearchUiState.Idle)
    }

    @Test
    fun failuresShowAGenericErrorAndRetryRunsAgain() = runTest(main.dispatcher) {
        var fail = true
        val engine = FakeEngine { q -> if (fail) error("provider.example.invalid exploded") else SearchResults.empty(q) }
        val vm = SearchViewModel(engine)
        vm.onQueryChange("news")
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SearchUiState.Error("news"))

        fail = false
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SearchUiState.NoResults("news"))
        assertThat(engine.queries).containsExactly("news", "news")
    }

    @Test
    fun visibilityChangesRerunTheQuery() = runTest(main.dispatcher) {
        val changes = MutableSharedFlow<Unit>()
        val engine = FakeEngine { SearchResults.empty(it) }
        val vm = SearchViewModel(engine, visibilityChanges = changes)
        vm.onQueryChange("adult")
        advanceUntilIdle()
        changes.emit(Unit)
        advanceUntilIdle()
        assertThat(engine.queries).containsExactly("adult", "adult")
    }
}
