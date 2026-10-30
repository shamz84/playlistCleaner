package app.playlistplayer.feature.live

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.library.RoomFavouritesStore
import app.playlistplayer.feature.search.SearchTestEnv
import app.playlistplayer.feature.search.TestChannel
import app.playlistplayer.feature.search.TestMovie
import app.playlistplayer.feature.vod.FavouriteKind
import app.playlistplayer.feature.vod.FavouriteRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavouritesRepositoryTest {
    private lateinit var env: SearchTestEnv
    private lateinit var repository: FavouritesRepository
    private var id = 0L

    @Before
    fun setUp() = runBlocking {
        env = SearchTestEnv()
        repository = FavouritesRepository(env.userData, env.searchStore, visibility = { env.visibility }, clock = { env.now++ })
        id = env.playlist("Main")
        env.syncLive(
            id,
            listOf(
                TestChannel("BBC One", "🇬🇧 UK", 101),
                TestChannel("ITV", "🇬🇧 UK", 102),
                TestChannel("Plain Entry", "Other"), // no stream id: keyed by ordinal
                TestChannel("Late Show", "🔞 Adult", 104),
            ),
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun favouriteRemovedByResyncIsHiddenThenReappears() = runBlocking<Unit> {
        assertThat(repository.setChannelFavourite(id, 0, true)).isTrue()
        assertThat(repository.toggleChannelFavourite(id, 2)).isTrue()
        assertThat(repository.favouriteChannels().first().map { it.name }).containsExactly("Plain Entry", "BBC One").inOrder()
        assertThat(repository.isChannelFavourite(id, 0)).isTrue()

        // BBC One is dropped by the provider: hidden, the row stays, nothing crashes.
        env.syncLive(id, listOf(TestChannel("ITV", "🇬🇧 UK", 102), TestChannel("Channel 4", "🇬🇧 UK", 103), TestChannel("Plain Entry", "Other")))
        assertThat(repository.favouriteChannels().first().map { it.name }).containsExactly("Plain Entry")
        assertThat(env.userData.favourites()).hasSize(2)

        // It comes back elsewhere in the list: found again by stream id.
        env.syncLive(id, listOf(TestChannel("ITV", "🇬🇧 UK", 102), TestChannel("Channel 4", "🇬🇧 UK", 103), TestChannel("Plain Entry", "Other"), TestChannel("BBC One HD", "News", 101)))
        val back = repository.favouriteChannels().first()
        assertThat(back.map { it.name }).containsExactly("Plain Entry", "BBC One HD").inOrder()
        assertThat(back.last().ordinal).isEqualTo(3)
        assertThat(back.last().groupTitle).isEqualTo("News")
    }

    @Test
    fun lockedGroupsAreFilteredAndHeadersCannotBeFavourited() = runBlocking<Unit> {
        repository.setChannelFavourite(id, 3, true)
        repository.setChannelFavourite(id, 1, true)
        env.visibility.hiddenGroups += id to "🔞 Adult"
        assertThat(repository.favouriteChannels().first().map { it.name }).containsExactly("ITV")
        assertThat(repository.setChannelFavourite(id, 99, true)).isFalse()
    }

    @Test
    fun favouriteTitlesResolveThroughTheIndexAndHideWhenGone() = runBlocking<Unit> {
        env.syncCatalog(id, movies = listOf(TestMovie(1, "EN - Dune (2021)", "1"), TestMovie(2, "EN - Leo (2023)", "9")))
        env.indexer.ensureAllFresh()
        val dune = env.repository.search("dune").movies.single().hits.single()
        val leo = env.repository.search("leo").movies.single().hits.single()
        val favourites = RoomFavouritesStore(env.userData, clock = { env.now++ })
        favourites.setFavourite(FavouriteRef(FavouriteKind.MOVIE, id, dune.titleKey), true)
        favourites.setFavourite(FavouriteRef(FavouriteKind.MOVIE, id, leo.titleKey), true)

        assertThat(repository.favouriteTitles().first().map { it.title }).containsExactly("Leo", "Dune").inOrder()
        env.visibility.hiddenCategories += Triple(id, VodKind.MOVIE, "9")
        assertThat(repository.favouriteTitles().first().map { it.title }).containsExactly("Dune")

        // Dune leaves the catalog: hidden; it returns: shown again with the same key.
        env.syncCatalog(id, movies = listOf(TestMovie(2, "EN - Leo (2023)", "9")))
        env.indexer.ensureAllFresh()
        assertThat(repository.favouriteTitles().first()).isEmpty()
        env.syncCatalog(id, movies = listOf(TestMovie(1, "EN - Dune (2021)", "1")))
        env.indexer.ensureAllFresh()
        assertThat(repository.favouriteTitles().first().single().anchorId).isEqualTo("1")
    }
}
