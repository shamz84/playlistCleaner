package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.TitleOverride
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodTitle
import app.playlistplayer.core.database.VodTitleStore
import app.playlistplayer.feature.search.SearchTestEnv
import app.playlistplayer.feature.search.TestMovie
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/** The title index over real Room (U20): the SQL paths browse, Trending and sync rely on. */
@RunWith(RobolectricTestRunner::class)
class VodTitleIndexRoomTest {
    private lateinit var env: SearchTestEnv
    private var main = 0L

    @Before
    fun setUp() = runBlocking {
        env = SearchTestEnv()
        main = env.playlist("Main")
        Unit
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun theFirstPageOfA45kCatalogIsOneQueryWithoutGrouping() = runBlocking<Unit> {
        // ~45k movies like the provider's catalog: 40k distinct titles, 5k second versions, one adult category.
        val movies = (0 until 45_000).map { i ->
            val n = i % 40_000
            val category = if (n % 50 == 0) "9" else "1"
            TestMovie(i.toLong() + 1, "EN - Movie ${n + 1} (${1950 + n % 75})${if (i >= 40_000) " 4K" else ""}", category, tmdb = "${100_000 + n}")
        }
        env.syncCatalog(main, movies = movies)
        env.titleIndexer.ensureFresh(main)
        val grouped = env.grouper.calls.get()
        assertThat(grouped).isEqualTo(2) // once per kind, in the background build

        val hidden = setOf("9")
        var first: List<VodCard> = emptyList()
        var total = 0
        val elapsed = measureTimeMillis {
            total = env.vod.count(main, VodKind.MOVIE, hidden)
            first = env.vod.page(main, VodKind.MOVIE, VodSort.RECENTLY_ADDED, hidden, 0, VodBrowseViewModel.DEFAULT_PAGE_SIZE)
        }
        println("U20 first page of ${movies.size} movies: count + page in ${elapsed}ms")
        assertThat(first).hasSize(VodBrowseViewModel.DEFAULT_PAGE_SIZE)
        assertThat(total).isEqualTo(40_000 - 800) // every 50th title is only in the hidden category
        // A generous bound for a loaded CI host; typically a few tens of milliseconds.
        assertThat(elapsed).isLessThan(3_000)

        // Every other browse read is an indexed query too: none of them groups the catalog.
        for (sort in VodSort.entries) {
            assertThat(env.vod.page(main, VodKind.MOVIE, sort, hidden, 30_000, 60)).hasSize(60)
        }
        val trending = env.vod.trending(main, VodKind.MOVIE, listOf(TrendingEntry(105_041, "Movie 5042", null, 1966)), hidden, 20)
        assertThat(trending.map { it.title }).containsExactly("Movie 5042")
        assertThat(trending.single().versionCount).isEqualTo(1)
        assertThat(env.vod.title(main, VodKind.MOVIE, "40002")!!.title.versions.map { it.item.id }).containsExactly("2", "40002").inOrder()
        assertThat(env.grouper.calls.get()).isEqualTo(grouped)
    }

    @Test
    fun aCatalogSyncRecomputesTheKeysAndSwapsTheOldGenerationOut() = runBlocking<Unit> {
        env.syncCatalog(main, movies = listOf(TestMovie(1, "EN - Dune (2021)", "1", tmdb = "438631"), TestMovie(2, "EN - Leo (2023)", "1")))
        env.titleIndexer.ensureFresh(main)
        val store = VodTitleStore(env.db)
        val first = store.state(main)!!
        assertThat(first.catalogGeneration).isEqualTo(env.catalog.state(main)!!.activeGeneration)
        val duneKey = env.vod.title(main, VodKind.MOVIE, "1")!!.key

        // Unchanged catalog: no regrouping.
        assertThat(env.titleIndexer.ensureFresh(main)).isFalse()

        // A sync brings a new generation: Dune gets a second version, Leo is gone, Arrival is new.
        env.syncCatalog(
            main,
            movies = listOf(
                TestMovie(1, "EN - Dune (2021)", "1", tmdb = "438631"),
                TestMovie(3, "EN - Dune (2021) 4K", "1", tmdb = "438631"),
                TestMovie(4, "EN - Arrival (2016)", "1"),
            ),
        )
        // Until the rebuild the old index stays readable (built from the previous catalog).
        assertThat(env.vod.page(main, VodKind.MOVIE, VodSort.A_Z, emptySet(), 0, 10).map { it.title }).containsExactly("Dune", "Leo").inOrder()
        assertThat(env.titleIndexer.ensureFresh(main)).isTrue()

        val second = store.state(main)!!
        assertThat(second.catalogGeneration).isEqualTo(env.catalog.state(main)!!.activeGeneration)
        assertThat(store.generationsPresent(main)).containsExactly(second.activeGeneration)
        assertThat(env.vod.page(main, VodKind.MOVIE, VodSort.A_Z, emptySet(), 0, 10).map { it.title }).containsExactly("Arrival", "Dune").inOrder()
        val dune = env.vod.title(main, VodKind.MOVIE, "3")!!
        assertThat(dune.key).isEqualTo(duneKey) // stable identity across re-syncs
        assertThat(dune.title.versions.map { it.item.id }).containsExactly("1", "3").inOrder()
        assertThat(env.vod.title(main, VodKind.MOVIE, "2")).isNull()
    }

    @Test
    fun trendingMatchesByTmdbIdThenTitleAndYearInRankOrder() = runBlocking<Unit> {
        env.syncCatalog(
            main,
            movies = listOf(
                TestMovie(1, "EN - Dune (2021) 4K", "1", tmdb = "438631"),
                TestMovie(2, "EN - Dune (2021) HD", "1", tmdb = "438631"),
                TestMovie(3, "EN - Leo (2023)", "1", tmdb = "1075794"),
                TestMovie(4, "EN - Bluey (2018)", "1"),
                TestMovie(5, "EN - Planet Earth", "1"),
                TestMovie(6, "EN - Adult Film (2020)", "9", tmdb = "5"),
                // A sports item reusing a film's tmdb id with another year: rejected.
                TestMovie(7, "SOC - Arsenal - Liverpool 04.02.2024", "1", tmdb = "470044"),
            ),
        )
        env.titleIndexer.ensureFresh(main)
        val entries = listOf(
            TrendingEntry(999, "Not In Catalog", null, 2024),
            TrendingEntry(5, "Adult Film", null, 2020), // hidden category
            TrendingEntry(1075794, "Leo", null, 2023),
            TrendingEntry(77, "Bluey", null, 2018), // no tmdb in the catalog: title + year
            TrendingEntry(470044, "Arsenal", null, 2017),
            TrendingEntry(438631, "Dune", null, 2021),
            TrendingEntry(88, "Planet Earth", null, 2006), // the catalog has no year: movies need one
        )
        val matched = env.vod.trending(main, VodKind.MOVIE, entries, setOf("9"), 20)
        assertThat(matched.map { it.title }).containsExactly("Leo", "Bluey", "Dune").inOrder()
        assertThat(matched.last().versionCount).isEqualTo(2)
        assertThat(env.vod.trending(main, VodKind.MOVIE, entries, setOf("9"), 2).map { it.title }).containsExactly("Leo", "Bluey").inOrder()
        assertThat(env.vod.trending(main, VodKind.MOVIE, entries, emptySet(), 20).map { it.title }).containsExactly("Adult Film", "Leo", "Bluey", "Dune").inOrder()
    }

    @Test
    fun indexBuildsNeverOverlapOtherBackgroundJobs() = runBlocking<Unit> {
        env.syncCatalog(main, movies = (1..200L).map { TestMovie(it, "EN - Title $it (2000)", "1") })
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        suspend fun heavy(ms: Long) {
            maxInside.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(ms)
            inside.decrementAndGet()
        }
        val grouped = AtomicInteger()
        val slowGrouper = object : (List<VodItem>, List<TitleOverride>) -> List<VodTitle> {
            override fun invoke(items: List<VodItem>, overrides: List<TitleOverride>): List<VodTitle> {
                grouped.incrementAndGet()
                maxInside.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(30)
                inside.decrementAndGet()
                return TitleGrouper.group(items, overrides)
            }
        }
        val indexer = VodTitleIndexer(
            source = CatalogStoreVodSource(env.catalog) { listOf(VodPlaylist(main, "Main")) },
            index = env.titleIndex,
            overrideStamp = { env.overrides.stamp(it) },
            gate = env.gate,
            group = slowGrouper,
        )
        // Queued in this order (the gate is FIFO): a catalog sync and an EPG import (simulated, on the
        // same gate the real syncers take), the title index build, then the search index.
        listOf<suspend () -> Unit>(
            { env.gate.run("catalog-sync") { heavy(80) } },
            { indexer.ensureFresh(main) },
            { env.gate.run("epg-import") { heavy(80) } },
            { env.indexer.ensureFresh(main) },
        ).mapIndexed { i, job -> async(Dispatchers.Default) { delay(i * 15L); job() } }.awaitAll()
        assertThat(grouped.get()).isEqualTo(2)
        assertThat(maxInside.get()).isEqualTo(1)
        // The search index was built from the title index the slow build wrote, without regrouping.
        assertThat(env.grouper.calls.get()).isEqualTo(0)
        assertThat(env.searchStore.vodCount(main)).isEqualTo(200)
        assertThat(env.titleIndex.state(main)!!.activeGeneration).isNotNull()
    }
}
