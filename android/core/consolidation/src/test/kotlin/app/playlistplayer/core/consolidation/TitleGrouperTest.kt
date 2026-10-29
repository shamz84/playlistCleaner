package app.playlistplayer.core.consolidation

import app.playlistplayer.core.consolidation.NameFixtures.vodName
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TitleGrouperTest {

    private var order = 0

    private fun movie(
        name: String,
        tmdb: String? = null,
        playlist: String = "p1",
        category: String? = null,
        categoryLanguage: String? = null,
        id: String = "m${order}",
        kind: VodKind = VodKind.MOVIE,
    ) = VodItem(
        id = id, playlistId = playlist, kind = kind, name = name, tmdb = tmdb,
        categoryName = category, categoryLanguage = categoryLanguage, providerOrder = order++,
    )

    private fun List<VodTitle>.names() = map { t -> t.versions.map { it.item.name } }

    private fun List<VodTitle>.titleOf(itemName: String) = single { t -> t.versions.any { it.item.name == itemName } }

    @Test
    fun duneVersionsWithSameTmdbFormOneTitleAndDefaultToEnglish4k() {
        val items = listOf(
            movie("EN - Dune (2021) 4K", tmdb = "438631"),
            movie("|FR| Dune (2021)", tmdb = "438631"),
            movie("Dune (2021) FHD", tmdb = "438631"),
        )
        val titles = TitleGrouper.group(items)
        assertThat(titles).hasSize(1)
        val dune = titles.single()
        assertThat(dune.title).isEqualTo("Dune")
        assertThat(dune.year).isEqualTo(2021)
        assertThat(dune.versions).hasSize(3)
        val prefs = VersionPreferences(languages = listOf("en", "fr"))
        assertThat(dune.defaultVersion(prefs).item.name).isEqualTo("EN - Dune (2021) 4K")
        assertThat(dune.rankedVersions(prefs).map { it.item.name })
            .containsExactly("EN - Dune (2021) 4K", "|FR| Dune (2021)", "Dune (2021) FHD").inOrder()
    }

    @Test
    fun duneTitlesOfDifferentYearsStaySeparate() {
        val noTmdb = TitleGrouper.group(listOf(movie("EN - Dune (1984)"), movie("EN - Dune (2021)")))
        assertThat(noTmdb).hasSize(2)
        // Even a (wrongly) shared tmdb is rejected when years differ.
        val sharedTmdb = TitleGrouper.group(listOf(movie("EN - Dune (1984)", "1"), movie("EN - Dune (2021)", "1")))
        assertThat(sharedTmdb).hasSize(2)
        assertThat(sharedTmdb.map { it.year }).containsExactly(1984, 2021)
    }

    @Test
    fun realItEndsFamilyWithDifferingYearsIsNotMerged() {
        val items = listOf(
            movie(vodName("4K-TOP - It Ends (2026)"), "1100"),
            movie(vodName("EN - It Ends  (2025)"), "1100"),
            movie(vodName("TOP - It Ends (2026)"), "1100"),
        )
        val titles = TitleGrouper.group(items)
        assertThat(titles.map { it.year to it.versions.size }).containsExactly(2026 to 2, 2025 to 1)
    }

    @Test
    fun sportsItemsReusingAFilmTmdbDoNotMergeWithTheFilm() {
        val arsenalTmdb = "475094"
        val items = listOf(
            movie(vodName("EN - Arsenal  (2017)"), arsenalTmdb),
            movie(vodName("TOP - Arsenal"), arsenalTmdb),
            movie(vodName("NF - Arsenal"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - Liverpool 04.02.2024"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - Liverpool 07.01.2024"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - PSV 20.09.2023"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - Manchester United 03.09.2023"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - Manchester United 22.07.2023"), arsenalTmdb),
            movie(vodName("SOC - Arsenal - Fulham 26.08.2023"), arsenalTmdb),
        )
        val titles = TitleGrouper.group(items)
        val film = titles.titleOf("EN - Arsenal  (2017)")
        // The same-name, no-year items share the tmdb and the title, so they join the film.
        assertThat(film.versions.map { it.item.name })
            .containsExactly("EN - Arsenal  (2017)", "TOP - Arsenal", "NF - Arsenal").inOrder()
        assertThat(film.year).isEqualTo(2017)
        // Every match is its own title: none merges with the film or with another match.
        assertThat(titles).hasSize(1 + 6)
        assertThat(titles.filter { it !== film }.all { it.versions.size == 1 }).isTrue()
    }

    @Test
    fun socItemWithFilmTmdbButDifferentYearDoesNotMerge() {
        val items = listOf(
            movie(vodName("EN - Arsenal  (2017)"), "475094"),
            movie("SOC - Arsenal (2024)", "475094"),
        )
        assertThat(TitleGrouper.group(items)).hasSize(2)
    }

    @Test
    fun realLeoFamilyMergesOnTmdbAndPrefersEnglish() {
        val leo = NameFixtures.vod.filter { TitleTokenizer.tokenize(it, TokenizeMode.VOD).baseName == "leo" }
        assertThat(leo).hasSize(11)
        val titles = TitleGrouper.group(leo.map { movie(it, tmdb = "1075794") })
        assertThat(titles).hasSize(1)
        val title = titles.single()
        assertThat(title.title).isEqualTo("Leo")
        assertThat(title.defaultVersion().item.name).isEqualTo("EN - Leo (2023)")
        // With the platform version's category in English, the 4K Netflix copy wins.
        val withCategory = TitleGrouper.group(
            leo.map { name -> movie(name, "1075794", category = if (name.startsWith("4K-NF")) "EN - NETFLIX ⁴ᴷ" else null) },
        )
        assertThat(withCategory.single().defaultVersion().item.name).isEqualTo("4K-NF - Leo (2023)")
    }

    @Test
    fun realAvengersFamilyIncludingNoYearAndDottedNames() {
        val avengers = NameFixtures.vod.filter {
            TitleTokenizer.tokenize(it, TokenizeMode.VOD).baseName == "avengers infinity war"
        }
        assertThat(avengers).hasSize(10)
        val titles = TitleGrouper.group(avengers.map { movie(it, tmdb = "299536") })
        assertThat(titles).hasSize(1)
        assertThat(titles.single().year).isEqualTo(2018)
    }

    @Test
    fun differentTitlesWithSameTmdbAndYearMerge() {
        val items = listOf(
            movie(vodName("4K-TOP - Batman: Knightfall Part 1: Knightfall (2026)"), "9001"),
            movie(vodName("EN - Batman: Knightfall Part 1  (2026)"), "9001"),
            movie(vodName("TOP - Batman: Knightfall Part 1: Knightfall (2026)"), "9001"),
        )
        val titles = TitleGrouper.group(items)
        assertThat(titles).hasSize(1)
        assertThat(titles.single().title).isEqualTo("Batman: Knightfall Part 1: Knightfall")
    }

    @Test
    fun withoutTmdbMissingYearNeverMergesWithKnownYear() {
        val titles = TitleGrouper.group(
            listOf(movie("Leo (2023)"), movie("EN - Leo"), movie("NF - Leo"), movie("IN - Leo (2023)")),
        )
        assertThat(titles.names()).containsExactly(
            listOf("Leo (2023)", "IN - Leo (2023)"),
            listOf("EN - Leo", "NF - Leo"),
        )
    }

    @Test
    fun itemWithoutTmdbJoinsTheUniqueMatchingTmdbTitle() {
        val titles = TitleGrouper.group(
            listOf(
                movie(vodName("EN - Superbad  (2007)"), "8363"),
                movie(vodName("IN - Superbad (2007)")), // no tmdb
                movie(vodName("IN-EN - Superbad (2007)"), "8363"),
            ),
        )
        assertThat(titles).hasSize(1)
        assertThat(titles.single().tmdb).isEqualTo("8363")
    }

    @Test
    fun moviesAndSeriesAreSeparateNamespaces() {
        val titles = TitleGrouper.group(
            listOf(
                movie("EN - Utopia (2013)", tmdb = "46896"),
                movie("EN - Utopia (2013) (GB)", tmdb = "46896", kind = VodKind.SERIES),
            ),
        )
        assertThat(titles).hasSize(2)
        assertThat(titles.map { it.kind }).containsExactly(VodKind.MOVIE, VodKind.SERIES)
    }

    @Test
    fun groupingIsScopedPerPlaylist() {
        val titles = TitleGrouper.group(
            listOf(
                movie("EN - Leo (2023)", "1075794", playlist = "p1"),
                movie("EN - Leo (2023)", "1075794", playlist = "p2"),
            ),
        )
        assertThat(titles).hasSize(2)
        assertThat(titles.map { it.playlistId }).containsExactly("p1", "p2")
        assertThat(titles.map { it.key }.toSet()).hasSize(2)
    }

    @Test
    fun platformPrefixTakesLanguageFromCategory() {
        val nf = TitleGrouper.versionOf(movie(vodName("NF - Leo (2023)"), category = "EN - NETFLIX"))
        assertThat(nf.version.language).isEqualTo("en")
        val hinted = TitleGrouper.versionOf(movie(vodName("D+ - Toy Story 5 (2026)"), categoryLanguage = "EN"))
        assertThat(hinted.version.language).isEqualTo("en")
        // A language in the name wins over the category.
        val own = TitleGrouper.versionOf(movie(vodName("IN-TL - Leo (2023)"), category = "EN - MOVIES"))
        assertThat(own.version.language).isEqualTo("in-tl")
        val unknown = TitleGrouper.versionOf(movie(vodName("SOC - Norway vs Sweden 01.06.2026")))
        assertThat(unknown.version.language).isNull()
    }

    @Test
    fun nonPreferredLanguageOnlyTitleStaysVisibleWithBestQualityDefault() {
        val titles = TitleGrouper.group(
            listOf(
                movie(vodName("IN-EN - Michael (2026)").replace("IN-EN", "IN-TL"), "1"),
                movie(vodName("4K-IN - Michael (2026)").replace("4K-IN", "4K-IN-TL"), "1"),
            ),
        )
        assertThat(titles).hasSize(1)
        val default = titles.single().defaultVersion(VersionPreferences(languages = listOf("en")))
        assertThat(default.tokens.quality).isEqualTo(QualityTier.UHD)
        assertThat(default.version.language).isEqualTo("in-tl")
    }

    @Test
    fun unparseableNamesFallBackToTheirOwnTitleAndAreNeverDropped() {
        val items = listOf(movie(""), movie("ᴿᴬᵂ"), movie("4K"), movie("   "), movie("EN - Leo (2023)"))
        val titles = TitleGrouper.group(items)
        assertThat(titles).hasSize(5)
        assertThat(titles.flatMap { t -> t.versions.map { it.item.id } }).containsExactlyElementsIn(items.map { it.id })
    }

    @Test
    fun tmdbValuesAreNormalized() {
        assertThat(TitleGrouper.normalizeTmdb(" 0123 ")).isEqualTo("123")
        assertThat(TitleGrouper.normalizeTmdb("0")).isNull()
        assertThat(TitleGrouper.normalizeTmdb("")).isNull()
        assertThat(TitleGrouper.normalizeTmdb(null)).isNull()
        val titles = TitleGrouper.group(listOf(movie("EN - Leo (2023)", "1075794"), movie("NF - Leo (2023)", "01075794")))
        assertThat(titles).hasSize(1)
    }

    @Test
    fun realVodNamesConsolidateWithoutCrossYearMerges() {
        val items = NameFixtures.vod.map { movie(it) }
        val titles = TitleGrouper.group(items)
        assertThat(titles.size).isLessThan(items.size)
        assertThat(titles.sumOf { it.versions.size }).isEqualTo(items.size)
        for (t in titles) {
            val years = t.versions.map { it.tokens.year }.toSet()
            assertWithMessage(t.key).that(years).hasSize(1)
            val bases = t.versions.map { it.tokens.baseName }.toSet()
            assertWithMessage(t.key).that(bases).hasSize(1)
        }
        println("VOD fixture: ${items.size} items -> ${titles.size} titles")
    }

    @Test
    fun performanceSmokeGroups45kItemsQuickly() {
        val items = (0 until 225).flatMap { copy ->
            NameFixtures.vod.mapIndexed { i, name ->
                val base = TitleTokenizer.tokenize(name, TokenizeMode.VOD).baseName
                VodItem(
                    id = "$copy-$i", playlistId = "p1", kind = VodKind.MOVIE, name = "$name $copy",
                    tmdb = if (i % 8 == 0) null else "$copy-$base", providerOrder = copy * 1000 + i,
                )
            }
        }
        assertThat(items.size).isAtLeast(45_000)
        val start = System.nanoTime()
        val titles = TitleGrouper.group(items)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("TitleGrouper: ${items.size} items -> ${titles.size} titles in ${ms}ms")
        assertThat(titles.sumOf { it.versions.size }).isEqualTo(items.size)
        assertThat(ms).isLessThan(20_000L)
    }

    @Test
    fun splitOverridePersistsAcrossResync() {
        fun catalog(extra: Boolean): List<VodItem> {
            order = 0
            return buildList {
                add(movie("EN - Leo (2023)", "1075794", id = "s1"))
                add(movie("NF - Leo (2023)", "1075794", id = "s2"))
                add(movie("IN-TL - Leo (2023)", "1075794", id = "s3"))
                if (extra) add(movie("4K-NF - Leo (2023)", "1075794", id = "s4"))
            }
        }
        val split = listOf(SplitOverride("p1", setOf("s3")))

        val first = TitleGrouper.group(catalog(extra = false), split)
        assertThat(first.names()).containsExactly(
            listOf("EN - Leo (2023)", "NF - Leo (2023)"),
            listOf("IN-TL - Leo (2023)"),
        )
        val splitKey = first.titleOf("IN-TL - Leo (2023)").key

        // Re-sync: a new version arrives and the provider reorders the list.
        val resynced = TitleGrouper.group(catalog(extra = true).reversed(), split)
        assertThat(resynced).hasSize(2)
        assertThat(resynced.titleOf("IN-TL - Leo (2023)").versions).hasSize(1)
        assertThat(resynced.titleOf("IN-TL - Leo (2023)").key).isEqualTo(splitKey)
        assertThat(resynced.titleOf("4K-NF - Leo (2023)").versions.map { it.item.id })
            .containsExactly("s1", "s2", "s4")
    }

    @Test
    fun mergeOverrideJoinsTitlesOfTheSameKindOnly() {
        val items = listOf(
            movie("EN - Batman: Knightfall Part 1  (2026)", id = "a"),
            movie("TOP - Batman: Knightfall Part 1: Knightfall (2026)", id = "b"),
            movie("EN - Batman: Knightfall (2026)", id = "c", kind = VodKind.SERIES),
            movie("EN - Unrelated (2020)", id = "d"),
        )
        assertThat(TitleGrouper.group(items)).hasSize(4)
        val merged = TitleGrouper.group(items, listOf(MergeOverride("p1", setOf("a", "b", "c"))))
        assertThat(merged).hasSize(3)
        val batman = merged.titleOf("EN - Batman: Knightfall Part 1  (2026)")
        assertThat(batman.versions.map { it.item.id }).containsExactly("a", "b").inOrder()
        assertThat(batman.title).isEqualTo("Batman: Knightfall Part 1")
        // Override for another playlist or for missing ids is ignored.
        val ignored = TitleGrouper.group(
            items,
            listOf(MergeOverride("p2", setOf("a", "b")), SplitOverride("p1", setOf("gone"))),
        )
        assertThat(ignored).hasSize(4)
    }
}
