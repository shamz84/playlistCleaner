package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.SplitOverride
import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VodCatalogRepositoryTest {

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .playlist(2, "Family")
        .vodCategory(1, "10", "EN Movies")
        .vodCategory(1, "20", "4K Movies")
        .vodCategory(1, "30", "Empty")
        .vodCategory(2, "10", "EN Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "20", tmdb = "438631", added = 10)
        .movie(1, 102, "EN - Dune (2021) HD", "10", tmdb = "438631")
        .movie(1, 104, "EN - Leo (2023) FHD", "10", tmdb = "1075794", added = 30)
        .movie(1, 105, "EN - Arrival (2016)", "10", added = 20)
        // Same title and tmdb in another playlist: must stay separate.
        .movie(2, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631")
        .movie(2, 202, "EN - Dune (2021) HD", "10", tmdb = "438631")

    private val grouper = CountingGrouper()
    private val index = InMemoryVodTitleIndex()
    private val indexer = VodTitleIndexer(source, index, group = grouper, pageSize = 2)
    private val repository = VodCatalogRepository(source, index)

    @Test
    fun `the index merges across categories and pages through the catalog once`() = runBlocking<Unit> {
        indexer.ensureAllFresh()
        assertThat(source.pageCalls).isEqualTo(5) // page size 2: playlist 1 has 4 rows (3 calls), playlist 2 has 2 (2 calls)
        val dune = repository.title(1, VodKind.MOVIE, "101")!!
        assertThat(dune.title.title).isEqualTo("Dune")
        assertThat(dune.title.versions.map { it.item.id }).containsExactly("101", "102").inOrder()
        assertThat(repository.title(1, VodKind.MOVIE, "102")!!.key).isEqualTo(dune.key)
        assertThat(dune.bestQuality).isEqualTo(QualityTier.UHD)
        assertThat(dune.posterUrl).isEqualTo("https://img.example/101.jpg")
        // Versions carry their category's language hint, as when they were grouped.
        assertThat(dune.title.versions.map { it.item.categoryName }).containsExactly("4K Movies", "EN Movies").inOrder()

        val cards = repository.page(1, VodKind.MOVIE, VodSort.A_Z, emptySet(), 0, 10)
        assertThat(cards.map { it.title }).containsExactly("Arrival", "Dune", "Leo").inOrder()
        assertThat(cards.first { it.title == "Arrival" }.bestQuality).isNull() // unlabeled: no badge
        assertThat(cards.first { it.title == "Dune" }.versionCount).isEqualTo(2)
        assertThat(repository.count(1, VodKind.MOVIE, emptySet())).isEqualTo(3)
    }

    @Test
    fun `titles are never merged across playlists`() = runBlocking<Unit> {
        indexer.ensureAllFresh()
        val one = repository.title(1, VodKind.MOVIE, "101")!!
        val two = repository.title(2, VodKind.MOVIE, "101")!!
        assertThat(one.key).isNotEqualTo(two.key)
        assertThat(one.title.versions.map { it.item.playlistId }.distinct()).containsExactly("1")
        assertThat(two.title.versions.map { it.item.id }).containsExactly("101", "202")
        assertThat(repository.title(2, VodKind.MOVIE, "102")).isNull()
    }

    @Test
    fun `the index is rebuilt only for a new catalog generation or changed overrides`() = runBlocking<Unit> {
        var stamp = "none"
        var overrides = emptyList<SplitOverride>()
        val indexer = VodTitleIndexer(source, index, overrides = { overrides }, overrideStamp = { stamp }, group = grouper)
        assertThat(indexer.ensureFresh(1)).isTrue()
        assertThat(indexer.ensureFresh(1)).isFalse()
        assertThat(grouper.calls.get()).isEqualTo(2) // one pass per kind
        val firstKey = repository.title(1, VodKind.MOVIE, "101")!!.key

        // A catalog refresh renames the movie: new generation, new keys.
        source.vodRows.replaceAll { if (it.playlistId == 1L && it.streamId == 101L) it.copy(name = "EN - Dune Part One (2021) 4K", tmdb = null) else it }
        source.generations[1] = 2
        assertThat(indexer.ensureFresh(1)).isTrue()
        assertThat(index.state(1)!!.catalogGeneration).isEqualTo(2)
        assertThat(repository.title(1, VodKind.MOVIE, "101")!!.key).isNotEqualTo(firstKey)
        assertThat(repository.title(1, VodKind.MOVIE, "102")!!.title.versions.map { it.item.id }).containsExactly("102")

        // An override edit regroups under the same catalog generation.
        overrides = listOf(SplitOverride("1", setOf("104")))
        stamp = "split"
        assertThat(indexer.ensureFresh(1)).isTrue()
        assertThat(index.state(1)!!.overridesStamp).isEqualTo("split")
        assertThat(grouper.calls.get()).isEqualTo(6)

        // Playlist 2 was never touched.
        assertThat(indexer.ensureFresh(2)).isTrue()
        assertThat(indexer.ensureFresh(2)).isFalse()
    }

    @Test
    fun `a playlist whose catalog is gone loses its index`() = runBlocking<Unit> {
        indexer.ensureAllFresh()
        source.generations.remove(1)
        assertThat(indexer.ensureFresh(1)).isTrue()
        assertThat(index.state(1)).isNull()
        assertThat(repository.isIndexPending(1)).isFalse()
        assertThat(repository.page(1, VodKind.MOVIE, VodSort.A_Z, emptySet(), 0, 10)).isEmpty()
    }

    @Test
    fun `sort ranks put unknown added times and years last and keep provider order on ties`() = runBlocking<Unit> {
        source.movie(1, 106, "EN - Bluey", "10").movie(1, 107, "EN - Alien", "10")
        indexer.ensureAllFresh()
        suspend fun titles(sort: VodSort) = repository.page(1, VodKind.MOVIE, sort, emptySet(), 0, 10).map { it.title }
        assertThat(titles(VodSort.RECENTLY_ADDED)).containsExactly("Leo", "Arrival", "Dune", "Bluey", "Alien").inOrder()
        assertThat(titles(VodSort.RELEASE_YEAR)).containsExactly("Leo", "Dune", "Arrival", "Alien", "Bluey").inOrder()
        assertThat(titles(VodSort.A_Z)).containsExactly("Alien", "Arrival", "Bluey", "Dune", "Leo").inOrder()
        // Paging is an offset into the same order.
        assertThat(repository.page(1, VodKind.MOVIE, VodSort.A_Z, emptySet(), 3, 10).map { it.title }).containsExactly("Dune", "Leo").inOrder()
    }

    @Test
    fun `hidden categories are left out, but a title stays while any version is visible`() = runBlocking<Unit> {
        indexer.ensureAllFresh()
        val filtered = VodCatalogRepository(source, index, isCategoryHidden = { _, _, id -> id == "20" })
        assertThat(filtered.categories(1, VodKind.MOVIE).map { it.id }).containsExactly("10", "30").inOrder()
        val hidden = filtered.hiddenCategories(1, VodKind.MOVIE)
        assertThat(hidden).containsExactly("20")
        // Dune has an HD version outside the hidden 4K category.
        assertThat(filtered.page(1, VodKind.MOVIE, VodSort.A_Z, hidden, 0, 10).map { it.title }).containsExactly("Arrival", "Dune", "Leo").inOrder()
        assertThat(filtered.page(1, VodKind.MOVIE, VodSort.A_Z, setOf("10", "20"), 0, 10)).isEmpty()
        assertThat(filtered.count(1, VodKind.MOVIE, setOf("10"))).isEqualTo(1)
    }

    @Test
    fun `the index is pending until the first build`() = runBlocking<Unit> {
        assertThat(repository.isIndexPending(1)).isTrue()
        indexer.ensureFresh(1)
        assertThat(repository.isIndexPending(1)).isFalse()
        assertThat(repository.isIndexPending(2)).isTrue()
    }
}
