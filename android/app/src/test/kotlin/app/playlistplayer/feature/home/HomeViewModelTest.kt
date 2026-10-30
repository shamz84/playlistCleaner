package app.playlistplayer.feature.home

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.library.RoomFavouritesStore
import app.playlistplayer.feature.library.RoomLastChannelStore
import app.playlistplayer.feature.library.RoomPlaybackProgressStore
import app.playlistplayer.feature.library.RoomRecentChannelsStore
import app.playlistplayer.feature.live.FavouritesRepository
import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.feature.search.SearchTestEnv
import app.playlistplayer.feature.search.TestChannel
import app.playlistplayer.feature.search.TestMovie
import app.playlistplayer.feature.vod.FavouriteKind
import app.playlistplayer.feature.vod.FavouriteRef
import app.playlistplayer.feature.vod.MainDispatcherRule
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.ProgressKey
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var env: SearchTestEnv
    private var id = 0L
    private val sections = MutableStateFlow<List<PlaylistSection>>(emptyList())

    private val liveBrowse = object : LiveBrowseSource {
        override fun sections(): Flow<List<PlaylistSection>> = sections
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> = emptyFlow()
    }

    @Before
    fun setUp() = runBlocking {
        env = SearchTestEnv()
        id = env.playlist("Main")
        env.syncLive(
            id,
            listOf(TestChannel("BBC One", "🇬🇧 UK", 101), TestChannel("ITV", "🇬🇧 UK", 102), TestChannel("Late Show", "🔞 Adult", 103)),
        )
        env.syncCatalog(id, movies = listOf(TestMovie(1, "EN - Dune (2021)", "1"), TestMovie(2, "EN - Night Film (2020)", "9")))
        env.indexer.ensureAllFresh()
    }

    @After
    fun tearDown() = env.close()

    private val progress by lazy { PlaybackProgressRepository(RoomPlaybackProgressStore(env.userData)) { env.now++ } }

    private fun viewModel() = HomeViewModel(
        continueWatching = { limit -> progress.continueWatching(limit) },
        titles = { keys -> env.searchStore.vodTitles(keys) },
        favourites = FavouritesRepository(env.userData, env.searchStore, visibility = { env.visibility }, clock = { env.now++ }),
        recents = RoomRecentChannelsStore(env.userData, clock = { env.now++ }),
        liveBrowse = liveBrowse,
        visibility = { env.visibility },
    )

    private suspend fun HomeViewModel.awaitState(predicate: (HomeUiState) -> Boolean): HomeUiState =
        withTimeout(10_000) { state.first { !it.loading && predicate(it) } }

    private suspend fun titleKey(query: String) = env.repository.search(query).movies.single().hits.single().titleKey

    @Test
    fun firstRunShowsTheHintAndOnlyLiveGroups() = runBlocking<Unit> {
        sections.value = listOf(PlaylistSection(id, "Main", listOf(GroupItem(id, 0, "🇬🇧 UK", 2))))
        val state = viewModel().awaitState { it.rows.isNotEmpty() }
        assertThat(state.showFirstRunHint).isTrue()
        assertThat(state.rows).containsExactly(HomeRow.LiveGroups(sections.value.single()))
    }

    @Test
    fun rowsAppearInOrderAndHiddenContentIsExcluded() = runBlocking<Unit> {
        sections.value = listOf(PlaylistSection(id, "Main", listOf(GroupItem(id, 0, "🇬🇧 UK", 2))))
        val dune = titleKey("dune")
        val night = titleKey("night film")
        progress.save(ProgressKey(dune), id, VodKind.MOVIE, "Dune", "1", 30, 100)
        progress.save(ProgressKey(night), id, VodKind.MOVIE, "Night Film", "2", 30, 100)
        progress.save(ProgressKey("$id|movie|gone"), id, VodKind.MOVIE, "Gone", "9", 30, 100) // not in the catalog
        val favourites = RoomFavouritesStore(env.userData, clock = { env.now++ })
        favourites.setFavourite(FavouriteRef(FavouriteKind.CHANNEL, id, "s:101"), true)
        favourites.setFavourite(FavouriteRef(FavouriteKind.CHANNEL, id, "s:103"), true)
        favourites.setFavourite(FavouriteRef(FavouriteKind.MOVIE, id, dune), true)
        favourites.setFavourite(FavouriteRef(FavouriteKind.MOVIE, id, night), true)
        val last = RoomLastChannelStore(env.userData, RoomRecentChannelsStore(env.userData, clock = { env.now++ }), clock = { env.now++ })
        last.setLastChannel(id, LastChannel(1, 0))
        last.setLastChannel(id, LastChannel(2, 1))
        env.visibility.hiddenGroups += id to "🔞 Adult"
        env.visibility.hiddenCategories += Triple(id, VodKind.MOVIE, "9")

        val state = viewModel().awaitState { it.rows.size == 5 }

        assertThat(state.showFirstRunHint).isFalse()
        val cont = state.rows[0] as HomeRow.ContinueWatching
        assertThat(cont.items.map { it.title.title }).containsExactly("Dune")
        assertThat(cont.items.single().fraction).isWithin(0.001f).of(0.3f)
        assertThat((state.rows[1] as HomeRow.FavouriteChannels).channels.map { it.name }).containsExactly("BBC One")
        assertThat((state.rows[2] as HomeRow.FavouriteTitles).titles.map { it.title }).containsExactly("Dune")
        assertThat((state.rows[3] as HomeRow.RecentChannels).channels.map { it.name }).containsExactly("ITV")
        assertThat(state.rows[4]).isInstanceOf(HomeRow.LiveGroups::class.java)
    }

    @Test
    fun emptyRowsAreHiddenAndRefreshPicksUpNewProgress() = runBlocking<Unit> {
        val vm = viewModel()
        val first = vm.awaitState { true }
        assertThat(first.rows).isEmpty()
        assertThat(first.showFirstRunHint).isTrue()

        progress.save(ProgressKey(titleKey("dune")), id, VodKind.MOVIE, "Dune", "1", 50, 100)
        vm.refresh()
        val state = vm.awaitState { it.rows.isNotEmpty() }
        assertThat(state.rows.single()).isInstanceOf(HomeRow.ContinueWatching::class.java)
        assertThat(state.showFirstRunHint).isFalse()
    }
}
