package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVodCatalogSource()
        .playlist(1, "Main")
        .playlist(2, "Family")
        .vodCategory(1, "10", "EN Movies")
        .vodCategory(1, "20", "4K Movies")
        .vodCategory(1, "30", "Empty")
        .vodCategory(1, "40", "Kids")
        .vodCategory(1, "50", "Docs")
        .vodCategory(2, "10", "EN Movies")
        .movie(1, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631")
        .movie(1, 102, "EN - Dune (2021) HD", "10", tmdb = "438631")
        .movie(1, 103, "FR - Dune (2021) FHD", "10", tmdb = "438631")
        .movie(1, 104, "EN - Leo (2023) FHD", "10", tmdb = "1075794")
        .movie(1, 105, "4K-EN - Leo (2023)", "20", tmdb = "1075794")
        .movie(1, 106, "EN - Bluey (2018)", "40")
        .movie(1, 107, "EN - Planet Earth (2006)", "50")
        .movie(2, 101, "EN - Dune (2021) 4K", "10", tmdb = "438631")

    @Test
    fun `sections per playlist name, rails per category, one card per merged title`() = runTest(main.dispatcher) {
        val vm = VodBrowseViewModel(VodKind.MOVIE, VodCatalogRepository(source, main.dispatcher))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.loading).isFalse()
        assertThat(s.sections.map { it.playlist.name }).containsExactly("Main", "Family").inOrder()
        val main = s.sections.first()
        // The empty category is dropped once loaded.
        assertThat(main.rails.map { it.category.name }).containsExactly("EN Movies", "4K Movies", "Kids", "Docs").inOrder()
        val en = main.rails.first()
        assertThat(en.status).isEqualTo(RailStatus.LOADED)
        assertThat(en.cards.map { it.title }).containsExactly("Dune", "Leo").inOrder()
        val dune = en.cards.first()
        assertThat(dune.year).isEqualTo(2021)
        assertThat(dune.bestQuality).isEqualTo(QualityTier.UHD)
        assertThat(dune.versionCount).isEqualTo(3)
        assertThat(dune.anchorId).isEqualTo("101")
        assertThat(dune.posterUrl).isNotNull()
        // Family's Dune is its own card.
        val family = s.sections[1].rails.single().cards.single()
        assertThat(family.playlistId).isEqualTo(2)
        assertThat(family.titleKey).isNotEqualTo(dune.titleKey)
    }

    @Test
    fun `rails load in chunks as they come into view`() = runTest(main.dispatcher) {
        val vm = VodBrowseViewModel(VodKind.MOVIE, VodCatalogRepository(source, main.dispatcher), railChunk = 2)
        advanceUntilIdle()
        val rails = vm.state.value.sections.first().rails
        assertThat(rails.map { it.status }).containsExactly(
            RailStatus.LOADED, RailStatus.LOADED, RailStatus.NOT_LOADED, RailStatus.NOT_LOADED, RailStatus.NOT_LOADED,
        ).inOrder()

        vm.onRailVisible(1, 2)
        advanceUntilIdle()
        val after = vm.state.value.sections.first().rails
        // "Empty" loaded and was dropped; Kids loaded; Docs is still waiting.
        assertThat(after.map { it.category.name to it.status }).containsExactly(
            "EN Movies" to RailStatus.LOADED,
            "4K Movies" to RailStatus.LOADED,
            "Kids" to RailStatus.LOADED,
            "Docs" to RailStatus.NOT_LOADED,
        ).inOrder()
    }

    @Test
    fun `grouping happens off the main dispatcher`() = runTest(main.dispatcher) {
        val grouping = StandardTestDispatcher(testScheduler, name = "vod-grouping")
        val vm = VodBrowseViewModel(VodKind.MOVIE, VodCatalogRepository(source, grouping))
        // Main (unconfined) ran the ViewModel; rails wait for the grouping dispatcher.
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.sections.first().rails.first().status).isEqualTo(RailStatus.LOADING)
        advanceUntilIdle()
        assertThat(vm.state.value.sections.first().rails.first().status).isEqualTo(RailStatus.LOADED)
    }

    @Test
    fun `a failing playlist leaves other sections usable`() = runTest(main.dispatcher) {
        val broken = object : VodCatalogSource by source {
            override suspend fun vodCategories(playlistId: Long) =
                if (playlistId == 2L) error("db closed") else source.vodCategories(playlistId)
        }
        val vm = VodBrowseViewModel(VodKind.MOVIE, VodCatalogRepository(broken, main.dispatcher))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.sections[0].failed).isFalse()
        assertThat(s.sections[0].rails.first().cards).isNotEmpty()
        assertThat(s.sections[1].failed).isTrue()
    }

    @Test
    fun `series browse groups series`() = runTest(main.dispatcher) {
        source.seriesCategory(1, "70", "EN Series")
            .series(1, 201, "EN - Utopia (2013) 4K", "70", tmdb = "46896")
            .series(1, 202, "EN - Utopia (2013) HD", "70", tmdb = "46896")
        val vm = VodBrowseViewModel(VodKind.SERIES, VodCatalogRepository(source, main.dispatcher))
        advanceUntilIdle()
        val card = vm.state.value.sections.first().rails.single().cards.single()
        assertThat(card.title).isEqualTo("Utopia")
        assertThat(card.kind).isEqualTo(VodKind.SERIES)
        assertThat(card.versionCount).isEqualTo(2)
        assertThat(vm.state.value.sections[1].rails).isEmpty()
    }
}
