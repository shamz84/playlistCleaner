package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class VodCatalogRepositoryTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val grouping = StandardTestDispatcher(main.dispatcher.scheduler, name = "vod-grouping")

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .playlist(2, "Family")
        .vodCategory(1, "10", "EN Movies")
        .vodCategory(1, "20", "4K Movies")
        .vodCategory(1, "30", "Empty")
        .vodCategory(2, "10", "EN Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "20", tmdb = "438631")
        .movie(1, 102, "EN - Dune (2021) HD", "10", tmdb = "438631")
        .movie(1, 104, "EN - Leo (2023) FHD", "10", tmdb = "1075794")
        .movie(1, 105, "EN - Arrival (2016)", "10")
        // Same title and tmdb in another playlist: must stay separate.
        .movie(2, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631")
        .movie(2, 202, "EN - Dune (2021) HD", "10", tmdb = "438631")

    private val repository = VodCatalogRepository(source, groupingDispatcher = grouping, pageSize = 2)

    @Test
    fun `grouping runs on the grouping dispatcher, not the caller's`() = runTest(main.dispatcher) {
        val seen = mutableListOf<ContinuationInterceptor?>()
        repository.onGrouping = { seen += it[ContinuationInterceptor] }
        repository.categoryTitles(VodCategory(1, VodKind.MOVIE, "10", "EN Movies"))
        repository.index(1, VodKind.MOVIE)
        assertThat(seen).hasSize(2)
        assertThat(seen.all { it === grouping }).isTrue()
    }

    @Test
    fun `index merges across categories and pages through the catalog`() = runTest(main.dispatcher) {
        val index = repository.index(1, VodKind.MOVIE)
        assertThat(index.titles.map { it.title.title }).containsExactly("Dune", "Leo", "Arrival").inOrder()
        val dune = index.titleForItem("101")!!
        assertThat(dune.title.versions.map { it.item.id }).containsExactly("101", "102").inOrder()
        assertThat(index.titleForItem("102")).isSameInstanceAs(dune)
        assertThat(index.titleForKey(dune.key)).isSameInstanceAs(dune)
        assertThat(dune.bestQuality).isEqualTo(QualityTier.UHD)
        assertThat(dune.posterUrl).isEqualTo("https://img.example/101.jpg")
        assertThat(index.titleForItem("105")!!.bestQuality).isNull() // unlabeled: no badge
        assertThat(source.pageCalls).isEqualTo(3) // 4 rows, page size 2
    }

    @Test
    fun `titles are never merged across playlists`() = runTest(main.dispatcher) {
        val one = repository.index(1, VodKind.MOVIE).titleForItem("101")!!
        val two = repository.index(2, VodKind.MOVIE).titleForItem("101")!!
        assertThat(one.key).isNotEqualTo(two.key)
        assertThat(one.title.versions.map { it.item.playlistId }.distinct()).containsExactly("1")
        assertThat(two.title.versions.map { it.item.id }).containsExactly("101", "202")
        assertThat(two.title.versions.map { it.item.playlistId }.distinct()).containsExactly("2")
        assertThat(repository.index(2, VodKind.MOVIE).titleForItem("102")).isNull()
    }

    @Test
    fun `results are cached per playlist and generation`() = runTest(main.dispatcher) {
        val category = VodCategory(1, VodKind.MOVIE, "10", "EN Movies")
        val first = repository.categoryTitles(category)
        val firstIndex = repository.index(1, VodKind.MOVIE)
        assertThat(repository.groupingRuns).isEqualTo(2)
        assertThat(repository.categoryTitles(category)).isSameInstanceAs(first)
        assertThat(repository.index(1, VodKind.MOVIE)).isSameInstanceAs(firstIndex)
        assertThat(repository.groupingRuns).isEqualTo(2)

        // Other playlists have their own entries.
        repository.index(2, VodKind.MOVIE)
        assertThat(repository.groupingRuns).isEqualTo(3)

        // A catalog refresh (new generation) regroups that playlist only.
        source.generations[1] = 2
        assertThat(repository.categoryTitles(category)).isNotSameInstanceAs(first)
        assertThat(repository.index(1, VodKind.MOVIE)).isNotSameInstanceAs(firstIndex)
        assertThat(repository.groupingRuns).isEqualTo(5)
        repository.index(2, VodKind.MOVIE)
        assertThat(repository.groupingRuns).isEqualTo(5)
    }

    @Test
    fun `concurrent requests share one grouping pass`() = runTest(main.dispatcher) {
        val a = async { repository.index(1, VodKind.MOVIE) }
        val b = async { repository.index(1, VodKind.MOVIE) }
        runCurrent()
        advanceUntilIdle()
        assertThat(a.await()).isSameInstanceAs(b.await())
        assertThat(repository.groupingRuns).isEqualTo(1)
    }

    @Test
    fun `category rails group one category, with category language hints`() = runTest(main.dispatcher) {
        val titles = repository.categoryTitles(VodCategory(1, VodKind.MOVIE, "10", "EN Movies"))
        assertThat(titles.map { it.title.title }).containsExactly("Dune", "Leo", "Arrival").inOrder()
        assertThat(titles.first().title.versions.map { it.item.id }).containsExactly("102")
        assertThat(titles.first().title.versions.single().item.categoryName).isEqualTo("EN Movies")
    }

    @Test
    fun `hidden categories are left out`() = runTest(main.dispatcher) {
        val filtered = VodCatalogRepository(source, grouping, isCategoryHidden = { _, _, id -> id == "20" })
        assertThat(filtered.categories(1, VodKind.MOVIE).map { it.id }).containsExactly("10", "30").inOrder()
    }
}
