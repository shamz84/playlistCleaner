package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .playlist(2, "Family")
        .vodCategory(1, "10", "EN Movies")
        .vodCategory(1, "40", "Kids")
        .vodCategory(1, "50", "Docs")
        .vodCategory(1, "99", "Adult")
        .vodCategory(2, "10", "EN Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631", added = 100)
        .movie(1, 102, "EN - Dune (2021) HD", "10", tmdb = "438631", added = 300)
        .movie(1, 103, "FR - Dune (2021) FHD", "10", tmdb = "438631")
        .movie(1, 104, "EN - Leo (2023) FHD", "10", tmdb = "1075794", added = 200)
        .movie(1, 106, "EN - Bluey (2018)", "40", added = 50)
        .movie(1, 107, "EN - Planet Earth", "50")
        .movie(1, 108, "EN - Adult Film (2020)", "99", tmdb = "5", added = 400)
        .movie(2, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631", added = 10)

    private val hidden = setOf("99")
    private val sorts = InMemoryVodSortStore()
    private val progressStore = InMemoryPlaybackProgressStore()
    private var now = 1_000L
    private val progress = PlaybackProgressRepository(progressStore) { now }
    private val seriesInfo = FakeSeriesInfoLoader()

    private fun repository(src: VodCatalogSource = source, index: VodTitleIndex = indexOf(src)) = indexedRepository(src, hidden, index)

    private fun vm(
        kind: VodKind = VodKind.MOVIE,
        trending: TrendingProvider? = null,
        repo: VodCatalogRepository = repository(),
        pageSize: Int = VodBrowseViewModel.DEFAULT_PAGE_SIZE,
        changes: Flow<Unit> = emptyFlow(),
    ) = VodBrowseViewModel(kind, repo, trending, progress, seriesInfo, sorts, pageSize = pageSize, changes = changes)

    private fun TestScope.loaded(vm: VodBrowseViewModel): VodBrowseUiState {
        advanceUntilIdle()
        return vm.state.value
    }

    private suspend fun titleKey(playlistId: Long, kind: VodKind, itemId: String): String =
        repository().title(playlistId, kind, itemId)!!.key

    @Test
    fun `sections per playlist name, one card per merged title, recently added first, hidden categories excluded`() = runTest(main.dispatcher) {
        val s = loaded(vm())
        assertThat(s.loading).isFalse()
        assertThat(s.sections.map { it.playlist.name }).containsExactly("Main", "Family").inOrder()
        val main = s.sections.first()
        assertThat(main.sort).isEqualTo(VodSort.RECENTLY_ADDED)
        // Dune's newest version was added at 300; the unknown "added" goes last; Adult is PIN-hidden.
        assertThat(main.titles.map { it.title }).containsExactly("Dune", "Leo", "Bluey", "Planet Earth").inOrder()
        val dune = main.titles.first()
        assertThat(dune.year).isEqualTo(2021)
        assertThat(dune.bestQuality).isEqualTo(QualityTier.UHD)
        assertThat(dune.versionCount).isEqualTo(3)
        assertThat(dune.anchorId).isEqualTo("101")
        // Family's Dune is its own card (never merged across playlists).
        val family = s.sections[1].titles.single()
        assertThat(family.playlistId).isEqualTo(2)
        assertThat(family.titleKey).isNotEqualTo(dune.titleKey)
        assertThat(s.showTmdbCredit).isFalse()
    }

    @Test
    fun `sort orders and the choice persists per section`() = runTest(main.dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.setSort(1, VodSort.A_Z)
        advanceUntilIdle()
        assertThat(first.state.value.sections[0].titles.map { it.title }).containsExactly("Bluey", "Dune", "Leo", "Planet Earth").inOrder()

        first.setSort(1, VodSort.RELEASE_YEAR)
        advanceUntilIdle()
        // Newest year first, unknown years last.
        assertThat(first.state.value.sections[0].titles.map { it.title }).containsExactly("Leo", "Dune", "Bluey", "Planet Earth").inOrder()
        assertThat(first.state.value.sections[1].sort).isEqualTo(VodSort.RECENTLY_ADDED)

        // A new screen (or app launch) reads the stored choice for that section only.
        val again = loaded(vm())
        assertThat(again.sections[0].sort).isEqualTo(VodSort.RELEASE_YEAR)
        assertThat(again.sections[0].titles.map { it.title }).containsExactly("Leo", "Dune", "Bluey", "Planet Earth").inOrder()
        assertThat(again.sections[1].sort).isEqualTo(VodSort.RECENTLY_ADDED)
        assertThat(vm(kind = VodKind.SERIES).state.value.sections.firstOrNull()?.sort ?: VodSort.RECENTLY_ADDED).isEqualTo(VodSort.RECENTLY_ADDED)
    }

    @Test
    fun `the full list pages in`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 3)
        val s = loaded(vm).sections[0]
        assertThat(s.titles).hasSize(3)
        assertThat(s.totalTitles).isEqualTo(4)
        assertThat(s.hasMore).isTrue()
        vm.loadMore(1)
        advanceUntilIdle()
        assertThat(vm.state.value.sections[0].titles).hasSize(4)
        assertThat(vm.state.value.sections[0].hasMore).isFalse()
    }

    @Test
    fun `trending shows only catalog matches in rank order, with title and year fallback, never hidden titles`() = runTest(main.dispatcher) {
        val entries = listOf(
            TrendingEntry(999, "Not In Catalog", null, 2024),
            TrendingEntry(5, "Adult Film", null, 2020), // in a hidden category
            TrendingEntry(1075794, "Leo", null, 2023),
            TrendingEntry(77, "Bluey", null, 2018), // no tmdb id in the catalog: title + year
            TrendingEntry(438631, "Dune", null, 2021),
            TrendingEntry(88, "Planet Earth", null, 2006), // catalog has no year: movies need one
            TrendingEntry(12, "Dune", null, 1984), // another Dune: the catalog's Dune has a tmdb id
        )
        val s = loaded(vm(trending = { kind -> entries.takeIf { kind == VodKind.MOVIE } }))
        assertThat(s.sections[0].trending.map { it.title }).containsExactly("Leo", "Bluey", "Dune").inOrder()
        assertThat(s.sections[1].trending.map { it.title }).containsExactly("Dune")
        assertThat(s.showTmdbCredit).isTrue()
    }

    @Test
    fun `missing key or failed fetch hides trending and the rest of the screen works`() = runTest(main.dispatcher) {
        val none = loaded(vm(trending = { null }))
        assertThat(none.sections.all { it.trending.isEmpty() }).isTrue()
        assertThat(none.showTmdbCredit).isFalse()
        assertThat(none.sections[0].titles).isNotEmpty()

        val failing = loaded(vm(trending = { error("network down") }))
        assertThat(failing.sections.all { it.trending.isEmpty() }).isTrue()
        assertThat(failing.sections[0].failed).isFalse()
        assertThat(failing.sections[0].titles).hasSize(4)
    }

    @Test
    fun `movies continue watching excludes completed and barely started movies`() = runTest(main.dispatcher) {
        val dune = titleKey(1, VodKind.MOVIE, "101")
        val leo = titleKey(1, VodKind.MOVIE, "104")
        val bluey = titleKey(1, VodKind.MOVIE, "106")
        val adult = titleKey(1, VodKind.MOVIE, "108")
        now = 1; progress.save(ProgressKey(bluey), 1, VodKind.MOVIE, "Bluey", "106", 2, 100)
        now = 2; progress.save(ProgressKey(leo), 1, VodKind.MOVIE, "Leo", "104", 96, 100)
        now = 3; progress.save(ProgressKey(adult), 1, VodKind.MOVIE, "Adult", "108", 50, 100)
        now = 4; progress.save(ProgressKey(dune), 1, VodKind.MOVIE, "Dune", "101", 50, 100)

        val vm = vm()
        val s = loaded(vm).sections[0]
        assertThat(s.continueWatching.map { it.card.title }).containsExactly("Dune")
        assertThat(s.continueWatching.single().fraction).isWithin(0.001f).of(0.5f)
        assertThat(loaded(vm).sections[1].continueWatching).isEmpty()

        // Finishing Dune removes it on the next refresh.
        now = 5; progress.save(ProgressKey(dune), 1, VodKind.MOVIE, "Dune", "101", 99, 100)
        vm.refreshContinueWatching()
        assertThat(loaded(vm).sections[0].continueWatching).isEmpty()
    }

    @Test
    fun `tv shows continue watching has one card per show with the episode in progress or the next one`() = runTest(main.dispatcher) {
        source.seriesCategory(1, "70", "EN Series")
            .series(1, 201, "EN - Utopia (2013)", "70", tmdb = "46896", lastModified = 10)
            .series(1, 202, "EN - Lost (2004)", "70", tmdb = "4607", lastModified = 30)
            .series(1, 203, "EN - Fargo (2014)", "70", lastModified = 20)
        seriesInfo.episodes(201, 1 to 1, 1 to 2, 1 to 3)
            .episodes(202, 1 to 1, 2 to 5, 2 to 6)
            .episodes(203, 1 to 1, 1 to 2, 1 to 3)
        val utopia = titleKey(1, VodKind.SERIES, "201")
        val lost = titleKey(1, VodKind.SERIES, "202")
        val fargo = titleKey(1, VodKind.SERIES, "203")
        now = 1; progress.save(ProgressKey(utopia, 1, 1), 1, VodKind.SERIES, "S1 E1", "201", 95, 100)
        now = 2; progress.save(ProgressKey(fargo, 1, 3), 1, VodKind.SERIES, "S1 E3", "203", 99, 100) // finale watched: no next
        now = 3; progress.save(ProgressKey(lost, 2, 5), 1, VodKind.SERIES, "S2 E5", "202", 40, 100)
        now = 4; progress.save(ProgressKey(utopia, 1, 2), 1, VodKind.SERIES, "S1 E2", "201", 98, 100)

        val s = loaded(vm(kind = VodKind.SERIES)).sections[0]
        assertThat(s.titles.map { it.title }).containsExactly("Lost", "Fargo", "Utopia").inOrder()
        assertThat(s.continueWatching.map { Triple(it.card.title, it.season to it.episode, it.upNext) }).containsExactly(
            Triple("Utopia", 1 to 3, true),
            Triple("Lost", 2 to 5, false),
        ).inOrder()
        assertThat(s.continueWatching[0].fraction).isNull()
        assertThat(s.continueWatching[1].fraction).isWithin(0.001f).of(0.4f)
        // Browsing never calls get_series_info: "next" comes from cached episode lists only.
        assertThat(seriesInfo.calls).isEmpty()
    }

    @Test
    fun `a finished show without cached episodes gets no next card and no network call`() = runTest(main.dispatcher) {
        source.seriesCategory(1, "70", "EN Series").series(1, 201, "EN - Utopia (2013)", "70", tmdb = "46896")
        seriesInfo.episodes(201, 1 to 1, 1 to 2)
        seriesInfo.cachedIds = emptySet()
        val utopia = titleKey(1, VodKind.SERIES, "201")
        progress.save(ProgressKey(utopia, 1, 1), 1, VodKind.SERIES, "S1 E1", "201", 99, 100)

        val s = loaded(vm(kind = VodKind.SERIES)).sections[0]
        assertThat(s.titles.map { it.title }).containsExactly("Utopia")
        assertThat(s.continueWatching).isEmpty()
        assertThat(seriesInfo.calls).isEmpty()
    }

    @Test
    fun `browsing never groups the catalog`() = runTest(main.dispatcher) {
        val grouper = CountingGrouper()
        val index = indexOf(source, grouper)
        // The background build grouped once per kind and playlist...
        val built = grouper.calls.get()
        assertThat(built).isEqualTo(4)
        val trending = listOf(TrendingEntry(438631, "Dune", null, 2021), TrendingEntry(77, "Bluey", null, 2018))
        val leo = repository(index = index).title(1, VodKind.MOVIE, "104")!!.key
        progress.save(ProgressKey(leo), 1, VodKind.MOVIE, "Leo", "104", 50, 100)

        val vm = vm(trending = { trending }, repo = repository(index = index), pageSize = 2)
        val s = loaded(vm).sections[0]
        vm.setSort(1, VodSort.A_Z)
        vm.loadMore(1)
        advanceUntilIdle()
        // ...and the first page, paging, sorting, Trending, Continue watching and detail lookups grouped nothing.
        assertThat(s.titles).hasSize(2)
        assertThat(s.trending.map { it.title }).containsExactly("Dune", "Bluey").inOrder()
        assertThat(s.continueWatching.map { it.card.title }).containsExactly("Leo")
        assertThat(vm.state.value.sections[0].titles.map { it.title }).containsExactly("Bluey", "Dune", "Leo", "Planet Earth").inOrder()
        assertThat(grouper.calls.get()).isEqualTo(built)
    }

    @Test
    fun `sections wait for a title index still being built and load when it lands`() = runTest(main.dispatcher) {
        val index = InMemoryVodTitleIndex()
        val vm = vm(repo = repository(index = index), changes = index.changes())
        val pending = loaded(vm)
        assertThat(pending.sections.map { it.loading }).containsExactly(true, true)
        assertThat(pending.isEmpty).isFalse()

        // The background indexer finishes after the catalog sync.
        VodTitleIndexer(source, index).ensureAllFresh()
        val ready = loaded(vm)
        assertThat(ready.sections.map { it.loading }).containsExactly(false, false)
        assertThat(ready.sections[0].titles.map { it.title }).containsExactly("Dune", "Leo", "Bluey", "Planet Earth").inOrder()
    }

    @Test
    fun `a failing playlist leaves other sections usable`() = runTest(main.dispatcher) {
        val index = indexOf(source).apply { failingPlaylists = setOf(2L) }
        val s = loaded(vm(repo = repository(index = index)))
        assertThat(s.sections[0].failed).isFalse()
        assertThat(s.sections[0].titles).isNotEmpty()
        assertThat(s.sections[1].failed).isTrue()
        assertThat(s.isEmpty).isFalse()
    }
}
