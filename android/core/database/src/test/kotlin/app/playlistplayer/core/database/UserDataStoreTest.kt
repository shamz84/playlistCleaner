package app.playlistplayer.core.database

import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.PlaybackProgressEntity
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.entity.TitleOverrideEntity
import app.playlistplayer.core.database.entity.TitlePickEntity
import app.playlistplayer.core.database.search.SearchLiveEntity
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserDataStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistStore
    private lateinit var store: UserDataStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        playlists = PlaylistStore(db)
        store = UserDataStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Writes and activates a generation; each entry is (name, streamId?). */
    private suspend fun sync(playlistId: Long, entries: List<Pair<String, Long?>>) {
        val generation = playlists.beginGeneration(playlistId)
        val writer = playlists.writer(playlistId, generation)
        entries.forEachIndexed { i, (name, streamId) ->
            writer.add(
                ChannelEntity(
                    playlistId, generation, i, groupOrdinal = 0, name = name, streamId = streamId, containerExtension = "ts",
                    tvgId = null, tvgName = null, logo = null, cuid = null, tvgChno = null, catchupType = null, catchupDays = 0,
                    isHeader = false,
                ),
            )
        }
        writer.writeGroups(listOf(ChannelGroupEntity(playlistId, generation, 0, "🇬🇧 UK", entries.size)))
        playlists.activateGeneration(playlistId, generation) { it }
    }

    @Test
    fun favouriteChannelHiddenWhileMissingAndBackWhenItReturns() = runBlocking<Unit> {
        val id = playlists.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1)
        sync(id, listOf("BBC One" to 10L, "ITV" to 11L))
        store.setFavourite("CHANNEL", id, StreamKeys.of(10L, 0), favourite = true, now = 5)
        store.setFavourite("CHANNEL", id, StreamKeys.of(null, 1), favourite = true, now = 6) // by ordinal
        assertThat(store.observeFavouriteChannels().first().map { it.name }).containsExactly("ITV", "BBC One").inOrder()

        // Re-sync drops stream 10 and moves ITV: the favourite row stays, the resolved list hides it.
        sync(id, listOf("Channel 4" to 12L))
        assertThat(store.observeFavouriteChannels().first()).isEmpty()
        assertThat(store.favourites()).hasSize(2)

        // It returns at a different position: found again by stream id.
        sync(id, listOf("Channel 4" to 12L, "Dave" to 13L, "BBC One HD" to 10L))
        val resolved = store.observeFavouriteChannels().first()
        assertThat(resolved.map { it.name to it.ordinal }).containsExactly("Dave" to 1, "BBC One HD" to 2).inOrder()
        assertThat(resolved.first().groupTitle).isEqualTo("🇬🇧 UK")
    }

    @Test
    fun recentChannelsKeepTheLatest30() = runBlocking<Unit> {
        val id = playlists.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1)
        sync(id, (0 until 40).map { "C$it" to (100L + it) })
        repeat(40) { store.recordRecent(id, StreamKeys.of(100L + it, it), now = 1000L + it) }
        store.recordRecent(id, StreamKeys.of(100L, 0), now = 5000) // re-watch moves to the front

        val rows = store.recentRows()
        assertThat(rows).hasSize(30)
        assertThat(rows.first().streamKey).isEqualTo("s:100")
        assertThat(rows.map { it.streamKey }).doesNotContain("s:110")
        assertThat(store.observeRecentChannels().first().map { it.name }.take(3)).containsExactly("C0", "C39", "C38").inOrder()
    }

    @Test
    fun lastChannelFollowsItsStreamAcrossRenumbering() = runBlocking<Unit> {
        val id = playlists.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1)
        sync(id, listOf("A" to 1L, "B" to 2L))
        store.setLastChannel(id, ordinal = 1, groupOrdinal = 0, streamKey = "s:2", now = 1)
        sync(id, listOf("Z" to 9L, "A" to 1L, "B" to 2L))
        assertThat(store.resolveLastChannel(id)!!.ordinal).isEqualTo(2)
        sync(id, listOf("Z" to 9L))
        assertThat(store.resolveLastChannel(id)).isNull()
    }

    @Test
    fun progressPicksAndOverridesPersist() = runBlocking<Unit> {
        val id = playlists.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1)
        val key = "$id|movie|tmdb:1|year:2021"
        store.putProgress(PlaybackProgressEntity("", key, null, null, id, "MOVIE", "Dune", "v1", 1000, 10_000, 1, false))
        store.putProgress(PlaybackProgressEntity("", key, 1, 2, id, "SERIES", "S1 E2", "e2", 5, 10, 2, false))
        store.setPick(TitlePickEntity(key, id, "v9", 1))
        store.addOverride(TitleOverrideEntity(playlistId = id, kind = "SPLIT", payload = "1,2", createdAt = 1))

        assertThat(store.progress(key, null, null)!!.positionMs).isEqualTo(1000)
        assertThat(store.progress(key, 1, 2)!!.label).isEqualTo("S1 E2")
        assertThat(store.recentProgress(10).map { it.label }).containsExactly("S1 E2", "Dune").inOrder()
        assertThat(store.pick(key)!!.versionId).isEqualTo("v9")
        assertThat(store.overrides(id).single().payload).isEqualTo("1,2")
    }

    @Test
    fun deletingAPlaylistRemovesEveryPersonalRow() = runBlocking<Unit> {
        val a = playlists.createPlaylist("A", PlaylistSourceType.DRIVE, true, 1)
        val b = playlists.createPlaylist("B", PlaylistSourceType.DRIVE, true, 2)
        for (id in listOf(a, b)) {
            sync(id, listOf("BBC One" to 10L))
            store.setFavourite("CHANNEL", id, "s:10", true, 1)
            store.setFavourite("MOVIE", id, "$id|movie|x", true, 1)
            store.recordRecent(id, "s:10", 1)
            store.setLastChannel(id, 0, 0, "s:10", 1)
            store.putProgress(PlaybackProgressEntity("", "$id|movie|x", null, null, id, "MOVIE", "X", "v", 1, 10, 1, false))
            store.setPick(TitlePickEntity("$id|movie|x", id, "v", 1))
            store.addOverride(TitleOverrideEntity(playlistId = id, kind = "MERGE", payload = "1,2", createdAt = 1))
            SearchStore(db).replaceLive(id, 1, listOf(SearchLiveEntity(0, id, 0, 0, "G", "s:10", "bbc one", "BBC One", null)), 1)
        }
        assertThat(store.rowCount(a)).isEqualTo(7)

        playlists.deletePlaylist(a)

        assertThat(store.rowCount(a)).isEqualTo(0)
        assertThat(store.rowCount(b)).isEqualTo(7)
        assertThat(SearchStore(db).liveCount(a)).isEqualTo(0)
        assertThat(SearchStore(db).liveCount(b)).isEqualTo(1)
    }
}
