package app.playlistplayer.core.database.search

import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var store: SearchStore
    private lateinit var playlists: PlaylistStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = SearchStore(db)
        playlists = PlaylistStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun live(playlistId: Long, ordinal: Int, name: String, group: Int = 0) = SearchLiveEntity(
        playlistId = playlistId,
        ordinal = ordinal,
        groupOrdinal = group,
        groupName = "G$group",
        streamKey = "s:${1000 + ordinal}",
        normalizedName = name.lowercase(),
        displayName = name,
        logo = null,
    )

    private fun vod(playlistId: Long, key: String, title: String, year: Int? = null, kind: String = "MOVIE") = SearchVodEntity(
        playlistId = playlistId,
        kind = kind,
        titleKey = key,
        normalizedTitle = title.lowercase(),
        displayTitle = title,
        year = year,
        anchorId = key,
        posterUrl = null,
        categoryIds = "1",
        versionCount = 1,
    )

    @Test
    fun trigramMatchesSubstringsAcrossPlaylistsBestFirst() = runBlocking<Unit> {
        store.replaceLive(1, 1, listOf(live(1, 0, "UK BBC One HD"), live(1, 1, "BBC One"), live(1, 2, "BBC Two")), now = 1)
        store.replaceLive(2, 1, listOf(live(2, 0, "BBC One London"), live(2, 1, "ITV 1")), now = 1)

        val hits = store.searchLive("bbc one", limit = 50)

        assertThat(hits.map { it.playlistId to it.displayName }).containsExactly(
            1L to "BBC One", // exact
            2L to "BBC One London", // prefix
            1L to "UK BBC One HD", // substring
        ).inOrder()
    }

    @Test
    fun shortQueriesFallBackToPrefixLike() = runBlocking<Unit> {
        store.replaceLive(1, 1, listOf(live(1, 0, "itv 1"), live(1, 1, "uk itv 2"), live(1, 2, "british")), now = 1)

        // "it" cannot use trigrams; name and word prefixes still match, inner substrings do not.
        assertThat(store.searchLive("it", 50).map { it.displayName }).containsExactly("itv 1", "uk itv 2").inOrder()
        assertThat(store.searchLive("i", 50).map { it.displayName }).containsExactly("itv 1", "uk itv 2").inOrder()
        assertThat(store.searchLive("  ", 50)).isEmpty()
    }

    @Test
    fun vodTitlesMatchPartialWordsAndKeepKinds() = runBlocking<Unit> {
        store.replaceVod(
            1,
            catalogGeneration = 3,
            overridesStamp = "x",
            rows = listOf(vod(1, "k1", "Dune", 2021), vod(1, "k2", "Dune: Part Two", 2024), vod(1, "k3", "Dunkirk", 2017), vod(1, "k4", "Arrival")),
            now = 1,
        )
        store.replaceVod(1, 3, "x", rows = listOf(vod(1, "k1", "Dune", 2021), vod(1, "s1", "Dune Prophecy", kind = "SERIES")), now = 2)

        val hits = store.searchVod("dun", 50)
        assertThat(hits.map { it.titleKey }).containsExactly("k1", "s1").inOrder()
        assertThat(hits.map { it.kind }).containsExactly("MOVIE", "SERIES").inOrder()
        assertThat(store.state(1)!!.catalogGeneration).isEqualTo(3)
        assertThat(store.vodTitles(listOf("s1", "missing")).single().displayTitle).isEqualTo("Dune Prophecy")
    }

    @Test
    fun replacingRowsKeepsTheFtsIndexInStep() = runBlocking<Unit> {
        store.replaceLive(1, 1, listOf(live(1, 0, "Sky Sports Main Event")), now = 1)
        assertThat(store.liveIndexMatches("sports", 1)).isEqualTo(1)

        store.replaceLive(1, 2, listOf(live(1, 0, "Eurosport 1"), live(1, 1, "Eurosport 2")), now = 2)
        assertThat(store.liveIndexMatches("sports", 1)).isEqualTo(0)
        assertThat(store.liveIndexMatches("eurosport", 1)).isEqualTo(2)
        assertThat(store.searchLive("sky", 10)).isEmpty()
        assertThat(store.state(1)!!.liveGeneration).isEqualTo(2)
    }

    @Test
    fun deletingAPlaylistDropsItsIndexOnly() = runBlocking<Unit> {
        val a = playlists.createPlaylist("A", PlaylistSourceType.DRIVE, true, 1)
        val b = playlists.createPlaylist("B", PlaylistSourceType.DRIVE, true, 2)
        store.replaceLive(a, 1, listOf(live(a, 0, "BBC One")), now = 1)
        store.replaceLive(b, 1, listOf(live(b, 0, "BBC One")), now = 1)
        store.replaceVod(a, 1, null, listOf(vod(a, "k", "Dune")), now = 1)

        playlists.deletePlaylist(a)

        assertThat(store.searchLive("bbc", 10).map { it.playlistId }).containsExactly(b)
        assertThat(store.searchVod("dune", 10)).isEmpty()
        assertThat(store.liveIndexMatches("bbc", a)).isEqualTo(0)
        assertThat(store.state(a)).isNull()
        assertThat(store.state(b)).isNotNull()
    }

    @Test
    fun searchOver15kEntriesIsFast() = runBlocking<Unit> {
        val words = listOf("sports", "news", "movies", "kids", "music", "docs", "drama", "comedy", "action", "racing")
        val rows = (0 until 15_000).map { i ->
            val name = when {
                i % 1000 == 0 -> "UK BBC One ${i / 1000}"
                else -> "Channel ${words[i % words.size]} ${i % 97} ${words[(i / 10) % words.size]} $i"
            }
            live(1, i, name, group = i / 100)
        }
        store.replaceLive(1, 1, rows, now = 1)
        store.replaceVod(1, 1, null, (0 until 10_000).map { vod(1, "k$it", "Title ${words[it % words.size]} $it") }, now = 1)
        store.searchLive("warm up", 50) // first query compiles statements

        val timings = listOf("bbc one", "sports 4", "news", "ra", "channel music 1").map { q ->
            val start = System.nanoTime()
            val live = store.searchLive(q, 200)
            val vod = store.searchVod(q, 100)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            println("SEARCH_PERF query='$q' live=${live.size} vod=${vod.size} ms=${"%.1f".format(ms)}")
            ms
        }
        assertThat(store.searchLive("bbc one", 200)).hasSize(15)
        assertThat(timings.max()).isLessThan(200.0)
    }
}
