package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleDetailViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .vodCategory(1, "10", "EN Movies")
        .vodCategory(1, "20", "4K Movies")
        .vodCategory(1, "30", "French Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "20", tmdb = "438631")
        .movie(1, 102, "EN - Dune (2021) HD", "10", tmdb = "438631")
        .movie(1, 103, "FR - Dune (2021) FHD", "30", tmdb = "438631", ext = "mp4")

    private val repository = VodCatalogRepository(source, groupingDispatcher = main.dispatcher)
    private val picks = InMemoryTitlePickStore()
    private val favourites = InMemoryFavouritesStore()
    private val progressStore = InMemoryPlaybackProgressStore()
    private var now = 1_000L
    private val progress = PlaybackProgressRepository(progressStore) { now }
    private val movieInfo = FakeMovieInfoLoader()
    private var account: AccountProvider = AccountProvider { testAccount }

    private fun viewModel(itemId: String = "102") = TitleDetailViewModel(
        playlistId = 1,
        itemId = itemId,
        repository = repository,
        movieInfo = movieInfo,
        picks = picks,
        favourites = favourites,
        progress = progress,
        preferences = defaultPreferences,
        accounts = { account.streamAccount(it) },
    )

    @Test
    fun `EN 4K, EN HD and FR FHD default to EN 4K, merged across categories`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.title).isEqualTo("Dune")
        assertThat(s.year).isEqualTo(2021)
        assertThat(s.versions.map { it.id }).containsExactly("101", "102", "103").inOrder()
        assertThat(s.selectedVersionId).isEqualTo("101")
        assertThat(s.versions.single { it.isDefault }.id).isEqualTo("101")
        assertThat(s.languageChips.map { it.key }).containsExactly("en", "fr").inOrder()
        assertThat(s.qualityChips.map { it.key }).containsExactly("UHD", "FHD", "HD").inOrder()
    }

    @Test
    fun `filtering to French shows only FR FHD`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectLanguage("fr")
        val s = vm.state.value
        assertThat(s.versions.map { it.id }).containsExactly("103")
        assertThat(s.versions.single().quality).isEqualTo(QualityTier.FHD)
        assertThat(s.selectedVersionId).isEqualTo("103")
        assertThat(s.qualityChips.map { it.key }).containsExactly("FHD")

        vm.selectLanguage("fr") // toggling the active chip clears the filter
        assertThat(vm.state.value.versions).hasSize(3)
        assertThat(vm.state.value.selectedVersionId).isEqualTo("103")
    }

    @Test
    fun `quality filter narrows versions`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectQuality("HD")
        assertThat(vm.state.value.versions.map { it.id }).containsExactly("102")
        assertThat(vm.state.value.languageChips.map { it.key }).containsExactly("en")
    }

    @Test
    fun `manual FR pick persists and is the default on the next visit`() = runTest(main.dispatcher) {
        val first = viewModel()
        advanceUntilIdle()
        first.selectVersion("103")
        advanceUntilIdle()
        assertThat(picks.pick(first.state.value.titleKey!!)).isEqualTo("103")

        val second = viewModel(itemId = "101")
        advanceUntilIdle()
        assertThat(second.state.value.selectedVersionId).isEqualTo("103")
        assertThat(second.state.value.versions.single { it.isDefault }.id).isEqualTo("103")
        val request = second.playbackRequest(resume = false)!!
        assertThat(request.sources.first().id).isEqualTo("movie:103")
    }

    @Test
    fun `play builds movie sources, selected first, without leaking credentials into state`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val request = vm.playbackRequest(resume = false)!!
        assertThat(request.sources.map { it.id }).containsExactly("movie:101", "movie:102", "movie:103").inOrder()
        assertThat(request.sources.first().url).endsWith("/movie/user-secret/pass-secret/101.mkv")
        assertThat(request.sources.last().url).endsWith("/103.mp4")
        assertThat(request.sources.all { !it.isLive }).isTrue()
        assertThat(request.kind).isEqualTo(VodKind.MOVIE)
        assertThat(request.progressKey).isEqualTo(ProgressKey(vm.state.value.titleKey!!))
        assertThat(request.toString()).doesNotContain("pass-secret")
        assertThat(request.toString()).doesNotContain("provider.invalid")
        assertThat(vm.state.value.toString()).doesNotContain("pass-secret")
    }

    @Test
    fun `no account sets a play error`() = runTest(main.dispatcher) {
        account = AccountProvider { null }
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.playbackRequest(resume = false)).isNull()
        assertThat(vm.state.value.playError).isEqualTo(PlayError.NO_ACCOUNT)
    }

    @Test
    fun `details failure keeps versions usable and retry loads them`() = runTest(main.dispatcher) {
        movieInfo.fail = true
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.detailsFailed).isTrue()
        assertThat(vm.state.value.versions).hasSize(3)
        assertThat(vm.state.value.plot).isNull()

        movieInfo.fail = false
        vm.retryDetails()
        advanceUntilIdle()
        assertThat(vm.state.value.detailsFailed).isFalse()
        assertThat(vm.state.value.plot).isEqualTo("A desert planet.")
        assertThat(vm.state.value.backdropUrl).isNotNull()
        assertThat(movieInfo.calls.first()).isEqualTo(101L) // default version first
    }

    @Test
    fun `resume is offered from saved progress and used by the play action`() = runTest(main.dispatcher) {
        val key = viewModel().also { advanceUntilIdle() }.state.value.titleKey!!
        progress.save(ProgressKey(key), 1, VodKind.MOVIE, "Dune", "movie:101", positionMs = 50_000, durationMs = 100_000)

        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.resumePositionMs).isEqualTo(50_000)
        assertThat(vm.playbackRequest(resume = true)!!.startPositionMs).isEqualTo(50_000)
        assertThat(vm.playbackRequest(resume = false)!!.startPositionMs).isEqualTo(0)
    }

    @Test
    fun `favourite toggle round-trips through the store`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.isFavourite).isFalse()
        vm.toggleFavourite()
        advanceUntilIdle()
        val ref = FavouriteRef(FavouriteKind.MOVIE, 1, vm.state.value.titleKey!!)
        assertThat(vm.state.value.isFavourite).isTrue()
        assertThat(favourites.current.value).containsExactly(ref)

        val again = viewModel(itemId = "103")
        advanceUntilIdle()
        assertThat(again.state.value.isFavourite).isTrue()
        again.toggleFavourite()
        advanceUntilIdle()
        assertThat(again.state.value.isFavourite).isFalse()
        assertThat(favourites.current.value).isEmpty()
    }

    @Test
    fun `unknown item shows not found`() = runTest(main.dispatcher) {
        val vm = viewModel(itemId = "999")
        advanceUntilIdle()
        assertThat(vm.state.value.notFound).isTrue()
    }
}
