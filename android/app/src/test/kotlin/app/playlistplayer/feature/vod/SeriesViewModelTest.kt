package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .seriesCategory(1, "50", "EN Series")
        .seriesCategory(1, "60", "4K Series")
        .series(1, 201, "EN - Utopia (2013) 4K", "60", tmdb = "46896")
        .series(1, 202, "EN - Utopia (2013) HD", "50", tmdb = "46896")
        .vodCategory(1, "10", "EN Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631")

    private val repository = VodCatalogRepository(source, groupingDispatcher = main.dispatcher)
    private val info = FakeSeriesInfoLoader()
        .episodes(201, 1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 6, 2 to 1)
        .episodes(202, 1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to 6)
    private val picks = InMemoryTitlePickStore()
    private val favourites = InMemoryFavouritesStore()
    private val progress = PlaybackProgressRepository(InMemoryPlaybackProgressStore()) { 0L }

    private fun viewModel(itemId: String = "202") = SeriesViewModel(
        playlistId = 1,
        itemId = itemId,
        repository = repository,
        seriesInfo = info,
        picks = picks,
        favourites = favourites,
        progress = progress,
        preferences = defaultPreferences,
        accounts = { testAccount },
    )

    @Test
    fun `episodes merge across sources and only offer sources that contain them`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.versions.map { it.id }).containsExactly("201", "202").inOrder()
        assertThat(s.selectedVersionId).isEqualTo("201")
        assertThat(s.seasons.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(s.seasons.first().selected).isTrue()
        assertThat(s.episodes.map { it.key.episode }).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        val ep4 = s.episodes.single { it.key.episode == 4 }
        assertThat(ep4.versions.map { it.id }).containsExactly("201", "202").inOrder()
        assertThat(ep4.selectedVersionId).isEqualTo("201")
        val ep5 = s.episodes.single { it.key.episode == 5 }
        assertThat(ep5.versions.map { it.id }).containsExactly("202")
        assertThat(ep5.selectedVersionId).isEqualTo("202")

        vm.selectSeason(2)
        advanceUntilIdle()
        assertThat(vm.state.value.episodes.map { it.key }).containsExactly(EpisodeKey(2, 1))
        assertThat(vm.state.value.episodes.single().versions.map { it.id }).containsExactly("201")
    }

    @Test
    fun `auto-advance plays HD episode 5 then returns to 4K for episode 6`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val ep4 = vm.playbackRequest(EpisodeKey(1, 4))!!
        assertThat(ep4.sources.map { it.id }).containsExactly("episode:201014", "episode:202014").inOrder()
        assertThat(ep4.sources.first().url).endsWith("/series/user-secret/pass-secret/201014.mp4")
        assertThat(ep4.nextEpisodeLabel).contains("S1 E5")
        assertThat(ep4.kind).isEqualTo(VodKind.SERIES)

        val ep5 = vm.nextEpisodeRequest()!!
        assertThat(ep5.sources.map { it.id }).containsExactly("episode:202015")
        assertThat(ep5.progressKey).isEqualTo(ProgressKey(vm.state.value.titleKey!!, 1, 5))

        val ep6 = vm.nextEpisodeRequest()!!
        assertThat(ep6.sources.first().id).isEqualTo("episode:201016")

        val s2e1 = vm.nextEpisodeRequest()!!
        assertThat(s2e1.sources.map { it.id }).containsExactly("episode:201021")
        assertThat(s2e1.nextEpisodeLabel).isNull()
        assertThat(vm.nextEpisodeRequest()).isNull()
    }

    @Test
    fun `an explicit HD choice is kept while advancing`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val ep1 = vm.playbackRequest(EpisodeKey(1, 1), versionId = "202")!!
        assertThat(ep1.sources.first().id).isEqualTo("episode:202011")
        assertThat(vm.nextEpisodeRequest()!!.sources.first().id).isEqualTo("episode:202012")
        assertThat(vm.state.value.selectedVersionId).isEqualTo("202")
    }

    @Test
    fun `selected source version is stored as the title pick`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectVersion("202")
        advanceUntilIdle()
        assertThat(picks.pick(vm.state.value.titleKey!!)).isEqualTo("202")
        assertThat(vm.state.value.episodes.single { it.key.episode == 1 }.selectedVersionId).isEqualTo("202")

        val again = viewModel(itemId = "201")
        advanceUntilIdle()
        assertThat(again.state.value.selectedVersionId).isEqualTo("202")
    }

    @Test
    fun `get_series_info failure shows retry and leaves browse usable`() = runTest(main.dispatcher) {
        info.failing += setOf(201L, 202L)
        val browse = VodBrowseViewModel(VodKind.SERIES, repository)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.episodesFailed).isTrue()
        assertThat(vm.state.value.episodesRetryable).isTrue()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.title).isEqualTo("Utopia")
        assertThat(browse.state.value.loading).isFalse()
        assertThat(browse.state.value.sections.single().rails.flatMap { it.cards }.map { it.title }).contains("Utopia")

        info.failing.clear()
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.episodesFailed).isFalse()
        assertThat(vm.state.value.episodes).hasSize(6)
    }

    @Test
    fun `one failing source still lists the other source's episodes`() = runTest(main.dispatcher) {
        info.failing += 201L
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.episodesFailed).isFalse()
        assertThat(vm.state.value.failedSources).isEqualTo(1)
        assertThat(vm.state.value.episodes.flatMap { it.versions }.map { it.id }.distinct()).containsExactly("202")
        // Sticky 4K is not available: the comparator's next best is used.
        assertThat(vm.playbackRequest(EpisodeKey(1, 1))!!.sources.single().id).isEqualTo("episode:202011")
    }

    @Test
    fun `episode rows show progress and watched state`() = runTest(main.dispatcher) {
        val key = viewModel().also { advanceUntilIdle() }.state.value.titleKey!!
        progress.save(ProgressKey(key, 1, 2), 1, VodKind.SERIES, "ep2", "episode:201012", 96_000, 100_000)
        progress.save(ProgressKey(key, 1, 3), 1, VodKind.SERIES, "ep3", "episode:201013", 40_000, 100_000)
        val vm = viewModel()
        advanceUntilIdle()
        val rows = vm.state.value.episodes.associateBy { it.key.episode }
        assertThat(rows.getValue(2).watched).isTrue()
        assertThat(rows.getValue(3).watched).isFalse()
        assertThat(rows.getValue(3).progressFraction).isWithin(1e-9).of(0.4)
        assertThat(vm.playbackRequest(EpisodeKey(1, 3))!!.startPositionMs).isEqualTo(40_000)
        assertThat(vm.playbackRequest(EpisodeKey(1, 2))!!.startPositionMs).isEqualTo(0)
    }

    @Test
    fun `series favourite toggle round-trips`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleFavourite()
        advanceUntilIdle()
        assertThat(favourites.current.value).containsExactly(FavouriteRef(FavouriteKind.SERIES, 1, vm.state.value.titleKey!!))
        vm.toggleFavourite()
        advanceUntilIdle()
        assertThat(favourites.current.value).isEmpty()
    }
}
