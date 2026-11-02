package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.MergeOverride
import app.playlistplayer.core.consolidation.SplitOverride
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.library.RoomTitleOverrideStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    private lateinit var env: SearchTestEnv
    private var main = 0L
    private var family = 0L

    @Before
    fun setUp() = runBlocking {
        env = SearchTestEnv()
        main = env.playlist("Main")
        family = env.playlist("Family")
        env.syncLive(
            main,
            listOf(
                TestChannel("### UK ###", "🇬🇧 UK", isHeader = true),
                TestChannel("UK: BBC One HD", "🇬🇧 UK", 101),
                TestChannel("UK: BBC One FHD", "🇬🇧 UK", 102),
                TestChannel("UK: BBC Two", "🇬🇧 UK", 103),
                TestChannel("Ebbw Vale TV", "Local", 104),
                TestChannel("Night BBC One Late", "🔞 Adult", 105),
            ),
        )
        env.syncLive(family, listOf(TestChannel("BBC One", "Kids & Family", 201), TestChannel("CBBC", "Kids & Family", 202)))
        env.syncCatalog(
            main,
            movies = listOf(
                TestMovie(1, "EN - Dune (2021) 4K", "1", tmdb = "438631"),
                TestMovie(2, "NF - Dune (2021)", "1", tmdb = "438631"),
                TestMovie(3, "EN - Dune: Part Two (2024)", "1", tmdb = "693134"),
                TestMovie(4, "EN - Dunkirk (2017)", "1"),
                TestMovie(5, "EN - Dûne Nights (2019)", "9"),
            ),
            series = listOf(TestSeries(50, "EN - Dune: Prophecy (2024)", "2")),
        )
        Unit
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun bbcOneReturnsEntriesGroupedUnderEachPlaylistName() = runBlocking<Unit> {
        val results = env.repository.search("bbc one")

        assertThat(results.live.map { it.playlist.name }).containsExactly("Main", "Family").inOrder()
        assertThat(results.live[0].hits.map { it.name }).containsExactly("UK: BBC One HD", "UK: BBC One FHD", "Night BBC One Late")
        assertThat(results.live[1].hits.map { it.name }).containsExactly("BBC One")
        assertThat(results.live[1].hits.single().groupName).isEqualTo("Kids & Family")
        assertThat(results.live[0].hits.first().streamKey).isEqualTo("s:101")
    }

    @Test
    fun partialDunReturnsDuneTitlesIgnoringCaseAndDiacritics() = runBlocking<Unit> {
        for (q in listOf("dun", "DUN", "Dûn")) {
            val results = env.repository.search(q)
            val movies = results.movies.single()
            assertThat(movies.playlist.name).isEqualTo("Main")
            // Two Dune versions merged into one title; diacritics folded ("Dûne Nights").
            assertThat(movies.hits.map { it.title }).containsExactly("Dune", "Dunkirk", "Dune: Part Two", "Dune Nights")
            val dune = movies.hits.first { it.title == "Dune" }
            assertThat(dune.versionCount).isEqualTo(2)
            assertThat(dune.anchorId).isEqualTo("1")
            assertThat(results.series.single().hits.map { it.title }).containsExactly("Dune: Prophecy")
        }
        // A hit's key is the browse title's key.
        val hit = env.repository.search("dune part").movies.single().hits.single()
        assertThat(env.vod.title(main, VodKind.MOVIE, hit.anchorId)!!.key).isEqualTo(hit.titleKey)
    }

    @Test
    fun twoCharacterQueriesUsePrefixMatching() = runBlocking<Unit> {
        val results = env.repository.search("bb")
        // Word prefixes match ("BBC ..."), inner substrings ("Ebbw", "CBBC") do not.
        assertThat(results.live.flatMap { it.hits }.map { it.name }).containsExactly(
            "BBC One", "UK: BBC Two", "UK: BBC One HD", "UK: BBC One FHD", "Night BBC One Late",
        )
        assertThat(env.repository.search("eb").live.flatMap { it.hits }.map { it.name }).containsExactly("Ebbw Vale TV")
        assertThat(env.repository.search("  ").isEmpty).isTrue()
    }

    @Test
    fun lockedGroupsAndCategoriesAreExcluded() = runBlocking<Unit> {
        env.visibility.hiddenGroups += main to "🔞 Adult"
        env.visibility.hiddenCategories += Triple(main, VodKind.MOVIE, "9")

        val live = env.repository.search("bbc one").live.flatMap { it.hits }.map { it.name }
        assertThat(live).doesNotContain("Night BBC One Late")
        assertThat(live).hasSize(3)
        assertThat(env.repository.search("dune").movies.flatMap { it.hits }.map { it.title }).doesNotContain("Dune Nights")
        // Only locked content matches: an ordinary empty result, nothing hints at hidden matches.
        assertThat(env.repository.search("night").isEmpty).isTrue()
        assertThat(env.repository.search("nights").isEmpty).isTrue()
    }

    @Test
    fun indexIsRebuiltAfterAGenerationFlip() = runBlocking<Unit> {
        assertThat(env.repository.search("bbc two").liveCount).isEqualTo(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            env.indexer.start(scope, debounceMillis = 10)
            env.syncLive(main, listOf(TestChannel("UK: BBC Three", "🇬🇧 UK", 106)))

            withTimeout(10_000) {
                while (env.repository.search("bbc three").liveCount == 0) delay(20)
            }
            assertThat(env.repository.search("bbc two").liveCount).isEqualTo(0)
            assertThat(env.searchStore.state(main)!!.liveGeneration).isEqualTo(env.playlists.syncState(main)!!.activeGeneration)
            // The family playlist and the catalog were not rebuilt.
            assertThat(env.repository.search("cbbc").liveCount).isEqualTo(1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun catalogFlipRebuildsVodOnly() = runBlocking<Unit> {
        env.indexer.ensureAllFresh()
        val liveBuilds = env.indexer.liveBuilds
        env.syncCatalog(main, movies = listOf(TestMovie(7, "EN - Arrival (2016)", "1")))

        assertThat(env.indexer.ensureFresh(main)).isTrue()
        assertThat(env.indexer.liveBuilds).isEqualTo(liveBuilds)
        assertThat(env.repository.search("dune").movies).isEmpty()
        assertThat(env.repository.search("arrival").movieCount).isEqualTo(1)
        assertThat(env.indexer.ensureFresh(main)).isFalse()
    }

    @Test
    fun searchRebuildsReuseThePrecomputedTitlesWithoutRegrouping() = runBlocking<Unit> {
        env.indexer.ensureAllFresh()
        val grouped = env.grouper.calls.get()
        assertThat(grouped).isEqualTo(2) // main's movies and series, once
        val before = env.repository.search("dune").movies.single().hits.map { it.titleKey }

        // Losing the search index (upgrade, wipe) rebuilds it by copying the title index.
        env.searchStore.clearPlaylist(main)
        assertThat(env.indexer.ensureFresh(main)).isTrue()
        assertThat(env.grouper.calls.get()).isEqualTo(grouped)
        assertThat(env.repository.search("dune").movies.single().hits.map { it.titleKey }).containsExactlyElementsIn(before)
        assertThat(env.searchStore.state(main)!!.catalogGeneration).isEqualTo(env.titleIndex.state(main)!!.catalogGeneration)
    }

    @Test
    fun overridesPersistAndApplyToBrowseAndSearch() = runBlocking<Unit> {
        assertThat(env.repository.search("dune").movies.single().hits.count { it.title == "Dune" }).isEqualTo(1)

        env.overrides.add(SplitOverride(main.toString(), setOf("2")))
        env.indexer.ensureFresh(main)

        val dunes = env.repository.search("dune").movies.single().hits.filter { it.title == "Dune" }
        assertThat(dunes).hasSize(2)
        assertThat(env.vod.title(main, VodKind.MOVIE, "2")!!.title.versions).hasSize(1)

        // A fresh store over the same database sees the same records.
        val reopened = RoomTitleOverrideStore(env.userData)
        assertThat(reopened.overrides(main)).containsExactly(SplitOverride(main.toString(), setOf("2")))
        val merge = reopened.add(MergeOverride(main.toString(), setOf("1", "4")))
        assertThat(reopened.list(main).map { it.id }).contains(merge)
        assertThat(reopened.stamp(main)).isNotEqualTo(RoomTitleOverrideStore.EMPTY_STAMP)
        reopened.remove(merge)
        assertThat(reopened.overrides(main)).hasSize(1)
    }

    @Test
    fun xtreamOffEmptiesTheVodIndex() = runBlocking<Unit> {
        env.indexer.ensureAllFresh()
        env.playlists.setXtreamEnabled(main, false)
        env.indexer.ensureFresh(main)
        assertThat(env.repository.search("dune").movies).isEmpty()
        assertThat(env.repository.search("bbc one").live).isNotEmpty()
    }
}
