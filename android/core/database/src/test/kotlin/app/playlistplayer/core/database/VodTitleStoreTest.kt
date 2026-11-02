package app.playlistplayer.core.database

import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VodTitleStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistStore
    private lateinit var titles: VodTitleStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        playlists = PlaylistStore(db)
        titles = VodTitleStore(db)
    }

    @After
    fun tearDown() = db.close()

    /** A title with its ranks given directly (the app computes them at index time). */
    private fun title(
        playlistId: Long,
        key: String,
        ordinal: Int,
        added: Int = ordinal,
        az: Int = ordinal,
        year: Int = ordinal,
        kind: String = "MOVIE",
        name: String = key,
        releaseYear: Int? = null,
        hasTmdb: Boolean = false,
    ) = VodTitleEntity(
        playlistId = playlistId, generation = 0, kind = kind, titleKey = key, ordinal = ordinal, addedRank = added, azRank = az,
        yearRank = year, title = name, normalizedTitle = name.lowercase(), titleYear = releaseYear, year = releaseYear, tmdb = null,
        hasTmdb = hasTmdb, addedAt = null, anchorId = "$ordinal", posterUrl = null, bestQuality = null, versionCount = 1,
        searchText = name.lowercase(), categoryIds = "",
    )

    private fun item(playlistId: Long, key: String, itemId: String, category: String?, tmdb: String? = null, order: Int = 0, kind: String = "MOVIE") =
        VodTitleItemEntity(playlistId, 0, kind, itemId, key, category, tmdb, order)

    private suspend fun playlist(): Long = playlists.createPlaylist("P", PlaylistSourceType.DRIVE, xtreamEnabled = true, createdAt = 1)

    @Test
    fun replaceSwapsGenerationsAtomicallyAndDropsTheOldOne() = runBlocking<Unit> {
        val p = playlist()
        titles.replace(p, catalogGeneration = 1, overridesStamp = "a", titles = listOf(title(p, "old", 0)), items = listOf(item(p, "old", "1", "10")), now = 5)
        val first = titles.state(p)!!
        assertThat(first.catalogGeneration).isEqualTo(1)
        assertThat(first.overridesStamp).isEqualTo("a")

        // Rows of a generation that is not active (e.g. a rebuild still being written) are invisible.
        db.vodTitleDao().insertTitles(listOf(title(p, "pending", 0).copy(generation = 99)))
        assertThat(titles.page(p, "MOVIE", TitleOrder.ADDED, emptySet(), 0, 10).map { it.titleKey }).containsExactly("old")

        titles.replace(p, catalogGeneration = 2, overridesStamp = "a", titles = listOf(title(p, "new", 0)), items = listOf(item(p, "new", "1", "10")), now = 6)
        val second = titles.state(p)!!
        assertThat(second.activeGeneration).isGreaterThan(first.activeGeneration!!)
        assertThat(second.catalogGeneration).isEqualTo(2)
        assertThat(second.titleCount).isEqualTo(1)
        assertThat(titles.page(p, "MOVIE", TitleOrder.ADDED, emptySet(), 0, 10).map { it.titleKey }).containsExactly("new")
        assertThat(titles.titleForItem(p, "MOVIE", "1")!!.titleKey).isEqualTo("new")
        // The previous generation and the stray rows are gone.
        assertThat(titles.generationsPresent(p)).containsExactly(second.activeGeneration)
    }

    @Test
    fun pagesFollowEachPrecomputedRank() = runBlocking<Unit> {
        val p = playlist()
        val rows = listOf(
            title(p, "a", 0, added = 2, az = 0, year = 1),
            title(p, "b", 1, added = 0, az = 2, year = 2),
            title(p, "c", 2, added = 1, az = 1, year = 0),
            title(p, "show", 0, kind = "SERIES"),
        )
        titles.replace(p, 1, "", rows, rows.map { item(p, it.titleKey, it.titleKey, null, kind = it.kind) }, 1)

        suspend fun keys(order: TitleOrder, offset: Int = 0, limit: Int = 10) =
            titles.page(p, "MOVIE", order, emptySet(), offset, limit).map { it.titleKey }
        assertThat(keys(TitleOrder.ADDED)).containsExactly("b", "c", "a").inOrder()
        assertThat(keys(TitleOrder.NAME)).containsExactly("a", "c", "b").inOrder()
        assertThat(keys(TitleOrder.YEAR)).containsExactly("c", "a", "b").inOrder()
        assertThat(keys(TitleOrder.ADDED, offset = 1, limit = 1)).containsExactly("c")
        assertThat(titles.count(p, "MOVIE", emptySet())).isEqualTo(3)
        assertThat(titles.count(p, "SERIES", emptySet())).isEqualTo(1)
    }

    @Test
    fun aTitleIsHiddenOnlyWhenEveryVersionIsInAHiddenCategory() = runBlocking<Unit> {
        val p = playlist()
        val rows = listOf(title(p, "adultOnly", 0), title(p, "mixed", 1), title(p, "uncategorised", 2), title(p, "plain", 3))
        val items = listOf(
            item(p, "adultOnly", "1", "99"),
            item(p, "adultOnly", "2", "98"),
            item(p, "mixed", "3", "99"),
            item(p, "mixed", "4", "10"),
            item(p, "uncategorised", "5", "99"),
            item(p, "uncategorised", "6", null),
            item(p, "plain", "7", "10"),
        )
        titles.replace(p, 1, "", rows, items, 1)
        val hidden = setOf("99", "98")

        assertThat(titles.page(p, "MOVIE", TitleOrder.ADDED, hidden, 0, 10).map { it.titleKey })
            .containsExactly("mixed", "uncategorised", "plain").inOrder()
        assertThat(titles.count(p, "MOVIE", hidden)).isEqualTo(3)
        assertThat(titles.titles(p, "MOVIE", listOf("adultOnly", "plain"), hidden).map { it.titleKey }).containsExactly("plain")
        // Detail lookups by item ignore visibility (the screens decide).
        assertThat(titles.titleForItem(p, "MOVIE", "1")!!.titleKey).isEqualTo("adultOnly")
        assertThat(titles.items(p, "mixed").map { it.itemId }).containsExactly("3", "4").inOrder()
    }

    @Test
    fun trendingLookupsUseTmdbIdsAndTitlesWithoutTmdb() = runBlocking<Unit> {
        val p = playlist()
        val rows = listOf(
            title(p, "dune", 0, name = "Dune", hasTmdb = true),
            title(p, "bluey", 1, name = "Bluey"),
            title(p, "bluey-tmdb", 2, name = "Bluey", hasTmdb = true),
            title(p, "bluey-adult", 3, name = "Bluey"),
        )
        val items = listOf(
            item(p, "dune", "1", "10", tmdb = "438631"),
            item(p, "dune", "2", "10", tmdb = "438631"),
            item(p, "bluey", "3", "10"),
            item(p, "bluey-tmdb", "4", "10", tmdb = "77"),
            item(p, "bluey-adult", "5", "99"),
        )
        titles.replace(p, 1, "", rows, items, 1)

        assertThat(titles.itemsByTmdb(p, "MOVIE", listOf("438631", "5")).map { it.itemId }).containsExactly("1", "2")
        assertThat(titles.itemsByTmdb(p, "SERIES", listOf("438631"))).isEmpty()
        // The title fallback only covers titles without any tmdb id, and parental hiding applies.
        assertThat(titles.titlesByName(p, "MOVIE", listOf("bluey"), setOf("99")).map { it.titleKey }).containsExactly("bluey")
        assertThat(titles.titlesByName(p, "MOVIE", listOf("bluey"), emptySet()).map { it.titleKey }).containsExactly("bluey", "bluey-adult")
    }

    @Test
    fun removingThePlaylistOrItsCatalogClearsTheIndex() = runBlocking<Unit> {
        val p = playlist()
        val q = playlist()
        listOf(p, q).forEach { id -> titles.replace(id, 1, "", listOf(title(id, "t", 0)), listOf(item(id, "t", "1", null)), 1) }

        CatalogStore(db).clearPlaylist(p)
        assertThat(titles.state(p)).isNull()
        assertThat(titles.count(p, "MOVIE", emptySet())).isEqualTo(0)
        assertThat(titles.count(q, "MOVIE", emptySet())).isEqualTo(1)

        playlists.deletePlaylist(q)
        assertThat(titles.state(q)).isNull()
        assertThat(titles.generationsPresent(q)).isEmpty()
    }
}
