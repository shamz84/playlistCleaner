package app.playlistplayer.feature.library

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.feature.search.SearchTestEnv
import app.playlistplayer.feature.search.TestChannel
import app.playlistplayer.feature.vod.FavouriteKind
import app.playlistplayer.feature.vod.FavouriteRef
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.ProgressKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryStoresTest {
    private lateinit var env: SearchTestEnv
    private var id = 0L

    @Before
    fun setUp() = runBlocking {
        env = SearchTestEnv()
        id = env.playlist("Main")
        env.syncLive(id, (0 until 40).map { TestChannel("Channel $it", if (it < 20) "UK" else "Sports", 500L + it) })
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun picksPersistAcrossStoreInstances() = runBlocking<Unit> {
        val key = "$id|movie|tmdb:438631|year:2021"
        RoomTitlePickStore(env.userData).setPick(key, "103")
        val reopened = RoomTitlePickStore(env.userData)
        assertThat(reopened.pick(key)).isEqualTo("103")
        reopened.clearPick(key)
        assertThat(RoomTitlePickStore(env.userData).pick(key)).isNull()
    }

    @Test
    fun continueWatchingAppliesTheU10Rules() = runBlocking<Unit> {
        var clock = 0L
        val repository = PlaybackProgressRepository(RoomPlaybackProgressStore(env.userData)) { clock++ }
        val early = ProgressKey("$id|movie|a")
        val middle = ProgressKey("$id|movie|b")
        val watched = ProgressKey("$id|movie|c")
        val e1 = ProgressKey("$id|series|d", season = 1, episode = 1)
        val e2 = ProgressKey("$id|series|d", season = 1, episode = 2)
        repository.save(early, id, VodKind.MOVIE, "A", "1", positionMs = 3, durationMs = 100) // 3%: no resume
        repository.save(middle, id, VodKind.MOVIE, "B", "2", positionMs = 50, durationMs = 100)
        repository.save(watched, id, VodKind.MOVIE, "C", "3", positionMs = 96, durationMs = 100) // 96%: watched
        repository.save(e1, id, VodKind.SERIES, "S1 E1", "e1", positionMs = 40, durationMs = 100)
        repository.save(e2, id, VodKind.SERIES, "S1 E2", "e2", positionMs = 20, durationMs = 100)

        val items = PlaybackProgressRepository(RoomPlaybackProgressStore(env.userData)).continueWatching()
        // One per title (latest episode), newest first; watched and too-early items excluded.
        assertThat(items.map { it.label }).containsExactly("S1 E2", "B").inOrder()
        assertThat(repository.resumePosition(middle)).isEqualTo(50)
        assertThat(repository.resumePosition(early)).isNull()
        assertThat(repository.isWatched(watched)).isTrue()
        assertThat(env.userData.progress("$id|movie|c", null, null)!!.watched).isTrue()
        assertThat(repository.get(e1)!!.key).isEqualTo(e1)
    }

    @Test
    fun tuningRecordsLastAndRecentChannelsCappedAt30() = runBlocking<Unit> {
        val recents = RoomRecentChannelsStore(env.userData, clock = { env.now++ })
        val last = RoomLastChannelStore(env.userData, recents, clock = { env.now++ })
        for (ordinal in 0 until 40) last.setLastChannel(id, LastChannel(ordinal, if (ordinal < 20) 0 else 1))
        last.setLastChannel(id, LastChannel(3, 0))

        val recent = recents.observe().first()
        assertThat(recent).hasSize(30)
        assertThat(recent.first().name).isEqualTo("Channel 3")
        assertThat(recent[1].name).isEqualTo("Channel 39")
        assertThat(recent.map { it.name }).doesNotContain("Channel 9")
        assertThat(last.lastChannel(id)).isEqualTo(LastChannel(3, 0))

        // A re-sync that shifts every entry: the last channel follows its stream id.
        env.syncLive(id, listOf(TestChannel("New", "UK", 1)) + (0 until 40).map { TestChannel("Channel $it", "UK", 500L + it) })
        assertThat(last.lastChannel(id)).isEqualTo(LastChannel(4, 0))
    }

    @Test
    fun favouritesStoreIsSharedAcrossKinds() = runBlocking<Unit> {
        val store = RoomFavouritesStore(env.userData, clock = { env.now++ })
        val movie = FavouriteRef(FavouriteKind.MOVIE, id, "$id|movie|x")
        val channel = FavouriteRef(FavouriteKind.CHANNEL, id, "s:500")
        store.setFavourite(movie, true)
        store.setFavourite(channel, true)
        store.setFavourite(channel, true) // idempotent
        assertThat(store.observe().first()).containsExactly(channel, movie)
        assertThat(store.isFavourite(movie)).isTrue()
        store.setFavourite(movie, false)
        assertThat(RoomFavouritesStore(env.userData).observe().first()).containsExactly(channel)
    }
}
